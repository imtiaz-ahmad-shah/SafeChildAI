package com.safechild.ai.data

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.util.Locale
import java.security.MessageDigest

object RoutineDemoModel {
    data class Assessment(val title: String, val needsReview: Boolean, val explanation: String)
    data class HistoryPoint(val latitude: Double, val longitude: Double, val timestampMillis: Long, val accuracyMeters: Double = Double.NaN)
    data class ReviewRecord(val title: String, val explanation: String, val firstSeenMillis: Long, val lastSeenMillis: Long, val latitude: Double, val longitude: Double, val feedback: String? = null, val synthetic: Boolean = false, val needsReview: Boolean = true)
    data class FeedbackEvaluation(val labeledSamples: Int, val confirmedReviewSignals: Int, val expectedRoutineChecks: Int, val reviewSignalsMarkedExpected: Int, val routineChecksMarkedConcern: Int) {
        val agreementSamples: Int get() = confirmedReviewSignals + expectedRoutineChecks
    }
    private const val PREFS = "safechild_demo_ai"

    /** Remove retired fictional schedule-model data; real learning and reviews remain untouched. */
    fun clearRetiredSyntheticData(context: Context, uid: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("routine_model_demo_$uid")
            .remove("routine_reviews_demo_$uid")
            .apply()
    }

