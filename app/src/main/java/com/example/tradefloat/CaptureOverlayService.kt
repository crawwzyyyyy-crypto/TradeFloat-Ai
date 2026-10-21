package com.example.tradefloat

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
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
import android.graphics.Bitmap

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
    private var horizonSeconds = 30
    private var frameBusy = false

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
            showPanel("Panel ready", "Waiting for screen-capture permission")
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            showPanel("Permission required", "Allow Display over other apps")
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(
            EXTRA_RESULT_CODE,
            Activity.RESULT_CANCELED
        )

        @Suppress("DEPRECATION")
        val data: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent::class.java
                )
            } else {
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

        if (resultCode != Activity.RESULT_OK || data == null) {
            showPanel("Capture permission missing", "Return to TradeFloat and start capture")
            return START_NOT_STICKY
        }

        if (projection != null) return START_NOT_STICKY

        startForeground(NOTIFICATION_ID, buildNotification())
        showPanel("Starting…", "Connecting to screen capture")

        try {
            val manager = getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            projection = manager.getMediaProjection(resultCode, data)

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels.coerceAtMost(720)
            val height = (
                width.toFloat() * metrics.heightPixels /
                    metrics.widthPixels
            ).toInt().coerceAtLeast(1)

            reader = ImageReader.newInstance(
                width, height, PixelFormat.RGBA_8888, 2
            )

            projection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        display?.release()
                        display = null
                        reader?.close()
                        reader = null
                        projection = null
                        updatePanel(
                            "Capture stopped",
                            "Restart screen capture from the app"
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
            )

            reader?.setOnImageAvailableListener({ source ->
                if (frameBusy) {
                    source.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }

                val image = source.acquireLatestImage()
                    ?: return@setOnImageAvailableListener

                frameBusy = true
                worker?.post {
                    try {
                        analyze(image)
                    } catch (e: Exception) {
                        updatePanel("UNCLEAR", e.message ?: "Frame analysis failed")
                    } finally {
                        image.close()
                        frameBusy = false
                    }
                }
            }, worker)

            updatePanel("UNCLEAR", "Capture active · analyzing visible chart")
        } catch (e: Exception) {
            updatePanel("Startup failed", e.message ?: "Check permissions and manifest")
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

        row.addView(button("30 SEC") {
            horizonSeconds = 30
            updatePanel("UNCLEAR", "30-second window selected; collecting frames")
        })

        row.addView(button("1 MIN") {
            horizonSeconds = 60
            updatePanel("UNCLEAR", "60-second window selected; collecting frames")
        })

        row.addView(button("STOP") {
            stopCapture()
            stopSelf()
        })

        root.addView(row)

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
            x = 16
            y = 160
        }

        try {
            wm?.addView(root, params)
            panel = root
        } catch (e: Exception) {
            status = null
            details = null
            stopSelf()
        }
    }

    private fun button(label: String, action: () -> Unit) =
        Button(this).apply {
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
        val plane = image.planes.firstOrNull() ?: return
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return

        val paddedWidth = rowStride / pixelStride
        val padded = Bitmap.createBitmap(
            paddedWidth,
            image.height,
            Bitmap.Config.ARGB_8888
        )

        try {
            plane.buffer.rewind()
            padded.copyPixelsFromBuffer(plane.buffer)

            val full = Bitmap.createBitmap(
                padded, 0, 0, image.width, image.height
            )

            try {
                val left = (full.width * 0.05f).toInt()
                val right = (full.width * 0.70f).toInt()
                val top = (full.height * 0.20f).toInt()
                val bottom = (full.height * 0.68f).toInt()

                if (right <= left || bottom <= top) return

                val chart = Bitmap.createBitmap(
                    full, left, top, right - left, bottom - top
                )

                try {
                    val found = detector.detect(chart)

                    val message = if (
                        !found.usable || found.candles.size < 4
                    ) {
                        "Chart not detected clearly · ${horizonSeconds}s"
                    } else {
                        "${found.candles.size} candle-like shapes · visual estimate only"
                    }

                    // Detection alone is not enough to predict future prices.
                    updatePanel("UNCLEAR", message)
                } finally {
                    chart.recycle()
                }
            } finally {
                full.recycle()
            }
        } finally {
            padded.recycle()
        }
    }

    private fun stopCapture() {
        try {
            reader?.setOnImageAvailableListener(null, null)
            display?.release()
            reader?.close()
            projection?.stop()
        } catch (_: Exception) {
        }

        display = null
        reader = null
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
