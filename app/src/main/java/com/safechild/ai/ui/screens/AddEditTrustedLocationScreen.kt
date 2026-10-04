package com.safechild.ai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.safechild.ai.data.models.TrustedLocation
import com.safechild.ai.ui.components.OSMCoordinate
import com.safechild.ai.ui.components.OpenStreetMapView
import com.safechild.ai.ui.theme.SafeChildAITheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditTrustedLocationScreen(
    auth: FirebaseAuth,
    firestore: FirebaseFirestore,
    locationIdToEdit: String? = null,
    onBackClick: () -> Unit,
    onSaveClick: (TrustedLocation) -> Unit,
    onDeleteClick: (TrustedLocation) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("other") }
    var latitudeText by remember { mutableStateOf("") }
    var longitudeText by remember { mutableStateOf("") }
    var coordinateError by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var radius by remember { mutableStateOf(150f) }
    var enabled by remember { mutableStateOf(true) }
    
    var isLoading by remember { mutableStateOf(locationIdToEdit != null) }
    var isSaving by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    var nameError by remember { mutableStateOf<String?>(null) }
    
    val parentUid = auth.currentUser?.uid
    val radiusOptions = listOf(50f, 100f, 150f, 200f, 500f)
    val typeOptions = listOf("Home", "School", "Academy", "Other")

    LaunchedEffect(locationIdToEdit, parentUid) {
        if (locationIdToEdit != null) {
            if (parentUid == null) {
                loadError = "Sign in again to load this trusted location."
                isLoading = false
            } else {
                firestore.collection("users").document(parentUid)
                    .collection("trustedLocations").document(locationIdToEdit)
                    .get()
                    .addOnSuccessListener { doc ->
                        val loc = doc.toObject(TrustedLocation::class.java)
                        if (doc.exists() && loc != null) {
                            name = loc.name
                            type = loc.type
                            latitudeText = loc.latitude.toString()
                            longitudeText = loc.longitude.toString()
                            radius = loc.radiusMeters
                            enabled = loc.enabled
                        } else {
                            loadError = "This location could not be found. Go back and refresh the list."
                        }
                        isLoading = false
                    }
                    .addOnFailureListener { error ->
                        loadError = "Could not load this location: " + (error.localizedMessage ?: "check your connection and try again.")
                        isLoading = false
                    }
            }
        }
    }

    fun saveLocation() {
        if (name.isBlank()) {
            nameError = "Enter location name."
            return
        }
        val latitude = latitudeText.trim().toDoubleOrNull()
        val longitude = longitudeText.trim().toDoubleOrNull()
        if (latitude == null || latitude !in -90.0..90.0) {
            coordinateError = "Enter a latitude between -90 and 90."
            return
        }
        if (longitude == null || longitude !in -180.0..180.0) {
            coordinateError = "Enter a longitude between -180 and 180."
            return
        }
        coordinateError = null
        operationError = null
        if (parentUid == null) {
            operationError = "Sign in again before saving this location."
            return
        }
        if (locationIdToEdit != null && loadError != null) return
        
        isSaving = true
        val locId = locationIdToEdit ?: firestore.collection("users").document(parentUid).collection("trustedLocations").document().id
        
        val data = hashMapOf(
            "name" to name,
            "type" to type.lowercase(),
            "latitude" to latitude,
            "longitude" to longitude,
            "radiusMeters" to radius,
            "enabled" to enabled,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        
        if (locationIdToEdit == null) {
            data["createdAt"] = FieldValue.serverTimestamp()
        }

        firestore.collection("users").document(parentUid)
            .collection("trustedLocations").document(locId)
            .set(data)
            .addOnSuccessListener {
                isSaving = false
                onSaveClick(TrustedLocation(id = locId, name = name))
            }
            .addOnFailureListener { error ->
                isSaving = false
                operationError = "Could not save location: " + (error.localizedMessage ?: "check your connection and try again.")
            }
    }

    fun deleteLocation() {
        if (parentUid == null || locationIdToEdit == null) {
            operationError = "Sign in again before deleting this location."
            return
        }
        if (loadError != null) return
        operationError = null
        isSaving = true
        firestore.collection("users").document(parentUid)
            .collection("trustedLocations").document(locationIdToEdit)
            .delete()
            .addOnSuccessListener {
                isSaving = false
                onDeleteClick(TrustedLocation(id = locationIdToEdit))
            }
            .addOnFailureListener { error ->
                isSaving = false
                operationError = "Could not delete location: " + (error.localizedMessage ?: "check your connection and try again.")
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (locationIdToEdit == null) "Add Trusted Location" else "Edit Trusted Location") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (locationIdToEdit != null) {
                        IconButton(onClick = { showDeleteDialog = true }, enabled = !isSaving && loadError == null) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                loadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

                // Real interactive map picker; selected coordinates change only when map taps or text edits set them.
                val pickedLatitude = latitudeText.trim().toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
                val pickedLongitude = longitudeText.trim().toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose this place on the map", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Tap the map to place the pin, then adjust coordinates or the safety radius below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OpenStreetMapView(
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                        latitude = pickedLatitude,
                        longitude = pickedLongitude,
                        markerTitle = name.ifBlank { "Trusted location" },
                        defaultZoom = 12.0,
                        locationZoom = 16.0,
                        fallbackCenter = OSMCoordinate(35.85, 71.79),
                        onCoordinatePicked = { point ->
                            latitudeText = "%.6f".format(java.util.Locale.US, point.latitude)
                            longitudeText = "%.6f".format(java.util.Locale.US, point.longitude)
                            coordinateError = null
                            operationError = null
                        }
                    )
                }

                // Status Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Monitoring Enabled", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Generate alerts for this location", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                // Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { 
                        name = it
                        nameError = null
                    },
                    label = { Text("Location Name") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    leadingIcon = { Icon(Icons.Default.Label, contentDescription = null) },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = { if (nameError != null) Text(nameError!!) }
                )

                // Type
                Column {
                    Text("Location Type", style = MaterialTheme.typography.labelSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        typeOptions.forEach { option ->
                            FilterChip(
                                selected = type.equals(option, ignoreCase = true),
                                onClick = { type = option.lowercase() },
                                label = { Text(option) }
                            )
                        }
                    }
                }

                // Coordinates
                Column {
                    Text("Coordinates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedTextField(
                            value = latitudeText,
                            onValueChange = { latitudeText = it; coordinateError = null },
                            label = { Text("Latitude") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = longitudeText,
                            onValueChange = { longitudeText = it; coordinateError = null },
                            label = { Text("Longitude") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true
                        )
                    }
                    Text("Use decimal coordinates, for example 35.84 and 71.79.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    coordinateError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                }

                Spacer(modifier = Modifier.weight(1f))

                operationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(
                    onClick = { saveLocation() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    enabled = !isSaving && loadError == null
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                    } else {
                        Text(if (locationIdToEdit == null) "Add Location" else "Save Changes")
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Location?") },
            text = { Text("Tracking and alerts for this location will be stopped.") },
            confirmButton = {
                TextButton(onClick = { 
                    showDeleteDialog = false
                    deleteLocation() 
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

