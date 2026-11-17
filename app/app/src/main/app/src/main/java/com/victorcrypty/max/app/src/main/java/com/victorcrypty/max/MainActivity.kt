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

    // Fallback screen built 100% in code, used if the normal screen itself fails.
    private fun showBareError(title: String, detail: String) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(32, 32, 32, 32)
        val t = TextView(this)
        t.text = "$title\n\n$detail"
        t.textSize = 14f
        box.addView(t)
        setContentView(box)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            CrashReporter.install(this)
            val previousCrash = CrashReporter.lastCrash(this)
            if (previousCrash != null) {
                // Show the crash story on a bare screen first, stop here.
                showBareError("LAST CRASH:", previousCrash)
                CrashReporter.clear(this)
                return
            }

            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_main)

            statusView = findViewById(R.id.statusView)
            logView = findViewById(R.id.logView)
            val toggleButton = findViewById<Button>(R.id.toggleButton)

            statusView.text = "Paused (tap button to listen)"

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

            // Do NOT auto-start listening. Ask for permissions now,
            // but only start when the button is pressed.
            requestNeededPermissions()
        } catch (e: Throwable) {
            // The safety net's safety net.
            try {
                CrashReporter.record(this, e)
            } catch (_: Exception) {}
            try {
                showBareError("APP ERROR:", e.stackTraceToString().take(2000))
            } catch (_: Exception) {}
        }
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
            val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
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
            Toast.makeText(this, "Mic ready. Tap the button to start listening.", Toast.LENGTH_LONG).show()
        }
    }
}
