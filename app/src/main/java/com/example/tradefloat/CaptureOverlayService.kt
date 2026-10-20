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
 * Complete replacement for TradeFloat's screen-capture/overlay service.
 *
 * - Shows the overlay before starting the capture pipeline, so a capture failure
 *   does not silently make the panel disappear.
 * - Registers MediaProjection.Callback before createVirtualDisplay().
 * - Samples frames in memory, throttled to roughly once per second.
 * - Uses a conservative visual pixel heuristic only. It is NOT OHLC extraction
 *   and must not be treated as a real-money trading signal.
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
    private var lastFrameAt = 0L
    @Volatile private var processing = false
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)

            // Important: show panel FIRST. If capture setup fails, the user can see why.
            if (Settings.canDrawOverlays(this)) {
                showOverlay()
                setStatus("LIVE • starting screen reader…")
            } else {
                setStatus("Overlay permission missing.\nEnable Display over other apps for TradeFloat.")
            }

            setupCapture()
        } catch (e: Exception) {
            showStartupError(e)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
                .setContentTitle("TradeFloat screen session")
                .setContentText("Screen frames are processed in memory.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("TradeFloat screen session")
                .setContentText("Screen frames are processed in memory.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        }
    }

    private fun setupCapture() {
        val dm = resources.displayMetrics
        val width = dm.widthPixels.coerceAtLeast(1)
        val height = dm.heightPixels.coerceAtLeast(1)
        val density = dm.densityDpi.coerceAtLeast(1)

        imageReader?.close()
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = try {
                reader.acquireLatestImage()
            } catch (_: Exception) {
                null
            } ?: return@setOnImageAvailableListener

            try {
                val now = SystemClock.elapsedRealtime()
                if (!processing && now - lastFrameAt >= 900L) {
                    processing = true
                    lastFrameAt = now
                    val result = try {
                        analyzeFrame(image)
                    } catch (e: Exception) {
                        "LIVE • analyzer error: ${e.javaClass.simpleName}"
                    }
                    setStatus(result)
                    processing = false
                }
            } finally {
                processing = false
                image.close()
            }
        }, null)

        val activeProjection = projection ?: throw IllegalStateException("Screen capture permission unavailable")
        // Android 14+ requires callback registration before createVirtualDisplay().
        activeProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, null)

        virtualDisplay = activeProjection.createVirtualDisplay(
            "TradeFloatLive",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        ) ?: throw IllegalStateException("Android did not create the screen display")
        setStatus("LIVE • reading frames…\nNo screenshot files are saved.")
    }

    /**
     * Lightweight visual diagnostic. Samples a reduced bitmap and looks for
     * saturated red/green pixels in an approximate chart region.
     * The resulting slope is descriptive only, not a future price prediction.
     */
    private fun analyzeFrame(image: Image): String {
        val bitmap = imageToSmallBitmap(image) ?: return "LIVE • frame unavailable"
        try {
            val width = bitmap.width
            val height = bitmap.height
            // Approximate chart plot area; excludes left sentiment bar and right price labels.
            val left = (width * 0.07f).toInt().coerceIn(0, width - 1)
            val right = (width * 0.68f).toInt().coerceIn(left + 1, width)
            val top = (height * 0.13f).toInt().coerceIn(0, height - 1)
            val bottom = (height * 0.66f).toInt().coerceIn(top + 1, height)
            val plotHeight = bottom - top

            var earlySum = 0.0
            var earlyCount = 0
            var lateSum = 0.0
            var lateCount = 0
            var coloredCount = 0
            var sampledCount = 0
            val plotWidth = right - left
            val earlyLimit = left + plotWidth / 3
            val lateLimit = right - plotWidth / 3

            var y = top
            while (y < bottom) {
                var x = left
                while (x < right) {
                    sampledCount++
                    val color = bitmap.getPixel(x, y)
                    if (isCandleColor(color)) {
                        coloredCount++
                        if (x <= earlyLimit) {
                            earlySum += y.toDouble()
                            earlyCount++
                        }
                        if (x >= lateLimit) {
                            lateSum += y.toDouble()
                            lateCount++
                        }
                    }
                    x += 2
                }
                y += 2
            }

            if (coloredCount < 12 || earlyCount < 3 || lateCount < 3) {
                return "LIVE • chart not recognized\nKeep the candles visible; detector needs calibration."
            }

            val earlyY = earlySum / earlyCount
            val lateY = lateSum / lateCount
            val delta = lateY - earlyY
            val trend = when {
                delta < -plotHeight * 0.025 -> "UP"
                delta > plotHeight * 0.025 -> "DOWN"
                else -> "UNCLEAR"
            }
            val coverage = (coloredCount * 100 / sampledCount.coerceAtLeast(1)).coerceIn(0, 100)
            return "LIVE VISUAL SLOPE: $trend\nSample coverage: $coverage%\nFrames update automatically\nIn-memory only • not a future-price prediction"
        } finally {
            bitmap.recycle()
        }
    }

    private fun isCandleColor(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val high = max(r, max(g, b))
        val low = min(r, min(g, b))
        if (high < 90 || high - low < 45) return false
        val redLike = r > g * 1.22 && r > b * 1.12
        val greenLike = g > r * 1.10 && g > b * 1.05
        return redLike || greenLike
    }

    private fun imageToSmallBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        if (pixelStride < 4 || width < 1 || height < 1) return null

        val rowPadding = ((rowStride - pixelStride * width) / pixelStride).coerceAtLeast(0)
        val paddedWidth = width + rowPadding
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)

        val cropped = if (paddedWidth != width) {
            Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
        } else {
            padded
        }

        // Keep analysis bounded for Android phone performance.
        val maxDimension = 360
        if (max(width, height) <= maxDimension) return cropped
        val scale = maxDimension.toFloat() / max(width, height)
        val small = Bitmap.createScaledBitmap(
            cropped,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            false
        )
        if (small !== cropped) cropped.recycle()
        return small
    }

    private fun showOverlay() {
        if (panel != null || !Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.rgb(20, 25, 33))
        }
        val header = TextView(this).apply {
            text = "TRADEFLOAT  ⋮"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(4, 2, 4, 8)
        }
        statusText = TextView(this).apply {
            text = "LIVE • starting…"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(4, 4, 4, 8)
        }
        val stopButton = Button(this).apply {
            text = "STOP LIVE SESSION"
            setOnClickListener { stopSelf() }
        }
        root.addView(header)
        root.addView(statusText)
        root.addView(stopButton)

        val windowType = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 18
            y = 120
        }

        header.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - touchX).toInt()
                        params.y = startY + (event.rawY - touchY).toInt()
                        try {
                            windowManager?.updateViewLayout(root, params)
                        } catch (_: Exception) {}
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager?.addView(root, params)
            panel = root
        } catch (e: Exception) {
            statusText = null
            panel = null
        }
    }

    private fun setStatus(message: String) {
        statusText?.post { statusText?.text = message }
    }

    private fun showStartupError(error: Exception) {
        val message = "STARTUP ERROR: ${error.javaClass.simpleName}\n${error.message ?: "No details"}"
        if (panel == null && Settings.canDrawOverlays(this)) showOverlay()
        setStatus(message)
        // Keep the foreground service alive so the user can read the error.
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL,
                "TradeFloat screen session",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopping = true
        try {
            panel?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        panel = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        super.onDestroy()
    }
}
