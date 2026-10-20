
package com.example.tradefloat

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color

class MainActivity : Activity() {

    private val captureRequest = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
            setBackgroundColor(Color.rgb(15, 18, 24))
        }

        val info = TextView(this).apply {
            text = "TRADEFLOAT\n\nFloating chart assistant\n\nThis starter tests Android screen capture and the floating panel. AI predictions are not implemented yet."
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 24)
        }

        val overlay = Button(this).apply {
            text = "1. Allow floating panel"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } else {
                    info.text = "Overlay permission enabled. Start the screen session."
                }
            }
        }

        val start = Button(this).apply {
            text = "2. Start screen session"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    info.text = "Enable Display over other apps first."
                } else {
                    val manager =
                        getSystemService(MEDIA_PROJECTION_SERVICE)
                            as MediaProjectionManager

                    startActivityForResult(
                        manager.createScreenCaptureIntent(),
                        captureRequest
                    )
                }
            }
        }

        val stop = Button(this).apply {
            text = "Stop floating panel"
            setOnClickListener {
                stopService(
                    Intent(
                        this@MainActivity,
                        CaptureOverlayService::class.java
                    )
                )
            }
        }

        layout.addView(info)
        layout.addView(overlay)
        layout.addView(start)
        layout.addView(stop)

        setContentView(layout)
    }

    @Deprecated("Legacy activity result API for starter compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == captureRequest &&
            resultCode == RESULT_OK &&
            data != null
        ) {
            val serviceIntent = Intent(
                this,
                CaptureOverlayService::class.java
            ).apply {
                putExtra(
                    CaptureOverlayService.EXTRA_RESULT_CODE,
                    resultCode
                )
                putExtra(
                    CaptureOverlayService.EXTRA_RESULT_DATA,
                    data
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            finish()
        }
    }
}
