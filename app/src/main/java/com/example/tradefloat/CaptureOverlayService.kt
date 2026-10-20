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

    @Volatile
    private var processing = false

    private data class CandleSample(
        val x: Int,
        val centerY: Double,
        val topY: Int,
        val bottomY: Int,
        val color: Int
    )

    private val frameTrendHistory = ArrayDeque<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        createNotificationChannel()

        val notification = buildNotification()

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo
                        .FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(
            EXTRA_RESULT_CODE,
            Activity.RESULT_CANCELED
        ) ?: Activity.RESULT_CANCELED

        @Suppress("DEPRECATION")
        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent::class.java
                )
            } else {
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val manager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

            projection = manager.getMediaProjection(
                resultCode,
                resultData
            )

            if (Settings.canDrawOverlays(this)) {
                showOverlay()
                setStatus("LIVE • starting screen reader…")
            } else {
                setStatus(
                    "Overlay permission missing.\n" +
                    "Enable Display over other apps."
                )
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
                .setContentText(
                    "Screen frames are processed in memory."
                )
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("TradeFloat screen session")
                .setContentText(
                    "Screen frames are processed in memory."
                )
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        }
    }

    private fun setupCapture() {
        val metrics = resources.displayMetrics

        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi.coerceAtLeast(1)

        imageReader?.close()

        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )

        imageReader?.setOnImageAvailableListener(
            { reader ->

                val image = try {
                    reader.acquireLatestImage()
                } catch (_: Exception) {
                    null
                } ?: return@setOnImageAvailableListener

                try {
                    val now = SystemClock.elapsedRealtime()

                    // Limit analysis to roughly once per second.
                    if (!processing && now - lastFrameAt >= 900L) {
                        processing = true
                        lastFrameAt = now

                        val result = try {
                            analyzeFrame(image)
                        } catch (e: Exception) {
                            "LIVE • analyzer error: " +
                                e.javaClass.simpleName
                        }

                        setStatus(result)
                    }
                } finally {
                    processing = false
                    image.close()
                }
            },
            null
        )

        val activeProjection = projection
            ?: throw IllegalStateException(
                "Screen capture permission unavailable"
            )

        // Register callback before creating the virtual display.
        activeProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            },
            null
        )

        virtualDisplay = activeProjection.createVirtualDisplay(
            "TradeFloatLive",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        ) ?: throw IllegalStateException(
            "Android did not create the screen display"
        )

        setStatus(
            "LIVE • reading frames…\n" +
            "No screenshot files are saved."
        )
    }

    /**
     * Experimental visual detector.
     *
     * Searches for vertical runs of red/green pixels inside an estimated
     * chart region and compares their positions. It does not reconstruct
     * reliable OHLC values and is not a validated trading predictor.
     */
    private fun analyzeFrame(image: Image): String {
        val bitmap = imageToSmallBitmap(image)
            ?: return "LIVE • frame unavailable"

        try {
            val width = bitmap.width
            val height = bitmap.height

            // Approximate portrait-chart region.
            // These boundaries may need calibration for your device.
            val left = (width * 0.055f)
                .toInt().coerceIn(0, width - 1)

            val right = (width * 0.70f)
                .toInt().coerceIn(left + 1, width)

            val top = (height * 0.14f)
                .toInt().coerceIn(0, height - 1)

            val bottom = (height * 0.68f)
                .toInt().coerceIn(top + 1, height)

            val plotHeight = bottom - top
            val plotWidth = right - left

            if (plotHeight < 30 || plotWidth < 30) {
                return "LIVE • chart area too small"
            }

            val candles = mutableListOf<CandleSample>()

            var coloredSamples = 0
            var totalSamples = 0

            var x = left

            while (x < right) {
                var y = top

                var runStart = -1
                var runCount = 0
                var runRed = 0
                var runGreen = 0

                fun finishRun(endY: Int) {
                    if (runStart >= 0 && runCount >= 2) {
                        val center =
                            (runStart + endY) / 2.0

                        val runColor =
                            if (runGreen > runRed) {
                                Color.GREEN
                            } else {
                                Color.RED
                            }

                        candles.add(
                            CandleSample(
                                x = x,
                                centerY = center,
                                topY = runStart,
                                bottomY = endY,
                                color = runColor
                            )
                        )
                    }

                    runStart = -1
                    runCount = 0
                    runRed = 0
                    runGreen = 0
                }

                while (y < bottom) {
                    totalSamples++

                    val color = bitmap.getPixel(x, y)

                    if (isCandleColor(color)) {
                        coloredSamples++

                        if (runStart < 0) {
                            runStart = y
                        }

                        runCount++

                        val red = Color.red(color)
                        val green = Color.green(color)

                        if (red > green * 1.18) {
                            runRed++
                        }

                        if (green > red * 1.08) {
                            runGreen++
                        }
                    } else {
                        finishRun(y - 2)
                    }

                    y += 2
                }

                finishRun(bottom - 1)
                x += 2
            }

            // Remove implausibly tall runs and isolated visual noise.
            val plausible = candles.filter {
                val runHeight = it.bottomY - it.topY

                runHeight >= 2 &&
                    runHeight <= plotHeight * 0.42
            }.sortedBy { it.x }

            val coverage = (
                coloredSamples * 100 /
                    totalSamples.coerceAtLeast(1)
            ).coerceIn(0, 100)

            if (plausible.size < 8) {
                frameTrendHistory.clear()

                return "LIVE • candles not detected\n" +
                    "Detected runs: ${plausible.size}\n" +
                    "Color coverage: $coverage%\n" +
                    "Chart calibration is still needed."
            }

            val firstX = plausible.first().x
            val lastX = plausible.last().x
            val span = (lastX - firstX).coerceAtLeast(1)

            val older = plausible
                .filter { it.x <= firstX + span / 3 }
                .map { it.centerY }

            val newer = plausible
                .filter { it.x >= lastX - span / 3 }
                .map { it.centerY }

            if (older.size < 3 || newer.size < 3) {
                return "LIVE • collecting candle samples\n" +
                    "Detected runs: ${plausible.size}\n" +
                    "Coverage: $coverage%"
            }

            // Android screen Y increases downward.
            // Smaller Y therefore corresponds to a higher chart position.
            val spatialDelta =
                newer.average() - older.average()

            val spatialTrend = when {
                spatialDelta < -plotHeight * 0.025 ->
                    "UP"

                spatialDelta > plotHeight * 0.025 ->
                    "DOWN"

                else ->
                    "UNCLEAR"
            }

            frameTrendHistory.addLast(spatialTrend)

            while (frameTrendHistory.size > 5) {
                frameTrendHistory.removeFirst()
            }

            val stableCount =
                frameTrendHistory.count { it == spatialTrend }

            val quality = when {
                plausible.size >= 18 &&
                    coverage >= 4 &&
                    stableCount >= 3 ->
                    "MODERATE VISUAL DETECTION"

                plausible.size >= 10 ->
                    "LOW VISUAL CONFIDENCE"

                else ->
                    "INSUFFICIENT DATA"
            }

            // This is a visual trend label, not a future price forecast.
            val direction =
                if (
                    quality == "MODERATE VISUAL DETECTION" &&
                    spatialTrend != "UNCLEAR"
                ) {
                    spatialTrend
                } else {
                    "UNCLEAR"
                }

            return "LIVE CANDLE TRACKER: $direction\n" +
                "$quality\n" +
                "Detected candle-like runs: ${plausible.size}\n" +
                "Visual slope: $spatialTrend\n" +
                "Consistency: $stableCount/5\n" +
                "Color coverage: $coverage%\n" +
                "Frames update automatically\n" +
                "Visual estimate only • not a trade prediction"

        } finally {
            bitmap.recycle()
        }
    }

    private fun isCandleColor(color: Int): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)

        val high = max(red, max(green, blue))
        val low = min(red, min(green, blue))

        if (high < 90 || high - low < 45) {
            return false
        }

        val redLike =
            red > green * 1.22 &&
            red > blue * 1.12

        val greenLike =
            green > red * 1.10 &&
            green > blue * 1.05

        return redLike || greenLike
    }

    private fun imageToSmallBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull()
            ?: return null

        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        val width = image.width
        val height = image.height

        if (pixelStride < 4 || width < 1 || height < 1) {
            return null
        }

        val rowPadding = (
            (rowStride - pixelStride * width) /
                pixelStride
        ).coerceAtLeast(0)

        val paddedWidth = width + rowPadding

        val padded = Bitmap.createBitmap(
            paddedWidth,
            height,
            Bitmap.Config.ARGB_8888
        )

        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)

        val cropped =
            if (paddedWidth != width) {
                Bitmap.createBitmap(
                    padded,
                    0,
                    0,
                    width,
                    height
                ).also {
                    padded.recycle()
                }
            } else {
                padded
            }

        // Limit processing cost on Android phones.
        val maxDimension = 360

        if (max(width, height) <= maxDimension) {
            return cropped
        }

        val scale =
            maxDimension.toFloat() /
                max(width, height)

        val small = Bitmap.createScaledBitmap(
            cropped,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            false
        )

        if (small !== cropped) {
            cropped.recycle()
        }

        return small
    }

    private fun showOverlay() {
        if (panel != null ||
            !Settings.canDrawOverlays(this)
        ) {
            return
        }

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(
                Color.rgb(20, 25, 33)
            )
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
            setOnClickListener {
                stopSelf()
            }
        }

        root.addView(header)
        root.addView(statusText)
        root.addView(stopButton)

        val windowType =
            if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams
                    .TYPE_APPLICATION_OVERLAY
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

        header.setOnTouchListener(
            object : View.OnTouchListener {
                private var startX = 0
                private var startY = 0
                private var touchX = 0f
                private var touchY = 0f

                override fun onTouch(
                    view: View,
                    event: MotionEvent
                ): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = params.x
                            startY = params.y
                            touchX = event.rawX
                            touchY = event.rawY
                            return true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            params.x =
                                startX +
                                (event.rawX - touchX).toInt()

                            params.y =
                                startY +
                                (event.rawY - touchY).toInt()

                            try {
                                windowManager?.updateViewLayout(
                                    root,
                                    params
                                )
                            } catch (_: Exception) {
                            }

                            return true
                        }
                    }

                    return false
                }
            }
        )

        try {
            windowManager?.addView(root, params)
            panel = root
        } catch (_: Exception) {
            statusText = null
            panel = null
        }
    }

    private fun setStatus(message: String) {
        statusText?.post {
            statusText?.text = message
        }
    }

    private fun showStartupError(error: Exception) {
        val message =
            "STARTUP ERROR: ${error.javaClass.simpleName}\n" +
            (error.message ?: "No details")

        if (panel == null &&
            Settings.canDrawOverlays(this)
        ) {
            showOverlay()
        }

        setStatus(message)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL,
                "TradeFloat screen session",
                NotificationManager.IMPORTANCE_LOW
            )

            (
                getSystemService(NOTIFICATION_SERVICE)
                    as NotificationManager
            ).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        try {
            panel?.let {
                windowManager?.removeView(it)
            }
        } catch (_: Exception) {
        }

        panel = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }

        virtualDisplay = null

        try {
            imageReader?.setOnImageAvailableListener(
                null,
                null
            )
        } catch (_: Exception) {
        }

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }

        imageReader = null

        try {
            projection?.stop()
        } catch (_: Exception) {
        }

        projection = null

        super.onDestroy()
    }
}
