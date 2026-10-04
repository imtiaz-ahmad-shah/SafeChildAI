package com.safechild.ai.ui.screens

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.safechild.ai.utils.QrCodeUtils
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildAccessScreen(
    auth: FirebaseAuth,
    firestore: FirebaseFirestore,
    onBackClick: () -> Unit,
    onContinueClick: () -> Unit
) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf("Generating pairing QR code...") }
    var childNameDraft by remember { mutableStateOf("") }
    var needsChildName by remember { mutableStateOf(false) }

    var pairingChildUid by remember { mutableStateOf<String?>(null) }
    var pairingRequestId by remember { mutableStateOf<String?>(null) }
    var handledRelationshipId by remember { mutableStateOf<String?>(null) }

    fun promptForChildName() {
        needsChildName = true
        isLoading = false
        qrBitmap = null
        error = null
        statusText = "Enter your name to continue."
    }

    fun createPairingRequest(childName: String) {
        val normalizedName = childName.trim()
        if (normalizedName.isBlank()) {
            promptForChildName()
            error = "Enter your name before creating a pairing code."
            return
        }
        childNameDraft = normalizedName
        needsChildName = false
        val activeAuthUser = auth.currentUser
        if (activeAuthUser == null) {
            error = "Authentication required. Please sign in."
            isLoading = false
            return
        }

        val childUid = activeAuthUser.uid
        pairingChildUid = null
        pairingRequestId = null
        handledRelationshipId = null
        isLoading = true
        error = null
        statusText = "Generating pairing QR code..."

        Log.d("QrPairing", "CHILD_AUTH_UID: $childUid")

        // Non-blocking background write to /users/{childUid}
        val profileData = hashMapOf(
            "uid" to childUid,
            "name" to childName,
            "role" to "child",
            "createdAt" to FieldValue.serverTimestamp()
        )

        firestore.collection("users").document(childUid)
            .set(profileData, SetOptions.merge())
            .addOnSuccessListener {
                Log.d("QrPairing", "CHILD_PROFILE_WRITE_SUCCESS: path=users/$childUid")
            }
            .addOnFailureListener { e ->
                val fe = e as? FirebaseFirestoreException
                val errorCode = fe?.code?.name ?: "UNKNOWN"
                Log.e("QrPairing", "CHILD_PROFILE_WRITE_FAILED: path=users/$childUid, class=${e.javaClass.name}, code=$errorCode, message=${e.message}")
            }

        // Create pairing request
        val reqRef = firestore.collection("pairing_requests").document()
        val newReqId = reqRef.id

        // Client-generated Timestamp (15 minutes in future)
        val expiresAtTimestamp = Timestamp(Date(System.currentTimeMillis() + 15 * 60 * 1000))

        val reqData = hashMapOf(
            "childUid" to childUid,
            "childName" to childName,
            "status" to "pending",
            "createdAt" to FieldValue.serverTimestamp(),
            "expiresAt" to expiresAtTimestamp
        )

        reqRef.set(reqData)
            .addOnSuccessListener {
                pairingChildUid = childUid
                pairingRequestId = newReqId
                handledRelationshipId = null
                Log.d("QrPairing", "CHILD_PAIRING_REQUEST_CREATED: path=pairing_requests/$newReqId")
                val qrString = QrCodeUtils.createQrString(newReqId)
                qrBitmap = QrCodeUtils.generateQrBitmap(qrString, 512)
                isLoading = false
                statusText = "Waiting for parent to scan QR code..."
            }
            .addOnFailureListener { e ->
                val fe = e as? FirebaseFirestoreException
                val errorCode = fe?.code?.name ?: "UNKNOWN"
                Log.e("QrPairing", "CHILD_PAIRING_REQUEST_CREATE_FAILED: path=pairing_requests/$newReqId, class=${e.javaClass.name}, code=$errorCode, message=${e.message}", e)
                isLoading = false
                error = "Failed to prepare pairing code: ${e.message}"
            }
    }

    LaunchedEffect(Unit) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            auth.signInAnonymously()
                .addOnSuccessListener { result ->
                    val user = result.user
                    if (user != null) {
                        Log.d("QrPairing", "CHILD_AUTH_UID: ${user.uid}")
                        promptForChildName()
                    } else {
                        error = "Failed to establish child identity."
                        isLoading = false
                    }
                }
                .addOnFailureListener { e ->
                    Log.w("QrPairing", "Anonymous auth failed (${e.message}). Attempting fallback child credential...")
                    val tempEmail = "child_${System.currentTimeMillis()}@safechild.ai"
                    val tempPass = "SafeChildPass123!"
                    auth.createUserWithEmailAndPassword(tempEmail, tempPass)
                        .addOnSuccessListener { res ->
                            val user = res.user
                            if (user != null) {
                                Log.d("QrPairing", "CHILD_AUTH_UID (Fallback): ${user.uid}")
                                promptForChildName()
                            } else {
                                error = "Failed to establish child identity."
                                isLoading = false
                            }
                        }
                        .addOnFailureListener { fe ->
                            error = "Authentication failed: ${fe.message ?: e.message}"
                            isLoading = false
                        }
                }
        } else {
            firestore.collection("users").document(currentUser.uid).get()
                .addOnSuccessListener { doc ->
                    if (doc.exists() && doc.getString("role") == "parent") {
                        // User is a parent. Sign out parent session to establish fresh child session.
                        auth.signOut()
                        auth.signInAnonymously()
                            .addOnSuccessListener { result ->
                                val user = result.user
                                if (user != null) {
                                    Log.d("QrPairing", "CHILD_AUTH_UID (Re-authed): ${user.uid}")
                                    promptForChildName()
                                } else {
                                    error = "Failed to establish child identity."
                                    isLoading = false
                                }
                            }
                            .addOnFailureListener { e ->
                                val tempEmail = "child_${System.currentTimeMillis()}@safechild.ai"
                                val tempPass = "SafeChildPass123!"
                                auth.createUserWithEmailAndPassword(tempEmail, tempPass)
                                    .addOnSuccessListener { res ->
                                        val user = res.user
                                        if (user != null) {
                                            Log.d("QrPairing", "CHILD_AUTH_UID (Fallback): ${user.uid}")
                                            promptForChildName()
                                        } else {
                                            error = "Failed to establish child identity."
                                            isLoading = false
                                        }
                                    }
                                    .addOnFailureListener { fe ->
                                        error = "Authentication failed: ${fe.message ?: e.message}"
                                        isLoading = false
                                    }
                            }
                    } else {
                        val savedName = doc.getString("name")?.trim()
                            ?.takeIf { it.isNotEmpty() && !it.equals("Child", ignoreCase = true) }
                            ?: currentUser.displayName?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Child", ignoreCase = true) }
                            ?: currentUser.email?.substringBefore("@")?.takeIf { it.isNotBlank() && !it.startsWith("child_", ignoreCase = true) }
                        if (savedName == null) {
                            promptForChildName()
                        } else {
                            childNameDraft = savedName
                            createPairingRequest(savedName)
                        }
                    }
                }
                .addOnFailureListener {
                    val savedName = currentUser.displayName?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Child", ignoreCase = true) }
                        ?: currentUser.email?.substringBefore("@")?.takeIf { it.isNotBlank() && !it.startsWith("child_", ignoreCase = true) }
                    if (savedName == null) promptForChildName() else {
                        childNameDraft = savedName
                        createPairingRequest(savedName)
                    }
                }
        }
    }

    // Listen for relationship creation by parent
    val activeUid = pairingChildUid
    val activeRequestId = pairingRequestId
    DisposableEffect(activeUid, activeRequestId) {
        if (activeUid == null || activeRequestId == null || auth.currentUser?.uid != activeUid) {
            Log.w("QrPairing", "CHILD_RELATIONSHIP_LISTENER_NOT_ATTACHED: requestUid=$activeUid authUid=${auth.currentUser?.uid} requestId=$activeRequestId")
            return@DisposableEffect onDispose {}
        }

        val currentAuthUser = auth.currentUser
        Log.d("QrPairing", "CHILD_RELATIONSHIP_LISTENER_ATTACHED: childUid=$activeUid, authUid=${currentAuthUser?.uid}, requestId=$activeRequestId")

        val listenerRegistration = firestore.collection("relationships")
            .whereEqualTo("childUid", activeUid)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    val errorCode = e.code.name
                    Log.e("QrPairing", "CHILD_RELATIONSHIP_LISTENER_FAILED: childUid=$activeUid, class=${e.javaClass.name}, code=$errorCode, message=${e.message}", e)
                    error = "Relationship listener error [$errorCode]: ${e.message}"
                    return@addSnapshotListener
                }

                if (snapshot != null && !snapshot.isEmpty) {
                    val relDoc = snapshot.documents.firstOrNull {
                        it.getString("status") == "active" && it.getString("requestId") == activeRequestId
                    } ?: return@addSnapshotListener
                    val parentUid = relDoc.getString("parentUid")
                    val relId = relDoc.id
                    if (parentUid != null && handledRelationshipId != relId && auth.currentUser?.uid == activeUid) {
                        handledRelationshipId = relId
                        Log.d("QrPairing", "CHILD_RELATIONSHIP_DETECTED: childUid=$activeUid, parentUid=$parentUid, relId=$relId")
                        Log.d("QrPairing", "CHILD_PROFILE_LINK_UPDATE_STARTED: path=users/$activeUid")

                        val userUpdate = hashMapOf(
                            "uid" to activeUid,
                            "role" to "child",
                            "parentUid" to parentUid,
                            "linkedAt" to FieldValue.serverTimestamp()
                        )
                        firestore.collection("users").document(activeUid)
                            .set(userUpdate, SetOptions.merge())
                            .addOnSuccessListener {
                                Log.d("QrPairing", "CHILD_PROFILE_LINK_UPDATE_SUCCESS: path=users/$activeUid")
                                Log.d("QrPairing", "PAIRING_COMPLETE_CHILD_SIDE")
                                onContinueClick()
                            }
                            .addOnFailureListener { pe ->
                                val fe = pe as? FirebaseFirestoreException
                                val errorCode = fe?.code?.name ?: "UNKNOWN"
                                Log.e("QrPairing", "CHILD_PROFILE_LINK_UPDATE_FAILED: path=users/$activeUid, class=${pe.javaClass.name}, code=$errorCode, message=${pe.message}", pe)
                                handledRelationshipId = null
                                error = "Failed to update profile [$errorCode]: ${pe.message}"
                            }
                    }
                }
            }

        onDispose {
            listenerRegistration.remove()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair Child Device") },
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
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Show QR to Parent",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Have your parent open SafeChild AI and scan this QR code to link your device.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (needsChildName) {
                        Text(
                            "What should your parent call you?",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = childNameDraft,
                            onValueChange = { childNameDraft = it.take(50) },
                            label = { Text("Child's name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { createPairingRequest(childNameDraft) },
                            enabled = childNameDraft.trim().isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Continue and create QR") }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    } else if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = statusText, style = MaterialTheme.typography.bodyMedium)
                    } else if (qrBitmap != null) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White,
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Image(
                                bitmap = qrBitmap!!.asImageBitmap(),
                                contentDescription = "Child Pairing QR Code",
                                modifier = Modifier
                                    .size(240.dp)
                                    .padding(12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else if (error != null) {
                        Text(
                            text = error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            val activeUser = auth.currentUser
                            if (activeUser != null) {
                                promptForChildName()
                            } else {
                                isLoading = true
                                error = null
                                auth.signInAnonymously()
                                    .addOnSuccessListener { res ->
                                        val newUid = res.user?.uid
                                        if (newUid != null) {
                                            promptForChildName()
                                        }
                                    }
                                    .addOnFailureListener { e ->
                                        val tempEmail = "child_${System.currentTimeMillis()}@safechild.ai"
                                        val tempPass = "SafeChildPass123!"
                                        auth.createUserWithEmailAndPassword(tempEmail, tempPass)
                                            .addOnSuccessListener { res ->
                                                val newUid = res.user?.uid
                                                if (newUid != null) {
                                                    promptForChildName()
                                                }
                                            }
                                            .addOnFailureListener { fe ->
                                                error = "Authentication failed: ${fe.message ?: e.message}"
                                                isLoading = false
                                            }
                                    }
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Try Again")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCode,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "This QR code expires in 15 minutes and is valid for a single pairing attempt.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
