package com.victorcrypty.max

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MaxLog {
    val lines = ArrayDeque<String>()
    fun add(s: String) {
        lines.addFirst(s)
        if (lines.size > 200) lines.removeLast()
    }
}

class ListenerService : Service(), RecognitionListener {

    private var recognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = ArrayDeque<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var lastCommand = ""
    private var lastActionAt = 0L
    private var summonedAt = 0L

    // Words the recognizer commonly produces for "Max".
    private val wakeVariants = listOf("max", "marks", "mark's", "macs", "mac's", "mass", "mac", "maps", "max's", "task", "tax", "macks")

    private fun isSummoned(): Boolean =
        System.currentTimeMillis() - summonedAt < 6000

    // Returns the position AFTER the wake word in the sentence, or -1.
    private fun findWakeWord(lower: String): Int {
        val words = lower.split(" ")
        for (i in words.indices) {
            val w = words[i].trim(',', '.', '!', '?')
            if (wakeVariants.any { w == it || (it == "max" && w.length > 3 && w.startsWith("max")) }) {
                // Position after this word in the ORIGINAL string.
                var pos = 0
                for (j in 0 until i) pos += words[j].length + 1
                return pos + words[i].length + 1
            }
        }
        return -1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Max::listener")
        wakeLock?.acquire()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                MaxLog.add("TTS: ready=$ttsReady")
                while (pendingSpeech.isNotEmpty()) {
                    tts?.speak(pendingSpeech.removeFirst(), TextToSpeech.QUEUE_ADD, null, "maxId")
                }
            }
        }
        MaxLog.add("Max service started")
        restartListening()
    }

    private fun say(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "maxId")
        } else {
            pendingSpeech.addLast(text)
        }
    }

    private fun goForeground() {
        val channelId = "max_listener"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Max listening", NotificationManager.IMPORTANCE_LOW)
        )
        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Max is listening")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun restartListening() {
        if (!hasMicPermission()) {
            MaxLog.add("no mic permission yet, retrying in 3s")
            handler.postDelayed({ restartListening() }, 3000)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            MaxLog.add("ERROR: speech recognition not available")
            return
        }
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(this@ListenerService)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_LOWER_QUALITY, true)
                }
                startListening(intent)
            }
        } catch (e: Exception) {
            MaxLog.add("listener restart failed: ${e.message}")
        }
    }

    private fun handleText(text: String, isFinal: Boolean) {
        val lower = text.lowercase().trim()
        MaxLog.add(if (isFinal) "FINAL: $text" else text)
        val now = System.currentTimeMillis()

        val wakePos = findWakeWord(lower)
        if (wakePos >= 0) {
            summonedAt = now
            val command = lower.substring(wakePos).trim().removePrefix(",").trim()
            if (command.isNotEmpty()) {
                execute(command)
            } else if (isFinal || lower.endsWith("max") || lower.endsWith("marks")) {
                MaxLog.add(">>> WAKE WORD, waiting for command")
                say("Yes?")
            }
            return
        }

        if (isSummoned() && lower.isNotEmpty()) {
            val sameAsLast = lower == lastCommand && (now - lastActionAt) < 8000
            if (!sameAsLast) {
                summonedAt = 0L
                execute(lower)
            }
        }
    }

    private fun execute(command: String) {
        lastCommand = command
        lastActionAt = System.currentTimeMillis()
        when {
            command.contains("time") -> {
                val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
                MaxLog.add(">>> ACTION: time -> $time")
                say("It's $time")
            }
            command.contains("flashlight") || command.contains("torch") -> {
                if (command.contains("off")) setTorch(false) else setTorch(true)
            }
            command.contains("open") -> {
                say("Opening apps comes in the next build")
            }
            else -> {
                MaxLog.add(">>> COMMAND: $command")
                say("I heard: $command. I can't do that one yet.")
            }
        }
    }

    private fun setTorch(on: Boolean) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.first {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            cm.setTorchMode(cameraId, on)
            MaxLog.add(">>> ACTION: flashlight ${if (on) "ON" else "OFF"}")
            say("Flashlight ${if (on) "on" else "off"}")
        } catch (e: Exception) {
            MaxLog.add("flashlight failed: ${e.message}")
            say("I couldn't reach the flashlight")
        }
    }

    override fun onPartialResults(partialResults: android.os.Bundle) {
        val results = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        results?.firstOrNull()?.let { handleText(it, false) }
    }

    override fun onResults(results: android.os.Bundle) {
        val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        texts?.firstOrNull()?.let { handleText(it, true) }
        restartListening()
    }

    override fun onError(error: Int) {
        val why = when (error) {
            7 -> "no match"
            11 -> "busy"
            else -> "error $error"
        }
        MaxLog.add("listener: $why, retrying in 2s")
        handler.postDelayed({ restartListening() }, 2000)
    }

    override fun onReadyForSpeech(params: android.os.Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        wakeLock?.release()
        super.onDestroy()
    }
}
