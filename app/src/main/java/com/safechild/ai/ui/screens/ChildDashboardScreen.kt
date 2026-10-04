package com.safechild.ai.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.safechild.ai.data.SafetyEventRepository
import com.safechild.ai.ui.components.OpenStreetMapView
import com.safechild.ai.utils.stopLocationService
import com.safechild.ai.utils.startLocationService
import com.safechild.ai.utils.isLocationSharingEnabled
import java.text.DateFormat
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildDashboardScreen(
    auth: FirebaseAuth,
    firestore: FirebaseFirestore,
    onLogoutClick: () -> Unit,
    onNavigateToLocation: () -> Unit,
    onNavigateToCheckIn: () -> Unit,
    onNavigateToHelp: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToPrivacy: () -> Unit
) {
    val context = LocalContext.current
    val childUid = remember { auth.currentUser?.uid }

    // Resume tracking after process death or app updates only when sharing was already enabled.
    LaunchedEffect(childUid) {
        val uid = childUid ?: return@LaunchedEffect
        val hasLocationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasLocationPermission && isLocationSharingEnabled(context, uid)) {
            runCatching { startLocationService(context, uid) }
                .onFailure { android.util.Log.e("ChildDashboard", "Could not resume enabled location sharing", it) }
        }
    }
    var childName by remember(childUid) {
        mutableStateOf(auth.currentUser?.displayName ?: auth.currentUser?.email?.substringBefore("@") ?: "Child")
    }
    DisposableEffect(childUid) {
        if (childUid.isNullOrBlank()) return@DisposableEffect onDispose { }
        val profileListener = firestore.collection("users").document(childUid)
            .addSnapshotListener { profile, _ ->
                profile?.getString("name")?.takeIf { it.isNotBlank() }?.let { childName = it }
            }
        onDispose { profileListener.remove() }
    }
    var childLocation by remember { mutableStateOf<Map<String, Any>?>(null) }
    var hasConnectedParent by remember { mutableStateOf(false) }
    var isCheckedIn by remember { mutableStateOf(false) }
    var lastCheckInTime by remember { mutableStateOf("Not checked in today") }
    var isSendingCheckIn by remember { mutableStateOf(false) }
    var isSendingSos by remember { mutableStateOf(false) }
    var safetyActionError by remember { mutableStateOf<String?>(null) }
    var showSosDialog by remember { mutableStateOf(false) }
    var sosSent by remember { mutableStateOf(false) }

    DisposableEffect(childUid) {
        if (childUid == null) return@DisposableEffect onDispose {}
        val listener = FirebaseFirestore.getInstance()
            .collection("users").document(childUid)
            .collection("location").document("current")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    childLocation = snapshot.data
                }
            }
        onDispose {
            listener.remove()
        }
    }

    DisposableEffect(childUid) {
        if (childUid == null) return@DisposableEffect onDispose {}
        val registration = firestore.collection("relationships")
            .whereEqualTo("childUid", childUid).whereEqualTo("status", "active")
            .addSnapshotListener { snapshot, _ -> hasConnectedParent = snapshot?.isEmpty == false }
        onDispose { registration.remove() }
    }

    DisposableEffect(childUid) {
        val uid = childUid
        if (uid.isNullOrBlank()) return@DisposableEffect onDispose { }
        val registration = SafetyEventRepository.listenForChildEvents(
            firestore = firestore,
            childUid = uid,
            onEvents = { events ->
                val startOfToday = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val latestToday = events.asSequence()
                    .filter { it.type == SafetyEventRepository.SAFE_CHECK_IN }
                    .mapNotNull { it.createdAt?.toDate()?.time }
                    .firstOrNull { it >= startOfToday }
                isCheckedIn = latestToday != null
                lastCheckInTime = latestToday?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(java.util.Date(it)) }
                    ?: "Not checked in today"
            },
            onError = { error ->
                android.util.Log.w("ChildDashboard", "Could not restore today's safe check-in status", error)
                safetyActionError = "Could not load today's check-in status. Check your connection."
            }
        )
        onDispose { registration.remove() }
    }

    if (showSosDialog) {
        AlertDialog(
            onDismissRequest = { showSosDialog = false },
            title = { Text("Send SOS?") },
                    text = { Text("This will send an SOS alert to your connected parent or guardian.") },
            confirmButton = {
                Button(
                    onClick = {
                                isSendingSos = true
                                safetyActionError = null
                                SafetyEventRepository.sendChildEvent(auth, firestore, SafetyEventRepository.SOS) { result ->
                                    isSendingSos = false
                                    result.onSuccess {
                                        sosSent = true
                                        showSosDialog = false
                                    }.onFailure {
                                        safetyActionError = it.localizedMessage ?: "SOS could not be sent. Please try again."
                                    }
                                }
                    },
                            enabled = !isSendingSos,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    if (isSendingSos) CircularProgressIndicator() else Text("Confirm SOS")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSosDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("SafeChild AI", style = MaterialTheme.typography.labelSmall)
                        Text("Hi, $childName", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            ChildBottomNavigation(
                currentRoute = "home",
                onHomeClick = { },
                onLocationClick = onNavigateToLocation,
                onCheckInClick = onNavigateToCheckIn,
                onHelpClick = onNavigateToHelp,
                onSettingsClick = onNavigateToSettings
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // SOS Alert Banner
            safetyActionError?.let { message ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(message, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            if (sosSent) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("SOS Sent", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            Text("SOS saved and visible to your connected parent in the app.", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = { sosSent = false }) {
                            Text("Clear")
                        }
                    }
                }
            }

            // Safety Status Card
            SafetyStatusCard(hasLocation = childLocation != null)

            // Current Location Card
            ChildCurrentLocationCard(
                childLocation = childLocation,
                locationName = if (childLocation != null) "Current Position" else "Locating...",
                lastUpdated = (childLocation?.get("timestamp") as? com.google.firebase.Timestamp)?.toDate()?.let {
                    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(it)
                } ?: "Waiting for first update",
                onViewLocationClick = onNavigateToLocation
            )

            // Safety Check-In Section
            CheckInCard(
                isCheckedIn = isCheckedIn,
                isSending = isSendingCheckIn,
                lastCheckInTime = lastCheckInTime,
                onCheckInClick = {
                    isSendingCheckIn = true
                    safetyActionError = null
                    SafetyEventRepository.sendChildEvent(auth, firestore, SafetyEventRepository.SAFE_CHECK_IN) { result ->
                        isSendingCheckIn = false
                        result.onSuccess {
                            isCheckedIn = true
                            lastCheckInTime = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
                        }.onFailure {
                            safetyActionError = it.localizedMessage ?: "Safe check-in could not be sent. Please try again."
                        }
                    }
                }
            )

            // SOS Button Section
            SosActionCard(onSosClick = { showSosDialog = true })

            // Parent Connection
            ParentConnectionCard(isConnected = hasConnectedParent, onViewConnectionClick = onNavigateToPrivacy)

            Spacer(modifier = Modifier.height(16.dp))
            
            TextButton(
                onClick = {
                    stopLocationService(context)
                    onLogoutClick()
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Temporary Logout")
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun SafetyStatusCard(hasLocation: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (hasLocation) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (hasLocation) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = Color.White)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = if (hasLocation) "A location update is available." else "No location update is available yet.",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (hasLocation) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "This status does not indicate that everything is safe.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasLocation) Color(0xFF2E7D32).copy(alpha = 0.8f) else MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
fun ChildCurrentLocationCard(
    childLocation: Map<String, Any>?,
    locationName: String,
    lastUpdated: String,
    onViewLocationClick: () -> Unit
) {
    val lat = (childLocation?.get("latitude") as? Number)?.toDouble()
    val lng = (childLocation?.get("longitude") as? Number)?.toDouble()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Your Current Location",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                        OpenStreetMapView(
                            modifier = Modifier.fillMaxSize(),
                            latitude = lat,
                            longitude = lng,
                            markerTitle = "My Location",
                            locationZoom = 14.0
                        )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(locationName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Last updated $lastUpdated", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onViewLocationClick, contentPadding = PaddingValues(0.dp)) {
                        Text("View Location")
                    }
                }
            }
        }
    }
}

@Composable
fun CheckInCard(
    isCheckedIn: Boolean,
    isSending: Boolean = false,
    lastCheckInTime: String,
    onCheckInClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("I'm Safe", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Let your parent know that you are okay.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            Button(
                onClick = onCheckInClick,
                enabled = !isCheckedIn && !isSending,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp)
            ) {
                if (isSending) {
                    CircularProgressIndicator()
                } else if (isCheckedIn) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Check-in sent ✓")
                } else {
                    Text("I'm Safe")
                }
            }
            
            if (isCheckedIn) {
                Text(
                    "Your check-in is saved for your connected parent.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Latest check-in: $lastCheckInTime",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
fun SosActionCard(onSosClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Need Help?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "If you are in an emergency, use the SOS button to quickly notify your parent or guardian.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Button(
                onClick = onSosClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Warning, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("SEND SOS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ParentConnectionCard(isConnected: Boolean, onViewConnectionClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, contentDescription = null, tint = Color.White)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Parent / Guardian", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                Text(if (isConnected) "Connected" else "Not connected", style = MaterialTheme.typography.labelSmall,
                    color = if (isConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onViewConnectionClick) {
                Text("View")
            }
        }
    }
}

@Composable
fun ChildBottomNavigation(
    currentRoute: String,
    onHomeClick: () -> Unit,
    onLocationClick: () -> Unit,
    onCheckInClick: () -> Unit,
    onHelpClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        NavigationBarItem(
            icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
            label = { Text("Home") },
            selected = currentRoute == "home",
            onClick = onHomeClick
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.LocationOn, contentDescription = "Location") },
            label = { Text("Location") },
            selected = currentRoute == "location",
            onClick = onLocationClick
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.TaskAlt, contentDescription = "Check-in") },
            label = { Text("Check-in") },
            selected = currentRoute == "checkin",
            onClick = onCheckInClick
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Emergency, contentDescription = "Help") },
            label = { Text("Help") },
            selected = currentRoute == "help",
            onClick = onHelpClick
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings") },
            selected = currentRoute == "settings",
            onClick = onSettingsClick
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun ChildDashboardPreview() {
    // Preview won't work easily with real FirebaseAuth
}