    fun loadReviewHistory(context: Context, uid: String, synthetic: Boolean): List<ReviewRecord> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(reviewKey(uid, synthetic), null) ?: return emptyList()
        return runCatching {
            val items = JSONArray(raw)
            (0 until items.length()).mapNotNull { i ->
                val item = items.optJSONObject(i) ?: return@mapNotNull null
                ReviewRecord(item.optString("title"), item.optString("explanation"), item.optLong("firstSeen"), item.optLong("lastSeen"), item.optDouble("latitude"), item.optDouble("longitude"), item.optString("feedback").takeIf { it.isNotBlank() }, synthetic, item.optBoolean("needsReview", true))
            }.sortedByDescending { it.lastSeenMillis }
        }.getOrDefault(emptyList())
    }

    fun observeReviewHistory(context: Context, uid: String, synthetic: Boolean, onChanged: (List<ReviewRecord>) -> Unit): () -> Unit {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val watchedKey = reviewKey(uid, synthetic)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == watchedKey) onChanged(loadReviewHistory(context, uid, synthetic))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun reviewDocumentId(uid: String, record: ReviewRecord): String {
        val input = uid + "|" + record.synthetic + "|" + record.firstSeenMillis + "|" + record.title
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    fun updateReviewFeedback(context: Context, uid: String, record: ReviewRecord, feedback: String): Boolean {
        if (feedback !in setOf("expected", "concern")) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = reviewKey(uid, record.synthetic)
        val items = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
        var updated = false
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            if (item.optLong("firstSeen") == record.firstSeenMillis && item.optString("title") == record.title) {
                item.put("feedback", feedback)
                updated = true
                break
            }
        }
        if (updated) prefs.edit().putString(key, items.toString()).apply()
        return updated
    }

    /** Keeps a short, parent-phone-only timeline of review signals; repeated samples are grouped into one 20-minute episode. */
    fun recordAssessment(context: Context, uid: String, synthetic: Boolean, assessment: Assessment, timestamp: Long, latitude: Double, longitude: Double): Boolean {
        if (timestamp <= 0L) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = reviewKey(uid, synthetic)
        val old = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
        val items = (0 until old.length()).mapNotNull { old.optJSONObject(it) }.toMutableList()
        val latest = items.lastOrNull()
        val sameEpisode = latest != null && latest.optString("title") == assessment.title && timestamp >= latest.optLong("lastSeen") && timestamp - latest.optLong("lastSeen") <= 20 * 60 * 1000L
        if (sameEpisode) {
            latest!!.put("lastSeen", timestamp).put("latitude", latitude).put("longitude", longitude).put("explanation", assessment.explanation).put("needsReview", assessment.needsReview)
        } else {
            items += JSONObject().put("title", assessment.title).put("explanation", assessment.explanation)
                .put("firstSeen", timestamp).put("lastSeen", timestamp).put("latitude", latitude).put("longitude", longitude)
                .put("needsReview", assessment.needsReview)
        }
        val retained = items.takeLast(30)
        prefs.edit().putString(key, JSONArray(retained).toString()).apply()
        return !sameEpisode
    }

    fun evaluateFeedback(records: List<ReviewRecord>): FeedbackEvaluation {
        val labeled = records.filter { it.feedback == "expected" || it.feedback == "concern" }
        return FeedbackEvaluation(
            labeledSamples = labeled.size,
            confirmedReviewSignals = labeled.count { it.needsReview && it.feedback == "concern" },
            expectedRoutineChecks = labeled.count { !it.needsReview && it.feedback == "expected" },
            reviewSignalsMarkedExpected = labeled.count { it.needsReview && it.feedback == "expected" },
            routineChecksMarkedConcern = labeled.count { !it.needsReview && it.feedback == "concern" }
        )
    }
    data class UnsupervisedReadiness(val usableSamples: Int, val distinctDays: Int, val ready: Boolean)

    /** Fit and persist a per-child Isolation Forest from that child's own observed location history. */
    fun trainUnsupervised(context: Context, uid: String, points: List<HistoryPoint>): UnsupervisedReadiness {
        val observations = points.map { ChildLocationFeatures.Observation(it.latitude, it.longitude, it.timestampMillis, it.accuracyMeters) }
        val data = ChildLocationFeatures.prepare(observations)
        val count = data?.observations?.size ?: 0
        val days = data?.distinctDays ?: 0
        val ready = count >= IsolationForest.MIN_TRAINING_SAMPLES && days >= IsolationForest.MIN_TRAINING_DAYS
        if (!ready || data == null) return UnsupervisedReadiness(count, days, false)
        val forest = IsolationForest.fit(data.vectors, uid.hashCode().toLong()) ?: return UnsupervisedReadiness(count, days, false)
        val samples = JSONArray()
        data.vectors.forEach { vector -> samples.put(JSONArray().apply { vector.forEach { put(it) } }) }
        val model = JSONObject().put("version", ISOLATION_MODEL_VERSION)
            .put("trainedAtMillis", System.currentTimeMillis()).put("sampleCount", count).put("distinctDays", days)
            .put("originLatitude", data.originLatitude).put("originLongitude", data.originLongitude)
            .put("threshold", forest.threshold).put("samples", samples)
        val modelKey = isolationKey(uid)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(modelKey, model.toString()).apply()
        isolationForestCache[modelKey] = model.toString() to forest
        return UnsupervisedReadiness(count, days, true)
    }

    fun lastUnsupervisedTrainedAt(context: Context, uid: String): Long = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(isolationKey(uid), null) ?: return 0L
        val model = JSONObject(raw)
        if (model.optInt("version") == ISOLATION_MODEL_VERSION) model.optLong("trainedAtMillis", 0L) else 0L
    }.getOrDefault(0L)
    fun lastUnsupervisedSampleCount(context: Context, uid: String): Int = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(isolationKey(uid), null) ?: return 0
        val model = JSONObject(raw)
        if (model.optInt("version") == ISOLATION_MODEL_VERSION) model.optInt("sampleCount", 0) else 0
    }.getOrDefault(0)
    fun unsupervisedReadiness(points: List<HistoryPoint>): UnsupervisedReadiness {
        val status = ChildLocationFeatures.readiness(points.map { ChildLocationFeatures.Observation(it.latitude, it.longitude, it.timestampMillis, it.accuracyMeters) })
        return UnsupervisedReadiness(status.usableSamples, status.distinctDays, status.ready)
    }

    /** Returns null during cold start; after fit, unusual patterns become review-only signals. */
    fun assessUnsupervised(context: Context, uid: String, point: HistoryPoint): Assessment? {
        if (!point.latitude.isFinite() || point.latitude !in -90.0..90.0 || !point.longitude.isFinite() || point.longitude !in -180.0..180.0 ||
            point.timestampMillis <= 0L || !point.accuracyMeters.isFinite() || point.accuracyMeters !in 0.0..100.0) return null
        val modelKey = isolationKey(uid)
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(modelKey, null) ?: return null
        val forest = isolationForestCache[modelKey]?.takeIf { it.first == raw }?.second ?: runCatching {
            val model = JSONObject(raw)
            if (model.optInt("version") != ISOLATION_MODEL_VERSION) return null
            val samplesJson = model.getJSONArray("samples")
            val samples = (0 until samplesJson.length()).map { index ->
                val row = samplesJson.getJSONArray(index)
                DoubleArray(row.length()) { row.getDouble(it) }
            }
            IsolationForest.fit(samples, uid.hashCode().toLong())
        }.getOrNull()?.also { isolationForestCache[modelKey] = raw to it } ?: return null
        val model = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val vector = ChildLocationFeatures.vector(
            model.getDouble("originLatitude"), model.getDouble("originLongitude"),
            ChildLocationFeatures.Observation(point.latitude, point.longitude, point.timestampMillis, point.accuracyMeters)
        )
        val score = forest.score(vector)
        return if (score >= forest.threshold) Assessment(
            "Location pattern needs a check", true,
            "The child’s Isolation Forest model found this location/time pattern unusual (anomaly score ${"%.2f".format(Locale.US, score)}). This is a review prompt, not proof of danger; check the map and contact the child."
        ) else Assessment(
            "No unusual pattern detected", false,
            "The child’s Isolation Forest model found this point consistent with learned location patterns (score ${"%.2f".format(Locale.US, score)}). Keep checking live location and alerts as usual."
        )
    }
    private const val ISOLATION_MODEL_VERSION = 1
    private val isolationForestCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, IsolationForest>>()
    private fun isolationKey(uid: String) = "isolation_forest_v" + ISOLATION_MODEL_VERSION + "_" + uid
    private fun reviewKey(uid: String, synthetic: Boolean) = "routine_reviews_" + (if (synthetic) "demo" else "real") + "_" + uid
}
