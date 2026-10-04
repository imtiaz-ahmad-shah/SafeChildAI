package com.safechild.ai.data

import java.util.Calendar
import java.util.Random
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Per-child unsupervised Isolation Forest. Scores are review signals, never emergency alerts. */
internal class IsolationForest private constructor(
    private val trees: List<Node>,
    private val minima: DoubleArray,
    private val ranges: DoubleArray,
    private val sampleSize: Int,
    val threshold: Double
) {
    private data class Node(
        val feature: Int = -1,
        val split: Double = 0.0,
        val left: Node? = null,
        val right: Node? = null,
        val size: Int = 1
    )

    fun score(features: DoubleArray): Double {
        require(features.size == minima.size && features.all(Double::isFinite))
        val normalized = DoubleArray(features.size) { i ->
            if (ranges[i] <= EPSILON) 0.5 else ((features[i] - minima[i]) / ranges[i]).coerceIn(0.0, 1.0)
        }
        val meanPath = trees.map { pathLength(normalized, it, 0) }.average()
        val normalizer = averagePathLength(sampleSize)
        return if (normalizer <= 0.0) 0.0 else 2.0.pow(-meanPath / normalizer)
    }

    fun isAnomaly(features: DoubleArray): Boolean = score(features) >= threshold

    private fun pathLength(point: DoubleArray, node: Node, depth: Int): Double {
        if (node.feature < 0 || node.left == null || node.right == null) return depth + averagePathLength(node.size)
        return pathLength(point, if (point[node.feature] < node.split) node.left else node.right, depth + 1)
    }

    companion object {
        private const val TREE_COUNT = 64
        private const val MAX_SAMPLE = 256
        private const val EPSILON = 1e-12

        fun fit(training: List<DoubleArray>, seed: Long = 42L): IsolationForest? {
            if (training.size < MIN_TRAINING_SAMPLES || training.any { it.size != 4 || it.any { value -> !value.isFinite() } }) return null
            val dimensions = training.first().size
            val minima = DoubleArray(dimensions) { d -> training.minOf { it[d] } }
            val maxima = DoubleArray(dimensions) { d -> training.maxOf { it[d] } }
            val ranges = DoubleArray(dimensions) { d -> maxima[d] - minima[d] }
            val normalized = training.map { row -> DoubleArray(dimensions) { d ->
                if (ranges[d] <= EPSILON) 0.5 else (row[d] - minima[d]) / ranges[d]
            } }
            val sampleSize = min(MAX_SAMPLE, normalized.size)
            val heightLimit = ceil(ln(sampleSize.toDouble()) / ln(2.0)).toInt()
            val random = Random(seed)
            val trees = List(TREE_COUNT) {
                val sample = if (normalized.size <= sampleSize) normalized else normalized.shuffled(random).take(sampleSize)
                build(sample, 0, heightLimit, random)
            }
            val provisional = IsolationForest(trees, minima, ranges, sampleSize, 0.65)
            val scores = training.map(provisional::score).sorted()
            // A conservative per-child tail threshold avoids treating ordinary variation as danger.
            val tail = scores[((scores.size - 1) * 0.95).toInt()]
            return IsolationForest(trees, minima, ranges, sampleSize, max(0.55, tail * 0.9))
        }

        private fun build(rows: List<DoubleArray>, depth: Int, heightLimit: Int, random: Random): Node {
            if (depth >= heightLimit || rows.size <= 1) return Node(size = rows.size)
            val features = rows.first().indices.filter { d -> rows.minOf { it[d] } + EPSILON < rows.maxOf { it[d] } }
            if (features.isEmpty()) return Node(size = rows.size)
            val feature = features[random.nextInt(features.size)]
            val low = rows.minOf { it[feature] }
            val high = rows.maxOf { it[feature] }
            val split = low + random.nextDouble() * (high - low)
            val leftRows = rows.filter { it[feature] < split }
            val rightRows = rows.filter { it[feature] >= split }
            if (leftRows.isEmpty() || rightRows.isEmpty()) return Node(size = rows.size)
            return Node(feature, split, build(leftRows, depth + 1, heightLimit, random), build(rightRows, depth + 1, heightLimit, random), rows.size)
        }

        private fun averagePathLength(size: Int): Double = when {
            size <= 1 -> 0.0
            size == 2 -> 1.0
            else -> 2.0 * (ln(size - 1.0) + EULER_GAMMA) - 2.0 * (size - 1.0) / size
        }

        const val MIN_TRAINING_SAMPLES = 36
        const val MIN_TRAINING_DAYS = 3
        const val MIN_SAMPLE_SPACING_MS = 10 * 60 * 1000L
        private const val EULER_GAMMA = 0.5772156649015329
    }
}

/** Converts GPS observations into spatial and weekly-time features, in meters relative to a saved origin. */
internal object ChildLocationFeatures {
    data class Observation(val latitude: Double, val longitude: Double, val timestampMillis: Long, val accuracyMeters: Double)
    data class Dataset(val originLatitude: Double, val originLongitude: Double, val observations: List<Observation>, val vectors: List<DoubleArray>, val distinctDays: Int)
    data class Readiness(val usableSamples: Int, val distinctDays: Int, val ready: Boolean)

    fun prepare(points: List<ChildLocationFeatures.Observation>): Dataset? {
        val valid = points.asSequence().filter {
            it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 &&
                it.timestampMillis > 0L && it.accuracyMeters.isFinite() && it.accuracyMeters in 0.0..100.0
        }.sortedBy { it.timestampMillis }.fold(mutableListOf<Observation>()) { acc, p ->
            if (acc.isEmpty() || p.timestampMillis - acc.last().timestampMillis >= IsolationForest.MIN_SAMPLE_SPACING_MS) acc += p
            acc
        }
        if (valid.isEmpty()) return null
        val originLat = valid.first().latitude
        val originLon = valid.first().longitude
        val cosLat = cos(Math.toRadians(originLat))
        val vectors = valid.map { p ->
            val c = Calendar.getInstance().apply { timeInMillis = p.timestampMillis }
            val minuteOfWeek = ((c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7) * 1440 + c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
            val angle = 2.0 * Math.PI * minuteOfWeek / 10080.0
            doubleArrayOf(
                (p.latitude - originLat) * 111_320.0,
                (p.longitude - originLon) * 111_320.0 * cosLat,
                sin(angle),
                cos(angle)
            )
        }
        val days = valid.map { Calendar.getInstance().apply { timeInMillis = it.timestampMillis }.let { c ->
            c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
        } }.distinct().size
        return Dataset(originLat, originLon, valid, vectors, days)
    }

    fun readiness(points: List<Observation>): Readiness {
        val data = prepare(points)
        val count = data?.observations?.size ?: 0
        val days = data?.distinctDays ?: 0
        return Readiness(count, days, count >= IsolationForest.MIN_TRAINING_SAMPLES && days >= IsolationForest.MIN_TRAINING_DAYS)
    }

    fun vector(originLatitude: Double, originLongitude: Double, point: Observation): DoubleArray {
        val c = Calendar.getInstance().apply { timeInMillis = point.timestampMillis }
        val minuteOfWeek = ((c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7) * 1440 + c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val angle = 2.0 * Math.PI * minuteOfWeek / 10080.0
        return doubleArrayOf(
            (point.latitude - originLatitude) * 111_320.0,
            (point.longitude - originLongitude) * 111_320.0 * cos(Math.toRadians(originLatitude)),
            sin(angle), cos(angle)
        )
    }
}

