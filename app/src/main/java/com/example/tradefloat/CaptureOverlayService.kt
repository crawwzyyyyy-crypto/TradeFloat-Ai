
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
        private const val PREFS = "tradefloat_roi"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private var windowManager: WindowManager? = null
    private var panel: View? = null
    private var statusText: TextView? = null
    private var regionText: TextView? = null

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    @Volatile private var processing = false
    @Volatile private var stopped = false

    @Volatile private var roiLeft = 0.055f
    @Volatile private var roiRight = 0.70f
    @Volatile private var roiTop = 0.14f
    @Volatile private var roiBottom = 0.68f

    private var lastFrameAt = 0L

    private data class Candle(
        val x: Int,
        val high: Double,
        val low: Double,
        val open: Double,
        val close: Double,
        val color: Int
    )

    private data class PixelRun(
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
        val height: Int
            get() = bottom - top + 1
    }

    private val recentDirections = ArrayDeque<String>()

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
            loadCalibration()

            val manager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            projection = manager.getMediaProjection(
                resultCode,
                resultData
            )

            if (Settings.canDrawOverlays(this)) {
                showOverlay()
                setStatus("LIVE • initializing candle detector…")
            }

            startWorker()
            setupCapture()

        } catch (e: Exception) {
            if (panel == null && Settings.canDrawOverlays(this)) {
                showOverlay()
            }

            setStatus(
                "STARTUP ERROR: ${e.javaClass.simpleName}\n" +
                    (e.message ?: "Restart the capture session.")
            )
        }

        return START_NOT_STICKY
    }

    private fun startWorker() {
        if (workerThread != null) return

        workerThread = HandlerThread(
            "TradeFloatAnalysis"
        ).also {
            it.start()
            workerHandler = Handler(it.looper)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
                .setContentTitle("TradeFloat screen session")
                .setContentText("Candle detection and analysis active.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("TradeFloat screen session")
                .setContentText("Candle detection and analysis active.")
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

                if (!stopped && !processing &&
                    now - lastFrameAt >= 1000L
                ) {
                    processing = true
                    lastFrameAt = now

                    val accepted = workerHandler?.post {
                        try {
                            if (!stopped) {
                                setStatus(analyzeFrame(image))
                            }
                        } catch (e: Exception) {
                            setStatus(
                                "ANALYSIS ERROR: " +
                                    e.javaClass.simpleName
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
            ?: throw IllegalStateException("Projection unavailable")

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
            "Could not create virtual display"
        )

        setStatus("LIVE • waiting for chart frames…")
    }

    // ------------------------------------------------------------
    // STEP 3: SAVED CHART REGION CALIBRATION
    // ------------------------------------------------------------

    private fun loadCalibration() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        roiLeft = p.getFloat("left", 0.055f)
        roiRight = p.getFloat("right", 0.70f)
        roiTop = p.getFloat("top", 0.14f)
        roiBottom = p.getFloat("bottom", 0.68f)
        validateRegion()
    }

    private fun saveCalibration() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putFloat("left", roiLeft)
            .putFloat("right", roiRight)
            .putFloat("top", roiTop)
            .putFloat("bottom", roiBottom)
            .apply()
    }

    private fun validateRegion() {
        roiLeft = roiLeft.coerceIn(0f, 0.85f)
        roiRight = roiRight.coerceIn(0.15f, 1f)
        roiTop = roiTop.coerceIn(0f, 0.85f)
        roiBottom = roiBottom.coerceIn(0.15f, 1f)

        if (roiRight - roiLeft < 0.10f) {
            roiRight = (roiLeft + 0.10f).coerceAtMost(1f)
            roiLeft = (roiRight - 0.10f).coerceAtLeast(0f)
        }

        if (roiBottom - roiTop < 0.10f) {
            roiBottom = (roiTop + 0.10f).coerceAtMost(1f)
            roiTop = (roiBottom - 0.10f).coerceAtLeast(0f)
        }
    }

    private fun adjustRegion(key: String, amount: Float) {
        when (key) {
            "left" -> roiLeft += amount
            "right" -> roiRight += amount
            "top" -> roiTop += amount
            "bottom" -> roiBottom += amount
        }

        validateRegion()
        saveCalibration()
        updateRegionLabel()

        synchronized(recentDirections) {
            recentDirections.clear()
        }

        setStatus("REGION UPDATED • collecting fresh frames…")
    }

    private fun updateRegionLabel() {
        regionText?.post {
            regionText?.text =
                "L ${(roiLeft * 100).toInt()}%  " +
                "R ${(roiRight * 100).toInt()}%  " +
                "T ${(roiTop * 100).toInt()}%  " +
                "B ${(roiBottom * 100).toInt()}%"
        }
    }

    // ------------------------------------------------------------
    // STEP 4: CANDLE CANDIDATE DETECTION
    //
    // Screen pixels cannot reliably recover OHLC without correctly
    // identifying bodies, wicks and the chart's price scale.
    // This detector estimates candidates; it does not claim that
    // every candidate is a verified candlestick.
    // ------------------------------------------------------------

    private fun analyzeFrame(image: Image): String {
        val bitmap = imageToSmallBitmap(image)
            ?: return "LIVE • frame unavailable"

        try {
            val width = bitmap.width
            val height = bitmap.height

            val left = (width * roiLeft)
                .toInt().coerceIn(0, width - 1)
            val right = (width * roiRight)
                .toInt().coerceIn(left + 1, width)
            val top = (height * roiTop)
                .toInt().coerceIn(0, height - 1)
            val bottom = (height * roiBottom)
                .toInt().coerceIn(top + 1, height)

            val plotWidth = right - left
            val plotHeight = bottom - top

            if (plotWidth < 40 || plotHeight < 40) {
                return "LIVE • chart region too small"
            }

            val runs = mutableListOf<PixelRun>()
            var colored = 0
            var sampled = 0

            // Sample every second pixel to reduce CPU use.
            var x = left

            while (x < right) {
                var y = top
                var runStart = -1
                var runEnd = -1
                var redCount = 0
                var greenCount = 0

                fun finishRun() {
                    if (runStart >= 0 &&
                        runEnd - runStart >= 2
                    ) {
                        val runColor = if (
                            greenCount > redCount
                        ) Color.GREEN else Color.RED

                        runs.add(
                            PixelRun(
                                x = x,
                                top = runStart,
                                bottom = runEnd,
                                color = runColor
                            )
                        )
                    }

                    runStart = -1
                    runEnd = -1
                    redCount = 0
                    greenCount = 0
                }

                while (y < bottom) {
                    val pixel = bitmap.getPixel(x, y)
                    sampled++

                    if (isCandleColor(pixel)) {
                        colored++

                        if (runStart < 0) runStart = y
                        runEnd = y

                        val r = Color.red(pixel)
                        val g = Color.green(pixel)

                        if (r > g * 1.18) redCount++
                        if (g > r * 1.08) greenCount++
                    } else {
                        finishRun()
                    }

                    y += 2
                }

                finishRun()
                x += 2
            }

            val coverage = colored * 100 /
                sampled.coerceAtLeast(1)

            val filteredRuns = runs.filter {
                val h = it.bottom - it.top + 1
                h >= 3 && h <= plotHeight * 0.30
            }

            val candidates = groupRuns(
                filteredRuns,
                plotHeight
            )

            if (candidates.size < 6) {
                return "STEP 4 • INSUFFICIENT CANDIDATES\n" +
                    "Candidate groups: ${candidates.size}\n" +
                    "Color coverage: $coverage%\n" +
                    "Check chart-region calibration."
            }

            // Approximate candle OHLC from the visible candidate bounds.
            // This is not equivalent to detecting the true body and wick.
            val candles = candidates.map { c ->
                val topY = c.top.toDouble()
                val bottomY = c.bottom.toDouble()

                val isGreen = c.color == Color.GREEN

                Candle(
                    x = c.x,
                    high = topY,
                    low = bottomY,
                    open = if (isGreen) bottomY else topY,
                    close = if (isGreen) topY else bottomY,
                    color = c.color
                )
            }.sortedBy { it.x }

            val technical = calculateTechnicalAnalysis(candles)

            return "STEP 4 • CANDLE DETECTION\n" +
                "Candidate candles: ${candles.size}\n" +
                "Color coverage: $coverage%\n" +
                "OHLC: PIXEL ESTIMATES ONLY\n\n" +
                "STEP 5 • TECHNICAL ANALYSIS\n" +
                technical +
                "\n\nNot verified market OHLC or a trade prediction."

        } finally {
            bitmap.recycle()
        }
    }

    private fun groupRuns(
        runs: List<PixelRun>,
        plotHeight: Int
    ): List<CandleCandidate> {
        val sorted = runs.sortedWith(
            compareBy<PixelRun> { it.x }
                .thenBy { it.top }
        )

        val groups = mutableListOf<CandleCandidate>()

        for (run in sorted) {
            val previous = groups.lastOrNull()

            val gap = if (previous == null) {
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

            val merge = previous != null &&
                previous.color == run.color &&
                run.x - previous.right <= 6 &&
                gap <= max(3, plotHeight / 100)

            if (merge && previous != null) {
                groups[groups.lastIndex] = CandleCandidate(
                    x = (previous.left + max(previous.right, run.x)) / 2,
                    left = previous.left,
                    right = max(previous.right, run.x),
                    top = min(previous.top, run.top),
                    bottom = max(previous.bottom, run.bottom),
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

        return groups.filter {
            it.height >= 3 &&
                it.height <= plotHeight * 0.30 &&
                it.right - it.left <= 12
        }.sortedBy { it.x }
    }

    private fun isCandleColor(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)

        val highest = max(r, max(g, b))
        val lowest = min(r, min(g, b))

        if (highest < 90 || highest - lowest < 45) {
            return false
        }

        val redLike = r > g * 1.22 && r > b * 1.12
        val greenLike = g > r * 1.10 && g > b * 1.05

        return redLike || greenLike
    }

    // ------------------------------------------------------------
    // STEP 5: TECHNICAL-ANALYSIS ENGINE
    //
    // EMA, RSI and MACD calculations are implemented conventionally.
    // Their inputs here are pixel-derived estimates, not real prices.
    // ------------------------------------------------------------

    private fun calculateTechnicalAnalysis(
        candles: List<Candle>
    ): String {
        if (candles.size < 26) {
            return "Status: INSUFFICIENT DATA\n" +
                "Need at least 26 detected candle candidates; " +
                "found ${candles.size}."
        }

        val closes = candles.map { it.close }

        val ema9 = ema(closes, 9)
        val ema21 = ema(closes, 21)

        val rsi14 = rsi(closes, 14)

        val ema12 = emaSeries(closes, 12)
        val ema26 = emaSeries(closes, 26)

        val macdLine = closes.indices.map {
            ema12[it] - ema26[it]
        }

        val signalLine = emaSeries(macdLine, 9)

        val last = closes.lastIndex

        val latestEma9 = ema9.last()
        val latestEma21 = ema21.last()
        val latestRsi = rsi14.last()
        val latestMacd = macdLine.last()
        val latestSignal = signalLine.last()

        val recentCount = min(5, closes.size - 1)
        val momentum = closes.last() -
            closes[closes.lastIndex - recentCount]

        val emaUp = latestEma9 > latestEma21
        val emaDown = latestEma9 < latestEma21
        val macdUp = latestMacd > latestSignal
        val macdDown = latestMacd < latestSignal

        val momentumUp = momentum < 0.0
        val momentumDown = momentum > 0.0

        // Pixel coordinates are inverted: smaller Y is higher on screen.
        // These comparisons describe image-space direction only.
        val votesUp = listOf(
            emaUp,
            macdUp,
            momentumUp
        ).count { it }

        val votesDown = listOf(
            emaDown,
            macdDown,
            momentumDown
        ).count { it }

        val direction = when {
            votesUp >= 3 -> "UP"
            votesDown >= 3 -> "DOWN"
            else -> "UNCLEAR"
        }

        val rsiLabel = when {
            latestRsi >= 70 -> "HIGH"
            latestRsi <= 30 -> "LOW"
            else -> "MID-RANGE"
        }

        return "Directional estimate: $direction\n" +
            "EMA 9: ${format(latestEma9)}\n" +
            "EMA 21: ${format(latestEma21)}\n" +
            "RSI 14: ${format(latestRsi)} ($rsiLabel)\n" +
            "MACD: ${format(latestMacd)}\n" +
            "MACD signal: ${format(latestSignal)}\n" +
            "Recent image momentum: ${format(momentum)}\n" +
            "Agreement: UP $votesUp / DOWN $votesDown\n" +
            "Inputs are pixel-derived estimates."
    }

    private fun ema(
        values: List<Double>,
        period: Int
    ): List<Double> = emaSeries(values, period)

    private fun emaSeries(
        values: List<Double>,
        period: Int
    ): List<Double> {
        if (values.isEmpty()) return emptyList()

        val alpha = 2.0 / (period + 1.0)
        val result = ArrayList<Double>(values.size)

        var current = values.first()
        result.add(current)

        for (i in 1 until values.size) {
            current = values[i] * alpha +
                current * (1.0 - alpha)
            result.add(current)
        }

        return result
    }

    private fun rsi(
        values: List<Double>,
        period: Int
    ): List<Double> {
        if (values.isEmpty()) return emptyList()

        val result = MutableList(values.size) { 50.0 }

        var avgGain = 0.0
        var avgLoss = 0.0

        for (i in 1 until values.size) {
            val change = values[i] - values[i - 1]
            val gain = max(change, 0.0)
            val loss = max(-change, 0.0)

            if (i <= period) {
                avgGain += gain / period
                avgLoss += loss / period
            } else {
                avgGain = (
                    avgGain * (period - 1) + gain
                ) / period

                avgLoss = (
                    avgLoss * (period - 1) + loss
                ) / period
            }

            result[i] = when {
                avgLoss == 0.0 && avgGain == 0.0 -> 50.0
                avgLoss == 0.0 -> 100.0
                else -> {
                    val rs = avgGain / avgLoss
                    100.0 - 100.0 / (1.0 + rs)
                }
            }
        }

        return result
    }

    private fun format(value: Double): String {
        return if (value.isFinite()) {
            String.format(java.util.Locale.US, "%.3f", value)
        } else {
            "N/A"
        }
    }

    // ------------------------------------------------------------
    // BITMAP CONVERSION
    // ------------------------------------------------------------

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
            (rowStride - pixelStride * width) / pixelStride
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
            Bitmap.createBitmap(padded, 0, 0, width, height)
                .also { padded.recycle() }
        } else {
            padded
        }

        // Keep the original aspect ratio while reducing CPU use.
        val maxDimension = 720

        if (max(width, height) <= maxDimension) {
            return cropped
        }

        val scale = maxDimension.toFloat() /
            max(width, height)

        val scaled = Bitmap.createScaledBitmap(
            cropped,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            false
        )

        if (scaled !== cropped) cropped.recycle()

        return scaled
    }

    // ------------------------------------------------------------
    // FLOATING PANEL + REGION CONTROLS
    // ------------------------------------------------------------

    private fun calibrationRow(
        label: String,
        key: String
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val name = TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }

        fun control(
            textValue: String,
            amount: Float
        ) = Button(this).apply {
            text = textValue
            textSize = 14f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                adjustRegion(key, amount)
            }
        }

        row.addView(
            name,
            LinearLayout.LayoutParams(0, 40, 1f)
        )
        row.addView(control("−", -0.02f),
            LinearLayout.LayoutParams(44, 40))
        row.addView(control("+", 0.02f),
            LinearLayout.LayoutParams(44, 40))

        return row
    }

    private fun showOverlay() {
        if (panel != null || !Settings.canDrawOverlays(this)) {
            return
        }

        windowManager = getSystemService(
            WINDOW_SERVICE
        ) as WindowManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 8, 10, 8)
            setBackgroundColor(Color.rgb(20, 25, 33))
        }

        val header = TextView(this).apply {
            text = "TRADEFLOAT • STEP 4 + 5"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(3, 2, 3, 6)
        }

        statusText = TextView(this).apply {
            text = "LIVE • initializing…"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(3, 3, 3, 6)
        }

        val regionTitle = TextView(this).apply {
            text = "CHART REGION"
            textSize = 12f
            setTextColor(Color.WHITE)
        }

        regionText = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.LTGRAY)
        }

        val stopButton = Button(this).apply {
            text = "STOP LIVE SESSION"
            setOnClickListener { stopSelf() }
        }

        root.addView(header)
        root.addView(statusText)
        root.addView(regionTitle)
        root.addView(regionText)
        root.addView(calibrationRow("Left edge", "left"))
        root.addView(calibrationRow("Right edge", "right"))
        root.addView(calibrationRow("Top edge", "top"))
        root.addView(calibrationRow("Bottom edge", "bottom"))
        root.addView(stopButton)

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 12
            y = 80
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
            updateRegionLabel()
        } catch (_: Exception) {
            panel = null
            statusText = null
            regionText = null
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
            projectionCallback?.let {
                projection?.unregisterCallback(it)
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
        statusText = null
        regionText = null

        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null

        super.onDestroy()
    }
}
