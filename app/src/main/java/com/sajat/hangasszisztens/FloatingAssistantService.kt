package com.sajat.hangasszisztens
import android.animation.ValueAnimator; import android.app.*; import android.content.*; import android.graphics.*; import android.graphics.drawable.GradientDrawable; import android.hardware.camera2.CameraManager; import android.net.Uri; import android.os.*; import android.provider.*; import android.speech.*; import android.speech.tts.TextToSpeech; import android.view.*; import android.view.animation.AccelerateDecelerateInterpolator; import android.widget.*; import androidx.core.app.NotificationCompat; import java.text.SimpleDateFormat; import java.util.*; import kotlin.concurrent.thread; import kotlin.math.abs; import kotlin.math.min
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
class FloatingAssistantService : Service() {
    companion object {
        const val ACTION_START_LISTENING = "com.sajat.hangasszisztens.START_LISTENING"
        const val ACTION_MACRO_COMMAND = "com.sajat.hangasszisztens.MACRO_COMMAND"
    }

    private enum class HudState { IDLE, LISTENING, THINKING, SPEAKING }

    private lateinit var wm: WindowManager; private lateinit var root: FrameLayout; private lateinit var icon: ReactorHudView; private lateinit var closeBtn: TextView
    private lateinit var bubble: TextView
    private lateinit var params: WindowManager.LayoutParams; private lateinit var closeParams: WindowManager.LayoutParams
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var sr: SpeechRecognizer? = null; private var tts: TextToSpeech? = null; private val h = Handler(Looper.getMainLooper()); private val hist = ArrayDeque<String>()
    private var historyUpdatedAt = 0L
    private lateinit var audioManager: AudioManager
    private var mediaSession: MediaSession? = null
    private var pendingSrStart = false
    private var recognitionActive = false
    private var consecutiveRecognitionErrors = 0
    private var activeListening = false
    private var thinking = false
    private var recognizerSoundsMuted = false
    private var restartAfterSpeech = false
    private var scoReceiverRegistered = false
    private var originalAudioMode = AudioManager.MODE_NORMAL
    private var originalSpeakerphoneOn = false
    private var originalCommunicationDevice: AudioDeviceInfo? = null
    private var docked = true; private var right = true; private var listening = false; private var speaking = false; private var sw = 1080; private var sh = 2400
    private val wakeWordPattern = Regex("^(?:jarvis|dzsárvisz|dzsarvis|dzsárvis|javis)(?:\\b|(?=\\s|[,.:;!?-]|$))\\s*[,.:;!?-]*\\s*", RegexOption.IGNORE_CASE)
    private val activeListeningTimeout = Runnable {
        activeListening = false
        updateHudAnimation()
    }
    private val hideBubble = Runnable {
        if (::bubble.isInitialized) {
            bubble.animate().alpha(0f).setDuration(500L).withEndAction {
                bubble.visibility = View.GONE
            }.start()
        }
    }
    private val unmuteRecognizerSounds = Runnable {
        if (recognizerSoundsMuted) {
            listOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION).forEach {
                audioManager.adjustStreamVolume(it, AudioManager.ADJUST_UNMUTE, 0)
            }
            recognizerSoundsMuted = false
        }
    }
    private val scoStartTimeout = Runnable {
        if (pendingSrStart) {
            pendingSrStart = false
            updateHudAnimation()
            beginSr()
        }
    }
    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR) == AudioManager.SCO_AUDIO_STATE_CONNECTED && pendingSrStart) {
                h.removeCallbacks(scoStartTimeout)
                pendingSrStart = false
                updateHudAnimation()
                beginSr()
            }
        }
    }
    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this) ||
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.e("J.A.R.V.I.S.", "A lebegő ablak vagy a mikrofon engedélye hiányzik")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START_LISTENING) restartSr()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        originalAudioMode = audioManager.mode
        originalSpeakerphoneOn = audioManager.isSpeakerphoneOn
        originalCommunicationDevice = if (
            Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) audioManager.communicationDevice else null
        val ch = "jarvis_ch"; if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(ch, "J.A.R.V.I.S.", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, NotificationCompat.Builder(this, ch).setContentTitle("J.A.R.V.I.S. aktív").setSmallIcon(android.R.drawable.ic_btn_speak_now).build())
        if (!Settings.canDrawOverlays(this) ||
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.e("J.A.R.V.I.S.", "Indítás megszakítva: hiányzó lebegő ablak- vagy mikrofonengedély")
            stopSelf()
            return
        }
        ContextCompat.registerReceiver(this, scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED)
        scoReceiverRegistered = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager; sw = resources.displayMetrics.widthPixels; sh = resources.displayMetrics.heightPixels
        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                tts?.apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    language = Locale("hu", "HU")
                    voices
                        ?.filter {
                            it.locale.language == "hu" &&
                                (it.quality >= Voice.QUALITY_HIGH || it.isNetworkConnectionRequired)
                        }
                        ?.maxByOrNull { it.quality }
                        ?.let { voice = it }
                    setSpeechRate(1.05f)
                    setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            h.post { speaking = true; updateHudAnimation() }
                        }
                        override fun onDone(utteranceId: String?) {
                            h.post { finishSpeech() }
                        }
                        override fun onError(utteranceId: String?) {
                            h.post { finishSpeech() }
                        }
                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            h.post { finishSpeech() }
                        }
                    })
                }
            }
        }
        root = FrameLayout(this)
        icon = ReactorHudView(this)
        root.addView(icon, FrameLayout.LayoutParams(-1, -1))
        val t = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        params = WindowManager.LayoutParams(dp(22f), dp(145f), t, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = sw - dp(22f); y = sh / 3 }
        closeBtn = TextView(this).apply { text = "✕"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#E53935")) }; visibility = View.GONE }
        bubble = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            maxWidth = dp(280f)
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.parseColor("#EE06152F"))
                setStroke(dp(1f), Color.parseColor("#7733B5E5"))
            }
            visibility = View.GONE
            alpha = 0f
        }
        closeParams = WindowManager.LayoutParams(dp(58f), dp(58f), t, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(48f) }
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            t,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(closeBtn, closeParams); wm.addView(root, params); wm.addView(bubble, bubbleParams)
        dock(); touch(); initSr(); initMediaSession(); startSr()
    }
    private fun hudBackground() = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(Color.parseColor("#AA060B19")); setStroke(dp(1f), Color.parseColor("#7700E676")) }
    private fun dock() { docked = true; root.background = null; icon.setHudState(true, currentHudState()); params.width = dp(22f); params.height = dp(145f); params.x = if (right) sw - dp(22f) else 0; try { wm.updateViewLayout(root, params) } catch (_: Exception) {} }
    private fun undock() {
        docked = false
        root.background = hudBackground()
        icon.setHudState(false, currentHudState())
        params.width = dp(76f)
        params.height = dp(76f)
        if (params.x > sw - dp(80f)) params.x = sw - dp(92f)
        if (params.x < dp(10f)) params.x = dp(16f)
        try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
    }
    private fun touch() {
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var drag = false
        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY; drag = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx; val dy = e.rawY - ty
                    if (!drag && (abs(dx) > 35 || abs(dy) > 35)) { drag = true; if (docked) undock(); closeBtn.visibility = View.VISIBLE }
                    if (drag) { params.x = (sx + dx.toInt()).coerceIn(0, sw - params.width); params.y = (sy + dy.toInt()).coerceIn(dp(24f), sh - params.height - dp(24f)); try { wm.updateViewLayout(root, params) } catch (_: Exception) {} }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    closeBtn.visibility = View.GONE
                    if (drag) {
                        if (e.rawY > sh - dp(125f) && abs(e.rawX - sw / 2f) < dp(75f)) stopSelf()
                        else if (e.rawX < 65 || e.rawX > sw - 65) { right = e.rawX >= sw / 2f; dock() }
                    } else { tts?.stop(); if (docked) undock(); activateListening() }
                    true
                }
                else -> false
            }
        }
    }
    private fun initSr() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            android.util.Log.e("J.A.R.V.I.S.", "A készüléken nem érhető el hangfelismerő")
            return
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        sr = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p0: Bundle?) {
                if (sr !== recognizer) return
                listening = true
                icon.alpha = 0.7f
                updateHudAnimation()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(p0: Float) {}
            override fun onBufferReceived(p0: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (sr !== recognizer) return
                listening = false
                icon.alpha = 1f
                updateHudAnimation()
            }
            override fun onError(p0: Int) {
                if (sr !== recognizer) return
                listening = false
                recognitionActive = false
                icon.alpha = 1f
                updateHudAnimation()
                android.util.Log.w("J.A.R.V.I.S.", "A hangfelismerés leállt hibával: $p0")
                scheduleRecognitionRetry()
            }
            override fun onResults(b: Bundle?) {
                if (sr !== recognizer) return
                listening = false
                recognitionActive = false
                consecutiveRecognitionErrors = 0
                icon.alpha = 1f
                updateHudAnimation()
                val result = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (result != null) handleRecognition(result) else scheduleWakeListening()
            }
            override fun onPartialResults(p0: Bundle?) {}
            override fun onEvent(p0: Int, p1: Bundle?) {}
        })
    }
    private fun initMediaSession() {
        mediaSession = MediaSession(this, "J.A.R.V.I.S.").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (event != null && event.keyCode in listOf(
                            KeyEvent.KEYCODE_CALL,
                            KeyEvent.KEYCODE_HEADSETHOOK,
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            KeyEvent.KEYCODE_MEDIA_PLAY,
                            KeyEvent.KEYCODE_MEDIA_PAUSE
                        ) && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        restartSr()
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
                override fun onPlay() = restartSr()
                override fun onPause() = restartSr()
            })
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON, null, this@FloatingAssistantService, MediaButtonReceiver::class.java)
            setMediaButtonReceiver(
                PendingIntent.getBroadcast(
                    this@FloatingAssistantService,
                    0,
                    mediaButtonIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE).setState(PlaybackState.STATE_PAUSED, 0, 0f).build())
            isActive = true
        }
    }
    private fun bluetoothHeadset(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
            return audioManager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
        }
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }
    private fun routeAudioToBluetooth(): Boolean {
        val device = bluetoothHeadset() ?: return false
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false
        if (Build.VERSION.SDK_INT >= 31 && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
            if (!audioManager.setCommunicationDevice(device)) {
                audioManager.mode = originalAudioMode
                audioManager.isSpeakerphoneOn = originalSpeakerphoneOn
                return false
            }
        } else {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
        }
        return true
    }
    private fun startSr() {
        if (listening || recognitionActive || pendingSrStart) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            android.util.Log.e("J.A.R.V.I.S.", "Hangfelismerés nem indítható mikrofonengedély nélkül")
            return
        }
        if (sr == null) initSr()
        if (sr == null) return
        try {
            if (routeAudioToBluetooth()) {
                pendingSrStart = true
                updateHudAnimation()
                h.postDelayed(scoStartTimeout, 3000)
                if (Build.VERSION.SDK_INT >= 31 && bluetoothHeadset()?.type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    h.removeCallbacks(scoStartTimeout)
                    pendingSrStart = false
                    beginSr()
                }
            } else beginSr()
        } catch (e: SecurityException) {
            android.util.Log.e("J.A.R.V.I.S.", "Bluetooth hangútvonal nem érhető el", e)
            beginSr()
        }
    }

    private fun restartSr() {
        pendingSrStart = false
        h.removeCallbacks(scoStartTimeout)
        if (listening) {
            try { sr?.cancel() } catch (e: Exception) {
                android.util.Log.w("J.A.R.V.I.S.", "A korábbi hangfelismerés megszakítása sikertelen", e)
            }
        }
        sr?.destroy()
        sr = null
        listening = false
        recognitionActive = false
        initSr()
        startSr()
    }

    private fun handleRecognition(raw: String) {
        val trimmed = raw.trim()
        val wakeMatch = wakeWordPattern.find(trimmed)
        if (wakeMatch != null) {
            val command = trimmed.substring(wakeMatch.range.last + 1).trim()
            if (command.isEmpty()) {
                activateListening()
                return
            }
            runCmd(command)
            scheduleWakeListening(recreateRecognizer = true)
            return
        }
        runCmd(raw)
        scheduleWakeListening()
    }

    private fun activateListening() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(40L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(40L)
            }
        }
        activeListening = true
        h.removeCallbacks(activeListeningTimeout)
        h.postDelayed(activeListeningTimeout, 8_000L)
        updateHudAnimation()
        startSr()
    }

    private fun scheduleWakeListening(
        recreateRecognizer: Boolean = false,
        delayMillis: Long = 700L
    ) {
        h.postDelayed({
            if (tts?.isSpeaking == true) {
                restartAfterSpeech = true
            } else {
                if (recreateRecognizer) restartSr() else startSr()
            }
        }, delayMillis)
    }

    private fun scheduleRecognitionRetry() {
        val delayMillis = (700L * (1L shl consecutiveRecognitionErrors.coerceAtMost(5)))
            .coerceAtMost(20_000L)
        consecutiveRecognitionErrors++
        scheduleWakeListening(delayMillis = delayMillis)
    }

    private fun finishSpeech() {
        speaking = tts?.isSpeaking == true
        updateHudAnimation()
        if (!speaking && restartAfterSpeech) {
            restartAfterSpeech = false
            startSr()
        }
    }

    private fun beginSr() {
        val recognizer = sr
        if (recognizer == null) {
            android.util.Log.e("J.A.R.V.I.S.", "Hangfelismerő nem áll rendelkezésre")
            return
        }
        try {
            recognizerSoundsMuted = true
            h.removeCallbacks(unmuteRecognizerSounds)
            h.postDelayed(unmuteRecognizerSounds, 300L)
            listOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION).forEach {
                audioManager.adjustStreamVolume(it, AudioManager.ADJUST_MUTE, 0)
            }
            recognitionActive = true
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hu-HU")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        }
        catch (e: Exception) {
            recognitionActive = false
            android.util.Log.e("J.A.R.V.I.S.", "Hangfelismerés indítása sikertelen", e)
            scheduleRecognitionRetry()
        }
    }
    private fun say(t: String) {
        h.post {
            showBubble(t)
            val speech = t.trim().takeIf { it.isNotEmpty() } ?: return@post
            val sentences = Regex("[^.!?]+[.!?]+|[^.!?]+$")
                .findAll(speech)
                .map { it.value.trim() }
                .filter { it.isNotEmpty() }
                .toList()
            sentences.forEachIndexed { index, sentence ->
                val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                tts?.speak(sentence, queueMode, null, "jarvis-${System.nanoTime()}")
            }
        }
    }

    private fun showBubble(text: String) {
        if (!::bubble.isInitialized) return
        h.removeCallbacks(hideBubble)
        bubble.text = text
        val maxBubbleWidth = dp(280f)
        bubbleParams.x = if (right) {
            (params.x - maxBubbleWidth - dp(8f)).coerceAtLeast(dp(8f))
        } else {
            (params.x + params.width + dp(8f)).coerceAtMost(sw - maxBubbleWidth)
        }
        bubbleParams.y = (params.y - dp(62f)).coerceAtLeast(dp(24f))
        bubble.visibility = View.VISIBLE
        bubble.alpha = 0f
        try {
            wm.updateViewLayout(bubble, bubbleParams)
            bubble.animate().alpha(1f).setDuration(180L).start()
            h.postDelayed(hideBubble, 4_500L)
        } catch (e: WindowManager.BadTokenException) {
            android.util.Log.w("J.A.R.V.I.S.", "A HUD szövegbuborék nem jeleníthető meg", e)
        }
    }
    private fun app(pkg: String, n: String) { val i = packageManager.getLaunchIntentForPackage(pkg); if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); say("$n megnyitása.") } else say("$n nincs telepítve.") }
    private fun torch(on: Boolean) { try { val cm = getSystemService(CAMERA_SERVICE) as CameraManager; cm.setTorchMode(cm.cameraIdList[0], on); say(if (on) "Zseblámpa bekapcsolva." else "Zseblámpa kikapcsolva.") } catch (_: Exception) { say("Zseblámpa hiba.") } }
    private fun runCmd(raw: String) {
        val c = raw.lowercase(Locale("hu", "HU"))
        when {
            "idő" in c || "hány óra" in c || "mennyi az idő" in c -> say("Pontos idő: " + SimpleDateFormat("HH:mm", Locale("hu", "HU")).format(Date()))
            "akkumulátor" in c || "töltöttség" in c -> {
                val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) {
                    say("Az akkumulátor töltöttsége ${level * 100 / scale} százalék.")
                } else {
                    say("Az akkumulátor töltöttsége most nem olvasható.")
                }
            }
            c.startsWith("makró ") || c == "makró" -> {
                val command = raw.trim().substringAfter(' ', "").trim()
                if (command.isEmpty()) {
                    say("Mondd el, melyik makrót futtassam.")
                } else {
                    sendBroadcast(
                        Intent(ACTION_MACRO_COMMAND)
                            .putExtra("command", command)
                    )
                    say("Makró indítása: $command")
                }
            }
            "dátum" in c || "milyen nap" in c -> say("Ma " + SimpleDateFormat("yyyy. MMMM d., EEEE", Locale("hu", "HU")).format(Date()) + " van.")
            "zseblámpa" in c -> torch("ki" !in c && "le" !in c)
            "youtube" in c -> app("com.google.android.youtube", "YouTube")
            "spotify" in c -> app("com.spotify.music", "Spotify")
            "waze" in c -> app("com.waze", "Waze")
            "térkép" in c || "maps" in c -> app("com.google.android.apps.maps", "Térkép")
            "naptár" in c && "emlékeztess" !in c -> app("com.google.android.calendar", "Naptár")
            "beállítások" in c -> { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); say("Beállítások.") }
            c.startsWith("navigáció") || c.startsWith("navigálj") || c.startsWith("útvonal") -> {
                val destination = raw.replace(
                    Regex("(?i)^\\s*(navigáció|navigálj(?:\\s+ide)?|útvonal)\\s*"),
                    ""
                ).trim()
                if (destination.isEmpty()) {
                    say("Hová navigáljak?")
                } else {
                    try {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("google.navigation:q=" + Uri.encode(destination))
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        say("Navigáció: $destination")
                    } catch (e: Exception) {
                        android.util.Log.w("J.A.R.V.I.S.", "Navigáció nem indítható", e)
                        say("Nincs elérhető navigációs alkalmazás.")
                    }
                }
            }
            "tárcsáz" in c || "hívd" in c -> { startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); say("Tárcsázó.") }
            "időzítő" in c -> {
                val minuteToken = Regex("\\d+").find(c)?.value
                val minutes = if (minuteToken == null) 5L else minuteToken.toLongOrNull()
                if (minutes == null || minutes !in 1L..1_440L) {
                    say("Adj meg egy időzítőt 1 és 1440 perc között.")
                } else {
                    try {
                        startActivity(Intent(AlarmClock.ACTION_SET_TIMER).apply {
                            putExtra(AlarmClock.EXTRA_LENGTH, (minutes * 60).toInt())
                            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                        say("$minutes perces időzítő.")
                    } catch (error: Exception) {
                        android.util.Log.e("J.A.R.V.I.S.", "Az időzítő nem indítható", error)
                        say("Az időzítőt nem sikerült beállítani.")
                    }
                }
            }
            "ébresztő" in c || "ébressz" in c -> {
                val numberTokens = Regex("\\d+").findAll(c).map { it.value }.toList()
                val numbers = numberTokens.mapNotNull { it.toIntOrNull() }
                val hour = numbers.getOrNull(0) ?: 7
                val minute = numbers.getOrNull(1) ?: 0
                if (numberTokens.size != numbers.size || hour !in 0..23 || minute !in 0..59) {
                    say("Az ébresztő időpontja érvénytelen.")
                } else {
                    try {
                        startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                            putExtra(AlarmClock.EXTRA_HOUR, hour)
                            putExtra(AlarmClock.EXTRA_MINUTES, minute)
                            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                        say("Ébresztő beállítva.")
                    } catch (error: Exception) {
                        android.util.Log.e("J.A.R.V.I.S.", "Az ébresztő nem indítható", error)
                        say("Az ébresztőt nem sikerült beállítani.")
                    }
                }
            }
            "emlékeztess" in c || "jegyzet" in c -> {
                val title = raw.replace(Regex("(?i)(emlékeztess|hogy|jegyzet|:)"), "")
                    .trim()
                    .ifEmpty { raw }
                try {
                    startActivity(Intent(Intent.ACTION_INSERT).apply {
                        data = CalendarContract.Events.CONTENT_URI
                        putExtra(CalendarContract.Events.TITLE, title)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    say("Emlékeztető: $title")
                } catch (error: Exception) {
                    android.util.Log.e("J.A.R.V.I.S.", "Az emlékeztető nem indítható", error)
                    say("Az emlékeztetőt nem sikerült megnyitni.")
                }
            }
            "keress rá" in c -> { val q = raw.replace(Regex("(?i)(keress rá|:)"), "").trim(); startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); say("Keresés: $q") }
            else -> askAi(raw)
        }
    }
    private fun askAi(q: String) {
        val k = try {
            ApiKeyStore.read(this).orEmpty()
        } catch (error: Exception) {
            android.util.Log.e("J.A.R.V.I.S.", "A mentett API-kulcs nem olvasható", error)
            say("A mentett API-kulcs nem olvasható. Nyisd meg a vezérlőpultot.")
            return
        }
        if (k.isEmpty()) { say("Ezt hallottam: $q. Add meg az API-kulcsot a vezérlőpulton."); return }
        thinking = true
        updateHudAnimation()
        say("Pillanat...")
        thread {
            try {
                val prompt = synchronized(hist) {
                    val now = System.currentTimeMillis()
                    if (now - historyUpdatedAt > 10 * 60 * 1000L) hist.clear()
                    while (hist.size >= 12) {
                        hist.removeFirst()
                        if (hist.isNotEmpty()) hist.removeFirst()
                    }
                    hist.addLast("Felhasználó: $q")
                    historyUpdatedAt = now
                    "Te J.A.R.V.I.S. magyar hangasszisztens vagy. Válaszolj 2 rövid, tömör mondatban, formázás nélkül.\n" +
                        hist.joinToString("\n")
                }
                val ans = GeminiApi.generateContent(k, prompt).replace("*", "").trim()
                synchronized(hist) {
                    while (hist.size >= 12) {
                        hist.removeFirst()
                        if (hist.isNotEmpty()) hist.removeFirst()
                    }
                    hist.addLast("J.A.R.V.I.S.: $ans")
                    historyUpdatedAt = System.currentTimeMillis()
                }
                h.post { thinking = false; updateHudAnimation() }
                say(ans)
            } catch (e: Exception) {
                android.util.Log.e("J.A.R.V.I.S.", "Gemini kérés sikertelen az összes modellen", e)
                h.post {
                    thinking = false
                    updateHudAnimation()
                    Toast.makeText(this, e.message ?: "Gemini kérés sikertelen.", Toast.LENGTH_LONG).show()
                }
                say("Gemini hiba, ellenőrizd a netet vagy a kulcsot.")
            }
        }
    }
    private fun currentHudState(): HudState = when {
        speaking -> HudState.SPEAKING
        thinking -> HudState.THINKING
        activeListening -> HudState.LISTENING
        else -> HudState.IDLE
    }

    private fun updateHudAnimation() {
        if (::icon.isInitialized) icon.setHudState(docked, currentHudState())
    }
    private inner class ReactorHudView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var isDocked = true
        private var hudState = HudState.IDLE
        private var pulse = 0.5f
        private val pulseAnimator = ValueAnimator.ofFloat(0.35f, 1f).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            duration = 1_700L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        init {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        fun setHudState(docked: Boolean, state: HudState) {
            isDocked = docked
            hudState = state
            pulseAnimator.duration = when (state) {
                HudState.IDLE -> 1_700L
                HudState.LISTENING -> 620L
                HudState.THINKING -> 1_050L
                HudState.SPEAKING -> 850L
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val accent = when (hudState) {
                HudState.IDLE -> Color.rgb(90, 220, 232)
                HudState.LISTENING -> Color.rgb(0, 230, 118)
                HudState.THINKING -> Color.rgb(0, 229, 255)
                HudState.SPEAKING -> Color.rgb(255, 179, 0)
            }
            val cyan = Color.rgb(0, 229, 255)
            val green = accent
            val alpha = (70 + 75 * pulse).toInt().coerceIn(0, 255)

            paint.style = Paint.Style.FILL
            paint.shader = RadialGradient(
                cx, cy, dp(if (isDocked) 24f else 42f) * (0.72f + pulse * 0.45f),
                intArrayOf(Color.argb(alpha, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.argb(0, 0, 229, 255)),
                null,
                Shader.TileMode.CLAMP
            )
            if (isDocked) {
                val track = RectF(cx - dp(4f), dp(8f).toFloat(), cx + dp(4f), height - dp(8f).toFloat())
                paint.shader = null
                paint.color = Color.argb(50, Color.red(accent), Color.green(accent), Color.blue(accent))
                canvas.drawRoundRect(track, dp(5f).toFloat(), dp(5f).toFloat(), paint)
                paint.shader = RadialGradient(
                    cx, cy, dp(24f) * (0.72f + pulse * 0.45f),
                    intArrayOf(Color.argb(alpha, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.argb(0, 0, 229, 255)),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, dp(24f) * (0.72f + pulse * 0.45f), paint)
                paint.shader = null
                paint.color = Color.argb(alpha, Color.red(accent), Color.green(accent), Color.blue(accent))
                canvas.drawRoundRect(
                    RectF(cx - dp(2f), cy - dp(13f) * (0.75f + pulse * 0.35f), cx + dp(2f), cy + dp(13f) * (0.75f + pulse * 0.35f)),
                    dp(3f).toFloat(), dp(3f).toFloat(), paint
                )
            } else {
                val radius = min(width, height) * 0.45f
                canvas.drawCircle(cx, cy, radius, paint)
                paint.shader = null
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                val rings = listOf(0.34f, 0.52f, 0.72f, 0.91f)
                rings.forEachIndexed { index, scale ->
                    paint.strokeWidth = dp(if (index == 2) 2f else 1f).toFloat()
                    val ringColor = if (index % 2 == 0) green else cyan
                    paint.color = Color.argb((alpha * (1f - index * 0.13f)).toInt(), Color.red(ringColor), Color.green(ringColor), Color.blue(ringColor))
                    canvas.drawCircle(cx, cy, radius * scale, paint)
                }
                val bounds = RectF(cx - radius * 0.82f, cy - radius * 0.82f, cx + radius * 0.82f, cy + radius * 0.82f)
                paint.strokeWidth = dp(2f).toFloat()
                paint.color = Color.argb(alpha, Color.red(accent), Color.green(accent), Color.blue(accent))
                canvas.drawArc(bounds, -65f, 105f, false, paint)
                canvas.drawArc(bounds, 112f, 112f, false, paint)
                canvas.drawArc(bounds, 245f, 55f, false, paint)
                paint.style = Paint.Style.FILL
                val coreRadius = radius * (0.17f + pulse * 0.035f)
                paint.shader = RadialGradient(
                    cx, cy, coreRadius * 1.8f,
                    intArrayOf(Color.WHITE, accent, cyan, Color.argb(0, 0, 229, 255)),
                    floatArrayOf(0f, 0.35f, 0.62f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, coreRadius * 1.8f, paint)
                paint.shader = null
                paint.color = Color.argb(alpha, Color.red(accent), Color.green(accent), Color.blue(accent))
                canvas.drawCircle(cx, cy, coreRadius, paint)
            }
            paint.shader = null
        }

        fun stopAnimation() {
            pulseAnimator.cancel()
        }
    }
    override fun onDestroy() {
        pendingSrStart = false
        h.removeCallbacks(activeListeningTimeout)
        h.removeCallbacks(hideBubble)
        h.removeCallbacks(scoStartTimeout)
        h.removeCallbacks(unmuteRecognizerSounds)
        if (recognizerSoundsMuted) {
            listOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION).forEach {
                audioManager.adjustStreamVolume(it, AudioManager.ADJUST_UNMUTE, 0)
            }
            recognizerSoundsMuted = false
        }
        if (scoReceiverRegistered) {
            try { unregisterReceiver(scoReceiver) } catch (e: IllegalArgumentException) { android.util.Log.w("J.A.R.V.I.S.", "SCO receiver was already unregistered", e) }
        }
        mediaSession?.run { isActive = false; release() }
        mediaSession = null
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val previousDevice = originalCommunicationDevice
                if (previousDevice == null) audioManager.clearCommunicationDevice() else audioManager.setCommunicationDevice(previousDevice)
            }
        }
        else { audioManager.isBluetoothScoOn = false; audioManager.stopBluetoothSco() }
        audioManager.mode = originalAudioMode
        audioManager.isSpeakerphoneOn = originalSpeakerphoneOn
        if (::icon.isInitialized) icon.stopAnimation()
        if (::wm.isInitialized) {
            try {
                if (::root.isInitialized) wm.removeView(root)
                if (::closeBtn.isInitialized) wm.removeView(closeBtn)
                if (::bubble.isInitialized) wm.removeView(bubble)
            } catch (_: Exception) {}
        }
        sr?.destroy(); tts?.shutdown(); super.onDestroy()
    }
}
