package com.safechild.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.safechild.ai.data.SafetyEventRepository
import java.text.DateFormat

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChildCheckInScreen(auth: FirebaseAuth, firestore: FirebaseFirestore, onBackClick: () -> Unit) {
    val childUid = auth.currentUser?.uid.orEmpty()
    var events by remember(childUid) { mutableStateOf(emptyList<SafetyEventRepository.Event>()) }
    var isSending by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(childUid) {
        if (childUid.isBlank()) return@DisposableEffect onDispose { }
        val registration = SafetyEventRepository.listenForChildEvents(
            firestore, childUid,
            onEvents = { events = it.filter { event -> event.type == SafetyEventRepository.SAFE_CHECK_IN } },
            onError = { errorMessage = it.localizedMessage ?: "Could not load check-in history." }
        )
        onDispose { registration.remove() }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Column {
            Text("Safety Check-In", fontWeight = FontWeight.Bold)
            Text("Let your parent know you're okay.", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
        } }, navigationIcon = {
            IconButton(onClick = onBackClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        })
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50))
                        Text(if (events.isEmpty()) "No check-ins yet" else "${events.size} check-in${if (events.size == 1) "" else "s"} recorded",
                            fontWeight = FontWeight.Bold, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                        Text("Your check-in is saved and will appear on your connected parent's device.", textAlign = TextAlign.Center)
                    }
                }
            }
            item {
                Button(onClick = {
                    isSending = true
                    errorMessage = null
                    successMessage = null
                    SafetyEventRepository.sendChildEvent(auth, firestore, SafetyEventRepository.SAFE_CHECK_IN) { result ->
                        isSending = false
                        result.onSuccess { successMessage = "Check-in saved. Your parent can see it in Safety Check-Ins & SOS while their app is open." }
                            .onFailure { errorMessage = it.localizedMessage ?: "Check-in could not be sent." }
                    }
                }, enabled = !isSending && childUid.isNotBlank(), modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(32.dp)) {
                    if (isSending) CircularProgressIndicator()
                    else Text("I'm Safe", fontWeight = FontWeight.Bold, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                }
            }
            successMessage?.let { message -> item { Text(message, color = Color(0xFF2E7D32), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) } }
            errorMessage?.let { message -> item { Text(message, color = androidx.compose.material3.MaterialTheme.colorScheme.error) } }
            item { Text("Recent Check-Ins", fontWeight = FontWeight.Bold, style = androidx.compose.material3.MaterialTheme.typography.titleMedium) }
            if (events.isEmpty()) item { Text("No saved check-ins yet.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant) }
            items(events, key = { it.id }) { event ->
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, contentDescription = null)
                        Spacer(Modifier.height(1.dp))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("Check-in sent", fontWeight = FontWeight.SemiBold)
                            Text(event.createdAt?.toDate()?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it) } ?: "Just sent")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChildSosScreen(auth: FirebaseAuth, firestore: FirebaseFirestore, onBackClick: () -> Unit) {
    var showConfirmation by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Emergency SOS", fontWeight = FontWeight.Bold) }, navigationIcon = {
            IconButton(onClick = onBackClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = androidx.compose.material3.MaterialTheme.colorScheme.error)
            Text(if (sent) "SOS sent" else "Need urgent help?", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(if (sent) "Your connected parent can now see this SOS in SafeChild AI." else "Send an SOS alert to your connected parent. If you are in immediate danger, contact local emergency services too.", textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Button(onClick = { showConfirmation = true }, enabled = !sending && !sent, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                if (sending) CircularProgressIndicator() else Text(if (sent) "SOS sent" else "Send SOS", fontWeight = FontWeight.Bold)
            }
            error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
    if (showConfirmation) AlertDialog(
        onDismissRequest = { showConfirmation = false },
        title = { Text("Send an SOS?") },
        text = { Text("This will create an emergency alert for your connected parent.") },
        confirmButton = { TextButton(onClick = {
            showConfirmation = false
            sending = true
            error = null
            SafetyEventRepository.sendChildEvent(auth, firestore, SafetyEventRepository.SOS) { result ->
                sending = false
                result.onSuccess { sent = true }.onFailure { error = it.localizedMessage ?: "SOS could not be sent." }
            }
        }) { Text("Send SOS") } },
        dismissButton = { OutlinedButton(onClick = { showConfirmation = false }) { Text("Cancel") } }
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ParentSosScreen(auth: FirebaseAuth, firestore: FirebaseFirestore, onBackClick: () -> Unit) {
    val parentUid = auth.currentUser?.uid.orEmpty()
    var events by remember(parentUid) { mutableStateOf(emptyList<SafetyEventRepository.Event>()) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(parentUid) {
        if (parentUid.isBlank()) return@DisposableEffect onDispose { }
        val registration = SafetyEventRepository.listenForParentEvents(
            firestore, parentUid,
            onEvents = { events = it },
            onError = { error = it.localizedMessage ?: "Could not load safety events." }
        )
        onDispose { registration.remove() }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Safety Check-Ins & SOS") }, navigationIcon = {
            IconButton(onClick = onBackClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { message -> item { Text(message, color = androidx.compose.material3.MaterialTheme.colorScheme.error) } }
            if (events.isEmpty() && error == null) item { Text("No check-ins or SOS alerts yet.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant) }
            items(events, key = { it.id }) { event ->
                val isSos = event.type == SafetyEventRepository.SOS
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(
                    containerColor = if (isSos && event.status == SafetyEventRepository.OPEN) androidx.compose.material3.MaterialTheme.colorScheme.errorContainer else androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
                )) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (isSos) "SOS from ${event.childName}" else "${event.childName} checked in safe", fontWeight = FontWeight.Bold)
                        Text(event.createdAt?.toDate()?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it) } ?: "Just received")
                        Text(if (event.status == SafetyEventRepository.RESOLVED) "Resolved" else if (isSos) "Needs your attention" else "Received")
                        if (isSos && event.status == SafetyEventRepository.OPEN) {
                            TextButton(onClick = {
                                SafetyEventRepository.resolveParentEvent(firestore, event.id) { result ->
                                    result.onFailure { error = it.localizedMessage ?: "Could not resolve this SOS." }
                                }
                            }) { Text("Mark as handled") }
                        }
                    }
                }
            }
        }
    }
}


