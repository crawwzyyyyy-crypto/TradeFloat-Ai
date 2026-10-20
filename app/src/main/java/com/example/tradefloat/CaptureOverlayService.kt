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
            if (Settings.canDrawOverlays(this)) showOverlay()
            setupCapture()
        } catch (e: Exception) {
            statusText?.post {
                statusText?.text = "Capture failed: ${e.javaClass.simpleName}\\nStop and restart screen sharing."
            }
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
        // Android 14+ requires callback registration before createVirtualDisplay().
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, null)
        virtualDisplay = projection?.createVirtualDisplay(
            "TradeFloatLive", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    // Heuristic: detect saturated colored pixels in a rough central ROI and compare
    // their vertical centroids in earlier vs later horizontal bands.
    // It may detect indicators/buttons instead of candles; calibrate before use.
    private fun analyzeFrame(image: Image): String {
        val bmp = toBitmap(image) ?: return "LIVE • frame unavailable"
        try {
            val left = (bmp.width * 0.06f).toInt()
            val right = (bmp.width * 0.86f).toInt()
            val top = (bmp.height * 0.16f).toInt()
            val bottom = (bmp.height * 0.78f).toInt()
            val pw = right - left
            val ph = bottom - top
            if (pw < 40 || ph < 40) return "LIVE • chart area too small"

            val bands = 8
            val centers = DoubleArray(bands) { Double.NaN }
            for (b in 0 until bands) {
                val x0 = left + pw * b / bands
                val x1 = left + pw * (b + 1) / bands
                var sumY = 0.0
                var count = 0
                var y = top
                while (y < bottom) {
                    var x = x0
                    while (x < x1) {
                        if (isMarketColor(bmp.getPixel(x, y))) {
                            sumY += y
                            count++
                        }
                        x += 3
                    }
                    y += 3
                }
                if (count >= 3) centers[b] = sumY / count
            }
            val early = (0..3).map { centers[it] }.filter { it.isFinite() }
            val late = (5..7).map { centers[it] }.filter { it.isFinite() }
            if (early.size < 2 || late.size < 2)
                return "LIVE • chart not recognized\nTry a clearer chart view; ROI needs calibration."
            val delta = late.average() - early.average()
            val threshold = ph * 0.025
            val trend = when {
                delta < -threshold -> "VISUAL TREND: UP"
                delta > threshold -> "VISUAL TREND: DOWN"
                else -> "VISUAL TREND: UNCLEAR"
            }
            return "$trend\nLive frame sampling ~2x/sec\nIn-memory only • no saved screenshots\nThis is NOT a future-price prediction"
        } finally {
            bmp.recycle()
        }
    }

    private fun isMarketColor(c: Int): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        val hi = max(r, max(g, b)); val lo = min(r, min(g, b))
        return hi > 105 && hi - lo > 55 &&
            ((g > r * 1.18 && g > b * 1.05) ||
             (r > g * 1.20 && r > b * 1.10) ||
             (b > r * 1.18 && b > g * 1.05))
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
