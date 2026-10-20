package com.example.tradefloat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val captureRequest = 501

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 28, 24, 28)
            setBackgroundColor(0xFF101318.toInt())
        }
        root.addView(TextView(this).apply {
            text = "TRADEFLOAT"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Visible-chart analysis • manual assistance\n\n" +
                "Screen pixels are not verified market prices. " +
                "This app does not place trades and cannot guarantee outcomes. " +
                "Use demo mode first."
            textSize = 16f
            setTextColor(0xFFD4D8E0.toInt())
            setPadding(0, 18, 0, 20)
        })
        root.addView(Button(this).apply {
            text = "Enable floating panel permission"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ))
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Start live screen session"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ))
                } else {
                    val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Stop live session"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureOverlayService::class.java))
            }
        })
        root.addView(TextView(this).apply {
            text = "ROADMAP\n\n" +
                "1 Floating panel: implemented\n" +
                "2 Live frame sampling: implemented\n" +
                "3 Chart-region calibration: implemented\n" +
                "4 Candle-like visual candidates: heuristic only\n" +
                "5 Image-proxy indicators: unverified, not market indicators\n" +
                "6 Conservative combined assessment: implemented\n" +
                "7 Expiry horizon tracking: implemented, not a forecast\n" +
                "8 Manual outcome validation: implemented\n\n" +
                "No automatic trading."
            textSize = 14f
            setTextColor(0xFFB7C0CE.toInt())
            setPadding(0, 22, 0, 12)
        })
        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 700)
        }
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, CaptureOverlayService::class.java).apply {
                putExtra(CaptureOverlayService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CaptureOverlayService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
            else startService(serviceIntent)
        }
    }
}
