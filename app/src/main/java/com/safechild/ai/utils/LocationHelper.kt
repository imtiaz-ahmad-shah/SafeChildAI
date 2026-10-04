package com.safechild.ai.utils

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.safechild.ai.data.models.TrustedLocation
import com.safechild.ai.services.GeofenceBroadcastReceiver

class LocationHelper(private val context: Context, private val firestore: FirebaseFirestore) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val geofencingClient: GeofencingClient =
        LocationServices.getGeofencingClient(context)

    private var locationCallback: LocationCallback? = null
    private var activeChildUid: String? = null

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates(childUid: String) {
        if (locationCallback != null && activeChildUid == childUid) return
        if (locationCallback != null) stopLocationUpdates()
        activeChildUid = childUid

        // Request one location about every minute, including while stationary.
        // Android may still defer delivery under Doze or manufacturer battery controls.
        val updateIntervalMillis = 1 * 60 * 1000L
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, updateIntervalMillis)
            .setMinUpdateIntervalMillis(updateIntervalMillis)
            .setMaxUpdateDelayMillis(updateIntervalMillis)
            .setMinUpdateDistanceMeters(0f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    updateLocationInFirestore(childUid, location)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            ).addOnFailureListener { error ->
                Log.e("LocationHelper", "Location updates could not be registered for $childUid", error)
                stopLocationUpdates()
            }
            
            // Also start geofencing
            setupGeofences(childUid)
        } catch (e: Exception) {
            Log.e("LocationHelper", "Error starting location updates: ${e.message}")
        }
    }

    fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
        }
        activeChildUid = null
        geofencingClient.removeGeofences(geofencePendingIntent)
    }

    private fun updateLocationInFirestore(childUid: String, location: Location) {
        val locationData = hashMapOf(
            "childUid" to childUid,
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "accuracy" to location.accuracy,
            "timestamp" to FieldValue.serverTimestamp()
        )

        // 1. Live Current Location (Single Overwritten Document)
        firestore.collection("users").document(childUid)
            .collection("location").document("current")
            .set(locationData)
            .addOnSuccessListener {
                Log.d("LocationHelper", "Current location updated successfully for $childUid")
            }
            .addOnFailureListener { e ->
                Log.e("LocationHelper", "Error updating current location: ${e.message}")
            }

        // 2. Historical Location Breadcrumbs (Appended Chronological Documents)
        val historyData = hashMapOf(
            "childUid" to childUid,
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "accuracy" to location.accuracy,
            "timestamp" to FieldValue.serverTimestamp()
        )

        firestore.collection("users").document(childUid)
            .collection("locationHistory")
            .add(historyData)
            .addOnSuccessListener {
                Log.d("LocationHelper", "Location history breadcrumb added for $childUid")
            }
            .addOnFailureListener { e ->
                Log.e("LocationHelper", "Error adding location history breadcrumb: ${e.message}")
            }
    }

    @SuppressLint("MissingPermission")
    private fun setupGeofences(childUid: String) {
        // 1. Get parentUid
        firestore.collection("users").document(childUid).get().addOnSuccessListener { doc ->
            val parentUid = doc.getString("parentUid") ?: return@addOnSuccessListener
            
            // 2. Fetch trusted locations
            firestore.collection("users").document(parentUid)
                .collection("trustedLocations")
                .whereEqualTo("enabled", true)
                .get()
                .addOnSuccessListener { result ->
                    val geofenceList = result.documents.mapNotNull { d ->
                        val loc = d.toObject(TrustedLocation::class.java)?.copy(id = d.id)
                        if (loc != null) {
                            Geofence.Builder()
                                .setRequestId(loc.id)
                                .setCircularRegion(loc.latitude, loc.longitude, loc.radiusMeters)
                                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                                .build()
                        } else null
                    }

                    if (geofenceList.isNotEmpty()) {
                        val geofencingRequest = GeofencingRequest.Builder()
                            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                            .addGeofences(geofenceList)
                            .build()

                        geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent)
                            .addOnSuccessListener { Log.d("LocationHelper", "Geofences added") }
                            .addOnFailureListener { e -> Log.e("LocationHelper", "Geofence fail: ${e.message}") }
                    }
                }
        }
    }
}
