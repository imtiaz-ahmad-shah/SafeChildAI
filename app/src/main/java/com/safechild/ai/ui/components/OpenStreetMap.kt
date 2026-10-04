package com.safechild.ai.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.LruCache
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.sin
import kotlin.math.tan

data class OSMCoordinate(val latitude: Double, val longitude: Double)
data class OSMPlace(val name: String, val latitude: Double, val longitude: Double)

/** Shared native raster map for parent and child location screens. */
@Composable
fun OpenStreetMapView(
    modifier: Modifier = Modifier,
    latitude: Double?,
    longitude: Double?,
    markerTitle: String,
    defaultZoom: Double = 5.0,
    locationZoom: Double = 15.0,
    places: List<OSMPlace> = emptyList(),
    route: List<OSMCoordinate> = emptyList(),
    fallbackCenter: OSMCoordinate? = null,
    onCoordinatePicked: ((OSMCoordinate) -> Unit)? = null
) {
    val context = LocalContext.current
    val location = latitude?.takeIf { it.isFinite() && it in -90.0..90.0 }
        ?.let { lat -> longitude?.takeIf { it.isFinite() && it in -180.0..180.0 && (lat != 0.0 || it != 0.0) }?.let { OSMCoordinate(lat, it) } }
    val zoom = (if (location == null) defaultZoom else locationZoom).coerceIn(2.0, 19.0)
    val mapReference = remember { arrayOfNulls<NativeOsmMapView>(1) }

    Box(modifier.clipToBounds()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext -> NativeOsmMapView(viewContext).apply {
                mapReference[0] = this
                updateMap(location, markerTitle, places, route, zoom, fallbackCenter, onCoordinatePicked)
            } },
            update = { view -> view.updateMap(location, markerTitle, places, route, zoom, fallbackCenter, onCoordinatePicked) }
        )

        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MapControl("+", "Zoom in") { mapReference[0]?.zoomBy(1.0) }
            MapControl("−", "Zoom out") { mapReference[0]?.zoomBy(-1.0) }
            MapControl("◎", "Center on child") { mapReference[0]?.centerOnLocation() }
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
        ) {
            Text(
                "© OpenStreetMap contributors",
                modifier = Modifier.clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.openstreetmap.org/copyright"))) }
                }.padding(horizontal = 7.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun MapControl(label: String, description: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.semantics { contentDescription = description }.clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 3.dp,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private class NativeOsmMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(92, 76, 181)
        strokeWidth = 4f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        setShadowLayer(3f * density, 0f, 1f * density, Color.WHITE)
    }
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom + kotlin.math.ln(detector.scaleFactor) / ln(2.0)).coerceIn(2.0, 19.0)
            invalidate()
            return true
        }
    })

    private var childLocation: OSMCoordinate? = null
    private var onCoordinatePicked: ((OSMCoordinate) -> Unit)? = null
    private var markerTitle: String = "Current location"
    private var places: List<OSMPlace> = emptyList()
    private var route: List<OSMCoordinate> = emptyList()
    private var centerLat = 30.3753
    private var centerLon = 69.3451
    private var zoom = 5.0
    private var locationZoom = 15.0
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var loadedVisibleTiles = 0
    private var failedVisibleKeys = emptyList<String>()
    private var firstDrawAt = 0L

    init {
        setBackgroundColor(Color.rgb(232, 237, 240))
        contentDescription = "OpenStreetMap location map"
        isClickable = true
    }

    fun updateMap(
        location: OSMCoordinate?,
        title: String,
        savedPlaces: List<OSMPlace>,
        recordedRoute: List<OSMCoordinate>,
        initialZoom: Double,
        fallbackCenter: OSMCoordinate?,
        onCoordinatePicked: ((OSMCoordinate) -> Unit)?
    ) {
        val hasMovedLocation = location != null && (childLocation == null ||
            kotlin.math.abs(childLocation!!.latitude - location.latitude) > 0.00001 ||
            kotlin.math.abs(childLocation!!.longitude - location.longitude) > 0.00001)
        childLocation = location
        this.onCoordinatePicked = onCoordinatePicked
        markerTitle = title
        places = savedPlaces.filter { place ->
            place.latitude.isFinite() && place.longitude.isFinite() &&
                place.latitude in -90.0..90.0 && place.longitude in -180.0..180.0
        }.take(100)
        route = recordedRoute.filter { isValid(it) }.takeLast(300)
        locationZoom = initialZoom.coerceIn(2.0, 19.0)
        if (hasMovedLocation) {
            centerLat = location!!.latitude
            centerLon = location.longitude
            zoom = locationZoom
        } else if (childLocation == null && firstDrawAt == 0L) {
            fallbackCenter?.takeIf(::isValid)?.let {
                centerLat = it.latitude
                centerLon = it.longitude
            }
            zoom = initialZoom.coerceIn(2.0, 19.0)
        }
        invalidate()
    }

    fun zoomBy(amount: Double) {
        zoom = (zoom + amount).coerceIn(2.0, 19.0)
        invalidate()
    }

    fun centerOnLocation() {
        childLocation?.let {
            centerLat = it.latitude
            centerLon = it.longitude
            zoom = locationZoom
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        if (firstDrawAt == 0L) firstDrawAt = System.currentTimeMillis()
        canvas.drawColor(Color.rgb(232, 237, 240))

        val tileZoom = floor(zoom).toInt().coerceIn(2, 19)
        val scale = 2.0.pow(zoom - tileZoom)
        val tilePx = TILE_SIZE * scale
        val (centerX, centerY) = project(centerLat, centerLon, tileZoom)
        val left = centerX * scale - width / 2.0
        val top = centerY * scale - height / 2.0
        val minTileX = floor(left / tilePx).toInt()
        val maxTileX = floor((left + width) / tilePx).toInt()
        val minTileY = floor(top / tilePx).toInt()
        val maxTileY = floor((top + height) / tilePx).toInt()
        val visibleFailures = ArrayList<String>()
        var visibleLoaded = 0
        val tileCount = 1 shl tileZoom

        for (y in minTileY..maxTileY) {
            if (y !in 0 until tileCount) continue
            for (x in minTileX..maxTileX) {
                val wrappedX = ((x % tileCount) + tileCount) % tileCount
                val key = "$tileZoom/$wrappedX/$y"
                val bitmap = OsmTileStore.get(key)
                val rect = android.graphics.RectF(
                    (x * tilePx - left).toFloat(),
                    (y * tilePx - top).toFloat(),
                    ((x + 1) * tilePx - left).toFloat(),
                    ((y + 1) * tilePx - top).toFloat()
                )
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, null, rect, tilePaint)
                    visibleLoaded++
                } else {
                    visibleFailures.add(key)
                    OsmTileStore.request(key, this)
                }
            }
        }
        loadedVisibleTiles = visibleLoaded
        failedVisibleKeys = visibleFailures
        drawRoute(canvas, tileZoom, scale, centerX, centerY)
        places.forEach { drawMarker(canvas, it.latitude, it.longitude, 0xffdc861d.toInt(), it.name, tileZoom, scale, centerX, centerY) }
        childLocation?.let { drawMarker(canvas, it.latitude, it.longitude, 0xff2767d5.toInt(), markerTitle, tileZoom, scale, centerX, centerY) }
        drawLoadingOrError(canvas, visibleLoaded, visibleFailures.size)
    }

    private fun drawRoute(canvas: Canvas, z: Int, scale: Double, centerX: Double, centerY: Double) {
        if (route.size < 2) return
        val path = Path()
        route.forEachIndexed { index, point ->
            val pos = screenPosition(point.latitude, point.longitude, z, scale, centerX, centerY)
            if (index == 0) path.moveTo(pos.first, pos.second) else path.lineTo(pos.first, pos.second)
        }
        canvas.drawPath(path, linePaint)
    }

    private fun drawMarker(canvas: Canvas, lat: Double, lon: Double, color: Int, label: String, z: Int, scale: Double, centerX: Double, centerY: Double) {
        val (x, y) = screenPosition(lat, lon, z, scale, centerX, centerY)
        if (x < -80f * density || x > width + 80f * density || y < -60f * density || y > height + 60f * density) return
        markerPaint.color = Color.WHITE
        markerPaint.style = Paint.Style.FILL
        canvas.drawCircle(x, y, 10f * density, markerPaint)
        markerPaint.color = color
        canvas.drawCircle(x, y, 6f * density, markerPaint)
        labelPaint.color = Color.rgb(35, 38, 45)
        canvas.drawText(label, x, y - 14f * density, labelPaint)
    }

    private fun drawLoadingOrError(canvas: Canvas, loaded: Int, missing: Int) {
        if (loaded > 0) return
        val elapsed = System.currentTimeMillis() - firstDrawAt
        val message = if (elapsed < TILE_ERROR_DELAY_MS) "Loading map…" else
            if (missing > 0) "Map tiles unavailable. Check internet and tap to retry." else "Loading map…"
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 49, 57)
            textSize = 15f * density
            textAlign = Paint.Align.CENTER
        }
        val x = width / 2f
        val y = height / 2f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xeefafafa.toInt() }
        val measured = text.measureText(message)
        canvas.drawRoundRect(x - measured / 2f - 14f * density, y - 25f * density, x + measured / 2f + 14f * density, y + 25f * density, 10f * density, 10f * density, bg)
        canvas.drawText(message, x, y + 5f * density, text)
        if (elapsed < TILE_ERROR_DELAY_MS) postInvalidateDelayed(500)
    }

    private fun screenPosition(lat: Double, lon: Double, z: Int, scale: Double, centerX: Double, centerY: Double): Pair<Float, Float> {
        val (x, y) = project(lat, lon, z)
        val worldSize = TILE_SIZE * (1 shl z)
        var dx = x - centerX
        if (dx > worldSize / 2.0) dx -= worldSize
        if (dx < -worldSize / 2.0) dx += worldSize
        return ((width / 2.0 + dx * scale).toFloat()) to ((height / 2.0 + (y - centerY) * scale).toFloat())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                moved = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> moved = true
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (kotlin.math.abs(event.x - downX) + kotlin.math.abs(event.y - downY) > 8f * density) moved = true
                    val z = floor(zoom).toInt().coerceIn(2, 19)
                    val scale = 2.0.pow(zoom - z)
                    val (cx, cy) = project(centerLat, centerLon, z)
                    val next = unproject(cx - dx / scale, cy - dy / scale, z)
                    centerLat = next.first; centerLon = next.second
                    invalidate()
                }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!moved) {
                    performClick()
                    val picker = onCoordinatePicked
                    if (picker != null) {
                        val z = floor(zoom).toInt().coerceIn(2, 19)
                        val scale = 2.0.pow(zoom - z)
                        val (cx, cy) = project(centerLat, centerLon, z)
                        val picked = unproject(
                            cx + (event.x - width / 2.0) / scale,
                            cy + (event.y - height / 2.0) / scale,
                            z
                        )
                        picker(OSMCoordinate(picked.first, picked.second))
                    } else if (loadedVisibleTiles == 0 || failedVisibleKeys.isNotEmpty()) {
                        failedVisibleKeys.forEach(OsmTileStore::retry)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun isValid(point: OSMCoordinate) = point.latitude.isFinite() && point.longitude.isFinite() &&
        point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0

    companion object {
        private const val TILE_SIZE = 256.0
        private const val TILE_ERROR_DELAY_MS = 12_000L
    }
}

private object OsmTileStore {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val pending = Collections.synchronizedSet(mutableSetOf<String>())
    private val failures = ConcurrentHashMap<String, Long>()
    private val workers = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "safechild-osm-tile").apply { isDaemon = true }
    }

    fun get(key: String): Bitmap? {
        val bitmap = cache.get(key)
        if (bitmap != null) return bitmap
        val failedAt = failures[key]
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_AFTER_MS) return null
        if (failedAt != null) failures.remove(key, failedAt)
        return null
    }

    fun request(key: String, view: View) {
        if (cache.get(key) != null || failures.containsKey(key) || !pending.add(key)) return
        val viewRef = WeakReference(view)
        workers.execute {
            var bitmap: Bitmap? = null
            try {
                val connection = (URL("https://tile.openstreetmap.org/$key.png").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    useCaches = true
                    setRequestProperty("User-Agent", "SafeChildAI/1.0 (com.safechild.ai; Android)")
                    setRequestProperty("Referer", "https://www.openstreetmap.org/")
                    setRequestProperty("Accept", "image/png,image/*;q=0.8,*/*;q=0.5")
                }
                connection.inputStream.use { bitmap = BitmapFactory.decodeStream(it) }
                connection.disconnect()
            } catch (_: Exception) {
                bitmap = null
            }
            if (bitmap != null) cache.put(key, bitmap!!) else failures[key] = System.currentTimeMillis()
            pending.remove(key)
            viewRef.get()?.postInvalidate()
        }
    }

    fun retry(key: String) {
        failures.remove(key)
    }

    private const val RETRY_AFTER_MS = 30_000L
}

private fun project(latitude: Double, longitude: Double, zoom: Int): Pair<Double, Double> {
    val size = 256.0 * (1 shl zoom)
    val lat = latitude.coerceIn(-85.05112878, 85.05112878)
    val x = (longitude + 180.0) / 360.0 * size
    val sinLat = sin(lat * PI / 180.0)
    val y = (0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * PI)) * size
    return x to y
}

private fun unproject(x: Double, y: Double, zoom: Int): Pair<Double, Double> {
    val size = 256.0 * (1 shl zoom)
    val longitude = (x / size) * 360.0 - 180.0
    val n = PI - 2.0 * PI * y / size
    val latitude = 180.0 / PI * atan(sinh(n))
    return latitude.coerceIn(-85.05112878, 85.05112878) to longitude.coerceIn(-180.0, 180.0)
}
