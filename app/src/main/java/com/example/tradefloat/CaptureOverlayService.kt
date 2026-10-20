package com.example.tradefloat

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * Experimental live visual trend prototype.
 * Frames are processed in memory and are not saved or uploaded.
 * The pixel heuristic is not a validated predictor and does not read exact OHLC.
 */
class CaptureOverlayService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL = "tradefloat_capture"
        private const val NOTIFICATION_ID = 17
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var windowManager: WindowManager? = null
    private var panel: View? = null
    private var statusText: TextView? = null
    private var lastAt = 0L
    @Volatile private var processing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL).setContentTitle("TradeFloat live session")
                .setContentText("Live frames are analyzed in memory.")
                .setSmallIcon(android.R.drawable.ic_menu_view).build()
        else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("TradeFloat live session")
                .setContentText("Live frames are analyzed in memory.")
                .setSmallIcon(android.R.drawable.ic_menu_view).build()
        }
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            setupCapture()
            if (Settings.canDrawOverlays(this)) showOverlay()
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun setupCapture() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = try { reader.acquireLatestImage() } catch (_: Exception) { null }
            if (image != null) {
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (!processing && now - lastAt >= 500L) {
                        processing = true
                        lastAt = now
                        val result = analyzeFrame(image)
                        statusText?.post { statusText?.text = result }
                        processing = false
                    }
                } catch (_: Exception) {
                    processing = false
                } finally {
                    image.close()
                }
            }
        }, null)
        virtualDisplay = projection?.createVirtualDisplay(
            "TradeFloatLive", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, null)
    }

    // Heuristic: detect saturated colored pixels in a rough central ROI and compare
    // their vertical centroids in earlier vs later horizontal bands.
    // It may detect indicators/buttons instead of candles; calibrate before use.
    private data class VisualCandle(
        val x: Int,
        val top: Int,
        val bottom: Int,
        val centerY: Double,
        val greenPixels: Int,
        val redPixels: Int
    )
    private var previousSummary = "Collecting chart frames…"

    /**
     * Continuous, in-memory visual candle approximation.
     * This detects narrow vertical clusters of saturated pixels; it is NOT
     * a guaranteed OHLC extractor and must be validated against the user's chart.
     */
    private fun analyzeFrame(image: Image): String {
        val bmp = toBitmap(image) ?: return "LIVE • frame unavailable"
        try {
            // Restrict to the chart plot, excluding left sentiment bar and right price scale.
            val x0 = (bmp.width * 0.07f).toInt().coerceAtLeast(0)
            val x1 = (bmp.width * 0.68f).toInt().coerceAtMost(bmp.width - 1)
            val y0 = (bmp.height * 0.13f).toInt().coerceAtLeast(0)
            val y1 = (bmp.height * 0.66f).toInt().coerceAtMost(bmp.height - 1)
            val roiW = x1 - x0
            val roiH = y1 - y0
            if (roiW < 50 || roiH < 50) return "LIVE • chart area too small"

            // Downscaled bitmap is sampled at its native pixels. Build a mask of
            // strongly red/green pixels, then detect contiguous colored x-runs.
            val mask = Array(roiH) { BooleanArray(roiW) }
            for (yy in 0 until roiH) {
                for (xx in 0 until roiW) {
                    val c = bmp.getPixel(x0 + xx, y0 + yy)
                    mask[yy][xx] = isCandleColor(c)
                }
            }

            // For each x, find vertical runs of colored pixels. Candle bodies and wicks
            // generally create a run; tiny isolated anti-aliased pixels are discarded.
            data class Run(val x: Int, val top: Int, val bottom: Int, val red: Int, val green: Int) {
                val height: Int get() = bottom - top + 1
            }
            val runs = mutableListOf<Run>()
            for (xx in 0 until roiW) {
                var yy = 0
                while (yy < roiH) {
                    if (!mask[yy][xx]) { yy++; continue }
                    val topRun = yy
                    var redCount = 0
                    var greenCount = 0
                    while (yy < roiH && mask[yy][xx]) {
                        val c = bmp.getPixel(x0 + xx, y0 + yy)
                        if (Color.red(c) > Color.green(c) * 1.18) redCount++
                        if (Color.green(c) > Color.red(c) * 1.08) greenCount++
                        yy++
                    }
                    val botRun = yy - 1
                    if (botRun - topRun + 1 >= 3) runs.add(Run(xx, topRun, botRun, redCount, greenCount))
                }
            }

            // Merge adjacent columns with overlapping/nearby vertical runs into candle candidates.
            data class Candidate(var left: Int, var right: Int, var top: Int, var bottom: Int,
                                 var red: Int, var green: Int)
            val candidates = mutableListOf<Candidate>()
            for (run in runs.sortedWith(compareBy<Run> { it.x }.thenBy { it.top })) {
                val candidate = candidates.lastOrNull {
                    run.x - it.right <= 3 &&
                    run.bottom >= it.top - 5 && run.top <= it.bottom + 5
                }
                if (candidate == null) {
                    candidates.add(Candidate(run.x, run.x, run.top, run.bottom, run.red, run.green))
                } else {
                    candidate.right = max(candidate.right, run.x)
                    candidate.top = min(candidate.top, run.top)
                    candidate.bottom = max(candidate.bottom, run.bottom)
                    candidate.red += run.red
                    candidate.green += run.green
                }
            }

            val candles = candidates.filter {
                val width = it.right - it.left + 1
                val height = it.bottom - it.top + 1
                width in 1..18 && height >= 4 && (it.red + it.green) >= 5
            }.sortedBy { it.left }

            if (candles.size < 5) {
                return "LIVE • candle detection weak\nDetected ${candles.size} candidates; chart ROI/color calibration needed."
            }

            // Candle image coordinates only; these are not exact price values.
            val recent = candles.takeLast(min(8, candles.size))
            val prior = candles.dropLast(min(4, candles.size)).takeLast(4)
            val recentCenter = recent.map { (it.top + it.bottom) / 2.0 }.average()
            val priorCenter = prior.map { (it.top + it.bottom) / 2.0 }.average()
            val threshold = roiH * 0.025
            val spatial = when {
                recentCenter < priorCenter - threshold -> "UP"
                recentCenter > priorCenter + threshold -> "DOWN"
                else -> "FLAT"
            }

            val last = candles.last()
            val lastColor = when {
                last.green > last.red * 1.15 -> "GREEN"
                last.red > last.green * 1.15 -> "RED"
                else -> "MIXED"
            }
            val highs = recent.map { it.top }
            val lows = recent.map { it.bottom }
            val structure = when {
                highs.takeLast(2).all { h -> h < (highs.dropLast(2).minOrNull() ?: h) } -> "higher visual highs"
                lows.takeLast(2).all { l -> l > (lows.dropLast(2).maxOrNull() ?: l) } -> "lower visual lows"
                else -> "mixed structure"
            }

            val direction = when {
                spatial == "UP" && lastColor != "RED" -> "UP"
                spatial == "DOWN" && lastColor != "GREEN" -> "DOWN"
                else -> "UNCLEAR"
            }
            return "LIVE VISUAL: $direction\nSpatial candle trend: $spatial\nLatest detected color: $lastColor\nStructure: $structure\nCandle-like components: ${candles.size}\nVisual analysis only; not a future-price prediction"
        } finally {
            bmp.recycle()
        }
    }

    private fun isCandleColor(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        if (hi < 90 || hi - lo < 45) return false
        val red = r > g * 1.22 && r > b * 1.12
        val green = g > r * 1.10 && g > b * 1.05
        return red || green
    }

    private fun toBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val w = image.width; val h = image.height
        if (pixelStride < 4 || w <= 0 || h <= 0) return null
        val pad = ((rowStride - pixelStride * w) / pixelStride).coerceAtLeast(0)
        val padded = Bitmap.createBitmap(w + pad, h, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val cropped = if (pad > 0) Bitmap.createBitmap(padded, 0, 0, w, h).also { padded.recycle() } else padded
        val maxDim = 360
        if (max(w, h) <= maxDim) return cropped
        val scale = maxDim.toFloat() / max(w, h)
        val small = Bitmap.createScaledBitmap(cropped, (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1), false)
        if (small !== cropped) cropped.recycle()
        return small
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.rgb(20, 25, 33))
        }
        val heading = TextView(this).apply {
            text = "TRADEFLOAT  ⋮"; textSize = 16f; setTextColor(Color.WHITE)
            setPadding(4, 2, 4, 8)
        }
        statusText = TextView(this).apply {
            text = "Starting live analysis…"; textSize = 13f
            setTextColor(Color.LTGRAY); setPadding(4, 4, 4, 8)
        }
        val stop = Button(this).apply {
            text = "Stop live session"; setOnClickListener { stopSelf() }
        }
        root.addView(heading); root.addView(statusText); root.addView(stop)
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 180 }
        heading.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY; return true }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = sx + (e.rawX - tx).toInt()
                        params.y = sy + (e.rawY - ty).toInt()
                        windowManager?.updateViewLayout(root, params); return true
                    }
                }
                return false
            }
        })
        panel = root
        try { windowManager?.addView(root, params) } catch (_: Exception) { stopSelf() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "TradeFloat screen session", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        try { panel?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        panel = null
        super.onDestroy()
    }
}
