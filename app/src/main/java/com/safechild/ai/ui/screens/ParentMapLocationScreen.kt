package com.safechild.ai.ui.screens

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.safechild.ai.ui.components.OpenStreetMapView
import com.safechild.ai.ui.components.OSMCoordinate
import com.safechild.ai.ui.components.OSMPlace
import com.safechild.ai.data.models.TrustedLocation
import kotlinx.coroutines.delay

private data class MapChild(val uid: String, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentMapLocationScreen(
    auth: FirebaseAuth,
    firestore: FirebaseFirestore,
    childUidParam: String?,
    onBackClick: () -> Unit,
    onManageTrustedLocationsClick: () -> Unit,
    onViewHistoryClick: () -> Unit
) {
    var children by remember { mutableStateOf<List<MapChild>>(emptyList()) }
    var selectedChildUid by remember { mutableStateOf(if (childUidParam != "all") childUidParam else null) }
    var selectedChildName by remember { mutableStateOf<String?>(null) }
    var childLocation by remember { mutableStateOf<Map<String, Any>?>(null) }
    var isLocationSharingEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(childLocation) {
        val updatedAt = (childLocation?.get("timestamp") as? com.google.firebase.Timestamp)?.toDate()?.time
        if (updatedAt == null) {
            isLocationSharingEnabled = false
        } else {
            while (true) {
                val age = System.currentTimeMillis() - updatedAt
                isLocationSharingEnabled = age in -MAX_CLOCK_SKEW_MS..ONLINE_WINDOW_MS
                val timeUntilOffline = ONLINE_WINDOW_MS - age
                if (timeUntilOffline <= 0L) break
                delay(timeUntilOffline.coerceAtMost(STATUS_REFRESH_INTERVAL_MS))
            }
            isLocationSharingEnabled = false
        }
    }
    var isLoading by remember { mutableStateOf(true) }
    var trustedLocations by remember { mutableStateOf<List<TrustedLocation>>(emptyList()) }
    var routePoints by remember { mutableStateOf<List<OSMCoordinate>>(emptyList()) }

    val parentUid = auth.currentUser?.uid

    DisposableEffect(parentUid) {
        if (parentUid == null) return@DisposableEffect onDispose {}
        val listener = firestore.collection("users").document(parentUid)
            .collection("trustedLocations").addSnapshotListener { snapshot, _ ->
                trustedLocations = snapshot?.documents?.mapNotNull { doc ->
                    doc.toObject(TrustedLocation::class.java)?.copy(id = doc.id)
                }?.filter { it.enabled } ?: emptyList()
            }
        onDispose { listener.remove() }
    }

    // Fetch all children first to populate a switcher if needed
    LaunchedEffect(parentUid) {
        if (parentUid == null) return@LaunchedEffect
        
        firestore.collection("relationships")
            .whereEqualTo("parentUid", parentUid)
            .whereEqualTo("status", "active")
            .get()
            .addOnSuccessListener { result ->
                if (!result.isEmpty) {
                    val childUids = result.documents.mapNotNull { it.getString("childUid") }
                    if (childUids.isNotEmpty()) {
                        val childDocs = mutableListOf<MapChild>()
                        var count = 0
                        childUids.forEach { uid ->
                            firestore.collection("users").document(uid).get()
                                .addOnSuccessListener { doc ->
                                    if (doc.exists() && doc.data != null) {
                                        childDocs.add(MapChild(uid, doc.getString("name") ?: "Child"))
                                    }
                                    count++
                                    if (count == childUids.size) {
                                        children = childDocs
                                        if (selectedChildUid == null || selectedChildUid == "all") {
                                            selectedChildUid = childUids.first()
                                        }
                                        isLoading = false
                                    }
                                }
                                .addOnFailureListener {
                                    count++
                                    if (count == childUids.size) {
                                        children = childDocs
                                        isLoading = false
                                    }
                                }
                        }
                    } else {
                        isLoading = false
                    }
                } else {
                    isLoading = false
                }
            }
    }

    // Listen for the selected child's location
    DisposableEffect(selectedChildUid) {
        val uid = selectedChildUid
        childLocation = null
        routePoints = emptyList()
        selectedChildName = null
        isLocationSharingEnabled = false
        if (uid == null || uid == "all") return@DisposableEffect onDispose {}
        
        // Get name
        firestore.collection("users").document(uid).get().addOnSuccessListener { userDoc ->
            selectedChildName = userDoc.getString("name") ?: "Child"
        }

        // Listen for location
        val listener = firestore.collection("users").document(uid)
            .collection("location").document("current")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("ParentMap", "Failed to read location for $uid", error)
                    childLocation = null
                } else if (snapshot != null && snapshot.exists()) {
                    childLocation = snapshot.data
                } else {
                    childLocation = null
                }
            }

        // Draw the saved location breadcrumbs as the child's recent route.
        val historyListener = firestore.collection("users").document(uid)
            .collection("locationHistory")
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("ParentMap", "Failed to read location history for $uid", error)
                    routePoints = emptyList()
                } else {
                    routePoints = snapshot?.documents.orEmpty().mapNotNull { doc ->
                        val lat = (doc.get("latitude") as? Number)?.toDouble()
                        val lng = (doc.get("longitude") as? Number)?.toDouble()
                        if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0 && (lat != 0.0 || lng != 0.0)) OSMCoordinate(lat, lng) else null
                    }.asReversed()
                }
            }

        onDispose {
            listener.remove()
            historyListener.remove()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Child Location", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (children.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No linked children found.", textAlign = TextAlign.Center)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
            ) {
                // Child Switcher (if multiple)
                if (children.size > 1) {
                    val selectedIndex = children.indexOfFirst { it.uid == selectedChildUid }.coerceAtLeast(0)
                    ScrollableTabRow(
                        selectedTabIndex = selectedIndex,
                        edgePadding = 16.dp,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        divider = {}
                    ) {
                        children.forEach { child ->
                            Tab(
                                selected = selectedChildUid == child.uid,
                                onClick = { selectedChildUid = child.uid },
                                text = { Text(child.name) }
                            )
                        }
                    }
                }

                // 1. Google Map View or Fallback
                val lat = (childLocation?.get("latitude") as? Number)?.toDouble()
                val lng = (childLocation?.get("longitude") as? Number)?.toDouble()

                val hasLocation = lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0 && (lat != 0.0 || lng != 0.0)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp)
                        .padding(16.dp)
                        .clip(RoundedCornerShape(20.dp))
                ) {
                    OpenStreetMapView(
                        modifier = Modifier.fillMaxSize(),
                        latitude = if (hasLocation) lat else null,
                        longitude = if (hasLocation) lng else null,
                        markerTitle = selectedChildName ?: "Child",
                        places = trustedLocations.map { OSMPlace(it.name, it.latitude, it.longitude) },
                        route = routePoints
                    )
                if (!hasLocation) {
                        Surface(
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                        ) {
                            Text("Waiting for child location update…", Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // 2. Current Location Card
                    ChildLocationCard(
                        name = selectedChildName ?: "Child",
                        location = if (isLocationSharingEnabled) "Current Position" else "Location unavailable",
                        status = if (isLocationSharingEnabled) "Normal" else "Offline",
                        lastUpdated = (childLocation?.get("timestamp") as? com.google.firebase.Timestamp)?.toDate()?.let {
                            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(it)
                        } ?: "No update yet",
                        accuracy = childLocation?.get("accuracy")?.toString()?.let { "±$it m" } ?: "-",
                        isEnabled = isLocationSharingEnabled
                    )

                    // 3. Location Details
                    LocationDetailsCard(
                        lat = childLocation?.get("latitude")?.toString() ?: "-",
                        lng = childLocation?.get("longitude")?.toString() ?: "-",
                        updatedAt = (childLocation?.get("timestamp") as? com.google.firebase.Timestamp)?.toDate()?.let {
                            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(it)
                        } ?: "-",
                        timeAtLocation = "Unavailable"
                    )

                    // 4. Trusted Locations
                    TrustedLocationsSection(
                        locations = trustedLocations,
                        onManageClick = onManageTrustedLocationsClick,
                        currentLatitude = lat?.takeIf { hasLocation && isLocationSharingEnabled },
                        currentLongitude = lng?.takeIf { hasLocation && isLocationSharingEnabled }
                    )

                    // 5. Location History Button
                    Button(
                        onClick = onViewHistoryClick,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(Icons.Default.History, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("View Location History")
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
fun ChildLocationCard(
    name: String,
    location: String,
    status: String,
    lastUpdated: String,
    accuracy: String,
    isEnabled: Boolean = true
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Current Location", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Child", style = MaterialTheme.typography.labelSmall)
                    Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Location", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = location, 
                        style = MaterialTheme.typography.bodyLarge, 
                        fontWeight = FontWeight.Bold, 
                        color = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Status", style = MaterialTheme.typography.labelSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (isEnabled) Color(0xFF4CAF50) else Color.Gray))
                        Spacer(Modifier.width(4.dp))
                        Text(status, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Last updated", style = MaterialTheme.typography.labelSmall)
                    Text(lastUpdated, style = MaterialTheme.typography.bodyMedium)
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = if (isEnabled) "Location accuracy: $accuracy" else "Location information will appear when sharing is enabled.", 
                style = MaterialTheme.typography.labelSmall, 
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun LocationDetailsCard(lat: String, lng: String, updatedAt: String, timeAtLocation: String) {
    Column {
        Text("Location Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                DetailItem(label = "Latitude", value = lat, modifier = Modifier.weight(1f))
                DetailItem(label = "Longitude", value = lng, modifier = Modifier.weight(1f))
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                DetailItem(label = "Last updated", value = updatedAt, modifier = Modifier.weight(1f))
                DetailItem(label = "Time at location", value = timeAtLocation, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun DetailItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun TrustedLocationsSection(
    locations: List<TrustedLocation>,
    onManageClick: () -> Unit,
    currentLatitude: Double? = null,
    currentLongitude: Double? = null
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Trusted Locations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = onManageClick) {
                Text("Manage")
            }
        }
        if (locations.isEmpty()) {
            Text("No active trusted locations. Add places to enable arrival and departure monitoring.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            locations.forEach { location ->
                val distanceMeters = if (currentLatitude != null && currentLongitude != null) {
                    FloatArray(1).also { result ->
                        Location.distanceBetween(currentLatitude, currentLongitude, location.latitude, location.longitude, result)
                    }[0].toDouble()
                } else null
                TrustedLocationItem(location.name, when (location.type.lowercase()) {
                    "home" -> Icons.Default.Home
                    "school" -> Icons.Default.School
                    "academy" -> Icons.Default.Book
                    else -> Icons.Default.Place
                }, MaterialTheme.colorScheme.primary, distanceMeters, location.radiusMeters)
            }
        }
    }
}

@Composable
fun TrustedLocationItem(name: String, icon: ImageVector, iconTint: Color, distanceMeters: Double? = null, radiusMeters: Float = 150f) {
    val insideRadius = distanceMeters?.let { it <= radiusMeters }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    text = distanceMeters?.let { "${formatDistance(it)} away · ${radiusMeters.toInt()} m radius" } ?: "${radiusMeters.toInt()} m radius",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = when (insideRadius) { true -> "Inside"; false -> "Outside"; null -> "Active" },
                style = MaterialTheme.typography.labelSmall,
                color = if (insideRadius == true) Color(0xFF388E3C) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatDistance(meters: Double): String =
    if (meters < 1000.0) "${meters.toInt()} m" else "${"%.1f".format(java.util.Locale.getDefault(), meters / 1000.0)} km"


private const val ONLINE_WINDOW_MS = 20 * 60 * 1000L
private const val MAX_CLOCK_SKEW_MS = 2 * 60 * 1000L
private const val STATUS_REFRESH_INTERVAL_MS = 60 * 1000L
