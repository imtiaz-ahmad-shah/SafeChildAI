package com.safechild.ai.services

import android.content.Context
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Query
import com.safechild.ai.data.RoutineDemoModel
import com.safechild.ai.data.IsolationForest

/** Parent-app process monitor for child locations, automatic routine learning, and AI review signals. */
class ParentAiRoutineMonitor(context: Context, private val firestore: FirebaseFirestore, private val parentUid: String) {
    private data class ChildWatch(val name: String, val registrations: List<ListenerRegistration>)
    private val appContext = context.applicationContext
    private val lock = Any()
    private val childWatches = mutableMapOf<String, ChildWatch>()
    private val historyPointsByChild = mutableMapOf<String, List<RoutineDemoModel.HistoryPoint>>()
    private val trainingChildren = mutableSetOf<String>()
    private var relationshipRegistration: ListenerRegistration? = null
    @Volatile private var stopped = false

    fun start() {
        val registration = firestore.collection("relationships")
            .whereEqualTo("parentUid", parentUid)
            .whereEqualTo("status", "active")
            .addSnapshotListener { relationships, error ->
                if (stopped) return@addSnapshotListener
                if (error != null) {
                    Log.w("ParentAiMonitor", "Could not monitor linked child relationships.", error)
                    return@addSnapshotListener
                }
                val desired = relationships?.documents.orEmpty().mapNotNull { relationship ->
                    val uid = relationship.getString("childUid") ?: return@mapNotNull null
                    val name = relationship.getString("childName")?.takeIf { it.isNotBlank() } ?: "Child"
                    uid to name
                }.toMap()
                reconcileChildren(desired)
            }
        synchronized(lock) {
            if (stopped) registration.remove() else relationshipRegistration = registration
        }
    }

