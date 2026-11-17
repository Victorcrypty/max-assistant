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
            logView.text = MaxLog.lines.joinToString("\n")
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Black box recorder: catch any crash and store it for reading later.
        CrashReporter.install(this)
        CrashReporter.lastCrash(this)?.let {
            MaxLog.add("LAST CRASH:")
            MaxLog.add(it)
            CrashReporter.clear(this)
        }

        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.statusView)
        logView = findViewById(R.id.logView)
        val toggleButton = findViewById<Button>(R.id.toggleButton)

        statusView.text = "Paused"

        toggleButton.setOnClickListener {
            val running = statusView.text.toString().contains("Listening")
            if (running) {
                stopService(Intent(this, ListenerService::class.java))
                statusView.text = "Paused"
            } else {
                startListener()
            }
        }

        handler.post(refresher)

        // Ask for permission FIRST, and only start listening after it is granted.
        requestNeededPermissions()
    }

    private fun startListener() {
        try {
            startForegroundService(Intent(this, ListenerService::class.java))
            statusView.text = "Listening..."
        } catch (e: Exception) {
            statusView.text = "Paused"
            Toast.makeText(this, "Could not start listener: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startListener()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListener()
            } else {
                Toast.makeText(this, "Max needs the microphone permission to listen", Toast.LENGTH_LONG).show()
            }
        }
    }
}
