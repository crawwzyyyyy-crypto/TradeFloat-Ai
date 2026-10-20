
package com.example.tradefloat

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
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
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CaptureOverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL = "tradefloat_capture"
        private const val NOTIFICATION_ID = 17

        // Initial chart region. These are screen fractions, not
        // guaranteed chart coordinates for every trading platform.
        private const val ROI_LEFT = 0.05f
        private const val ROI_RIGHT = 0.70f
        private const val ROI_TOP = 0.20f
        private const val ROI_BOTTOM = 0.68f

        private const val FRAME_INTERVAL_MS = 700L
    }

    @Volatile
    private var horizonSeconds = 30

    @Volatile
    private var processing = false

    @Volatile
    private var stopped = false

    @Volatile
    private var lastFrameAt = 0L

    @Volatile
    private var lastDirection = "UNCLEAR"

    @Volatile
    private var lastConfidence = "WAITING FOR CHART"

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private var windowManager: WindowManager? = null
    private var panel: View? = null
    private var headerText: TextView? = null
    private var directionText: TextView? = null
    private var detailText: TextView? = null

    private data class ColorRun(
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

    private data class VisualResult(
        val direction: String,
        val confidence: String,
        val details: String
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (projection != null) return START_NOT_STICKY

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
        } catch (_: Exception) {
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
            }

            startWorker()
            setupCapture()

        } catch (_: Exception) {
            showOverlayIfAllowed()
            showMessage(
                "UNCLEAR",
                "Screen capture failed.\nCheck permissions and restart."
            )
        }

        return START_NOT_STICKY
    }

    private fun startWorker() {
        if (workerThread != null) return

        workerThread = HandlerThread(
            "TradeFloatLiveChart"
        ).also {
            it.start()
            workerHandler = Handler(it.looper)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
                .setContentTitle("TradeFloat is running")
                .setContentText("Reading visible chart frames")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("TradeFloat is running")
                .setContentText("Reading visible chart frames")
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
            3
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
                    now - lastFrameAt >= FRAME_INTERVAL_MS
                ) {
                    processing = true
                    lastFrameAt = now

                    val accepted = workerHandler?.post {
                        try {
                            val result = analyzeFrame(image)

                            lastDirection = result.direction
                            lastConfidence = result.confidence

                            showMessage(
                                result.direction,
                                result.details
                            )
                        } catch (_: Exception) {
                            lastDirection = "UNCLEAR"
                            lastConfidence = "ANALYSIS ERROR"

                            showMessage(
                                "UNCLEAR",
                                "Chart analysis failed.\nWaiting for a readable frame."
                            )
                        } finally {
                            try {
                                image.close()
                            } catch (_: Exception) {
                            }
                            processing = false
                        }
                    } ?: false

                    if (!accepted) {
                        try {
                            image.close()
                        } catch (_: Exception) {
                        }
                        processing = false
                    }
                } else {
                    try {
                        image.close()
                    } catch (_: Exception) {
                    }
                }
            },
            null
        )

        val activeProjection = projection
            ?: throw IllegalStateException(
                "Screen-capture permission is missing."
            )

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }

        projectionCallback = callback

        activeProjection.registerCallback(
            callback,
            workerHandler
        )

        virtualDisplay = activeProjection.createVirtualDisplay(
            "TradeFloatChart",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            workerHandler
        ) ?: throw IllegalStateException(
            "Could not create screen display."
        )

        showMessage(
            "UNCLEAR",
            "Continuously reading the visible chart…"
        )
    }

    /*
     * Visual-only analysis:
     * 1. Detect red/green vertical runs.
     * 2. Group nearby runs into candle-like candidates.
     * 3. Compare broad and recent vertical movement.
     * 4. Require recent color evidence to agree with both.
     *
     * The output is not verified market OHLC or a calibrated
     * probability of a future move.
     */
    private fun analyzeFrame(image: Image): VisualResult {
        val bitmap = imageToSmallBitmap(image)
            ?: return unclear("Chart frame unavailable.")

        try {
            val width = bitmap.width
            val height = bitmap.height

            if (width < 50 || height < 50) {
                return unclear("Screen frame is too small.")
            }

            val left = (width * ROI_LEFT)
                .toInt().coerceIn(0, width - 2)

            val right = (width * ROI_RIGHT)
                .toInt().coerceIn(left + 1, width)

            val top = (height * ROI_TOP)
                .toInt().coerceIn(0, height - 2)

            val bottom = (height * ROI_BOTTOM)
                .toInt().coerceIn(top + 1, height)

            val plotWidth = right - left
            val plotHeight = bottom - top

            if (plotWidth < 40 || plotHeight < 40) {
                return unclear("Chart region is too small.")
            }

            val runs = mutableListOf<ColorRun>()
            var coloredPixels = 0
            var sampledPixels = 0

            // Scan multiple vertical columns through the ROI.
            var x = left

            while (x < right) {
                var y = top
                var start = -1
                var end = -1
                var redVotes = 0
                var greenVotes = 0

                fun finishRun() {
                    if (
                        start >= 0 &&
                        end - start + 1 >= 3
                    ) {
                        val color = when {
                            greenVotes > redVotes -> Color.GREEN
                            redVotes > greenVotes -> Color.RED
                            else -> Color.TRANSPARENT
                        }

                        if (color != Color.TRANSPARENT) {
                            runs.add(
                                ColorRun(
                                    x = x,
                                    top = start,
                                    bottom = end,
                                    color = color
                                )
                            )
                        }
                    }

                    start = -1
                    end = -1
                    redVotes = 0
                    greenVotes = 0
                }

                while (y < bottom) {
                    val pixel = bitmap.getPixel(x, y)
                    sampledPixels++

                    if (isCandleColor(pixel)) {
                        coloredPixels++

                        if (start < 0) start = y
                        end = y

                        val red = Color.red(pixel)
                        val green = Color.green(pixel)
                        val blue = Color.blue(pixel)

                        if (
                            red > green * 1.22 &&
                            red > blue * 1.12
                        ) {
                            redVotes++
                        }

                        if (
                            green > red * 1.10 &&
                            green > blue * 1.05
                        ) {
                            greenVotes++
                        }
                    } else {
                        finishRun()
                    }

                    y++
                }

                finishRun()
                x += 2
            }

            val coverage = coloredPixels * 100 /
                sampledPixels.coerceAtLeast(1)

            val filteredRuns = runs.filter {
                val runHeight = it.bottom - it.top + 1

                runHeight >= 3 &&
                    runHeight <= plotHeight * 0.22
            }

            val grouped = groupRuns(
                filteredRuns,
                plotHeight
            )

            // Merge close fragments instead of counting every
            // fragment as an independent candle.
            val candidates = mutableListOf<CandleCandidate>()

            for (candidate in grouped) {
                val previous = candidates.lastOrNull()

                if (
                    previous == null ||
                    candidate.x - previous.x >= 4
                ) {
                    candidates.add(candidate)
                } else if (candidate.height > previous.height) {
                    candidates[candidates.lastIndex] = candidate
                }
            }

            if (candidates.size < 10 || coverage < 1) {
                return unclear(
                    "Insufficient visible candle detail.\n" +
                        "Keep more chart candles visible."
                )
            }

            val span = (
                candidates.last().x - candidates.first().x
            ).coerceAtLeast(1)

            val firstX = candidates.first().x

            val older = candidates.filter {
                it.x <= firstX + span / 3
            }

            val newer = candidates.filter {
                it.x >= candidates.last().x - span / 3
            }

            if (older.size < 3 || newer.size < 3) {
                return unclear(
                    "Waiting for enough separated visual groups."
                )
            }

            // Screen Y grows downwards: smaller Y means visually higher.
            val broadDelta =
                newer.map { it.centerY }.average() -
                    older.map { it.centerY }.average()

            val recent = candidates.takeLast(
                min(8, candidates.size)
            )

            val recentDelta =
                recent.last().centerY -
                    recent.first().centerY

            val broadDirection = classifyDelta(
                broadDelta,
                plotHeight
            )

            val recentDirection = classifyDelta(
                recentDelta,
                (plotHeight * 0.60).toInt().coerceAtLeast(1)
            )

            // Color evidence from the rightmost candidates.
            val recentCandles = candidates.takeLast(
                min(6, candidates.size)
            )

            val greenCount = recentCandles.count {
                it.color == Color.GREEN
            }

            val redCount = recentCandles.count {
                it.color == Color.RED
            }

            val colorDirection = when {
                greenCount >= 4 && greenCount > redCount -> "UP"
                redCount >= 4 && redCount > greenCount -> "DOWN"
                else -> "UNCLEAR"
            }

            val direction = when {
                broadDirection == "UP" &&
                    recentDirection == "UP" &&
                    colorDirection == "UP" -> "UP"

                broadDirection == "DOWN" &&
                    recentDirection == "DOWN" &&
                    colorDirection == "DOWN" -> "DOWN"

                else -> "UNCLEAR"
            }

            val confidence = when {
                direction == "UNCLEAR" ->
                    "SIGNALS DISAGREE OR ARE WEAK"

                candidates.size >= 15 &&
                    coverage >= 1 ->
                    "VISUAL SIGNALS ALIGNED"

                else ->
                    "LIMITED VISUAL EVIDENCE"
            }

            // No probability is invented from pixel consistency.
            val safeDirection = if (
                confidence == "VISUAL SIGNALS ALIGNED"
            ) {
                direction
            } else {
                "UNCLEAR"
            }

            val details = buildString {
                append("Horizon: ${horizonSeconds}s\n")
                append("Visual groups: ${candidates.size}\n")
                append("Visual assessment: $confidence\n")
                append(
                    "Broad / recent: " +
                        "$broadDirection / $recentDirection\n"
                )
                append("Recent color evidence: $colorDirection\n")
                append("Screen-only estimate • not a forecast")
            }

            return VisualResult(
                direction = safeDirection,
                confidence = confidence,
                details = details
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun groupRuns(
        runs: List<ColorRun>,
        plotHeight: Int
    ): List<CandleCandidate> {
        val output = mutableListOf<CandleCandidate>()

        for (
            run in runs.sortedWith(
                compareBy<ColorRun> { it.x }.thenBy { it.top }
            )
        ) {
            val previous = output.lastOrNull()

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
                    run.x - previous.right <= 4 &&
                    verticalGap <= max(2, plotHeight / 120)

            if (canMerge && previous != null) {
                val newLeft = previous.left
                val newRight = max(previous.right, run.x)
                val newTop = min(previous.top, run.top)
                val newBottom = max(previous.bottom, run.bottom)

                output[output.lastIndex] = CandleCandidate(
                    x = (newLeft + newRight) / 2,
                    left = newLeft,
                    right = newRight,
                    top = newTop,
                    bottom = newBottom,
                    color = previous.color
                )
            } else {
                output.add(
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

        return output.filter {
            it.height >= 3 &&
                it.height <= plotHeight * 0.28 &&
                it.right - it.left <= 8
        }.sortedBy { it.x }
    }

    private fun classifyDelta(
        delta: Double,
        height: Int
    ): String {
        val threshold = height * 0.025

        return when {
            delta < -threshold -> "UP"
            delta > threshold -> "DOWN"
            else -> "UNCLEAR"
        }
    }

    private fun isCandleColor(color: Int): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)

        val high = max(red, max(green, blue))
        val low = min(red, min(green, blue))

        if (high < 100 || high - low < 50) return false

        return (
            red > green * 1.22 &&
                red > blue * 1.12
        ) || (
            green > red * 1.10 &&
                green > blue * 1.05
        )
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

        val maxDimension = 480

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

        if (scaled !== cropped) cropped.recycle()

        return scaled
    }

    private fun showOverlayIfAllowed() {
        if (Settings.canDrawOverlays(this)) showOverlay()
    }

    private fun showOverlay() {
        if (panel != null || !Settings.canDrawOverlays(this)) {
            return
        }

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 10)
            setBackgroundColor(Color.rgb(20, 25, 33))
        }

        headerText = TextView(this).apply {
            text = "TRADEFLOAT • LIVE • 30s"
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(2, 2, 2, 6)

            // Tap the title to switch between 30 seconds and 1 minute.
            // The three-button UI remains unchanged.
            setOnClickListener {
                horizonSeconds =
                    if (horizonSeconds == 30) 60 else 30

                text = "TRADEFLOAT • LIVE • " +
                    if (horizonSeconds == 30) "30s" else "1m"

                showMessage(
                    "UNCLEAR",
                    "Horizon changed.\nWaiting for a fresh chart reading."
                )
            }
        }

        directionText = TextView(this).apply {
            text = "UNCLEAR"
            textSize = 28f
            setTypeface(
                null,
                android.graphics.Typeface.BOLD
            )
            setTextColor(Color.LTGRAY)
            setPadding(2, 4, 2, 4)
        }

        detailText = TextView(this).apply {
            text = "Reading visible chart…"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(2, 2, 2, 8)
        }

        fun makeButton(
            label: String,
            color: Int,
            action: () -> Unit
        ): Button {
            return Button(this).apply {
                text = label
                textSize = 16f
                isAllCaps = false
                setTextColor(Color.WHITE)

                backgroundTintList =
                    ColorStateList.valueOf(color)

                setOnClickListener { action() }

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    46
                ).apply {
                    bottomMargin = 4
                }
            }
        }

        val upButton = makeButton(
            "UP ↑",
            Color.rgb(30, 180, 105)
        ) {
            Toast.makeText(
                this,
                "Manual UP selection only. No trade was placed.",
                Toast.LENGTH_SHORT
            ).show()
        }

        val downButton = makeButton(
            "DOWN ↓",
            Color.rgb(240, 65, 90)
        ) {
            Toast.makeText(
                this,
                "Manual DOWN selection only. No trade was placed.",
                Toast.LENGTH_SHORT
            ).show()
        }

        val stopButton = makeButton(
            "STOP",
            Color.rgb(70, 77, 88)
        ) {
            stopSelf()
        }

        root.addView(headerText)
        root.addView(directionText)
        root.addView(detailText)
        root.addView(upButton)
        root.addView(downButton)
        root.addView(stopButton)

        val type =
            if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.78f)
                .toInt().coerceAtLeast(1),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (resources.displayMetrics.heightPixels * 0.25f)
                .toInt()
        }

        // Drag the panel by its title without adding more controls.
        headerText?.setOnTouchListener(
            object : View.OnTouchListener {
                private var startX = 0
                private var startY = 0
                private var touchX = 0f
                private var touchY = 0f
                private var moved = false

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
                            moved = false
                            return true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - touchX
                            val dy = event.rawY - touchY

                            if (abs(dx) > 6 || abs(dy) > 6) {
                                moved = true
                            }

                            if (moved) {
                                params.x = startX + dx.toInt()
                                params.y = startY + dy.toInt()

                                try {
                                    windowManager?.updateViewLayout(
                                        root,
                                        params
                                    )
                                } catch (_: Exception) {
                                }
                            }
                            return true
                        }

                        MotionEvent.ACTION_UP -> {
                            if (!moved) {
                                headerText?.performClick()
                            }
                            return true
                        }
                    }

                    return true
                }
            }
        )

        try {
            windowManager?.addView(root, params)
            panel = root
        } catch (_: Exception) {
            headerText = null
            directionText = null
            detailText = null
            panel = null
        }
    }

    private fun unclear(message: String): VisualResult {
        return VisualResult(
            direction = "UNCLEAR",
            confidence = "INSUFFICIENT VISUAL EVIDENCE",
            details = "Horizon: ${horizonSeconds}s\n" +
                "$message\n" +
                "Screen-only estimate • not a forecast"
        )
    }

    private fun showMessage(
        direction: String,
        details: String
    ) {
        directionText?.post {
            directionText?.text = when (direction) {
                "UP" -> "UP ↑"
                "DOWN" -> "DOWN ↓"
                else -> "UNCLEAR"
            }

            directionText?.setTextColor(
                when (direction) {
                    "UP" -> Color.rgb(70, 230, 140)
                    "DOWN" -> Color.rgb(255, 95, 110)
                    else -> Color.LTGRAY
                }
            )
        }

        detailText?.post {
            detailText?.text = details
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
            panel?.let {
                windowManager?.removeView(it)
            }
        } catch (_: Exception) {
        }

        panel = null
        headerText = null
        directionText = null
        detailText = null

        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null

        super.onDestroy()
    }
}
