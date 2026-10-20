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
import android.widget.ScrollView
import android.widget.TextView
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class CaptureOverlayService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL = "tradefloat_capture"
        private const val NOTIFICATION_ID = 17
        private const val PREFS = "tradefloat_local"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var windowManager: WindowManager? = null
    private var panel: View? = null
    private var statusText: TextView? = null
    private var regionText: TextView? = null
    private var expiryText: TextView? = null
    private var validationText: TextView? = null

    @Volatile private var processing = false
    @Volatile private var stopped = false
    @Volatile private var roiLeft = 0.05f
    @Volatile private var roiRight = 0.72f
    @Volatile private var roiTop = 0.20f
    @Volatile private var roiBottom = 0.72f
    private var expirySeconds = 60
    private var lastFrameAt = 0L
    private var latestEstimate = "UNCLEAR"
    private var latestConfidence = "LOW VISUAL CONFIDENCE"
    private var latestAnalysisAt = 0L

    private data class Run(val x: Int, val top: Int, val bottom: Int, val color: Int)
    private data class Candidate(
        val x: Int, val left: Int, val right: Int,
        val top: Int, val bottom: Int, val color: Int
    ) {
        val centerY: Double get() = (top + bottom) / 2.0
        val height: Int get() = bottom - top + 1
    }

    private val trendHistory = ArrayDeque<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY
        createNotificationChannel()
        try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else startForeground(NOTIFICATION_ID, n)
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            loadSettings()
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(resultCode, data)
            if (Settings.canDrawOverlays(this)) showOverlay()
            startWorker()
            setupCapture()
        } catch (e: Exception) {
            if (panel == null && Settings.canDrawOverlays(this)) showOverlay()
            setStatus("STARTUP ERROR: ${e.javaClass.simpleName}\n${e.message ?: "Restart the session"}")
        }
        return START_NOT_STICKY
    }

    private fun startWorker() {
        if (workerThread != null) return
        workerThread = HandlerThread("TradeFloatWorker").also {
            it.start()
            workerHandler = Handler(it.looper)
        }
    }

    private fun buildNotification(): Notification =
        if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
                .setContentTitle("TradeFloat screen analysis")
                .setContentText("Visible screen frames processed in memory")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("TradeFloat screen analysis")
                .setContentText("Visible screen frames processed in memory")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true).build()
        }

    private fun setupCapture() {
        val m = resources.displayMetrics
        val w = m.widthPixels.coerceAtLeast(1)
        val h = m.heightPixels.coerceAtLeast(1)
        val density = m.densityDpi.coerceAtLeast(1)

        imageReader?.close()
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = try { reader.acquireLatestImage() } catch (_: Exception) { null }
                ?: return@setOnImageAvailableListener
            val now = SystemClock.elapsedRealtime()
            if (!processing && !stopped && now - lastFrameAt >= 900L) {
                processing = true
                lastFrameAt = now
                val accepted = workerHandler?.post {
                    try {
                        setStatus(analyzeFrame(image))
                    } catch (e: Exception) {
                        setStatus("ANALYSIS ERROR: ${e.javaClass.simpleName}\nDirection: UNCLEAR")
                    } finally {
                        try { image.close() } catch (_: Exception) {}
                        processing = false
                    }
                } ?: false
                if (!accepted) {
                    try { image.close() } catch (_: Exception) {}
                    processing = false
                }
            } else {
                try { image.close() } catch (_: Exception) {}
            }
        }, null)

        val active = projection ?: throw IllegalStateException("Screen-capture permission missing")
        val cb = object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }
        projectionCallback = cb
        active.registerCallback(cb, workerHandler)
        virtualDisplay = active.createVirtualDisplay(
            "TradeFloatVisibleChart", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, workerHandler
        ) ?: throw IllegalStateException("Could not create screen display")
        setStatus("LIVE • collecting chart frames\nCalibrate ROI to exclude menus and labels")
    }

    private fun loadSettings() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        roiLeft = p.getFloat("left", 0.05f)
        roiRight = p.getFloat("right", 0.72f)
        roiTop = p.getFloat("top", 0.20f)
        roiBottom = p.getFloat("bottom", 0.72f)
        expirySeconds = p.getInt("expiry", 60)
        validateRegion()
    }

    private fun saveSettings() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putFloat("left", roiLeft).putFloat("right", roiRight)
            .putFloat("top", roiTop).putFloat("bottom", roiBottom)
            .putInt("expiry", expirySeconds).apply()
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

    private fun adjustRegion(edge: String, amount: Float) {
        when (edge) {
            "left" -> roiLeft += amount
            "right" -> roiRight += amount
            "top" -> roiTop += amount
            "bottom" -> roiBottom += amount
        }
        validateRegion()
        saveSettings()
        updateRegionText()
        synchronized(trendHistory) { trendHistory.clear() }
        latestEstimate = "UNCLEAR"
        latestConfidence = "RECALIBRATING"
        setStatus("REGION UPDATED • collecting fresh samples\nDirection: UNCLEAR")
    }

    private fun adjustExpiry(amount: Int) {
        expirySeconds = (expirySeconds + amount).coerceIn(5, 900)
        saveSettings()
        expiryText?.text = "Horizon: ${durationText(expirySeconds)} • not a forecast"
    }

    private fun durationText(seconds: Int): String =
        if (seconds < 60) "${seconds}s" else {
            val mins = seconds / 60
            val rem = seconds % 60
            if (rem == 0) "${mins}m" else "${mins}m ${rem}s"
        }

    private fun updateRegionText() {
        regionText?.post {
            regionText?.text = String.format(
                Locale.US, "L %.0f%%  R %.0f%%  T %.0f%%  B %.0f%%",
                roiLeft * 100, roiRight * 100, roiTop * 100, roiBottom * 100
            )
        }
    }

    private fun analyzeFrame(image: Image): String {
        val bmp = imageToSmallBitmap(image) ?: return "STEP 4 • FRAME UNAVAILABLE\nDirection: UNCLEAR"
        try {
            val w = bmp.width
            val h = bmp.height
            val left = (w * roiLeft).toInt().coerceIn(0, w - 1)
            val right = (w * roiRight).toInt().coerceIn(left + 1, w)
            val top = (h * roiTop).toInt().coerceIn(0, h - 1)
            val bottom = (h * roiBottom).toInt().coerceIn(top + 1, h)
            val plotW = right - left
            val plotH = bottom - top
            if (plotW < 40 || plotH < 40) return "STEP 3 • CHART REGION TOO SMALL\nDirection: UNCLEAR"

            val runs = mutableListOf<Run>()
            var colored = 0
            var sampled = 0
            var x = left
            while (x < right) {
                var y = top
                var start = -1
                var end = -1
                var redVotes = 0
                var greenVotes = 0

                fun finishRun() {
                    if (start >= 0 && end - start + 1 >= 3) {
                        val c = when {
                            greenVotes > redVotes -> Color.GREEN
                            redVotes > greenVotes -> Color.RED
                            else -> Color.TRANSPARENT
                        }
                        if (c != Color.TRANSPARENT) runs.add(Run(x, start, end, c))
                    }
                    start = -1; end = -1; redVotes = 0; greenVotes = 0
                }

                while (y < bottom) {
                    val px = bmp.getPixel(x, y)
                    sampled++
                    if (isCandleColor(px)) {
                        colored++
                        if (start < 0) start = y
                        end = y
                        val r = Color.red(px)
                        val g = Color.green(px)
                        val b = Color.blue(px)
                        if (r > g * 1.22 && r > b * 1.12) redVotes++
                        if (g > r * 1.10 && g > b * 1.05) greenVotes++
                    } else finishRun()
                    y++
                }
                finishRun()
                x += 2
            }

            val coverage = colored * 100 / sampled.coerceAtLeast(1)
            val filtered = runs.filter {
                val rh = it.bottom - it.top + 1
                rh >= 3 && rh <= plotH * 0.22
            }
            val candidates = groupRuns(filtered, plotH)
            val distinct = mutableListOf<Candidate>()
            for (c in candidates) {
                val prev = distinct.lastOrNull()
                if (prev == null || c.x - prev.x >= 4) distinct.add(c)
                else if (c.height > prev.height) distinct[distinct.lastIndex] = c
            }

            if (distinct.size < 8) {
                synchronized(trendHistory) { trendHistory.clear() }
                latestEstimate = "UNCLEAR"
                latestConfidence = "INSUFFICIENT DATA"
                return "STEP 4 • CANDLE-LIKE CANDIDATES\nGroups: ${distinct.size} • coverage: $coverage%\n" +
                    "STEP 5 • IMAGE PROXIES UNAVAILABLE\nSTEP 6 • Direction: UNCLEAR\nNeed more visible chart samples"
            }

            val span = (distinct.last().x - distinct.first().x).coerceAtLeast(1)
            val firstX = distinct.first().x
            val older = distinct.filter { it.x <= firstX + span / 3 }
            val newer = distinct.filter { it.x >= distinct.last().x - span / 3 }
            if (older.size < 3 || newer.size < 3) {
                latestEstimate = "UNCLEAR"
                return "STEP 4 • COLLECTING CANDIDATES\nGroups: ${distinct.size}\nDirection: UNCLEAR"
            }

            val broadDelta = newer.map { it.centerY }.average() - older.map { it.centerY }.average()
            val recent = distinct.takeLast(min(8, distinct.size))
            val recentDelta = recent.last().centerY - recent.first().centerY
            val broad = classifyDelta(broadDelta, plotH)
            val recentSlope = classifyDelta(recentDelta, (plotH * 0.60).toInt())

            synchronized(trendHistory) {
                trendHistory.addLast(recentSlope)
                while (trendHistory.size > 5) trendHistory.removeFirst()
            }
            val consistency = synchronized(trendHistory) { trendHistory.count { it == recentSlope } }

            // These are pixel-coordinate proxies, never market-price indicators.
            val proxy = distinct.map { (h - it.centerY).toDouble() }
            val ema9 = ema(proxy, 9)
            val ema21 = ema(proxy, 21)
            val rsi14 = rsi(proxy, 14)
            val macd = ema(proxy, 12) - ema(proxy, 26)
            val macdSignal = ema(proxy.mapIndexed { i, v -> v - ema(proxy.take(i + 1), 26) }, 9)
            val momentum = if (proxy.size >= 4) proxy.last() - proxy[proxy.size - 4] else 0.0

            val quality = if (distinct.size >= 15 && coverage >= 1 && consistency >= 4 && recentSlope != "UNCLEAR")
                "REPEATED VISUAL PATTERN" else "LOW VISUAL CONFIDENCE"
            val agreement = when {
                broad == "UNCLEAR" || recentSlope == "UNCLEAR" -> "UNCLEAR"
                broad != recentSlope -> "CONFLICT"
                else -> broad
            }
            val direction = if (quality == "REPEATED VISUAL PATTERN" && agreement != "CONFLICT" && agreement != "UNCLEAR")
                agreement else "UNCLEAR"

            latestEstimate = direction
            latestConfidence = quality
            latestAnalysisAt = System.currentTimeMillis()

            return "STEP 4 • CANDLE-LIKE DETECTION\nGroups: ${distinct.size} • color coverage: $coverage%\n" +
                "STEP 5 • IMAGE-DERIVED PROXIES ONLY\n" +
                "EMA proxy 9: ${fmt(ema9)} • EMA proxy 21: ${fmt(ema21)}\n" +
                "RSI proxy 14: ${fmt(rsi14)}\nMACD proxy: ${fmt(macd)} • signal proxy: ${fmt(macdSignal)}\n" +
                "Pixel momentum: ${fmt(momentum)}\n" +
                "STEP 6 • Combined visual assessment: $direction\n" +
                "Broad slope: $broad • recent slope: $recentSlope\nConsistency: $consistency/5 • $quality\n" +
                "STEP 7 • Horizon: ${durationText(expirySeconds)} (not a forecast)\n" +
                "STEP 8 • Log estimate, then manually mark outcome\n" +
                "No verified OHLC • no price prediction • manual trading only"
        } finally {
            bmp.recycle()
        }
    }

    private fun groupRuns(runs: List<Run>, plotH: Int): List<Candidate> {
        val out = mutableListOf<Candidate>()
        for (r in runs.sortedWith(compareBy<Run> { it.x }.thenBy { it.top })) {
            val p = out.lastOrNull()
            val gap = if (p == null) Int.MAX_VALUE else when {
                r.top > p.bottom -> r.top - p.bottom
                p.top > r.bottom -> p.top - r.bottom
                else -> 0
            }
            val merge = p != null && p.color == r.color &&
                r.x - p.right <= 4 && gap <= max(2, plotH / 120)
            if (merge && p != null) {
                val l = p.left
                val rr = max(p.right, r.x)
                val t = min(p.top, r.top)
                val b = max(p.bottom, r.bottom)
                out[out.lastIndex] = Candidate((l + rr) / 2, l, rr, t, b, p.color)
            } else out.add(Candidate(r.x, r.x, r.x, r.top, r.bottom, r.color))
        }
        return out.filter {
            it.height >= 3 && it.height <= plotH * 0.28 && it.right - it.left <= 8
        }.sortedBy { it.x }
    }

    private fun classifyDelta(delta: Double, height: Int): String {
        val threshold = height * 0.025
        return when {
            delta < -threshold -> "UP"
            delta > threshold -> "DOWN"
            else -> "UNCLEAR"
        }
    }

    private fun ema(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return Double.NaN
        val alpha = 2.0 / (period + 1.0)
        var v = values.first()
        for (i in 1 until values.size) v = alpha * values[i] + (1 - alpha) * v
        return v
    }

    private fun rsi(values: List<Double>, period: Int): Double {
        if (values.size <= period) return Double.NaN
        var gains = 0.0
        var losses = 0.0
        for (i in (values.size - period) until values.size) {
            val change = values[i] - values[i - 1]
            if (change > 0) gains += change else losses -= change
        }
        if (losses == 0.0) return if (gains == 0.0) 50.0 else 100.0
        val rs = gains / losses
        return 100.0 - 100.0 / (1.0 + rs)
    }

    private fun fmt(v: Double): String =
        if (v.isNaN() || v.isInfinite()) "N/A" else String.format(Locale.US, "%.2f", v)

    private fun isCandleColor(c: Int): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        val hi = max(r, max(g, b)); val lo = min(r, min(g, b))
        if (hi < 100 || hi - lo < 50) return false
        return (r > g * 1.22 && r > b * 1.12) ||
            (g > r * 1.10 && g > b * 1.05)
    }

    private fun imageToSmallBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pxStride = plane.pixelStride
        val rowStride = plane.rowStride
        val w = image.width
        val h = image.height
        if (pxStride < 4 || w < 1 || h < 1) return null
        val padding = ((rowStride - pxStride * w) / pxStride).coerceAtLeast(0)
        val paddedW = w + padding
        val padded = Bitmap.createBitmap(paddedW, h, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val cropped = if (paddedW != w) {
            Bitmap.createBitmap(padded, 0, 0, w, h).also { padded.recycle() }
        } else padded
        val maxDim = 480
        if (max(w, h) <= maxDim) return cropped
        val scale = maxDim.toFloat() / max(w, h)
        val scaled = Bitmap.createScaledBitmap(
            cropped, (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1), false
        )
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }

    private fun showOverlay() {
        if (panel != null || !Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 8, 10, 8)
            setBackgroundColor(Color.rgb(20, 25, 33))
        }
        val header = TextView(this).apply {
            text = "TRADEFLOAT • LIVE"
            textSize = 17f; setTextColor(Color.WHITE); setPadding(4, 2, 4, 7)
        }
        statusText = TextView(this).apply {
            text = "Starting…"; textSize = 12f; setTextColor(Color.LTGRAY)
            setPadding(4, 4, 4, 8)
        }
        val regionTitle = TextView(this).apply {
            text = "CHART REGION"; textSize = 12f; setTextColor(Color.WHITE)
        }
        regionText = TextView(this).apply { textSize = 11f; setTextColor(Color.LTGRAY) }
        val roiRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(calibrationRow("Left edge", "left"))
            addView(calibrationRow("Right edge", "right"))
            addView(calibrationRow("Top edge", "top"))
            addView(calibrationRow("Bottom edge", "bottom"))
        }
        val horizon = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        horizon.addView(Button(this).apply { text = "−5s"; setOnClickListener { adjustExpiry(-5) } },
            LinearLayout.LayoutParams(60, 42))
        expiryText = TextView(this).apply {
            textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            text = "Horizon: ${durationText(expirySeconds)} • not a forecast"
        }
        horizon.addView(expiryText, LinearLayout.LayoutParams(0, 42, 1f))
        horizon.addView(Button(this).apply { text = "+5s"; setOnClickListener { adjustExpiry(5) } },
            LinearLayout.LayoutParams(60, 42))

        val log = Button(this).apply {
            text = "LOG CURRENT ESTIMATE"; setOnClickListener { logEstimate() }
        }
        val outcomeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        outcomeRow.addView(outcomeButton("UP", "UP"), LinearLayout.LayoutParams(0, 44, 1f))
        outcomeRow.addView(outcomeButton("DOWN", "DOWN"), LinearLayout.LayoutParams(0, 44, 1f))
        outcomeRow.addView(outcomeButton("FLAT/UNKNOWN", "FLAT"), LinearLayout.LayoutParams(0, 44, 1f))
        validationText = TextView(this).apply {
            textSize = 10f; setTextColor(Color.LTGRAY); setPadding(4, 4, 4, 5)
        }
        val stop = Button(this).apply { text = "STOP LIVE SESSION"; setOnClickListener { stopSelf() } }

        root.addView(header); root.addView(statusText); root.addView(regionTitle)
        root.addView(regionText); root.addView(roiRows); root.addView(horizon)
        root.addView(log); root.addView(outcomeRow); root.addView(validationText); root.addView(stop)
        val scroll = ScrollView(this).apply { addView(root) }

        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 12; y = 70
            width = (resources.displayMetrics.widthPixels * 0.84f).toInt()
            height = (resources.displayMetrics.heightPixels * 0.72f).toInt()
        }
        header.setOnTouchListener(object : View.OnTouchListener {
            private var sx = 0; private var sy = 0; private var tx = 0f; private var ty = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY; return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = sx + (e.rawX - tx).toInt()
                        params.y = sy + (e.rawY - ty).toInt()
                        try { windowManager?.updateViewLayout(scroll, params) } catch (_: Exception) {}
                        return true
                    }
                }
                return false
            }
        })
        try {
            windowManager?.addView(scroll, params)
            panel = scroll
            updateRegionText()
            updateValidationText()
        } catch (_: Exception) {
            statusText = null; regionText = null; expiryText = null
            validationText = null; panel = null
        }
    }

    private fun calibrationRow(label: String, key: String): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply {
            text = label; textSize = 12f; setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, 40, 1f))
        row.addView(Button(this).apply { text = "−"; setOnClickListener { adjustRegion(key, -0.02f) } },
            LinearLayout.LayoutParams(48, 40))
        row.addView(Button(this).apply { text = "+"; setOnClickListener { adjustRegion(key, 0.02f) } },
            LinearLayout.LayoutParams(48, 40))
        return row
    }

    private fun outcomeButton(label: String, value: String) = Button(this).apply {
        text = label; textSize = 9f; setOnClickListener { markOutcome(value) }
    }

    private fun logEstimate() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val record = listOf(
            System.currentTimeMillis().toString(), latestEstimate, latestConfidence,
            expirySeconds.toString(), latestAnalysisAt.toString(), ""
        ).joinToString("|")
        val old = p.getString("records", "") ?: ""
        p.edit().putString("records", if (old.isBlank()) record else "$old\n$record").apply()
        setStatus("ESTIMATE LOGGED • $latestEstimate\nAfter the chosen horizon, manually mark the observed outcome.")
        updateValidationText()
    }

    private fun markOutcome(outcome: String) {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val records = (p.getString("records", "") ?: "").lines().filter { it.isNotBlank() }.toMutableList()
        if (records.isEmpty()) {
            setStatus("No logged estimate. Tap LOG CURRENT ESTIMATE first.")
            return
        }
        val idx = records.indexOfLast { it.split("|").getOrNull(5).orEmpty().isBlank() }
        if (idx < 0) {
            setStatus("No unlabelled estimate available.")
            return
        }
        val fields = records[idx].split("|").toMutableList()
        if (fields.size < 6) {
            setStatus("Validation record invalid.")
            return
        }
        fields[5] = outcome
        records[idx] = fields.joinToString("|")
        p.edit().putString("records", records.joinToString("\n")).apply()
        setStatus("OUTCOME SAVED • $outcome\nOnly manually labeled records are counted.")
        updateValidationText()
    }

    private fun updateValidationText() {
        val lines = (getSharedPreferences(PREFS, MODE_PRIVATE).getString("records", "") ?: "")
            .lines().filter { it.isNotBlank() }
        var labeled = 0
        var matches = 0
        for (line in lines) {
            val f = line.split("|")
            if (f.size >= 6 && f[5].isNotBlank()) {
                labeled++
                if (f[1] != "UNCLEAR" && f[1] == f[5]) matches++
            }
        }
        validationText?.post {
            validationText?.text = "VALIDATION • records ${lines.size} • labeled $labeled • directional matches $matches/$labeled\n" +
                "Descriptive only; not a guarantee of future accuracy."
        }
    }

    private fun setStatus(s: String) {
        statusText?.post { statusText?.text = s }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "TradeFloat screen session", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        stopped = true
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { projectionCallback?.let { projection?.unregisterCallback(it) } } catch (_: Exception) {}
        projectionCallback = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try { panel?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        panel = null; statusText = null; regionText = null; expiryText = null; validationText = null
        workerThread?.quitSafely()
        workerThread = null; workerHandler = null
        super.onDestroy()
    }
}
