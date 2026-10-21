package com.example.tradefloat

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

class CaptureOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.example.tradefloat.START_CAPTURE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val CHANNEL_ID = "tradefloat_capture"
        private const val NOTIFICATION_ID = 301
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var wm: WindowManager? = null

    private var panel: LinearLayout? = null
    private var status: TextView? = null
    private var details: TextView? = null

    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null

    @Volatile
    private var frameBusy = false

    @Volatile
    private var stopped = false

    private var horizonSeconds = 30
    private var lastUiUpdate = 0L

    private val detector = CandleDetector()

    override fun onCreate() {
        super.onCreate()

        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        createNotificationChannel()

        workerThread = HandlerThread("TradeFloatAnalyzer").apply {
            start()
            worker = Handler(looper)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (intent?.action != ACTION_START) {
            showPanel(
                "TradeFloat ready",
                "Start screen capture from the app"
            )
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            showPanel(
                "Overlay permission required",
                "Allow Display over other apps in Android Settings"
            )
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(
            EXTRA_RESULT_CODE,
            Activity.RESULT_CANCELED
        )

        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(
                EXTRA_RESULT_DATA,
                Intent::class.java
            )
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            showPanel(
                "Capture permission missing",
                "Return to the app and grant screen capture"
            )
            return START_NOT_STICKY
        }

        if (projection != null) return START_NOT_STICKY

        stopped = false
        showPanel("Starting…", "Preparing screen capture")

        try {
            startForeground(NOTIFICATION_ID, buildNotification())

            val manager = getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            projection = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection unavailable")

            val metrics = resources.displayMetrics

            // Lower capture resolution to reduce memory use.
            val width = min(metrics.widthPixels, 480)
            val height = max(
                1,
                (width.toFloat() * metrics.heightPixels /
                    metrics.widthPixels).toInt()
            )

            reader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )

            projection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopped = true
                        releaseDisplayAndReader()
                        updatePanel(
                            "Capture stopped",
                            "Start capture again from TradeFloat"
                        )
                    }
                },
                Handler(mainLooper)
            )

            display = projection?.createVirtualDisplay(
                "TradeFloatScreen",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface,
                null,
                worker
            ) ?: throw IllegalStateException("Virtual display creation failed")

            reader?.setOnImageAvailableListener({ source ->
                if (stopped || frameBusy) {
                    try {
                        source.acquireLatestImage()?.close()
                    } catch (_: Exception) {
                    }
                    return@setOnImageAvailableListener
                }

                val image = try {
                    source.acquireLatestImage()
                } catch (_: Exception) {
                    null
                } ?: return@setOnImageAvailableListener

                frameBusy = true

                worker?.post {
                    try {
                        analyze(image)
                    } catch (e: Exception) {
                        updatePanel(
                            "Analysis error",
                            e.message ?: e.javaClass.simpleName
                        )
                    } finally {
                        try {
                            image.close()
                        } catch (_: Exception) {
                        }
                        frameBusy = false
                    }
                } ?: run {
                    image.close()
                    frameBusy = false
                }
            }, worker)

            updatePanel(
                "UNCLEAR",
                "Capture active · checking visible chart"
            )
        } catch (e: Exception) {
            stopped = true
            releaseDisplayAndReader()

            try {
                projection?.stop()
            } catch (_: Exception) {
            }

            projection = null

            updatePanel(
                "Startup failed",
                e.message ?: e.javaClass.simpleName
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
        }

        return START_NOT_STICKY
    }

    private fun showPanel(title: String, message: String) {
        if (panel != null) {
            updatePanel(title, message)
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 10, 14, 10)
            setBackgroundColor(Color.rgb(25, 27, 34))
        }

        status = TextView(this).apply {
            text = title
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }

        details = TextView(this).apply {
            text = message
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
        }

        root.addView(status)
        root.addView(details)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        row.addView(makeButton("30 SEC") {
            horizonSeconds = 30
            updatePanel("UNCLEAR", "30-second window selected")
        })

        row.addView(makeButton("1 MIN") {
            horizonSeconds = 60
            updatePanel("UNCLEAR", "60-second window selected")
        })

        row.addView(makeButton("STOP") {
            stopSelf()
        })

        root.addView(row)

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
            x = 16
            y = 160
        }

        try {
            wm?.addView(root, params)
            panel = root
        } catch (e: Exception) {
            status = null
            details = null
            updatePanel(
                "Panel failed",
                e.message ?: "Check overlay permission"
            )
        }
    }

    private fun makeButton(
        label: String,
        action: () -> Unit
    ): Button = Button(this).apply {
        text = label
        textSize = 10f
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun updatePanel(title: String, message: String) {
        Handler(mainLooper).post {
            status?.text = title
            details?.text = message
        }
    }

    private fun analyze(image: Image) {
        if (stopped) return

        val bitmap = imageToBitmap(image)
            ?: throw IllegalStateException("Could not read screen pixels")

        try {
            val left = (bitmap.width * 0.05f).toInt()
                .coerceIn(0, bitmap.width - 1)
            val right = (bitmap.width * 0.70f).toInt()
                .coerceIn(left + 1, bitmap.width)
            val top = (bitmap.height * 0.20f).toInt()
                .coerceIn(0, bitmap.height - 1)
            val bottom = (bitmap.height * 0.68f).toInt()
                .coerceIn(top + 1, bitmap.height)

            val chart = Bitmap.createBitmap(
                bitmap,
                left,
                top,
                right - left,
                bottom - top
            )

            try {
                val result = detector.detect(chart)

                val message = when {
                    !result.usable ->
                        "${result.message} · ${horizonSeconds}s"

                    else ->
                        "${result.candles.size} candidate shapes · OHLC unverified"
                }

                // A detected shape count is not a forecast.
                updatePanel("UNCLEAR", message)
            } finally {
                chart.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val source: ByteBuffer = plane.buffer.duplicate()

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        if (pixelStride < 4 || rowStride <= 0) return null

        val sample = 2
        val outWidth = (image.width + sample - 1) / sample
        val outHeight = (image.height + sample - 1) / sample
        val outputPixels = IntArray(outWidth * outHeight)

        val basePosition = source.position()
        val limit = source.limit()

        var outIndex = 0

        for (y in 0 until image.height step sample) {
            val rowOffset = basePosition + y * rowStride

            for (x in 0 until image.width step sample) {
                val index = rowOffset + x * pixelStride

                if (index < 0 || index + 3 >= limit) {
                    outputPixels[outIndex++] = Color.BLACK
                    continue
                }

                val r = source.get(index).toInt() and 0xFF
                val g = source.get(index + 1).toInt() and 0xFF
                val b = source.get(index + 2).toInt() and 0xFF
                val a = source.get(index + 3).toInt() and 0xFF

                outputPixels[outIndex++] =
                    (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return Bitmap.createBitmap(
            outputPixels,
            0,
            outWidth,
            outWidth,
            outHeight,
            Bitmap.Config.ARGB_8888
        )
    }

    private fun releaseDisplayAndReader() {
        try {
            reader?.setOnImageAvailableListener(null, null)
        } catch (_: Exception) {
        }

        try {
            display?.release()
        } catch (_: Exception) {
        }

        try {
            reader?.close()
        } catch (_: Exception) {
        }

        display = null
        reader = null
    }

    private fun stopCapture() {
        stopped = true
        releaseDisplayAndReader()

        try {
            projection?.stop()
        } catch (_: Exception) {
        }

        projection = null

        panel?.let {
            try {
                wm?.removeView(it)
            } catch (_: Exception) {
            }
        }

        panel = null
        status = null
        details = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TradeFloat capture",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("TradeFloat")
                .setContentText("Screen analysis active")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("TradeFloat")
                .setContentText("Screen analysis active")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        }
    }

    override fun onDestroy() {
        stopCapture()

        workerThread?.quitSafely()
        workerThread = null
        worker = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
