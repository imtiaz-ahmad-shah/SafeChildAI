package com.safechild.ai.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.safechild.ai.data.SafetyEventRepository
import com.safechild.ai.data.RoutineDemoModel
import com.safechild.ai.data.models.TrustedLocation
import com.safechild.ai.ui.theme.SafeChildAITheme
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentDashboardScreen(
    auth: FirebaseAuth,
    firestore: FirebaseFirestore,
    onLogoutClick: () -> Unit,
    onChildSelected: (String) -> Unit,
    onNavigateToMap: () -> Unit,
    onNavigateToAlerts: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToTrustedLocations: () -> Unit,
    onNavigateToChildConnection: () -> Unit = {},
    onNavigateToSos: () -> Unit
) {
    var childrenProfiles by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    val childrenLocations = remember { mutableStateMapOf<String, String>() }
    val childLocationUpdatedAt = remember { mutableStateMapOf<String, Long>() }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            childLocationUpdatedAt.forEach { (uid, updatedAt) ->
                val age = now - updatedAt
                childrenLocations[uid] = if (age in -MAX_CLOCK_SKEW_MS..ONLINE_WINDOW_MS) "Online" else "Offline"
            }
            delay(STATUS_REFRESH_INTERVAL_MS)
        }
    }
    val childrenEvents = remember { mutableStateMapOf<String, String>() }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var safetyEvents by remember { mutableStateOf<List<SafetyEventRepository.Event>>(emptyList()) }
    var safetyEventsError by remember { mutableStateOf<String?>(null) }

    val parentUid = auth.currentUser?.uid
    val appContext = LocalContext.current.applicationContext
    DisposableEffect(childrenProfiles) {
        childrenProfiles.mapNotNull { it["uid"] as? String }.forEach { uid ->
            RoutineDemoModel.clearRetiredSyntheticData(appContext, uid)
        }
        onDispose { }
    }

    DisposableEffect(parentUid) {
        if (parentUid == null) {
            Log.e("ParentDashboard", "Current User UID is null")
            errorMessage = "Authentication session error."
            isLoading = false
            return@DisposableEffect onDispose {}
        }
        
        Log.d("ParentDashboard", "Checking/Repairing parent profile for: $parentUid")
        val activeRegistrations = mutableListOf<ListenerRegistration>()
        
        firestore.collection("users").document(parentUid).get()
            .addOnSuccessListener { document ->
                val setupSnapshotListener = {
                    Log.d("ParentDashboard", "Listening for relationships for parent: $parentUid")
                    val relListener = firestore.collection("relationships")
                        .whereEqualTo("parentUid", parentUid)
                        .whereEqualTo("status", "active")
                        .addSnapshotListener { result, e ->
                            if (e != null) {
                                Log.e("ParentDashboard", "Firestore error checking relationships: ${e.code} - ${e.message}", e)
                                errorMessage = "Failed to check relationship status: ${e.code}"
                                isLoading = false
                                return@addSnapshotListener
                            }

                            if (result != null && !result.isEmpty) {
                                val childUids = result.documents.mapNotNull { it.getString("childUid") }
                                Log.d("ParentDashboard", "Found ${childUids.size} linked children")
                                
                                if (childUids.isNotEmpty()) {
                                    val profilesList = mutableListOf<Map<String, Any>>()
                                    var fetchedCount = 0

                                    childUids.forEach { uid ->
                                        firestore.collection("users").document(uid).get()
                                            .addOnSuccessListener { userDoc ->
                                                val data = userDoc.data
                                                if (data != null) {
                                                    profilesList.add(data)
                                                }
                                                fetchedCount++
                                                if (fetchedCount == childUids.size) {
                                                    childrenProfiles = profilesList
                                                    isLoading = false
                                                }
                                            }
                                            .addOnFailureListener { pe ->
                                                Log.e("ParentDashboard", "Failed to load child profile for $uid: ${pe.message}")
                                                fetchedCount++
                                                if (fetchedCount == childUids.size) {
                                                    childrenProfiles = profilesList
                                                    isLoading = false
                                                }
                                            }

                                        // Location listener
                                        val locListener = firestore.collection("users").document(uid)
                                            .collection("location").document("current")
                                            .addSnapshotListener { locSnapshot, locError ->
                                                if (locError != null) {
                                                    Log.e("ParentDashboard", "Error loading location for $uid: ${locError.message}")
                                                }
                                                val updatedAt = locSnapshot?.takeIf { it.exists() }
                                                    ?.getTimestamp("timestamp")?.toDate()?.time
                                                if (updatedAt == null) {
                                                    childLocationUpdatedAt.remove(uid)
                                                    childrenLocations[uid] = "Offline"
                                                } else {
                                                    childLocationUpdatedAt[uid] = updatedAt
                                                    val age = System.currentTimeMillis() - updatedAt
                                                    childrenLocations[uid] =
                                                        if (age in -MAX_CLOCK_SKEW_MS..ONLINE_WINDOW_MS) "Online" else "Offline"
                                                }
                                            }
                                        activeRegistrations.add(locListener)

                                        // Events listener
                                        val eventListener = firestore.collection("users").document(uid)
                                            .collection("locationEvents")
                                            .orderBy("timestamp", Query.Direction.DESCENDING)
                                            .limit(1)
                                            .addSnapshotListener { eventSnapshot, eventError ->
                                                if (eventError != null) {
                                                    Log.w("ParentDashboard", "No events or error for $uid: ${eventError.message}")
                                                }
                                                if (eventSnapshot != null && !eventSnapshot.isEmpty) {
                                                    val event = eventSnapshot.documents[0]
                                                    val type = event.getString("eventType") ?: ""
                                                    childrenEvents[uid] = if (type == "ENTER") "Entered safe zone" else "Left safe zone"
                                                }
                                            }
                                        activeRegistrations.add(eventListener)
                                    }
                                } else {
                                    childrenProfiles = emptyList()
                                    isLoading = false
                                }
                            } else {
                                Log.d("ParentDashboard", "No relationships found")
                                childrenProfiles = emptyList()
                                isLoading = false
                            }
                        }
                    activeRegistrations.add(relListener)
                }

                if (!document.exists() || document.getString("role") != "parent") {
                    Log.w("ParentDashboard", "Parent profile missing or incorrect role. Initiating repair...")
                    val currentUser = auth.currentUser
                    val userData = hashMapOf(
                        "uid" to parentUid,
                        "name" to (document.getString("name") ?: currentUser?.displayName ?: currentUser?.email?.substringBefore("@") ?: "Parent"),
                        "email" to (document.getString("email") ?: currentUser?.email ?: ""),
                        "role" to "parent",
                        "createdAt" to (document.getTimestamp("createdAt") ?: FieldValue.serverTimestamp())
                    )
                    firestore.collection("users").document(parentUid).set(userData)
                        .addOnSuccessListener {
                            Log.d("ParentDashboard", "Parent profile repaired successfully")
                            setupSnapshotListener()
                        }
                        .addOnFailureListener { pe ->
                            Log.e("ParentDashboard", "Failed to repair parent profile: ${pe.message}")
                            errorMessage = "Failed to initialize parent profile: ${pe.message}"
                            isLoading = false
                        }
                } else {
                    setupSnapshotListener()
                }
            }
            .addOnFailureListener { e ->
                Log.e("ParentDashboard", "Error fetching parent profile: ${e.message}")
                errorMessage = "Failed to verify parent profile configuration."
                isLoading = false
            }

        onDispose {
            activeRegistrations.forEach { it.remove() }
            activeRegistrations.clear()
        }
    }

    DisposableEffect(parentUid) {
        if (parentUid.isNullOrBlank()) return@DisposableEffect onDispose { }
        val registration = SafetyEventRepository.listenForParentEvents(
            firestore = firestore,
            parentUid = parentUid,
            onEvents = {
                safetyEvents = it
                safetyEventsError = null
            },
            onError = { error ->
                Log.e("SafetyEvents", "Parent dashboard safety-event listener failed for $parentUid", error)
                safetyEventsError = error.localizedMessage ?: "Could not load safety check-ins and SOS alerts."
            }
        )
        onDispose { registration.remove() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "SafeChild AI",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    ) 
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            SafeChildBottomNavigation(
                currentRoute = "home",
                onHomeClick = { },
                onMapClick = onNavigateToMap,
                onAlertsClick = onNavigateToAlerts,
                onHistoryClick = onNavigateToHistory,
                onSettingsClick = onNavigateToSettings
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (errorMessage != null) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { isLoading = true; errorMessage = null }) {
                        Text("Retry")
                    }
                }
            } else if (childrenProfiles.isEmpty()) {
                NoChildrenConnectedState(onNavigateToChildConnection)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    item {
                        Text(
                            text = "My Children",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    items(childrenProfiles) { profile ->
                        val uid = profile["uid"] as? String ?: ""
                        val name = profile["name"] as? String ?: "Child"
                        val status = childrenLocations[uid] ?: "Determining..."
                        val lastEvent = childrenEvents[uid] ?: "No recent events"
                        
                        ChildStatusCard(
                            name = name,
                            status = status,
                            lastUpdated = lastEvent,
                            locationName = if (status == "Online") "View Live Location" else "Offline",
                            onClick = { onChildSelected(uid) }
                        )
                    }

                    item {
                        SafetyEventsDashboardCard(
                            events = safetyEvents,
                            error = safetyEventsError,
                            onClick = onNavigateToSos
                        )
                    }

                    item {
            SafetyOverview(
                activeChildren = childrenProfiles.size,
                openSafetyEvents = safetyEvents.count { it.status == SafetyEventRepository.OPEN },
            )
                    }

                    item {
                        SafetyActions(
                            onAlertsClick = onNavigateToAlerts,
                            onTrustedLocationsClick = onNavigateToTrustedLocations,
                            onRoutineClick = onNavigateToHistory,
                            onSosClick = onNavigateToSos
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onLogoutClick,
                            modifier = Modifier.fillMaxWidth(),
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
        }
    }
}

@Composable
private fun SafetyEventsDashboardCard(
    events: List<SafetyEventRepository.Event>,
    error: String?,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Safety check-ins & SOS", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            when {
                error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                events.isEmpty() -> Text("No safety check-ins or SOS alerts yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> events.take(3).forEach { event ->
                    val isSos = event.type == SafetyEventRepository.SOS
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isSos) Icons.Default.Warning else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (isSos) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                if (isSos) "SOS from ${event.childName}" else "${event.childName} checked in safe",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                event.createdAt?.toDate()?.let {
                                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(it)
                                } ?: "Just received",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Text("Open safety activity", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// Keep ChildStatusCard and other sub-composables as they were...
@Composable
fun NoChildrenConnectedState(onNavigateToConnect: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.ChildCare, 
            contentDescription = null, 
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No children connected yet",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Connect a child to start using SafeChild AI.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onNavigateToConnect) {
            Text("Manage Connection")
        }
    }
}

@Composable
fun ChildStatusCard(
    name: String,
    status: String,
    lastUpdated: String,
    locationName: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.take(1),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Child",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (status == "Online") Color(0xFF4CAF50) else Color.Gray)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = locationName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = lastUpdated,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SafetyOverview(activeChildren: Int = 0, openSafetyEvents: Int = 0) {
    Column {
        Text(
            text = "Group Safety Overview",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OverviewItemCard(
                modifier = Modifier.weight(1f),
                title = "Connected Children",
                value = activeChildren.toString(),
                icon = Icons.Default.Place,
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
            OverviewItemCard(
                modifier = Modifier.weight(1f),
                title = "Group Alerts",
                value = "$openSafetyEvents new",
                icon = Icons.Default.NotificationsActive,
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@Composable
fun OverviewItemCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: ImageVector,
    containerColor: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = title, style = MaterialTheme.typography.labelMedium)
            Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SafetyActions(
    onAlertsClick: () -> Unit,
    onTrustedLocationsClick: () -> Unit,
    onRoutineClick: () -> Unit,
    onSosClick: () -> Unit
) {
    Column {
        Text(
            text = "Safety Actions",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SafetyActionCard(
                    modifier = Modifier.weight(1f),
                    title = "View Alerts",
                    icon = Icons.Default.Notifications,
                    onClick = onAlertsClick
                )
                SafetyActionCard(
                    modifier = Modifier.weight(1f),
                    title = "Trusted Locations",
                    icon = Icons.Default.Map,
                    onClick = onTrustedLocationsClick
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SafetyActionCard(
                    modifier = Modifier.weight(1f),
                    title = "AI Activity",
                    icon = Icons.AutoMirrored.Filled.EventNote,
                    onClick = onRoutineClick
                )
                SafetyActionCard(
                    modifier = Modifier.weight(1f),
                    title = "SOS / Emergency",
                    icon = Icons.Default.Warning,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = onSosClick
                )
            }
        }
    }
}

@Composable
fun SafetyActionCard(
    modifier: Modifier = Modifier,
    title: String,
    icon: ImageVector,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(100.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(imageVector = icon, contentDescription = null)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun SafeChildBottomNavigation(
    currentRoute: String,
    onHomeClick: () -> Unit,
    onMapClick: () -> Unit,
    onAlertsClick: () -> Unit,
    onHistoryClick: () -> Unit,
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
            icon = { Icon(Icons.Default.Map, contentDescription = "Map") },
            label = { Text("Map") },
            selected = currentRoute == "map",
            onClick = onMapClick
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Notifications, contentDescription = "Alerts") },
            label = { Text("Alerts") },
            selected = currentRoute == "alerts",
            onClick = onAlertsClick
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.History, contentDescription = "History") },
            label = { Text("History") },
            selected = currentRoute == "history",
            onClick = onHistoryClick
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings") },
            selected = currentRoute == "settings",
            onClick = onSettingsClick
        )
    }
}


private const val ONLINE_WINDOW_MS = 20 * 60 * 1000L
private const val MAX_CLOCK_SKEW_MS = 2 * 60 * 1000L
private const val STATUS_REFRESH_INTERVAL_MS = 30 * 1000L



