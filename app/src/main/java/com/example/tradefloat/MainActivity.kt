package com.example.tradefloat

import android.Manifest
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

    companion object {
        private const val CAPTURE_REQUEST = 501
        private const val NOTIFICATION_REQUEST = 700
    }

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
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
        })

        root.addView(TextView(this).apply {
            text = "Visible-chart analysis • manual assistance\n\n" +
                "Screen pixels are not verified market prices. " +
                "TradeFloat does not place trades or guarantee outcomes. " +
                "Use demo mode first."
            textSize = 16f
            setTextColor(0xFFD4D8E0.toInt())
            setPadding(0, 18, 0, 20)
        })

        root.addView(Button(this).apply {
            text = "Enable floating panel permission"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(settingsIntent)
                }
            }
        })

        root.addView(Button(this).apply {
            text = "Start live screen session"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                    return@setOnClickListener
                }

                val manager = getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

                startActivityForResult(
                    manager.createScreenCaptureIntent(),
                    CAPTURE_REQUEST
                )
            }
        })

        root.addView(Button(this).apply {
            text = "Stop live session"
            setOnClickListener {
                stopService(
                    Intent(
                        this@MainActivity,
                        CaptureOverlayService::class.java
                    )
                )
            }
        })

        root.addView(TextView(this).apply {
            text = "ROADMAP\n\n" +
                "1. Floating panel\n" +
                "2. Live screen capture\n" +
                "3. Chart-region sampling\n" +
                "4. Heuristic candle detection\n" +
                "5. Visual indicator experiments\n" +
                "6. Conservative assessment\n" +
                "7. Horizon selection\n" +
                "8. Manual outcome validation\n\n" +
                "Visual analysis is experimental. " +
                "No automatic trading."
            textSize = 14f
            setTextColor(0xFFB7C0CE.toInt())
            setPadding(0, 22, 0, 12)
        })

        setContentView(
            ScrollView(this).apply {
                addView(root)
            }
        )

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_REQUEST
            )
        }
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != CAPTURE_REQUEST) return

        if (resultCode != RESULT_OK || data == null) {
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            return
        }

        val serviceIntent = Intent(
            this,
            CaptureOverlayService::class.java
        ).apply {
            // Required by CaptureOverlayService.onStartCommand().
            action = CaptureOverlayService.ACTION_START

            putExtra(
                CaptureOverlayService.EXTRA_RESULT_CODE,
                resultCode
            )

            putExtra(
                CaptureOverlayService.EXTRA_RESULT_DATA,
                data
            )
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e(
                "TradeFloat",
                "Could not start capture service",
                e
            )
        }
    }
}
