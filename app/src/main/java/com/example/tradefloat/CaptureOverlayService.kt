
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
import android.os.Handler
import android.os.HandlerThread
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

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    @Volatile
    private var processing = false

    private var lastAnalysisAt = 0L

    private data class VisualCandle(
        val x: Int,
        val top: Int,
        val bottom: Int,
        val centerY: Double,
        val greenPixels: Int,
        val redPixels: Int
    )

    private val frameHistory =
        ArrayDeque<List<VisualCandle>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        createNotificationChannel()

        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
                .setContentTitle("TradeFloat live session")
                .setContentText("Screen analysis is active.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("TradeFloat live session")
                .setContentText("Screen analysis is active.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        }

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

        val resultCode = intent?.getIntExtra(
            EXTRA_RESULT_CODE,
            Activity.RESULT_CANCELED
        ) ?: Activity.RESULT_CANCELED

        @Suppress("DEPRECATION")
        val data: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent::class.java
                )
            } else {
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }

        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            // Always show the overlay before initializing capture.
            if (!Settings.canDrawOverlays(this)) {
                stopSelf()
                return START_NOT_STICKY
            }

            showOverlay("Starting screen capture…")

            val manager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

            projection = manager.getMediaProjection(resultCode, data)

            // IMPORTANT:
            // Register the callback BEFORE creating the virtual display.
            projection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopSelf()
                    }
                },
                Handler(mainLooper)
            )

            startWorker()
            setupCapture()

            updateStatus("LIVE • waiting for chart frames…")

        } catch (e: Exception) {
            // Keep the panel visible so the failure can be diagnosed.
            updateStatus(
                "STARTUP ERROR\n" +
                "${e.javaClass.simpleName}\n" +
                "${e.message ?: "No details"}"
            )
        }

        return START_NOT_STICKY
    }

    private fun startWorker() {
        if (workerThread != null) return

        workerThread = HandlerThread("TradeFloatAnalyzer").also {
            it.start()
            workerHandler = Handler(it.looper)
        }
    }

    private fun setupCapture() {
        val metrics = resources.displayMetrics

        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)

        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            3
        )

        imageReader?.setOnImageAvailableListener(
            { reader ->
                val image = try {
                    reader.acquireLatestImage()
                } catch (_: Exception) {
                    null
                }

                if (image != null) {
                    try {
                        val now = SystemClock.elapsedRealtime()

                        // Process at most about twice per second.
                        if (!processing && now - lastAnalysisAt >= 500L) {
                            processing = true
                            lastAnalysisAt = now

                            try {
                                val result = analyzeFrame(image)
                                updateStatus(result)
                            } finally {
                                processing = false
                            }
                        }
                    } catch (e: Exception) {
                        processing = false
                        updateStatus(
                            "ANALYSIS ERROR\n${e.javaClass.simpleName}"
                        )
                    } finally {
                        image.close()
                    }
                }
            },
            workerHandler
        )

        // Callback has already been registered above.
        virtualDisplay = projection?.createVirtualDisplay(
            "TradeFloatLive",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            workerHandler
        ) ?: throw IllegalStateException(
            "Android did not create the virtual display"
        )
    }

    private fun analyzeFrame(image: Image): String {
        val bitmap = toBitmap(image)
            ?: return "LIVE • frame unavailable"

        try {
            // Approximate chart area. This may need calibration.
            val left = (bitmap.width * 0.07f).toInt()
            val right = (bitmap.width * 0.68f).toInt()
            val top = (bitmap.height * 0.13f).toInt()
            val bottom = (bitmap.height * 0.66f).toInt()

            val plotWidth = right - left
            val plotHeight = bottom - top

            if (plotWidth < 40 || plotHeight < 40) {
                return "LIVE • chart area too small"
            }

            val columns = mutableListOf<VisualCandle>()

            var x = left
            while (x < right) {
                var minY = bottom
                var maxY = top
                var sumY = 0.0
                var count = 0
                var red = 0
                var green = 0

                var y = top
                while (y < bottom) {
                    val color = bitmap.getPixel(x, y)

                    if (isMarketColor(color)) {
                        minY = min(minY, y)
                        maxY = max(maxY, y)
                        sumY += y.toDouble()
                        count++

                        val r = Color.red(color)
                        val g = Color.green(color)

                        if (r > g * 1.2) red++
                        if (g > r * 1.1) green++
                    }

                    y += 2
                }

                if (count >= 3 && maxY - minY >= 3) {
                    columns.add(
                        VisualCandle(
                            x = x,
                            top = minY,
                            bottom = maxY,
                            centerY = sumY / count,
                            greenPixels = green,
                            redPixels = red
                        )
                    )
                }

                x += 2
            }

            if (columns.size < 10) {
                frameHistory.clear()
                return "LIVE • chart not recognized\n" +
                    "Keep the candlestick chart visible."
            }

            // Merge nearby colored columns into rough clusters.
            val candles = mutableListOf<VisualCandle>()
            var group = mutableListOf<VisualCandle>()

            for (column in columns) {
                if (
                    group.isNotEmpty() &&
                    column.x - group.last().x > 8
                ) {
                    candles.add(mergeColumns(group))
                    group = mutableListOf()
                }

                group.add(column)
            }

            if (group.isNotEmpty()) {
                candles.add(mergeColumns(group))
            }

            if (candles.size < 4) {
                return "LIVE • candle clusters unclear\n" +
                    "Chart calibration is needed."
            }

            frameHistory.addLast(candles)

            while (frameHistory.size > 8) {
                frameHistory.removeFirst()
            }

            val recent = candles.takeLast(min(8, candles.size))
            val older = candles
                .dropLast(min(4, candles.size))
                .takeLast(4)

            val spatial = if (older.isNotEmpty()) {
                val oldY = older.map { it.centerY }.average()
                val recentY = recent.map { it.centerY }.average()

                when {
                    recentY < oldY - plotHeight * 0.025 ->
                        "UP"
                    recentY > oldY + plotHeight * 0.025 ->
                        "DOWN"
                    else -> "FLAT"
                }
            } else {
                "FLAT"
            }

            var temporal = "WARMING UP"

            if (frameHistory.size >= 4) {
                val previousFrame =
                    frameHistory.elementAt(frameHistory.size - 4)

                val previousY = previousFrame
                    .takeLast(min(5, previousFrame.size))
                    .map { it.centerY }
                    .average()

                val currentY = candles
                    .takeLast(min(5, candles.size))
                    .map { it.centerY }
                    .average()

                temporal = when {
                    currentY < previousY - plotHeight * 0.008 ->
                        "UP"
                    currentY > previousY + plotHeight * 0.008 ->
                        "DOWN"
                    else -> "FLAT"
                }
            }

            val last = candles.last()

            val candleColor = when {
                last.greenPixels > last.redPixels * 1.15 ->
                    "GREEN"
                last.redPixels > last.greenPixels * 1.15 ->
                    "RED"
                else -> "MIXED"
            }

            val direction = when {
                temporal == "WARMING UP" -> "UNCLEAR"
                spatial == temporal && spatial != "FLAT" -> spatial
                temporal == "FLAT" && spatial != "FLAT" -> spatial
                else -> "UNCLEAR"
            }

            val structure = when {
                recent.size >= 4 &&
                    recent.takeLast(2).all {
                        it.top < recent.take(recent.size - 2)
                            .minOf { c -> c.top }
                    } -> "higher-high structure"

                recent.size >= 4 &&
                    recent.takeLast(2).all {
                        it.bottom > recent.take(recent.size - 2)
                            .maxOf { c -> c.bottom }
                    } -> "lower-low structure"

                else -> "mixed structure"
            }

            return "LIVE VISUAL: $direction\n" +
                "Spatial trend: $spatial\n" +
                "Recent frame movement: $temporal\n" +
                "Last detected color: $candleColor\n" +
                "Structure: $structure\n" +
                "Approx. clusters: ${candles.size}\n" +
                "Visual estimate only; not a price prediction"

        } finally {
            bitmap.recycle()
        }
    }

    private fun mergeColumns(
        group: List<VisualCandle>
    ): VisualCandle {
        val total = group.sumOf {
            max(1, it.greenPixels + it.redPixels)
        }

        val center = group.sumOf {
            it.centerY * max(1, it.greenPixels + it.redPixels)
        } / total

        return VisualCandle(
            x = group.map { it.x }.average().toInt(),
            top = group.minOf { it.top },
            bottom = group.maxOf { it.bottom },
            centerY = center,
            greenPixels = group.sumOf { it.greenPixels },
            redPixels = group.sumOf { it.redPixels }
        )
    }

    private fun isMarketColor(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)

        val highest = max(r, max(g, b))
        val lowest = min(r, min(g, b))

        return highest > 105 &&
            highest - lowest > 55 &&
            (
                (g > r * 1.18 && g > b * 1.05) ||
                (r > g * 1.20 && r > b * 1.10) ||
                (b > r * 1.18 && b > g * 1.05)
            )
    }

    private fun toBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null

        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        val width = image.width
        val height = image.height

        if (pixelStride < 4 || width <= 0 || height <= 0) {
            return null
        }

        val padding =
            ((rowStride - pixelStride * width) / pixelStride)
                .coerceAtLeast(0)

        val padded = Bitmap.createBitmap(
            width + padding,
            height,
            Bitmap.Config.ARGB_8888
        )

        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)

        val cropped = if (padding > 0) {
            Bitmap.createBitmap(padded, 0, 0, width, height)
                .also { padded.recycle() }
        } else {
            padded
        }

        // Reduce pixel-processing cost on mobile.
        val maxDimension = 360

        if (max(width, height) <= maxDimension) {
            return cropped
        }

        val scale = maxDimension.toFloat() /
            max(width, height)

        val resized = Bitmap.createScaledBitmap(
            cropped,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            false
        )

        if (resized !== cropped) {
            cropped.recycle()
        }

        return resized
    }

    private fun showOverlay(initialText: String) {
        if (panel != null) {
            updateStatus(initialText)
            return
        }

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.rgb(20, 25, 33))
        }

        val heading = TextView(this).apply {
            text = "TRADEFLOAT  ⋮"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(4, 2, 4, 8)
        }

        statusText = TextView(this).apply {
            text = initialText
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(4, 4, 4, 8)
        }

        val stopButton = Button(this).apply {
            text = "STOP LIVE SESSION"
            setOnClickListener { stopSelf() }
        }

        root.addView(heading)
        root.addView(statusText)
        root.addView(stopButton)

        val windowType =
            if (Build.VERSION.SDK_INT >= 26) {
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
            x = 24
            y = 180
        }

        // Drag the panel using its header.
        heading.setOnTouchListener(
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
                            params.x = startX +
                                (event.rawX - touchX).toInt()

                            params.y = startY +
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
        } catch (e: Exception) {
            statusText?.text =
                "OVERLAY ERROR\n${e.javaClass.simpleName}"
        }
    }

    private fun updateStatus(message: String) {
        val view = statusText ?: return

        view.post {
            try {
                view.text = message
            } catch (_: Exception) {
            }
        }
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
            panel?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (_: Exception) {
        }

        try {
            projection?.stop()
        } catch (_: Exception) {
        }

        workerThread?.quitSafely()

        workerThread = null
        workerHandler = null
        imageReader = null
        virtualDisplay = null
        projection = null
        panel = null
        statusText = null
        frameHistory.clear()

        super.onDestroy()
    }
}
