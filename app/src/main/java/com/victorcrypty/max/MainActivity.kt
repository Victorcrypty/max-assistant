package com.victorcrypty.max

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            try {
                logView.text = MaxLog.lines.joinToString("\n")
            } catch (_: Exception) {}
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.install(this)
        CrashReporter.lastCrash(this)?.let {
            MaxLog.add("LAST CRASH:")
            MaxLog.add(it)
            CrashReporter.clear(this)
        }

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(32, 32, 32, 32)

        statusView = TextView(this).apply {
            text = "Paused (tap button to listen)"
            textSize = 22f
        }
        layout.addView(statusView)

        val toggleButton = Button(this)
        toggleButton.text = "Start / Stop listening"
        layout.addView(toggleButton)

        logView = TextView(this).apply { textSize = 13f }
        val scroll = ScrollView(this)
        scroll.addView(logView)
        layout.addView(scroll)

        setContentView(layout)

        toggleButton.setOnClickListener {
            val running = statusView.text.toString().contains("Listening")
            if (running) {
                stopService(Intent(this, ListenerService::class.java))
                statusView.text = "Paused (tap button to listen)"
            } else {
                startListener()
            }
        }

        handler.post(refresher)
        requestNeededPermissions()
    }

    private fun startListener() {
        try {
            startForegroundService(Intent(this, ListenerService::class.java))
            statusView.text = "Listening..."
        } catch (e: Exception) {
            statusView.text = "Paused"
            Toast.makeText(this, "Could not start: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNeededPermissions() {
        try {
            val needed = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE
            )
            if (Build.VERSION.SDK_INT >= 33) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            val missing = needed.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Permission error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permissions ready. Tap the button to start listening.", Toast.LENGTH_LONG).show()
        }
    }
}
