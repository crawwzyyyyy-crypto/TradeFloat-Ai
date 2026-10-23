
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
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.min

class CaptureOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.example.tradefloat.START_CAPTURE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_ENDPOINT = "endpoint"

        private const val CHANNEL = "tradefloat_ai_capture"
        private const val NOTIFICATION_ID = 310
        private const val MAX_FRAMES = 8
        private const val FRAMES_TO_SEND = 3
        private const val CAPTURE_INTERVAL_MS = 700L
        private const val FRAME_MAX_AGE_MS = 5000L
        private const val FRAME_WAIT_LIMIT_MS = 2500L
    }

    private data class FrameSample(
        val timestamp: Long,
        val base64: String
    )

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private var wm: WindowManager? = null
    private var panel: LinearLayout? = null
    private var title: TextView? = null
    private var detail: TextView? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private val main = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()

    private val frameLock = Any()
    private val frames = mutableListOf<FrameSample>()

    @Volatile private var endpoint = ""
    @Volatile private var running = false
    @Volatile private var stopped = false
    @Volatile private var lastCapture = 0L

    override fun onCreate() {
        super.onCreate()

        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        "TradeFloat AI",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
        }

        captureThread = HandlerThread("TradeFloatCapture").also {
            it.start()
        }
        captureHandler = Handler(captureThread!!.looper)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action != ACTION_START ||
            !Settings.canDrawOverlays(this)
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        endpoint = intent.getStringExtra(EXTRA_ENDPOINT).orEmpty()

        val resultCode = intent.getIntExtra(
            EXTRA_RESULT_CODE,
            Activity.RESULT_CANCELED
        )

        @Suppress("DEPRECATION")
        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent::class.java
                )
            } else {
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

        if (resultCode != Activity.RESULT_OK ||
            resultData == null ||
            !endpoint.startsWith("https://")
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (projection != null) return START_NOT_STICKY

        try {
            val notificationBuilder =
                if (Build.VERSION.SDK_INT >= 26) {
                    Notification.Builder(this, CHANNEL)
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(this)
                }

            val notification = notificationBuilder
                .setContentTitle("TradeFloat AI")
                .setContentText("Live chart monitoring active")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()

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

            createPanel()
            update("STARTING", "Preparing live chart monitoring…")

            val manager =
                getSystemService(MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

            projection = manager.getMediaProjection(
                resultCode,
                resultData
            )

            val metrics = resources.displayMetrics
            val width = min(metrics.widthPixels, 640).coerceAtLeast(2)
            val height = (
                width.toFloat() * metrics.heightPixels /
                    metrics.widthPixels
                ).toInt().coerceAtLeast(2)

            reader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                3
            )

            projection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopped = true
                        running = false
                        releaseCapture()
                        update(
                            "CAPTURE STOPPED",
                            "Restart from TradeFloat"
                        )
                    }
                },
                main
            )

            display = projection?.createVirtualDisplay(
                "TradeFloatAI",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface,
                null,
                captureHandler
            )

            reader?.setOnImageAvailableListener({ source ->
                val now = System.currentTimeMillis()

                if (stopped || now - lastCapture <
                    CAPTURE_INTERVAL_MS
                ) {
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

                lastCapture = now

                try {
                    val encoded = imageToJpegBase64(image)

                    if (encoded != null && !stopped) {
                        synchronized(frameLock) {
                            frames.add(
                                FrameSample(
                                    System.currentTimeMillis(),
                                    encoded
                                )
                            )

                            val cutoff =
                                System.currentTimeMillis() -
                                    FRAME_MAX_AGE_MS

                            frames.removeAll {
                                it.timestamp < cutoff
                            }

                            while (frames.size > MAX_FRAMES) {
                                frames.removeAt(0)
                            }
                        }
                    }
                } catch (_: Exception) {
                    update(
                        "CAPTURE ERROR",
                        "Could not read a screen frame"
                    )
                } finally {
                    try {
                        image.close()
                    } catch (_: Exception) {
                    }
                }
            }, captureHandler)

            update(
                "LIVE MONITORING",
                "Chart frames updating · Choose 30 sec or 1 min"
            )

        } catch (e: Exception) {
            releaseCapture()
            update(
                "START FAILED",
                "${e.javaClass.simpleName} · Check capture permission"
            )
        }

        return START_NOT_STICKY
    }

    private fun createPanel() {
        if (panel != null) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 8)
            setBackgroundColor(Color.rgb(22, 25, 32))
        }

        title = TextView(this).apply {
            text = "TRADEFLOAT AI"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }

        detail = TextView(this).apply {
            text = "Starting live monitoring…"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(0, 5, 0, 8)
        }

        root.addView(title)
        root.addView(detail)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        row.addView(makeButton("30 sec") { begin(30) })
        row.addView(makeButton("1 min") { begin(60) })
        row.addView(makeButton("STOP") { stopSelf() })

        root.addView(row)

        val type =
            if (Build.VERSION.SDK_INT >= 26) {
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
            y = 120
        }

        wm?.addView(root, params)
        panel = root
    }

    private fun makeButton(
        label: String,
        action: () -> Unit
    ) = Button(this).apply {
        text = label
        textSize = 10f
        isAllCaps = false
        setPadding(3, 0, 3, 0)
        setOnClickListener { action() }
    }

    private fun begin(duration: Int) {
        if (stopped || projection == null) {
            update("CAPTURE STOPPED", "Restart from TradeFloat")
            return
        }

        if (running) {
            update(
                "BUSY",
                "Wait for the current analysis to finish"
            )
            return
        }

        running = true

        update(
            "ANALYSING",
            "Checking the latest chart frames…"
        )

        val deadline =
            System.currentTimeMillis() + FRAME_WAIT_LIMIT_MS

        dispatchWhenReady(duration, deadline)
    }

    private fun dispatchWhenReady(
        duration: Int,
        deadline: Long
    ) {
        if (!running || stopped) return

        val now = System.currentTimeMillis()

        val recent = synchronized(frameLock) {
            frames.filter {
                now - it.timestamp <= FRAME_MAX_AGE_MS
            }.takeLast(FRAMES_TO_SEND)
        }

        if (recent.size >= FRAMES_TO_SEND) {
            update(
                "ANALYSING",
                "Sending ${recent.size} recent frames to AI…"
            )

            try {
                network.execute {
                    askVision(
                        recent.map { it.base64 },
                        duration
                    )
                }
            } catch (_: Exception) {
                running = false
                update(
                    "UNCLEAR",
                    "Could not start the analysis request"
                )
            }

            return
        }

        if (now < deadline) {
            update(
                "ANALYSING",
                "Waiting briefly for fresh chart frames…"
            )

            main.postDelayed({
                dispatchWhenReady(duration, deadline)
            }, 250L)
        } else {
            running = false
            update(
                "UNCLEAR",
                "Not enough fresh frames; keep chart visible"
            )
        }
    }

    private fun askVision(
        images: List<String>,
        duration: Int
    ) {
        var connection: HttpURLConnection? = null

        try {
            // Keep the existing Worker request schema unchanged.
            val payload = JSONObject()
                .put("horizonSeconds", duration)
                .put("frames", JSONArray(images))

            connection =
                (URL(endpoint).openConnection() as HttpURLConnection)
                    .apply {
                        requestMethod = "POST"
                        connectTimeout = 10000
                        readTimeout = 20000
                        doOutput = true
                        setRequestProperty(
                            "Content-Type",
                            "application/json"
                        )
                    }

            connection.outputStream.use { output ->
                output.write(
                    payload.toString().toByteArray(Charsets.UTF_8)
                )
            }

            val status = connection.responseCode

            val stream =
                if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val body = stream?.bufferedReader()?.use {
                it.readText()
            }.orEmpty()

            if (status !in 200..299) {
                val serverMessage = try {
                    JSONObject(body).optString("error", "")
                } catch (_: Exception) {
                    ""
                }

                val message = when (status) {
                    401, 403 ->
                        "Worker authorization or API configuration error"
                    429 ->
                        "Rate limit or AI quota reached"
                    in 500..599 ->
                        "Worker or AI provider server error (HTTP $status)"
                    else ->
                        serverMessage.ifBlank {
                            "Worker returned HTTP $status"
                        }
                }

                update("UNCLEAR", message.take(150))
                return
            }

            val response = try {
                JSONObject(body)
            } catch (_: Exception) {
                update(
                    "UNCLEAR",
                    "Worker returned an invalid JSON response"
                )
                return
            }

            val direction = response
                .optString("direction", "UNCLEAR")
                .uppercase()

            val reason = response
                .optString("reason", "Insufficient evidence")

            val safeDirection =
                if (direction in listOf("UP", "DOWN", "UNCLEAR")) {
                    direction
                } else {
                    "UNCLEAR"
                }

            update(safeDirection, reason.take(150))

        } catch (_: SocketTimeoutException) {
            update(
                "UNCLEAR",
                "Worker timed out; check AI response time"
            )
        } catch (_: UnknownHostException) {
            update(
                "UNCLEAR",
                "Worker host not found; check the saved URL"
            )
        } catch (_: Exception) {
            update(
                "UNCLEAR",
                "Network request failed; check internet and Worker"
            )
        } finally {
            connection?.disconnect()
            running = false
        }
    }

    private fun imageToJpegBase64(image: Image): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer: ByteBuffer = plane.buffer.duplicate()

        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = y * rowStride + x * pixelStride

                if (offset + 3 >= buffer.limit()) continue

                val r = buffer.get(offset).toInt() and 0xFF
                val g = buffer.get(offset + 1).toInt() and 0xFF
                val b = buffer.get(offset + 2).toInt() and 0xFF
                val a = buffer.get(offset + 3).toInt() and 0xFF

                pixels[y * width + x] =
                    (a shl 24) or
                    (r shl 16) or
                    (g shl 8) or b
            }
        }

        val bitmap = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        bitmap.setPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        val scaledWidth = min(640, width)
        val scaledHeight = (
            scaledWidth.toFloat() * height / width
        ).toInt().coerceAtLeast(1)

        val scaled =
            if (scaledWidth == width && scaledHeight == height) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(
                    bitmap,
                    scaledWidth,
                    scaledHeight,
                    true
                )
            }

        val output = ByteArrayOutputStream()

        try {
            scaled.compress(
                Bitmap.CompressFormat.JPEG,
                65,
                output
            )

            return Base64.encodeToString(
                output.toByteArray(),
                Base64.NO_WRAP
            )
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }

    private fun update(
        heading: String,
        message: String
    ) {
        main.post {
            title?.text = heading
            detail?.text = message
        }
    }

    private fun releaseCapture() {
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

    override fun onDestroy() {
        stopped = true
        running = false

        releaseCapture()

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

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        network.shutdownNow()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
