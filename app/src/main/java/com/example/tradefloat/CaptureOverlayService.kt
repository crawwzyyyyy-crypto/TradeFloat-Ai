
package com.example.tradefloat

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import java.util.ArrayDeque
import kotlin.math.abs
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

    @Volatile
    private var stopped = false

    private var lastFrameAt = 0L
    private var projectionCallback: MediaProjection.Callback? = null

    private data class CandleRun(
        val x: Int,
        val top: Int,
        val bottom: Int,
        val color: Int
    )

    private data class CandleCandidate(
        val x: Int,
        val left: Int,
        val right: Int,
        val top: Int,
        val bottom: Int,
        val color: Int
    ) {
        val centerY: Double
            get() = (top + bottom) / 2.0

        val height: Int
            get() = bottom - top + 1
    }

    private val trendHistory = ArrayDeque<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (projection != null) {
            return START_NOT_STICKY
        }

        createNotificationChannel()

        try {
            val notification = buildNotification()

            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
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
                setStatus("LIVE • starting chart reader…")
            }

            startWorker()
            setupCapture()

        } catch (e: Exception) {
            setStatus(
                "STARTUP ERROR: ${e.javaClass.simpleName}\n" +
                    (e.message ?: "Check permissions and restart.")
            )
        }

        return START_NOT_STICKY
    }

    private fun startWorker() {
        if (workerThread != null) return

        workerThread = HandlerThread(
            "TradeFloatFrameWorker"
        ).also {
            it.start()
            workerHandler = Handler(it.looper)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
                .setContentTitle("TradeFloat screen session")
                .setContentText("Live visual analysis is running.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("TradeFloat screen session")
                .setContentText("Live visual analysis is running.")
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

                val now = SystemClock.elapsedRealtime()

                if (
                    !processing &&
                    !stopped &&
                    now - lastFrameAt >= 800L
                ) {
                    processing = true
                    lastFrameAt = now

                    // Keep expensive image processing off the UI thread.
                    val accepted = workerHandler?.post {
                        try {
                            val result = analyzeFrame(image)
                            setStatus(result)
                        } catch (e: Exception) {
                            setStatus(
                                "LIVE • analysis error: " +
                                    e.javaClass.simpleName
                            )
                        } finally {
                            image.close()
                            processing = false
                        }
                    } ?: false

                    if (!accepted) {
                        image.close()
                        processing = false
                    }
                } else {
                    image.close()
                }
            },
            null
        )

        val activeProjection = projection
            ?: throw IllegalStateException(
                "Screen-capture permission is missing."
            )

        // Android requires callback registration before display creation.
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }

        projectionCallback = callback
        activeProjection.registerCallback(callback, workerHandler)

        virtualDisplay = activeProjection.createVirtualDisplay(
            "TradeFloatLive",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            workerHandler
        ) ?: throw IllegalStateException(
            "Could not create the screen display."
        )

        setStatus(
            "LIVE • collecting chart frames…\n" +
                "Keep the chart visible for a few seconds."
        )
    }

    /**
     * Experimental image analysis.
     *
     * Finds colored vertical runs, groups nearby compatible runs,
     * then compares the visual positions of candidates across the plot.
     *
     * It does NOT recover verified OHLC prices or forecast future prices.
     */
    private fun analyzeFrame(image: Image): String {
        val bitmap = imageToSmallBitmap(image)
            ?: return "LIVE • frame unavailable"

        try {
            val width = bitmap.width
            val height = bitmap.height

            // Approximate chart bounds for the current portrait layout.
            // These are not user-calibrated coordinates.
            val left = (width * 0.055f)
                .toInt().coerceIn(0, width - 1)

            val right = (width * 0.70f)
                .toInt().coerceIn(left + 1, width)

            val top = (height * 0.14f)
                .toInt().coerceIn(0, height - 1)

            val bottom = (height * 0.68f)
                .toInt().coerceIn(top + 1, height)

            val plotWidth = right - left
            val plotHeight = bottom - top

            if (plotWidth < 30 || plotHeight < 30) {
                return "LIVE • chart area too small"
            }

            val runs = mutableListOf<CandleRun>()
            var coloredPixels = 0
            var sampledPixels = 0

            var x = left

            while (x < right) {
                var y = top
                var runStart = -1
                var runEnd = -1
                var runRed = 0
                var runGreen = 0

                fun finishRun() {
                    if (
                        runStart >= 0 &&
                        runEnd >= runStart &&
                        runEnd - runStart >= 2
                    ) {
                        val runColor =
                            if (runGreen > runRed) {
                                Color.GREEN
                            } else {
                                Color.RED
                            }

                        runs.add(
                            CandleRun(
                                x = x,
                                top = runStart,
                                bottom = runEnd,
                                color = runColor
                            )
                        )
                    }

                    runStart = -1
                    runEnd = -1
                    runRed = 0
                    runGreen = 0
                }

                while (y < bottom) {
                    val color = bitmap.getPixel(x, y)
                    sampledPixels++

                    if (isCandleColor(color)) {
                        coloredPixels++

                        if (runStart < 0) {
                            runStart = y
                        }

                        runEnd = y

                        val red = Color.red(color)
                        val green = Color.green(color)

                        if (red > green * 1.18) runRed++
                        if (green > red * 1.08) runGreen++
                    } else {
                        finishRun()
                    }

                    y += 2
                }

                finishRun()
                x += 2
            }

            val coverage = (
                coloredPixels * 100 /
                    sampledPixels.coerceAtLeast(1)
            ).coerceIn(0, 100)

            // Filter extreme runs, which are more likely to be unrelated UI.
            val usableRuns = runs.filter {
                val runHeight = it.bottom - it.top + 1

                runHeight >= 2 &&
                    runHeight <= plotHeight * 0.35
            }

            val candidates = groupCandleRuns(
                usableRuns,
                plotHeight
            )

            if (candidates.size < 6) {
                trendHistory.clear()

                return "LIVE • not enough candle candidates\n" +
                    "Candidates: ${candidates.size}\n" +
                    "Color coverage: $coverage%\n" +
                    "Keep more candles visible."
            }

            val firstX = candidates.first().x
            val lastX = candidates.last().x
            val span = (lastX - firstX).coerceAtLeast(1)

            val older = candidates
                .filter { it.x <= firstX + span / 3 }
                .map { it.centerY }

            val newer = candidates
                .filter { it.x >= lastX - span / 3 }
                .map { it.centerY }

            if (older.size < 2 || newer.size < 2) {
                return "LIVE • collecting chart structure\n" +
                    "Candidates: ${candidates.size}\n" +
                    "Coverage: $coverage%"
            }

            // Smaller screen Y corresponds to a higher position on the chart.
            val delta = newer.average() - older.average()

            val visualSlope = when {
                delta < -plotHeight * 0.025 -> "UP"
                delta > plotHeight * 0.025 -> "DOWN"
                else -> "UNCLEAR"
            }

            trendHistory.addLast(visualSlope)
            while (trendHistory.size > 5) {
                trendHistory.removeFirst()
            }

            val consistentFrames =
                trendHistory.count { it == visualSlope }

            // Detection quality describes only the visual heuristic.
            val quality = when {
                candidates.size >= 12 &&
                    coverage >= 1 &&
                    consistentFrames >= 4 ->
                    "VISUAL CANDIDATES CONSISTENT"

                candidates.size >= 6 ->
                    "LOW VISUAL CONFIDENCE"

                else ->
                    "INSUFFICIENT DATA"
            }

            // Never present this visual slope as a future-price prediction.
            val displayDirection =
                if (
                    quality == "VISUAL CANDIDATES CONSISTENT" &&
                    visualSlope != "UNCLEAR"
                ) {
                    visualSlope
                } else {
                    "UNCLEAR"
                }

            return "LIVE CHART TRACKER: $displayDirection\n" +
                "$quality\n" +
                "Candle candidates: ${candidates.size}\n" +
                "Visual slope: $visualSlope\n" +
                "Frame consistency: $consistentFrames/5\n" +
                "Color coverage: $coverage%\n" +
                "Frames update automatically\n" +
                "Visual estimate only • not a trade prediction"

        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Groups neighboring colored runs when their colors match and their
     * vertical ranges overlap or are close. This is still a heuristic:
     * a group is a candle candidate, not a verified candle.
     */
    private fun groupCandleRuns(
        runs: List<CandleRun>,
        plotHeight: Int
    ): List<CandleCandidate> {
        val sorted = runs.sortedWith(
            compareBy<CandleRun> { it.x }
                .thenBy { it.top }
        )

        val groups = mutableListOf<CandleCandidate>()

        for (run in sorted) {
            val previous = groups.lastOrNull()

            val verticalGap = if (previous == null) {
                Int.MAX_VALUE
            } else {
                when {
                    run.top > previous.bottom ->
                        run.top - previous.bottom

                    previous.top > run.bottom ->
                        previous.top - run.bottom

                    else -> 0
                }
            }

            val canMerge =
                previous != null &&
                previous.color == run.color &&
                run.x - previous.right <= 8 &&
                verticalGap <= max(4, plotHeight / 70)

            if (canMerge && previous != null) {
                val newLeft = previous.left
                val newRight = max(previous.right, run.x)
                val newTop = min(previous.top, run.top)
                val newBottom = max(previous.bottom, run.bottom)

                groups[groups.lastIndex] = CandleCandidate(
                    x = (newLeft + newRight) / 2,
                    left = newLeft,
                    right = newRight,
                    top = newTop,
                    bottom = newBottom,
                    color = previous.color
                )
            } else {
                groups.add(
                    CandleCandidate(
                        x = run.x,
                        left = run.x,
                        right = run.x,
                        top = run.top,
                        bottom = run.bottom,
                        color = run.color
                    )
                )
            }
        }

        // Remove unusually tall groups that likely combine unrelated objects.
        return groups
            .filter {
                it.height >= 3 &&
                    it.height <= plotHeight * 0.45 &&
                    it.right - it.left <= 20
            }
            .sortedBy { it.x }
    }

    private fun isCandleColor(color: Int): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)

        val highest = max(red, max(green, blue))
        val lowest = min(red, min(green, blue))

        if (highest < 90 || highest - lowest < 45) {
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
        val plane = image.planes.firstOrNull() ?: return null

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

        val cropped = if (paddedWidth != width) {
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

        val maxDimension = 360

        if (max(width, height) <= maxDimension) {
            return cropped
        }

        val scale =
            maxDimension.toFloat() / max(width, height)

        val scaled = Bitmap.createScaledBitmap(
            cropped,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            false
        )

        if (scaled !== cropped) {
            cropped.recycle()
        }

        return scaled
    }

    private fun showOverlay() {
        if (
            panel != null ||
            !Settings.canDrawOverlays(this)
        ) {
            return
        }

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

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
            setOnClickListener {
                stopSelf()
            }
        }

        root.addView(header)
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
        stopped = true

        try {
            imageReader?.setOnImageAvailableListener(null, null)
        } catch (_: Exception) {
        }

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        try {
            projectionCallback?.let { callback ->
                projection?.unregisterCallback(callback)
            }
        } catch (_: Exception) {
        }
        projectionCallback = null

        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null

        try {
            panel?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        panel = null
        statusText = null

        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null

        super.onDestroy()
    }
}
