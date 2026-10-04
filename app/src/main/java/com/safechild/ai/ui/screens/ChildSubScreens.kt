package com.safechild.ai.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.safechild.ai.ui.components.OpenStreetMapView
import com.safechild.ai.ui.components.OSMCoordinate
import com.safechild.ai.ui.components.OSMPlace
import com.safechild.ai.data.models.TrustedLocation
import com.safechild.ai.utils.startLocationService
import com.safechild.ai.utils.stopLocationService
import com.safechild.ai.utils.isLocationSharingEnabled

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildLocationScreen(
    auth: FirebaseAuth,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val childUid = remember { auth.currentUser?.uid ?: "" }
    var isSharingEnabled by remember(childUid) { mutableStateOf(isLocationSharingEnabled(context, childUid)) }
    var showBackgroundExplanation by remember { mutableStateOf(false) }
    var childLocation by remember { mutableStateOf<Map<String, Any>?>(null) }
    var routePoints by remember { mutableStateOf<List<OSMCoordinate>>(emptyList()) }
    var trustedPlaces by remember { mutableStateOf<List<OSMPlace>>(emptyList()) }

    DisposableEffect(childUid) {
        if (childUid.isBlank()) return@DisposableEffect onDispose {}
        val firestore = FirebaseFirestore.getInstance()
        val listener = firestore
            .collection("users").document(childUid)
            .collection("location").document("current")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    childLocation = snapshot.data
                }
            }
        val historyListener = firestore.collection("users").document(childUid)
            .collection("locationHistory")
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("ChildLocation", "Failed to read own location history", error)
                    routePoints = emptyList()
                } else {
                    routePoints = snapshot?.documents.orEmpty().mapNotNull { doc ->
                        val lat = (doc.get("latitude") as? Number)?.toDouble()
                        val lng = (doc.get("longitude") as? Number)?.toDouble()
                        if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0 && (lat != 0.0 || lng != 0.0)) OSMCoordinate(lat, lng) else null
                    }.asReversed()
                }
            }
        var parentPlacesListener: com.google.firebase.firestore.ListenerRegistration? = null
        val profileListener = firestore.collection("users").document(childUid)
            .addSnapshotListener { profile, error ->
                if (error != null) {
                    android.util.Log.e("ChildLocation", "Failed to find linked parent for trusted places", error)
                    return@addSnapshotListener
                }
                val parentUid = profile?.getString("parentUid")
                parentPlacesListener?.remove()
                parentPlacesListener = if (parentUid.isNullOrBlank()) null else {
                    firestore.collection("users").document(parentUid).collection("trustedLocations")
                        .whereEqualTo("enabled", true)
                        .addSnapshotListener { locations, placeError ->
                            if (placeError != null) {
                                android.util.Log.e("ChildLocation", "Failed to read linked trusted places", placeError)
                                trustedPlaces = emptyList()
                            } else {
                                trustedPlaces = locations?.documents.orEmpty().mapNotNull { doc ->
                                    val place = doc.toObject(TrustedLocation::class.java)
                                    if (place != null && place.latitude in -90.0..90.0 && place.longitude in -180.0..180.0 && (place.latitude != 0.0 || place.longitude != 0.0)) OSMPlace(place.name, place.latitude, place.longitude) else null
                                }
                            }
                        }
                }
            }
        onDispose {
            listener.remove()
            historyListener.remove()
            profileListener.remove()
            parentPlacesListener?.remove()
        }
    }

    val backgroundPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val hasBackground = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted || hasBackground) {
            isSharingEnabled = true
            startLocationService(context, childUid)
        } else {
            isSharingEnabled = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                showBackgroundExplanation = true
            } else {
                isSharingEnabled = true
                startLocationService(context, childUid)
            }
        } else {
            isSharingEnabled = false
        }
    }

    if (showBackgroundExplanation) {
        AlertDialog(
            onDismissRequest = { showBackgroundExplanation = false },
            title = { Text("Background Location") },
            text = { Text("SafeChild AI needs access to your location 'All the time' to keep your parent updated even when the app is closed or your screen is locked.") },
            confirmButton = {
                Button(onClick = {
                    showBackgroundExplanation = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                }) {
                    Text("Grant 'All the time'")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBackgroundExplanation = false
                    isSharingEnabled = true
                    startLocationService(context, childUid)
                }) {
                    Text("Foreground Only")
                }
            }
        )
    }

    fun handleToggle(enabled: Boolean) {
        if (enabled) {
            val hasFineLocation = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarseLocation = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (hasFineLocation || hasCoarseLocation) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val hasBackground = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasBackground) {
                        isSharingEnabled = true
                        startLocationService(context, childUid)
                    } else {
                        showBackgroundExplanation = true
                    }
                } else {
                    isSharingEnabled = true
                    startLocationService(context, childUid)
                }
            } else {
                val perms = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    perms.add("android.permission.POST_NOTIFICATIONS")
                }
                permissionLauncher.launch(perms.toTypedArray())
            }
        } else {
            isSharingEnabled = false
            stopLocationService(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("My Location", fontWeight = FontWeight.Bold)
                        Text("Your shared location", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // Map Area
            val lat = (childLocation?.get("latitude") as? Number)?.toDouble()
            val lng = (childLocation?.get("longitude") as? Number)?.toDouble()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(350.dp)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(20.dp))
            ) {
                OpenStreetMapView(
                    modifier = Modifier.fillMaxSize(),
                    latitude = lat,
                    longitude = lng,
                    markerTitle = "My Location",
                    places = trustedPlaces,
                    route = routePoints
                )
                if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0 || (lat == 0.0 && lng == 0.0)) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                    ) {
                        Text("Waiting for your location…", Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Location Sharing Status Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSharingEnabled) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isSharingEnabled) "Location Sharing: ON" else "Location Sharing: OFF",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isSharingEnabled) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Switch(
                                checked = isSharingEnabled,
                                onCheckedChange = { 
                                    handleToggle(it)
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isSharingEnabled) 
                                "Your location is currently being shared with your parent or guardian." 
                                else "Your location is not currently being shared.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSharingEnabled) Color(0xFF2E7D32).copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Last Updated Info
                Row(modifier = Modifier.fillMaxWidth()) {
                    Card(
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Last updated", style = MaterialTheme.typography.labelSmall)
                            Text((childLocation?.get("timestamp") as? com.google.firebase.Timestamp)?.toDate()?.let {
                                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(it)
                            } ?: "No update yet", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    Card(
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Status", style = MaterialTheme.typography.labelSmall)
                            Text(if (isSharingEnabled) "Tracking" else "Offline", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = if (isSharingEnabled) MaterialTheme.colorScheme.primary else Color.Gray)
                        }
                    }
                }

                // Privacy Information
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PrivacyTip, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("Your Privacy", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "SafeChild AI only uses information needed for the safety features enabled by your parent or guardian. The app does not read your private messages or access your camera or microphone.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // Parent Connection
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Sharing with", style = MaterialTheme.typography.labelSmall)
                            Text("Parent / Guardian", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("Connected", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50))
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildHelpScreen(
    auth: FirebaseAuth,
    firestore: FirebaseFirestore,
    onBackClick: () -> Unit,
    onCheckInClick: () -> Unit,
    onSosClick: () -> Unit
) {
    val context = LocalContext.current
    var contactMessage by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Help & Safety", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HelpActionCard(
                title = "I'm Safe",
                description = "Send a quick check-in.",
                icon = Icons.Default.CheckCircle,
                color = Color(0xFF4CAF50),
                onClick = onCheckInClick
            )

            HelpActionCard(
                title = "Contact Parent",
                description = "Get in touch with your parent or guardian.",
                icon = Icons.Default.Call,
                color = MaterialTheme.colorScheme.primary,
                onClick = {
                    val childUid = auth.currentUser?.uid
                    if (childUid == null) contactMessage = "Please sign in again to contact your parent."
                    else firestore.collection("relationships").whereEqualTo("childUid", childUid)
                        .whereEqualTo("status", "active").limit(1).get()
                        .addOnSuccessListener { result ->
                            val parentUid = result.documents.firstOrNull()?.getString("parentUid")
                            if (parentUid == null) contactMessage = "No connected parent was found."
                            else firestore.collection("users").document(parentUid).get()
                                .addOnSuccessListener { parent ->
                                    val email = parent.getString("email")
                                    if (email.isNullOrBlank()) contactMessage = "Your parent has no email address on their profile."
                                    else {
                                        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(email)}"))
                                        if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
                                        else contactMessage = "No email app is available on this device."
                                    }
                                }.addOnFailureListener { contactMessage = "Could not load your parent's contact details." }
                        }.addOnFailureListener { contactMessage = "Could not find your connected parent." }
                }
            )

            contactMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

            HelpActionCard(
                title = "SOS",
                description = "Use this if you need urgent help.",
                icon = Icons.Default.Warning,
                color = MaterialTheme.colorScheme.error,
                onClick = onSosClick
            )
        }
    }
}

