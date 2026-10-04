package com.safechild.ai.ui.screens

import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.safechild.ai.ui.components.QrScannerView
import com.safechild.ai.utils.QrCodeUtils
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentSettingsScreen(
    auth: FirebaseAuth,
    firestore: FirebaseFirestore,
    onBackClick: () -> Unit,
    onProfileClick: () -> Unit,
    onSafetySettingsClick: () -> Unit,
    onPrivacyDataClick: () -> Unit,
    onConnectedChildClick: () -> Unit,
    onAboutClick: () -> Unit,
    onChangePasswordClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    var childrenCount by remember { mutableStateOf(0) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    val parentUid = auth.currentUser?.uid

    LaunchedEffect(parentUid) {
        if (parentUid == null) return@LaunchedEffect
        firestore.collection("relationships")
            .whereEqualTo("parentUid", parentUid)
            .whereEqualTo("status", "active")
            .get()
            .addOnSuccessListener { result ->
                childrenCount = result.size()
            }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Log out?") },
            text = { Text("Are you sure you want to log out?") },
            confirmButton = {
                TextButton(onClick = onLogoutClick) {
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
                title = { Text("Settings") },
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
            // Account Section
            SettingsGroup(title = "Account") {
                SettingsItem(label = "Profile", icon = Icons.Default.Person, onClick = onProfileClick)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(label = "Change Password", icon = Icons.Default.Lock, onClick = onChangePasswordClick)
            }

            // Child & Family Section
            SettingsGroup(title = "Child & Family") {
                SettingsItem(
                    label = "Manage Connected Children", 
                    icon = Icons.Default.ChildCare, 
                    subtitle = if (childrenCount > 0) "$childrenCount children connected" else "No children connected",
                    onClick = onConnectedChildClick
                )
            }

            // Safety Section
            SettingsGroup(title = "Safety & Security") {
                SettingsItem(label = "Safety Settings", icon = Icons.Default.Security, onClick = onSafetySettingsClick)
            }

            // App Section
            SettingsGroup(title = "App") {
                SettingsItem(label = "Privacy & Data", icon = Icons.Default.VpnKey, onClick = onPrivacyDataClick)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
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
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentProfileScreen(auth: FirebaseAuth, firestore: FirebaseFirestore, onBackClick: () -> Unit) {
    val uid = auth.currentUser?.uid
    var profile by remember(uid) { mutableStateOf<Map<String, Any>?>(null) }
    var loading by remember(uid) { mutableStateOf(true) }
    LaunchedEffect(uid) {
        if (uid == null) { loading = false; return@LaunchedEffect }
        firestore.collection("users").document(uid).get()
            .addOnSuccessListener { profile = it.data; loading = false }
            .addOnFailureListener { loading = false }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Parent Profile") }, navigationIcon = {
        IconButton(onClick = onBackClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (loading) CircularProgressIndicator() else {
                ProfileValue("Name", profile?.get("name") as? String ?: auth.currentUser?.displayName ?: "Name not set")
                ProfileValue("Email", auth.currentUser?.email ?: "Email unavailable")
                ProfileValue("Account role", profile?.get("role") as? String ?: "parent")
            }
        }
    }
}

@Composable
private fun ProfileValue(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    } }
}

enum class QrPairingState {
    IDLE,
    QR_DETECTED,
    READING_PAIRING_REQUEST,
    AWAITING_CONFIRMATION,
    CREATING_RELATIONSHIP,
    CONNECTED,
    ERROR
}

private data class ConnectedChild(
    val uid: String,
    val name: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildConnectionScreen(
    auth: FirebaseAuth,
    firestore: FirebaseFirestore,
    onBackClick: () -> Unit,
    onBackToDashboardClick: () -> Unit,
    onConnectedChildClick: (String) -> Unit
) {
    var connectedChildren by remember { mutableStateOf<List<ConnectedChild>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var pairingState by remember { mutableStateOf(QrPairingState.IDLE) }

    var pendingScannedReqId by remember { mutableStateOf<String?>(null) }
    var pendingChildName by remember { mutableStateOf<String?>(null) }
    var pendingChildUid by remember { mutableStateOf<String?>(null) }
    var showConfirmDialog by remember { mutableStateOf(false) }

    val parentUid = auth.currentUser?.uid

    DisposableEffect(parentUid) {
        if (parentUid == null) return@DisposableEffect onDispose {}

        val listener = firestore.collection("relationships")
            .whereEqualTo("parentUid", parentUid)
            .whereEqualTo("status", "active")
            .addSnapshotListener { result, e ->
                if (e != null) {
                    Log.e("ChildConnection", "Error checking relationships: ${e.message}")
                    return@addSnapshotListener
                }
                if (result != null) {
                    val uids = result.documents.mapNotNull { it.getString("childUid") }.distinct()
                    connectedChildren = uids.map { ConnectedChild(it, "Child") }
                    uids.forEach { uid ->
                        firestore.collection("users").document(uid).get()
                            .addOnSuccessListener { userDoc ->
                                val name = userDoc.getString("name") ?: "Child"
                                connectedChildren = connectedChildren.map { child ->
                                    if (child.uid == uid) child.copy(name = name) else child
                                }
                            }
                    }
                }
            }

        onDispose {
            listener.remove()
        }
    }

    fun handleScannedQr(qrText: String) {
        if (pairingState == QrPairingState.READING_PAIRING_REQUEST ||
            pairingState == QrPairingState.AWAITING_CONFIRMATION ||
            pairingState == QrPairingState.CREATING_RELATIONSHIP ||
            pairingState == QrPairingState.CONNECTED) return

        pairingState = QrPairingState.QR_DETECTED
        Log.d("QrPairing", "QR_DETECTED: rawValue=$qrText")

        val reqId = QrCodeUtils.extractRequestId(qrText)
        Log.d("QrPairing", "QR_REQUEST_ID: $reqId")

        if (reqId == null) {
            pairingState = QrPairingState.ERROR
            errorMessage = "Invalid QR code. Please scan a valid child pairing QR code."
            Log.e("QrPairing", "ERROR: Failed to extract requestId from $qrText")
            return
        }

        pairingState = QrPairingState.READING_PAIRING_REQUEST
        errorMessage = null
        statusMessage = "Verifying pairing request..."

        firestore.collection("pairing_requests").document(reqId).get()
            .addOnSuccessListener { doc ->
                val exists = doc.exists()
                val childUid = doc.getString("childUid")
                val childName = doc.getString("childName") ?: "Child"
                val status = doc.getString("status")
                val expiresAt = doc.getTimestamp("expiresAt")

                Log.d("QrPairing", "PAIRING_REQUEST_READ: exists=$exists, childUid=$childUid, childName=$childName, status=$status")

                if (!exists) {
                    pairingState = QrPairingState.ERROR
                    errorMessage = "Pairing request not found or expired."
                    Log.e("QrPairing", "ERROR: pairing_requests/$reqId does not exist in Firestore")
                    return@addOnSuccessListener
                }

                if (status != "pending") {
                    pairingState = QrPairingState.ERROR
                    errorMessage = "This pairing request has already been used."
                    Log.e("QrPairing", "ERROR: pairing_requests/$reqId status is $status (expected pending)")
                    return@addOnSuccessListener
                }

                if (expiresAt == null || expiresAt.toDate().before(Date())) {
                    pairingState = QrPairingState.ERROR
                    errorMessage = "This pairing request has expired."
                    Log.e("QrPairing", "ERROR: pairing_requests/$reqId expired at $expiresAt")
                    return@addOnSuccessListener
                }

                if (childUid.isNullOrEmpty()) {
                    pairingState = QrPairingState.ERROR
                    errorMessage = "Invalid child profile in request."
                    Log.e("QrPairing", "ERROR: pairing_requests/$reqId has null or empty childUid")
                    return@addOnSuccessListener
                }

                pendingScannedReqId = reqId
                pendingChildUid = childUid
                pendingChildName = childName
                pairingState = QrPairingState.AWAITING_CONFIRMATION
                showConfirmDialog = true
            }
            .addOnFailureListener { e ->
                pairingState = QrPairingState.ERROR
                val fe = e as? FirebaseFirestoreException
                val errorCode = fe?.code?.name ?: "UNKNOWN"
                errorMessage = "Failed to verify pairing code: ${e.message}"
                Log.e("QrPairing", "PAIRING_REQUEST_READ_FAILED: Class=${e.javaClass.name}, Code=$errorCode, Msg=${e.message}", e)
            }
    }

    fun confirmAndLinkChild() {
        val reqId = pendingScannedReqId ?: return
        val childUid = pendingChildUid ?: return
        val pUid = auth.currentUser?.uid
        Log.d("QrPairing", "PARENT_UID: $pUid")

        if (pUid == null) {
            pairingState = QrPairingState.ERROR
            errorMessage = "Parent is not logged in. Please sign in again."
            Log.e("QrPairing", "ERROR: auth.currentUser is null when confirming pairing")
            return
        }

        pairingState = QrPairingState.CREATING_RELATIONSHIP
        showConfirmDialog = false
        statusMessage = "Linking child device..."

        Log.d("QrPairing", "RELATIONSHIP_WRITE_STARTED")

        val relId = "${pUid}_${childUid}"
        val relRef = firestore.collection("relationships").document(relId)
        val reqRef = firestore.collection("pairing_requests").document(reqId)
        Log.d("QrPairing", "PAIRING_TRANSACTION_STARTED: requestId=$reqId childUid=$childUid parentUid=$pUid")
        firestore.runTransaction { transaction ->
            val request = transaction.get(reqRef)
            if (!request.exists()) throw IllegalStateException("Pairing request not found.")
            if (request.getString("childUid") != childUid) throw IllegalStateException("Pairing request identity changed. Scan a new code.")
            if (request.getString("status") != "pending") throw IllegalStateException("This pairing code has already been used.")
            val expiresAt = request.getTimestamp("expiresAt")
            if (expiresAt == null || !expiresAt.toDate().after(Date())) throw IllegalStateException("This pairing code has expired.")
            if (pUid == childUid) throw IllegalStateException("A child device cannot pair with itself.")

            val childName = request.getString("childName") ?: pendingChildName ?: "Child"
            transaction.update(reqRef, mapOf(
                "status" to "used",
                "usedByParentUid" to pUid,
                "usedAt" to FieldValue.serverTimestamp()
            ))
            transaction.set(relRef, mapOf(
                "parentUid" to pUid,
                "childUid" to childUid,
                "childName" to childName,
                "status" to "active",
                "createdAt" to FieldValue.serverTimestamp(),
                "requestId" to reqId
            ))
            childName
        }
            .addOnSuccessListener { childName ->
                Log.d("QrPairing", "PAIRING_TRANSACTION_SUCCESS: relationships/$relId requestId=$reqId")
                pairingState = QrPairingState.CONNECTED
                statusMessage = "$childName connected successfully"
                errorMessage = null
            }
            .addOnFailureListener { e ->
                val fe = e as? FirebaseFirestoreException
                val errorCode = fe?.code?.name ?: "UNKNOWN"
                Log.e("QrPairing", "PAIRING_TRANSACTION_FAILED: Class=${e.javaClass.name}, Code=$errorCode, Msg=${e.message}", e)
                pairingState = QrPairingState.ERROR
                errorMessage = when {
                    e.message?.contains("already been used", ignoreCase = true) == true -> "This pairing code has already been used. Generate a new code on the child's device."
                    e.message?.contains("expired", ignoreCase = true) == true -> "This pairing code has expired. Generate a new code on the child's device."
                    else -> "Failed to connect child: ${e.message ?: "Please try again."}"
                }
            }
    }

    if (showConfirmDialog && pendingChildName != null) {
        AlertDialog(
            onDismissRequest = {
                showConfirmDialog = false
                pendingScannedReqId = null
                pendingChildUid = null
                pendingChildName = null
                pairingState = QrPairingState.IDLE
            },
            title = { Text("Confirm Pairing") },
            text = { Text("Connect to $pendingChildName?") },
            confirmButton = {
                Button(onClick = { confirmAndLinkChild() }) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showConfirmDialog = false
                    pendingScannedReqId = null
                    pendingChildUid = null
                    pendingChildName = null
                    pairingState = QrPairingState.IDLE
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connected Children") },
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = "Linked Devices",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth()
            )

            if (connectedChildren.isEmpty()) {
                Text(
                    text = "No children linked yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        connectedChildren.forEach { child ->
                            TextButton(
                                onClick = { onConnectedChildClick(child.uid) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Box(
                                    modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(child.name.take(1), color = Color.White)
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(child.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.weight(1f))
                                Text("Linked", color = Color(0xFF4CAF50), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            Text(
                text = "Scan Child QR Code",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth()
            )

            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }

            if (statusMessage != null) {
                Text(
                    text = statusMessage!!,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }

            // Keep the camera composed only while waiting to scan. Leaving this branch releases it.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                contentAlignment = Alignment.Center
            ) {
                if (pairingState == QrPairingState.CONNECTED) {
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(48.dp))
                            Text(statusMessage ?: "Child connected successfully", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                            Button(onClick = onBackToDashboardClick) { Text("Back to Dashboard") }
                        }
                    }
                } else if (pairingState == QrPairingState.IDLE) {
                    QrScannerView(
                        modifier = Modifier.fillMaxSize(),
                        onQrCodeScanned = ::handleScannedQr
                    )
                } else if (pairingState == QrPairingState.ERROR) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Scan another pairing code", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = {
                            errorMessage = null
                            statusMessage = null
                            pendingScannedReqId = null
                            pendingChildUid = null
                            pendingChildName = null
                            pairingState = QrPairingState.IDLE
                        }) { Text("Try Again") }
                    }
                } else if (pairingState == QrPairingState.READING_PAIRING_REQUEST || pairingState == QrPairingState.CREATING_RELATIONSHIP || pairingState == QrPairingState.AWAITING_CONFIRMATION || pairingState == QrPairingState.QR_DETECTED) {
                    CircularProgressIndicator()
                }
            }

            if (pairingState == QrPairingState.IDLE) Text(
                text = "Ask your child to open SafeChild AI, tap 'Child', and point your camera at their QR code.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

// Keep other components...
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentSafetySettingsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Safety Settings") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Current app capabilities", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Location sharing is controlled on the child's device from My Location.", style = MaterialTheme.typography.bodyMedium)
                    Text("New SOS alerts can ring on this phone while SafeChild AI is running. Android notification permission and the Emergency SOS alerts channel must be enabled.", style = MaterialTheme.typography.bodyMedium)
                    Text("If the app is force-stopped or Android removes it from memory, this no-billing build cannot guarantee delivery. Reliable push alerts in that state need a trusted server sender.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        .putExtra(Settings.EXTRA_CHANNEL_ID, "sos_emergency_alerts_v2")
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open SOS notification settings") }
                }
            }
        }
    }
}

@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun SettingsItem(label: String, icon: ImageVector, subtitle: String? = null, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                if (subtitle != null) {
                    Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
fun ToggleSettingsItem(label: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}



