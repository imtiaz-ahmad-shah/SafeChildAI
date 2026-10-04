package com.safechild.ai.data

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot

object SafetyEventRepository {
    const val SAFE_CHECK_IN = "safe_check_in"
    const val SOS = "sos"
    const val OPEN = "open"
    const val RESOLVED = "resolved"

    data class Event(
        val id: String,
        val childUid: String,
        val childName: String,
        val parentUid: String,
        val type: String,
        val status: String,
        val createdAt: Timestamp?
    )

    fun sendChildEvent(auth: FirebaseAuth, firestore: FirebaseFirestore, type: String, onComplete: (Result<Unit>) -> Unit) {
        val childUid = auth.currentUser?.uid
        if (childUid.isNullOrBlank()) {
            onComplete(Result.failure(IllegalStateException("Sign in again before sending a safety event.")))
            return
        }
        if (type != SAFE_CHECK_IN && type != SOS) {
            onComplete(Result.failure(IllegalArgumentException("Unsupported safety event.")))
            return
        }
        firestore.collection("users").document(childUid).get()
            .addOnSuccessListener { profile ->
                val childName = profile.getString("name")?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals("Child", ignoreCase = true) }
                    ?: auth.currentUser?.displayName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "Child"
                firestore.collection("relationships")
                    .whereEqualTo("childUid", childUid)
                    .whereEqualTo("status", "active")
                    .get()
                    .addOnSuccessListener { relationships ->
                        val activeParents = relationships.documents.mapNotNull { relationship ->
                            val parentUid = relationship.getString("parentUid")
                            if (parentUid.isNullOrBlank() || relationship.getString("childUid") != childUid) null
                            else parentUid
                        }.distinct()
                        if (activeParents.isEmpty()) {
                            onComplete(Result.failure(IllegalStateException("No connected parent was found. Reconnect with your parent and try again.")))
                            return@addOnSuccessListener
                        }
                        val batch = firestore.batch()
                        activeParents.forEach { parentUid ->
                            val ref = firestore.collection("safety_events").document()
                            batch.set(ref, mapOf(
                                "childUid" to childUid,
                                "childName" to childName,
                                "parentUid" to parentUid,
                                "type" to type,
                                "status" to OPEN,
                                "createdAt" to FieldValue.serverTimestamp()
                            ))
                        }
                        batch.commit().addOnSuccessListener { onComplete(Result.success(Unit)) }
                            .addOnFailureListener { onComplete(Result.failure(it)) }
                    }
                    .addOnFailureListener { onComplete(Result.failure(it)) }
            }
            .addOnFailureListener { onComplete(Result.failure(it)) }
    }

    fun listenForChildEvents(firestore: FirebaseFirestore, childUid: String, onEvents: (List<Event>) -> Unit, onError: (Exception) -> Unit): ListenerRegistration =
        firestore.collection("safety_events").whereEqualTo("childUid", childUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) onError(error) else if (snapshot != null) onEvents(snapshot.toEvents())
            }

    fun listenForParentEvents(firestore: FirebaseFirestore, parentUid: String, onEvents: (List<Event>) -> Unit, onError: (Exception) -> Unit): ListenerRegistration =
        firestore.collection("safety_events").whereEqualTo("parentUid", parentUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) onError(error) else if (snapshot != null) onEvents(snapshot.toEvents())
            }

    /** Watches live parent events for SOS alerts while the parent app process is running. */
    fun listenForParentSosAlerts(
        context: Context,
        firestore: FirebaseFirestore,
        parentUid: String,
        onNewSos: (Event) -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration {
        var receivedInitialSnapshot = false
        val listenerStartedAt = System.currentTimeMillis()
        val preferences = context.getSharedPreferences(SOS_NOTIFICATION_PREFS, Context.MODE_PRIVATE)
        val lastListenerStartedAt = preferences.getLong(listenerStartedKey(parentUid), 0L)
        preferences.edit().putLong(listenerStartedKey(parentUid), listenerStartedAt).apply()
        return firestore.collection("safety_events")
            .whereEqualTo("parentUid", parentUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener

                val isInitialSnapshot = !receivedInitialSnapshot
                receivedInitialSnapshot = true
                snapshot.documentChanges
                    .filter { it.type == com.google.firebase.firestore.DocumentChange.Type.ADDED }
                    .mapNotNull { change ->
                        val document = change.document
                        if (document.getString("type") != SOS || document.getString("status") != OPEN) return@mapNotNull null
                        Event(
                            id = document.id,
                            childUid = document.getString("childUid").orEmpty(),
                            childName = document.getString("childName") ?: "Child",
                            parentUid = document.getString("parentUid").orEmpty(),
                            type = document.getString("type").orEmpty(),
                            status = document.getString("status") ?: OPEN,
                            createdAt = document.getTimestamp("createdAt")
                        )
                    }
                    .filter { event ->
                        if (!isInitialSnapshot) true
                        else {
                            val createdAt = event.createdAt?.toDate()?.time ?: 0L
                            val cutoff = if (lastListenerStartedAt > 0L) lastListenerStartedAt else listenerStartedAt
                            createdAt >= cutoff - INITIAL_ALERT_GRACE_MS
                        }
                    }
                    .forEach(onNewSos)
            }
    }

    fun resolveParentEvent(firestore: FirebaseFirestore, eventId: String, onComplete: (Result<Unit>) -> Unit) {
        firestore.collection("safety_events").document(eventId)
            .update(mapOf("status" to RESOLVED, "resolvedAt" to FieldValue.serverTimestamp()))
            .addOnSuccessListener { onComplete(Result.success(Unit)) }
            .addOnFailureListener { onComplete(Result.failure(it)) }
    }

    private fun QuerySnapshot.toEvents(): List<Event> = documents.map { document ->
        Event(
            id = document.id,
            childUid = document.getString("childUid").orEmpty(),
            childName = document.getString("childName") ?: "Child",
            parentUid = document.getString("parentUid").orEmpty(),
            type = document.getString("type").orEmpty(),
            status = document.getString("status") ?: OPEN,
            createdAt = document.getTimestamp("createdAt")
        )
    }.sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }

    private const val INITIAL_ALERT_GRACE_MS = 10_000L
    private const val SOS_NOTIFICATION_PREFS = "safechild_sos_notifications"
    private fun listenerStartedKey(parentUid: String) = "listener_started_$parentUid"
}