@Composable
fun HelpActionCard(
    title: String,
    description: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(32.dp))
            }
            Spacer(modifier = Modifier.width(20.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildSettingsScreen(
    auth: FirebaseAuth,
    firestore: FirebaseFirestore,
    onBackClick: () -> Unit,
    onPrivacyClick: () -> Unit,
    onPrivacyDataClick: () -> Unit,
    onAboutClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    val context = LocalContext.current
    val childUid = auth.currentUser?.uid
    var childName by remember(childUid) { mutableStateOf("") }
    var isSavingName by remember { mutableStateOf(false) }
    var nameMessage by remember { mutableStateOf<String?>(null) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(childUid) {
        if (!childUid.isNullOrBlank()) {
            firestore.collection("users").document(childUid).get()
                .addOnSuccessListener { profile ->
                    childName = profile.getString("name").orEmpty()
                }
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Log out?") },
            text = { Text("Are you sure you want to log out?") },
            confirmButton = {
                Button(onClick = {
                    showLogoutDialog = false
                    stopLocationService(context)
                    onLogoutClick()
                }) {
                    Text("Log Out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SettingsGroup(title = "Child profile") {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This name appears to your parent and on check-ins and SOS alerts.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = childName,
                        onValueChange = { childName = it.take(50); nameMessage = null },
                        label = { Text("Your name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val uid = childUid
                            val normalizedName = childName.trim()
                            if (uid.isNullOrBlank() || normalizedName.isBlank()) {
                                nameMessage = "Enter your name before saving."
                            } else {
                                isSavingName = true
                                firestore.collection("users").document(uid)
                                    .set(mapOf("name" to normalizedName), com.google.firebase.firestore.SetOptions.merge())
                                    .addOnSuccessListener {
                                        childName = normalizedName
                                        nameMessage = "Name saved."
                                        isSavingName = false
                                    }
                                    .addOnFailureListener { error ->
                                        nameMessage = error.localizedMessage ?: "Could not save your name."
                                        isSavingName = false
                                    }
                            }
                        },
                        enabled = !isSavingName && childName.trim().isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { if (isSavingName) CircularProgressIndicator(Modifier.size(20.dp)) else Text("Save name") }
                    nameMessage?.let { Text(it, color = if (it == "Name saved.") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                }
            }

            // Privacy Section
            SettingsGroup(title = "Privacy") {
                SettingsItem(label = "My Privacy", icon = Icons.Default.Shield, onClick = onPrivacyClick)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(label = "Privacy & Data", icon = Icons.Default.VpnKey, onClick = onPrivacyDataClick)
            }

            // App Section
            SettingsGroup(title = "App") {
                SettingsItem(label = "About SafeChild AI", icon = Icons.Default.Info, onClick = onAboutClick)
            }

            // Logout
            Button(
                onClick = { showLogoutDialog = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text("Log Out")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildPrivacyScreen(
    onBackClick: () -> Unit,
    onLearnMoreClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Privacy", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = "Your parent or guardian can see the location you choose to share.",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Location is shared only while sharing is enabled on My Location and the app has location permission.", style = MaterialTheme.typography.bodyMedium)
                    Text("Your connected parent can see your current shared location and safety events in the app.", style = MaterialTheme.typography.bodyMedium)
                    Text("To review whether a check-in or SOS was sent, use the confirmation shown after sending it.", style = MaterialTheme.typography.bodyMedium)
                }
            }

            TextButton(
                onClick = onLearnMoreClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Learn More About Privacy")
                }
            }
        }
    }
}

@Composable
fun PrivacyStatusItem(label: String, value: String, icon: ImageVector, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.labelSmall)
            Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