    private fun reconcileChildren(desired: Map<String, String>) {
        synchronized(lock) {
            if (stopped) return
            val removed = childWatches.filter { (uid, watch) -> desired[uid] != watch.name }.keys
            removed.forEach { uid ->
                childWatches.remove(uid)?.registrations?.forEach(ListenerRegistration::remove)
                historyPointsByChild.remove(uid)
                trainingChildren.remove(uid)
            }
            desired.forEach { (childUid, childName) ->
                if (childWatches.containsKey(childUid)) return@forEach

                val locationWatch = firestore.collection("users").document(childUid)
                    .collection("location").document("current")
                    .addSnapshotListener { location, error ->
                        if (stopped) return@addSnapshotListener
                        if (error != null) {
                            Log.w("ParentAiMonitor", "Could not monitor a child location.", error)
                            return@addSnapshotListener
                        }
                        if (location == null || !location.exists()) return@addSnapshotListener
                        val latitude = (location.get("latitude") as? Number)?.toDouble() ?: return@addSnapshotListener
                        val longitude = (location.get("longitude") as? Number)?.toDouble() ?: return@addSnapshotListener
                        val timestamp = (location.get("timestamp") as? Timestamp)?.toDate()?.time ?: return@addSnapshotListener
                        if (System.currentTimeMillis() - timestamp > 20 * 60 * 1000L) return@addSnapshotListener
                        val accuracy = (location.get("accuracy") as? Number)?.toDouble() ?: Double.NaN
                        val livePoint = RoutineDemoModel.HistoryPoint(latitude, longitude, timestamp, accuracy)
                        val assessment = RoutineDemoModel.assessUnsupervised(appContext, childUid, livePoint)
                            ?: return@addSnapshotListener
                        val isNewAssessment = RoutineDemoModel.recordAssessment(appContext, childUid, false, assessment, timestamp, latitude, longitude)
                        val reviewRecord = RoutineDemoModel.loadReviewHistory(appContext, childUid, false)
                            .firstOrNull { it.title == assessment.title && it.lastSeenMillis == timestamp }
                        if (reviewRecord != null) {
                            val reviewData = mutableMapOf<String, Any>(
                                "parentUid" to parentUid,
                                "childUid" to childUid,
                                "title" to reviewRecord.title,
                                "explanation" to reviewRecord.explanation,
                                "firstSeenMillis" to reviewRecord.firstSeenMillis,
                                "lastSeenMillis" to reviewRecord.lastSeenMillis,
                                "latitude" to reviewRecord.latitude,
                                "longitude" to reviewRecord.longitude,
                                "synthetic" to reviewRecord.synthetic,
                                "needsReview" to reviewRecord.needsReview,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                            reviewRecord.feedback?.let { reviewData["feedback"] = it }
                            firestore.collection("users").document(childUid)
                                .collection("aiReviewHistory")
                                .document(RoutineDemoModel.reviewDocumentId(childUid, reviewRecord))
                                .set(reviewData, SetOptions.merge())
                                .addOnFailureListener { writeError ->
                                    Log.w("ParentAiMonitor", "Could not sync AI review history.", writeError)
                                }
                        }
                        if (isNewAssessment && assessment.needsReview) AiReviewNotificationManager.show(appContext, childUid, childName, assessment)
                    }

                var pendingHistoryPoints: List<RoutineDemoModel.HistoryPoint>? = null
                // The listener snapshot is the training source; don't issue a second full history query per update.
                // Training continues while the parent is on any signed-in screen, not just Routine.
                val historyWatch = firestore.collection("users").document(childUid)
                    .collection("locationHistory")
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .limit(5000)
                    .addSnapshotListener { snapshot, error ->
                        if (stopped) return@addSnapshotListener
                        if (error != null) {
                            Log.w("ParentAiMonitor", "Could not watch location history for automatic routine training.", error)
                        } else {
                            val points = snapshot?.documents.orEmpty().mapNotNull { document ->
                                val latitude = (document.get("latitude") as? Number)?.toDouble()
                                val longitude = (document.get("longitude") as? Number)?.toDouble()
                                val timestamp = document.getTimestamp("timestamp")?.toDate()?.time
                                val accuracy = (document.get("accuracy") as? Number)?.toDouble() ?: Double.NaN
                                if (latitude == null || longitude == null || timestamp == null) null
                                else RoutineDemoModel.HistoryPoint(latitude, longitude, timestamp, accuracy)
                            }
                            synchronized(lock) {
                                pendingHistoryPoints = points
                                if (childUid in childWatches) historyPointsByChild[childUid] = points
                            }
                            maybeTrainRoutine(childUid)
                        }
                    }
                val watch = ChildWatch(childName, listOf(locationWatch, historyWatch))
                if (stopped) {
                    watch.registrations.forEach(ListenerRegistration::remove)
                } else {
                    childWatches[childUid] = watch
                    pendingHistoryPoints?.let { historyPointsByChild[childUid] = it }
                    maybeTrainRoutine(childUid)
                }
            }
        }
    }

    private fun maybeTrainRoutine(childUid: String) {
        if (stopped || childUid.isBlank()) return
        val points = synchronized(lock) {
            if (childUid !in childWatches) return
            historyPointsByChild[childUid] ?: return
        }
        val trainedAt = RoutineDemoModel.lastUnsupervisedTrainedAt(appContext, childUid)
        val readiness = RoutineDemoModel.unsupervisedReadiness(points)
        if (!readiness.ready) {
            Log.i("ParentAiMonitor", "Child " + childUid + " Isolation Forest is waiting for enough accurate readings across " + IsolationForest.MIN_TRAINING_DAYS + " days; currently " + readiness.usableSamples + " readings across " + readiness.distinctDays + " days. Model fitting skipped.")
            return
        }
        val priorSamples = RoutineDemoModel.lastUnsupervisedSampleCount(appContext, childUid)
        val modelNeedsRefresh = trainedAt == 0L ||
            System.currentTimeMillis() - trainedAt >= MODEL_REFRESH_INTERVAL_MS ||
            (readiness.ready && readiness.usableSamples >= priorSamples + NEW_SAMPLE_REFRESH_COUNT)
        if (!modelNeedsRefresh || !claimTraining(childUid)) return
        try {
            if (stopped) return
            val fitted = RoutineDemoModel.trainUnsupervised(appContext, childUid, points)
            Log.i("ParentAiMonitor", "Child " + childUid + " Isolation Forest: " + fitted.usableSamples + " usable readings across " + fitted.distinctDays + " days; ready=" + fitted.ready + ".")
        } catch (trainingError: Exception) {
            Log.w("ParentAiMonitor", "Could not train the child-specific Isolation Forest for " + childUid + ".", trainingError)
        } finally {
            releaseTraining(childUid)
        }
    }

    private fun claimTraining(childUid: String): Boolean = synchronized(lock) {
        if (stopped || childUid in trainingChildren) false else trainingChildren.add(childUid)
    }

    private fun releaseTraining(childUid: String) {
        synchronized(lock) { trainingChildren.remove(childUid) }
    }

    fun stop() {
        stopped = true
        synchronized(lock) {
            relationshipRegistration?.remove()
            relationshipRegistration = null
            childWatches.values.flatMap { it.registrations }.forEach(ListenerRegistration::remove)
            childWatches.clear()
            historyPointsByChild.clear()
            trainingChildren.clear()
        }
    }

    private companion object {
        const val MODEL_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val NEW_SAMPLE_REFRESH_COUNT = 12
    }
}




