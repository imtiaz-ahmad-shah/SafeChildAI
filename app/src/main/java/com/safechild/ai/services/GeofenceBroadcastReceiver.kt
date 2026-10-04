package com.safechild.ai.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return

        if (geofencingEvent.hasError()) {
            val errorMessage = GeofenceStatusCodes.getStatusCodeString(geofencingEvent.errorCode)
            Log.e("GeofenceReceiver", "Error: $errorMessage")
            return
        }

        val geofenceTransition = geofencingEvent.geofenceTransition

        if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER ||
            geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT
        ) {
            val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return
            val transitionType = if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER) "ENTER" else "EXIT"
            
            val childUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
            val firestore = FirebaseFirestore.getInstance()
            val location = geofencingEvent.triggeringLocation

            triggeringGeofences.forEach { geofence ->
                val locationId = geofence.requestId
                // We need to fetch the location name. 
                // For now we'll try to find it from the parent's collection or just log the ID.
                // Better: The Geofence ID can be encoded as "name|id" or we just lookup.
                // Let's assume requestId is just the ID for now.
                
                val eventData = hashMapOf(
                    "childUid" to childUid,
                    "trustedLocationId" to locationId,
                    "eventType" to transitionType,
                    "latitude" to (location?.latitude ?: 0.0),
                    "longitude" to (location?.longitude ?: 0.0),
                    "timestamp" to FieldValue.serverTimestamp()
                )

                firestore.collection("users").document(childUid)
                    .collection("locationEvents").add(eventData)
                    .addOnSuccessListener {
                        Log.d("GeofenceReceiver", "$transitionType event logged for $locationId")
                    }
                    .addOnFailureListener { e ->
                        Log.e("GeofenceReceiver", "Failed to log event: ${e.message}")
                    }
            }
        }
    }
}
