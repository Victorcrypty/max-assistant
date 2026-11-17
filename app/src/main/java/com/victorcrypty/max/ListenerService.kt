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
import android.os.Build
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
    private var torchOn = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Max::listener")
        wakeLock?.acquire()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale.US
            }
        }
        MaxLog.add("Max service started")
        restartListening()
    }

    private fun say(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "maxId")
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
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10000)
                }
                startListening(intent)
            }
        } catch (e: Exception) {
            MaxLog.add("listener restart failed: ${e.message}")
        }
    }

    private fun handlePartial(text: String) {
        // Partials are only shown in the log, never acted on.
        MaxLog.add(text)
    }

    private fun handleFinal(text: String) {
        val lower = text.lowercase().trim()
        MaxLog.add("FINAL: $text")
        if (!lower.contains("max")) return

        val idx = lower.indexOf("max")
        val command = if (idx >= 0 && lower.length > idx + 3) {
            lower.substring(idx + 3).trim()
        } else ""
        execute(command.ifEmpty { "just wake word" })
    }

    private fun execute(command: String) {
        when {
            command == "just wake word" -> {
                MaxLog.add(">>> WAKE WORD, no command")
                say("Yes?")
            }
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
            torchOn = on
            MaxLog.add(">>> ACTION: flashlight ${if (on) "ON" else "OFF"}")
            say("Flashlight ${if (on) "on" else "off"}")
        } catch (e: Exception) {
            MaxLog.add("flashlight failed: ${e.message}")
            say("I couldn't reach the flashlight")
        }
    }

    override fun onPartialResults(partialResults: android.os.Bundle) {
        val results = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        results?.firstOrNull()?.let { handlePartial(it) }
    }

    override fun onResults(results: android.os.Bundle) {
        val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        texts?.firstOrNull()?.let { handleFinal(it) }
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
