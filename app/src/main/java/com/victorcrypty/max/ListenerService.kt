package com.victorcrypty.max

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
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

    private fun isSummoned(): Boolean =
        System.currentTimeMillis() - summonedAt < 6000

    private val wakeVariants = listOf(
        "victor", "victory", "victoria", "victor's", "victors",
        "viktor", "victo", "victa", "vector", "viktors"
    )

    private fun findWakeWord(lower: String): Int {
        val words = lower.split(" ")
        var pos = 0
        for (i in words.indices) {
            val w = words[i].trim(',', '.', '!', '?', '-')
            if (wakeVariants.contains(w)) {
                val after = pos + words[i].length + 1
                return if (after <= lower.length) after else lower.length
            }
            pos += words[i].length + 1
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

    private fun hasPermission(p: String): Boolean {
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    }

    private fun restartListening() {
        if (!hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
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
                }
                startListening(intent)
            }
        } catch (e: Exception) {
            MaxLog.add("listener restart failed: ${e.message}")
        }
    }

    private fun handleText(text: String, isFinal: Boolean) {
        try {
            val lower = text.lowercase().trim()
            MaxLog.add(if (isFinal) "FINAL: $text" else text)
            val now = System.currentTimeMillis()

            val wakePos = findWakeWord(lower)
            if (wakePos >= 0) {
                summonedAt = now
                val command = if (wakePos < lower.length) {
                    lower.substring(wakePos).trim().removePrefix(",").trim()
                } else ""
                if (command.isNotEmpty()) {
                    execute(command)
                } else if (isFinal || lower == "victor" || lower == "victory") {
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
        } catch (e: Exception) {
            MaxLog.add("handler error (kept alive): ${e.message}")
        }
    }

    private fun execute(command: String) {
        lastCommand = command
        lastActionAt = System.currentTimeMillis()
        when {
            command.contains("what can you do") || command.contains("help") -> {
                say("I can tell the time and date, check the battery, " +
                    "turn the flashlight on or off, open apps, " +
                    "and call people in your contacts.")
            }
            (command.startsWith("call") || command.contains(" call ")) -> {
                callContact(command)
            }
            command.contains("time") && !command.contains("timer") -> {
                val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
                MaxLog.add(">>> ACTION: time -> $time")
                say("It's $time")
            }
            command.contains("date") || command.contains("today") -> {
                val date = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(Date())
                MaxLog.add(">>> ACTION: date -> $date")
                say("Today is $date")
            }
            command.contains("battery") -> {
                val pct = getBatteryPercent()
                say(if (pct >= 0) "Battery is at $pct percent." else "I couldn't read the battery.")
            }
            command.contains("flashlight") || command.contains("torch") -> {
                if (command.contains("off")) setTorch(false) else setTorch(true)
            }
            command.startsWith("open") || command.contains("open ") -> {
                openApp(command)
            }
            else -> {
                MaxLog.add(">>> COMMAND: $command")
                say("I heard: $command. I can't do that one yet.")
            }
        }
    }

    private fun callContact(command: String) {
        if (!hasPermission(android.Manifest.permission.READ_CONTACTS)) {
            say("I need the contacts permission. Open the app and grant it.")
            return
        }
        // The name is whatever follows the word "call".
        val name = command.replace("call", " ").trim(',', '.', '!').trim()
        if (name.isEmpty()) {
            say("Call who?")
            return
        }
        var number: String? = null
        var foundName: String? = null
        try {
            val cursor: Cursor? = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                null, null, null
            )
            cursor?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(1) ?: continue
                    if (n.lowercase().contains(name) || name.contains(n.lowercase())) {
                        number = c.getString(0)
                        foundName = n
                        break
                    }
                }
            }
        } catch (e: Exception) {
            MaxLog.add("contact search failed: ${e.message}")
        }
        if (number == null) {
            MaxLog.add(">>> no contact for: $name")
            say("I couldn't find $name in your contacts")
            return
        }
        MaxLog.add(">>> ACTION: calling $foundName ($number)")
        say("Calling $foundName")
        try {
            val dial = Intent(Intent.ACTION_CALL, Uri.parse("tel:${number!!}"))
            dial.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(dial)
        } catch (e: Exception) {
            MaxLog.add("dial failed: ${e.message}")
            say("I couldn't place the call")
        }
    }

    private fun getBatteryPercent(): Int {
        return try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100) / scale else -1
        } catch (e: Exception) {
            -1
        }
    }

    private fun openApp(command: String) {
        try {
            val target = command.replace("open", " ").trim()
            if (target.isEmpty()) {
                say("Open what?")
                return
            }
            val pm = packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val activities = pm.queryIntentActivities(launcherIntent, 0)

            val known = mapOf(
                "whatsapp" to "com.whatsapp",
                "youtube" to "com.google.android.youtube",
                "instagram" to "com.instagram.android",
                "telegram" to "org.telegram.messenger",
                "chrome" to "com.android.chrome",
                "gmail" to "com.google.android.gm",
                "maps" to "com.google.android.maps",
                "spotify" to "com.spotify.music",
                "x" to "com.twitter.android",
                "twitter" to "com.twitter.android"
            )
            val pkg = known[target.removePrefix("the ").trim()] ?: ""
            if (pkg.isNotEmpty()) {
                val launch = pm.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    startActivity(launch)
                    say("Opening $target")
                    return
                }
            }

            val match = activities.firstOrNull {
                it.loadLabel(pm).toString().lowercase().contains(target) ||
                target.contains(it.loadLabel(pm).toString().lowercase())
            }
            if (match != null) {
                startActivity(pm.getLaunchIntentForPackage(match.activityInfo.packageName))
                say("Opening ${match.loadLabel(pm)}")
            } else {
                MaxLog.add(">>> no app found for: $target")
                say("I couldn't find an app called $target")
            }
        } catch (e: Exception) {
            MaxLog.add("open failed: ${e.message}")
            say("I couldn't open that app")
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
