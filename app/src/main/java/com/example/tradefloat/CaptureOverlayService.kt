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
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class CaptureOverlayService : Service() {
    companion object {
        const val ACTION_START = "com.example.tradefloat.START_CAPTURE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_ENDPOINT = "endpoint"
        private const val CHANNEL = "tradefloat_ai_capture"
        private const val NOTIFICATION_ID = 310
    }
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var wm: WindowManager? = null
    private var panel: LinearLayout? = null
    private var title: TextView? = null
    private var detail: TextView? = null
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private val network = Executors.newSingleThreadExecutor()
    private var endpoint = ""
    private var seconds = 30
    private var running = false
    private var stopped = false
    private var startedAt = 0L
    private var lastCapture = 0L
    private val frames = mutableListOf<String>()
    private val main = Handler()

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "TradeFloat AI", NotificationManager.IMPORTANCE_LOW)
            )
        }
        thread = HandlerThread("TradeFloatCapture").also { it.start() }
        worker = Handler(thread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        endpoint = intent.getStringExtra(EXTRA_ENDPOINT).orEmpty()
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) else intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (code != Activity.RESULT_OK || data == null || !endpoint.startsWith("https://")) { stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        try {
            // Show the panel before starting capture so startup failures are visible to the user.
            createPanel()
            update("STARTING", "Preparing screen capture…")
            val notification = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
            val built = notification.setContentTitle("TradeFloat AI").setContentText("Cloud chart vision active").setSmallIcon(android.R.drawable.ic_menu_view).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, built, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(NOTIFICATION_ID, built)
            projection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(code, data)
            val m = resources.displayMetrics
            val w = min(m.widthPixels, 640).coerceAtLeast(2)
            val h = (w.toFloat() * m.heightPixels / m.widthPixels).toInt().coerceAtLeast(2)
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopped = true
                    releaseCapture()
                    update("CAPTURE STOPPED", "Restart from TradeFloat")
                }
            }, Handler(mainLooper))
            display = projection?.createVirtualDisplay("TradeFloatAI", w, h, m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, worker)
            reader?.setOnImageAvailableListener({ src ->
                val now = System.currentTimeMillis()
                if (stopped || !running || now - lastCapture < 2500L) {
                    try { src.acquireLatestImage()?.close() } catch (_: Exception) {}
                    return@setOnImageAvailableListener
                }
                val img = try { src.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                lastCapture = now
                worker?.post {
                    try {
                        val encoded = imageToJpegBase64(img)
                        if (encoded != null && running && frames.size < 8) synchronized(frames) { frames.add(encoded) }
                    } catch (_: Exception) {
                        update("UNCLEAR", "Could not sample chart image")
                    } finally { try { img.close() } catch (_: Exception) {} }
                } ?: img.close()
            }, worker)
            update("READY", "Choose 30 sec or 1 min")
        } catch (e: Exception) {
            // Keep the panel visible rather than silently destroying the service.
            try { createPanel() } catch (_: Exception) {}
            update("START FAILED", "${e.javaClass.simpleName}: check permissions and screen-capture approval")
        }
        return START_NOT_STICKY
    }

    private fun createPanel() {
        if (panel != null) return
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 10, 12, 8); setBackgroundColor(Color.rgb(22, 25, 32)) }
        title = TextView(this).apply { text = "TRADEFLOAT AI"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.WHITE) }
        detail = TextView(this).apply { text = "Choose a time window"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.LTGRAY); setPadding(0, 5, 0, 8) }
        root.addView(title); root.addView(detail)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(makeButton("30 sec") { begin(30) })
        row.addView(makeButton("1 min") { begin(60) })
        row.addView(makeButton("STOP") { stopSelf() })
        root.addView(row)
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 12; y = 120 }
        wm?.addView(root, params); panel = root
    }

    private fun makeButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 10f; isAllCaps = false; setPadding(3, 0, 3, 0); setOnClickListener { action() }
    }

    private fun begin(duration: Int) {
        if (running) { update("BUSY", "Wait for the current reading to finish"); return }
        seconds = duration
        synchronized(frames) { frames.clear() }
        running = true
        startedAt = System.currentTimeMillis()
        lastCapture = 0L
        update("READING ${duration}s", "Sampling chart frames for cloud AI…")
        main.postDelayed({
            if (!running || stopped) return@postDelayed
            running = false
            val images = synchronized(frames) { frames.toList() }
            if (images.size < 3) {
                update("UNCLEAR", "Not enough readable frames; keep the chart visible")
            } else {
                update("ANALYSING", "Sending ${images.size} frames to vision AI…")
                network.execute { askVision(images, duration) }
            }
        }, duration * 1000L)
    }

    private fun askVision(images: List<String>, duration: Int) {
        var connection: HttpURLConnection? = null
        try {
            val json = JSONObject().put("horizonSeconds", duration).put("frames", JSONArray(images))
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 15000; readTimeout = 30000; doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            connection.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader().use { it.readText() }
            if (status !in 200..299) {
                val msg = try { JSONObject(response).optString("error", "Cloud AI unavailable") } catch (_: Exception) { "Cloud AI unavailable (HTTP $status)" }
                update("UNCLEAR", msg.take(110))
                return
            }
            val obj = JSONObject(response)
            val direction = obj.optString("direction", "UNCLEAR").uppercase()
            val explanation = obj.optString("reason", "Insufficient evidence")
            val safe = if (direction in listOf("UP", "DOWN", "UNCLEAR")) direction else "UNCLEAR"
            update(safe, explanation.take(150))
        } catch (e: Exception) {
            update("UNCLEAR", "AI request failed · check internet/Worker")
        } finally { connection?.disconnect() }
    }

    private fun imageToJpegBase64(image: Image): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer: ByteBuffer = plane.buffer.duplicate()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + max(0, rowPadding / pixelStride)
        val raw = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        raw.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        val cropped = Bitmap.createBitmap(raw, 0, 0, width, height)
        val scaledW = min(640, width)
        val scaledH = (scaledW.toFloat() * height / width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(cropped, scaledW, scaledH, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 65, out)
        raw.recycle(); cropped.recycle(); scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun update(head: String, body: String) {
        main.post { title?.text = head; detail?.text = body }
    }

    private fun releaseCapture() {
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        display = null; reader = null
    }

    override fun onDestroy() {
        stopped = true; running = false
        releaseCapture()
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        panel?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        panel = null
        thread?.quitSafely(); thread = null; worker = null
        network.shutdownNow()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "TradeFloat AI", NotificationManager.IMPORTANCE_LOW))
    }
}
