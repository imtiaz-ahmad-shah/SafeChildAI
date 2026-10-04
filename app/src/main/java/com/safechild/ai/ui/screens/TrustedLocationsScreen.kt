package com.safechild.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.safechild.ai.data.models.TrustedLocation
import com.safechild.ai.ui.theme.SafeChildAITheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustedLocationsScreen(
    auth: FirebaseAuth,
    firestore: FirebaseFirestore,
    onBackClick: () -> Unit,
    onAddLocationClick: () -> Unit,
    onEditLocationClick: (TrustedLocation) -> Unit
) {
    var locations by remember { mutableStateOf<List<TrustedLocation>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    val parentUid = auth.currentUser?.uid

    DisposableEffect(parentUid) {
        if (parentUid == null) return@DisposableEffect onDispose {}
        
        val listener = firestore.collection("users").document(parentUid)
            .collection("trustedLocations")
            .addSnapshotListener { snapshot, e ->
                if (snapshot != null) {
                    locations = snapshot.documents.mapNotNull { it.toObject(TrustedLocation::class.java)?.copy(id = it.id) }
                }
                isLoading = false
            }

        onDispose {
            listener.remove()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trusted Locations", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddLocationClick,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add Trusted Location") },
                shape = RoundedCornerShape(16.dp)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (locations.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No trusted locations added.", style = MaterialTheme.typography.bodyLarge)
                        Text("Add places like Home or School.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Column {
                            Text(
                                text = "Places your child regularly visits.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Trusted locations help SafeChild AI understand your child's normal routine and identify unusual activity.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    items(items = locations, key = { it.id }) { location ->
                        TrustedLocationCard(
                            location = location,
                            onEditClick = { onEditLocationClick(location) }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(80.dp)) // Space for FAB
                    }
                }
            }
        }
    }
}

@Composable
fun TrustedLocationCard(
    location: TrustedLocation,
    onEditClick: () -> Unit
) {
    val icon = when (location.type.lowercase()) {
        "home" -> Icons.Default.Home
        "school" -> Icons.Default.School
        "academy" -> Icons.Default.Book
        else -> Icons.Default.Place
    }

    val iconTint = when (location.type.lowercase()) {
        "home" -> Color(0xFF4CAF50)
        "school" -> Color(0xFF2196F3)
        "academy" -> Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (location.enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(iconTint.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = if (location.enabled) iconTint else Color.Gray)
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = location.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (location.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = "${String.format("%.4f", location.latitude)}, ${String.format("%.4f", location.longitude)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (location.enabled) Icons.Default.VerifiedUser else Icons.Default.Block,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (location.enabled) iconTint else Color.Gray
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (location.enabled) "Active • ${location.radiusMeters.toInt()}m radius" else "Disabled",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (location.enabled) iconTint else Color.Gray
                    )
                }
            }

            IconButton(onClick = onEditClick) {
                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
