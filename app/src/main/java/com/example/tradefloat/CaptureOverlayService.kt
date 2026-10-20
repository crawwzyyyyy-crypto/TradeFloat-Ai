package com.example.tradefloat

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.Gravity
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
        const val ACTION_START = "com.example.tradefloat.START_CAPTURE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val CHANNEL_ID = "tradefloat_capture"
        private const val NOTIFICATION_ID = 301
        private const val FRAME_INTERVAL_MS = 900L

        private const val ROI_LEFT = 0.05f
        private const val ROI_RIGHT = 0.70f
        private const val ROI_TOP = 0.20f
        private const val ROI_BOTTOM = 0.68f
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var windowManager: WindowManager? = null
    private var overlay: LinearLayout? = null
    private var resultText: TextView? = null
    private var detailText: TextView? = null

    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var horizonSeconds = 30

    @Volatile
    private var processingFrame = false

    private data class Sample(
        val timeMs: Long,
        val y: Float
    )

    private val samples = ArrayDeque<Sample>()
    private val detector = CandleDetector()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCapture()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startWorker()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (intent?.action == ACTION_START) {
            val resultCode = intent.getIntExtra(
                EXTRA_RESULT_CODE,
                Activity.RESULT_CANCELED
            )

            @Suppress("DEPRECATION")
            val resultData =
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(
                        EXTRA_RESULT_DATA,
                        Intent::class.java
                    )
                } else {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

            if (resultCode == Activity.RESULT_OK && resultData != null) {
                startForeground(NOTIFICATION_ID, buildNotification())
                startCapture(resultCode, resultData)
            } else {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startWorker() {
        workerThread = HandlerThread("TradeFloatAnalyzer").apply {
            start()
            worker = Handler(looper)
        }
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (projection != null) return

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val manager = getSystemService(
            MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        projection = manager.getMediaProjection(resultCode, data)
        val metrics = resources.displayMetrics

        frameWidth = min(metrics.widthPixels, 720)
        frameHeight = (
            frameWidth.toFloat() *
                metrics.heightPixels / metrics.widthPixels
            ).toInt().coerceAtLeast(1)

        imageReader = ImageReader.newInstance(
            frameWidth,
            frameHeight,
            PixelFormat.RGBA_8888,
            2
        )

        projection?.registerCallback(projectionCallback, Handler(mainLooper))

        virtualDisplay = projection?.createVirtualDisplay(
            "TradeFloatScreen",
            frameWidth,
            frameHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            worker
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            if (processingFrame) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            processingFrame = true

            worker?.post {
                try {
                    analyzeImage(image)
                } catch (_: Exception) {
                    showResult(
                        "UNCLEAR",
                        "Unable to analyze this frame"
                    )
                } finally {
                    image.close()
                    processingFrame = false
                }
            }
        }, worker)

        showOverlay()
    }

    private fun analyzeImage(image: Image) {
        val fullBitmap = imageToBitmap(image) ?: run {
            showResult("UNCLEAR", "Screen frame unavailable")
            return
        }

        try {
            val left = (fullBitmap.width * ROI_LEFT)
                .toInt().coerceIn(0, fullBitmap.width - 1)
            val right = (fullBitmap.width * ROI_RIGHT)
                .toInt().coerceIn(left + 1, fullBitmap.width)
            val top = (fullBitmap.height * ROI_TOP)
                .toInt().coerceIn(0, fullBitmap.height - 1)
            val bottom = (fullBitmap.height * ROI_BOTTOM)
                .toInt().coerceIn(top + 1, fullBitmap.height)

            val chart = Bitmap.createBitmap(
                fullBitmap,
                left,
                top,
                right - left,
                bottom - top
            )

            try {
                val detection = detector.detect(chart)

                if (!detection.usable || detection.candles.size < 4) {
                    showResult(
                        "UNCLEAR",
                        "Not enough clear candle shapes"
                    )
                    return
                }

                // Use the most recent visible candle positions as a
                // visual proxy. These are not actual market OHLC values.
                val recent = detection.candles
                    .sortedBy { it.centerX }
                    .takeLast(3)

                val yValues = recent.map { it.centerY.toFloat() }.sorted()
                val medianY = yValues[yValues.size / 2]
                val normalizedY = medianY / chart.height.toFloat()

                val now = SystemClock.elapsedRealtime()
                synchronized(samples) {
                    samples.addLast(Sample(now, normalizedY))

                    val cutoff = now - horizonSeconds * 1000L
                    while (
                        samples.isNotEmpty() &&
                        samples.peekFirst().timeMs < cutoff
                    ) {
                        samples.removeFirst()
                    }
                }

                val result = classifyTrend(now)

                showResult(
                    result,
                    "Horizon: ${horizonSeconds}s · " +
                        "${detection.candles.size} candle-like shapes · " +
                        "visual trend only"
                )
            } finally {
                chart.recycle()
            }
        } finally {
            fullBitmap.recycle()
        }
    }

    private fun classifyTrend(now: Long): String {
        val snapshot = synchronized(samples) { samples.toList() }

        if (snapshot.size < 8) return "UNCLEAR"

        val first = snapshot.first()
        val last = snapshot.last()

        if (now - last.timeMs > 3000L) return "UNCLEAR"
        if (last.timeMs - first.timeMs < 6000L) return "UNCLEAR"

        val change = last.y - first.y

        // Screen coordinates increase downwards:
        // a lower y-position can correspond to visually higher prices.
        return when {
            change < -0.012f -> "VISUAL UP ↑"
            change > 0.012f -> "VISUAL DOWN ↓"
            else -> "UNCLEAR"
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        if (pixelStride <= 0 || rowStride <= 0) return null

        val paddedWidth = rowStride / pixelStride
        val paddedBitmap = Bitmap.createBitmap(
            paddedWidth,
            image.height,
            Bitmap.Config.ARGB_8888
        )

        buffer.rewind()
        paddedBitmap.copyPixelsFromBuffer(buffer)

        val result = Bitmap.createBitmap(
            paddedBitmap,
            0,
            0,
            image.width,
            image.height
        )

        paddedBitmap.recycle()
        return result
    }

    private fun showOverlay() {
        if (overlay != null) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 12)
            setBackgroundColor(Color.rgb(24, 26, 32))
        }

        resultText = TextView(this).apply {
            text = "UNCLEAR"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        detailText = TextView(this).apply {
            text = "Waiting for chart frames…"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }

        root.addView(resultText)
        root.addView(detailText)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        buttons.addView(makeButton("30 SEC") {
            horizonSeconds = 30
            synchronized(samples) { samples.clear() }
            showResult("UNCLEAR", "30-second window selected; collecting frames")
        })

        buttons.addView(makeButton("1 MIN") {
            horizonSeconds = 60
            synchronized(samples) { samples.clear() }
            showResult("UNCLEAR", "60-second window selected; collecting frames")
        })

        buttons.addView(makeButton("STOP") {
            stopCapture()
            stopSelf()
        })

        root.addView(buttons)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 180
        }

        try {
            windowManager?.addView(root, params)
            overlay = root
        } catch (_: Exception) {
            stopCapture()
            stopSelf()
        }
    }

    private fun makeButton(
        label: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            textSize = 10f
            isAllCaps = false
            setOnClickListener { action() }
        }
    }

    private fun showResult(result: String, detail: String) {
        Handler(mainLooper).post {
            resultText?.text = result
            detailText?.text = detail
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TradeFloat screen analysis",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("TradeFloat")
                .setContentText("Screen analysis is active")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("TradeFloat")
                .setContentText("Screen analysis is active")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        }
    }

    private fun stopCapture() {
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            virtualDisplay?.release()
            imageReader?.close()
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        } catch (_: Exception) {
        }

        virtualDisplay = null
        imageReader = null
        projection = null

        overlay?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }

        overlay = null
        resultText = null
        detailText = null

        synchronized(samples) { samples.clear() }
    }

    override fun onDestroy() {
        stopCapture()
        workerThread?.quitSafely()
        workerThread = null
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
