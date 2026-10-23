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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    companion object { private const val REQUEST_CAPTURE = 101 }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
            setBackgroundColor(0xFF111318.toInt())
        }
        root.addView(TextView(this).apply {
            text = "TRADEFLOAT AI"
            textSize = 28f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Cloud vision chart reader\n\nThe floating panel has only 30 sec, 1 min and STOP. Set your free Cloudflare Worker URL below once."
            textSize = 15f; gravity = Gravity.CENTER; setTextColor(0xFFD2D6DF.toInt()); setPadding(0, 20, 0, 18)
        })
        val endpoint = EditText(this).apply {
            hint = "Cloudflare Worker URL (https://…workers.dev)"
            setSingleLine(true)
            setText(getSharedPreferences("tradefloat", MODE_PRIVATE).getString("endpoint", ""))
        }
        root.addView(endpoint)
        root.addView(Button(this).apply {
            text = "Save endpoint"
            setOnClickListener {
                val value = endpoint.text.toString().trim()
                if (!value.startsWith("https://")) {
                    Toast.makeText(this@MainActivity, "Enter an HTTPS Worker URL", Toast.LENGTH_LONG).show()
                } else {
                    getSharedPreferences("tradefloat", MODE_PRIVATE).edit().putString("endpoint", value).apply()
                    Toast.makeText(this@MainActivity, "Endpoint saved", Toast.LENGTH_SHORT).show()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Enable floating panel"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Start live chart reader"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    return@setOnClickListener
                }
                val url = getSharedPreferences("tradefloat", MODE_PRIVATE).getString("endpoint", "") ?: ""
                if (!url.startsWith("https://")) {
                    Toast.makeText(this@MainActivity, "Save the Worker URL first", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CAPTURE)
            }
        })
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
    }
    @Deprecated("Deprecated Android API kept for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE || resultCode != RESULT_OK || data == null) return
        val endpoint = getSharedPreferences("tradefloat", MODE_PRIVATE).getString("endpoint", "") ?: ""
        val service = Intent(this, CaptureOverlayService::class.java).apply {
            action = CaptureOverlayService.ACTION_START
            putExtra(CaptureOverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureOverlayService.EXTRA_RESULT_DATA, data)
            putExtra(CaptureOverlayService.EXTRA_ENDPOINT, endpoint)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        finish()
    }
}
