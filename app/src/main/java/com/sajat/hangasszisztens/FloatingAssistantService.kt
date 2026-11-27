package com.sajat.hangasszisztens
import android.animation.ValueAnimator; import android.app.*; import android.content.*; import android.graphics.*; import android.graphics.drawable.GradientDrawable; import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.net.Uri; import android.os.*; import android.provider.*; import android.speech.*; import android.speech.tts.TextToSpeech; import android.util.Base64; import android.view.*; import android.view.animation.AccelerateDecelerateInterpolator; import android.widget.*; import androidx.core.app.NotificationCompat; import java.text.SimpleDateFormat; import java.util.*; import kotlin.concurrent.thread; import kotlin.math.abs; import kotlin.math.min
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
class FloatingAssistantService : Service() {
    companion object {
        const val ACTION_START_LISTENING = "com.sajat.hangasszisztens.START_LISTENING"
        const val ACTION_MACRO_COMMAND = "com.sajat.hangasszisztens.MACRO_COMMAND"
        const val ACTION_HUD_UPDATE = "com.sajat.hangasszisztens.HUD_UPDATE"
        const val EXTRA_HUD_STATE = "hud_state"
        const val EXTRA_HUD_RMS = "hud_rms"
        const val EXTRA_AUTO_GREETING = "auto_greeting"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "neo_ch"
        private val crashHandlerInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

        internal fun installCrashHandler(context: Context) {
            if (!crashHandlerInstalled.compareAndSet(false, true)) return
            val appContext = context.applicationContext
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                val log = android.util.Log.getStackTraceString(error)
                val saved = appContext.getSharedPreferences("neo_prefs", Context.MODE_PRIVATE)
                    .edit().putString("last_crash_log", log).commit()
                if (!saved) android.util.Log.e("NEO", "Az összeomlási napló mentése sikertelen")
                val restart = PendingIntent.getForegroundService(
                    appContext,
                    901,
                    Intent(appContext, FloatingAssistantService::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                (appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                    .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1_000L, restart)
                if (previousHandler != null) previousHandler.uncaughtException(thread, error)
                else error.printStackTrace()
            }
        }
    }

    private enum class AssistantState { IDLE, WAKE_WORD_LISTENING, ACTIVE_LISTENING, PROCESSING, SPEAKING }

    private lateinit var wm: WindowManager; private lateinit var root: FrameLayout; private lateinit var icon: ReactorHudView; private lateinit var closeBtn: TextView
    private lateinit var cameraBtn: TextView
    private lateinit var bubble: TextView
    private lateinit var params: WindowManager.LayoutParams; private lateinit var closeParams: WindowManager.LayoutParams
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var sr: SpeechRecognizer? = null; private var tts: TextToSpeech? = null; private val h = Handler(Looper.getMainLooper()); private val hist = ArrayDeque<String>()
    private val pendingSpeech = ArrayDeque<String>()
    private var ttsReady = false
    private var historyUpdatedAt = 0L
    private lateinit var audioManager: AudioManager
    private var mediaSession: MediaSession? = null
    private var pendingSrStart = false
    private var recognitionActive = false
    private var consecutiveRecognitionErrors = 0
    private var activeListening = false
    private var thinking = false
    private var restartAfterSpeech = false
    private var scoReceiverRegistered = false
    private var originalAudioMode = AudioManager.MODE_NORMAL
    private var originalSpeakerphoneOn = false
    private var originalCommunicationDevice: AudioDeviceInfo? = null
    private var docked = true; private var right = true; private var listening = false; private var speaking = false; private var sw = 1080; private var sh = 2400
    @Volatile private var assistantState = AssistantState.IDLE
    @Volatile private var lastSpeechEventAt = SystemClock.elapsedRealtime()
    private val watchdog = object : Runnable {
        override fun run() {
            if (assistantState != AssistantState.SPEAKING &&
                assistantState != AssistantState.PROCESSING &&
                SystemClock.elapsedRealtime() - lastSpeechEventAt >= 12_000L
            ) {
                android.util.Log.w("NEO", "A hangfelismerő watchdog újraépíti a felismerőt")
                restartSr()
            }
            h.postDelayed(this, 12_000L)
        }
    }
    private val activeListeningTimeout = Runnable {
        activeListening = false
        updateHudAnimation()
    }
    private val recognitionStartTimeout = Runnable {
        if (recognitionActive && !listening) {
            recognitionActive = false
            showBubble("A hangfelismerő nem válaszol.")
            val recognizer = sr
            sr = null
            recognizer?.destroy()
            scheduleRecognitionRetry()
        }
    }
    private val hideBubble = Runnable {
        if (::bubble.isInitialized) {
            bubble.animate().alpha(0f).setDuration(500L).withEndAction {
                bubble.visibility = View.GONE
            }.start()
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
            android.util.Log.e("NEO", "A lebegő ablak vagy a mikrofon engedélye hiányzik")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("NEO aktív")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )

        if (intent?.action == ACTION_START_LISTENING) {
            tts?.stop()
            speaking = false
            restartAfterSpeech = false
            activateListeningNow()
        } else startSr()
        if (intent?.getBooleanExtra(EXTRA_AUTO_GREETING, false) == true) {
            say("NEO csatlakozott. Miben segíthetek?")
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        installCrashHandler(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        originalAudioMode = audioManager.mode
        originalSpeakerphoneOn = audioManager.isSpeakerphoneOn
        originalCommunicationDevice = if (
            Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) audioManager.communicationDevice else null
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "NEO",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        if (!Settings.canDrawOverlays(this) ||
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.e("NEO", "Indítás megszakítva: hiányzó lebegő ablak- vagy mikrofonengedély")
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
                    ttsReady = true
                    while (pendingSpeech.isNotEmpty()) enqueueSpeech(pendingSpeech.removeFirst())
                }
            }
        }
        root = FrameLayout(this)
        icon = ReactorHudView(this)
        root.addView(icon, FrameLayout.LayoutParams(-1, -1))
        cameraBtn = TextView(this).apply {
            text = "📷"
            textSize = 17f
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#EE06152F"))
                setStroke(dp(1f), Color.parseColor("#FF00E5FF"))
            }
            setOnClickListener {
                tts?.stop()
                speaking = false
                restartAfterSpeech = false
                captureImageAndAsk()
            }
        }
        root.addView(cameraBtn, FrameLayout.LayoutParams(dp(34f), dp(34f), Gravity.BOTTOM or Gravity.END))
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
        dock(); touch(); initSr(); initMediaSession()
        h.postDelayed(watchdog, 12_000L)
    }
    private fun hudBackground() = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(Color.parseColor("#AA060B19")); setStroke(dp(1f), Color.parseColor("#7700E676")) }
    private fun dock() { docked = true; cameraBtn.visibility = View.GONE; root.background = null; icon.setHudState(true, currentHudState()); params.width = dp(22f); params.height = dp(145f); params.x = if (right) sw - dp(22f) else 0; try { wm.updateViewLayout(root, params) } catch (_: Exception) {} }
    private fun undock() {
        docked = false
        root.background = hudBackground()
        icon.setHudState(false, currentHudState())
        params.width = dp(76f)
        params.height = dp(76f)
        cameraBtn.visibility = View.VISIBLE
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
                    if (!drag && (abs(dx) >= 15 || abs(dy) >= 15)) { drag = true; if (docked) undock(); closeBtn.visibility = View.VISIBLE }
                    if (drag) { params.x = (sx + dx.toInt()).coerceIn(0, sw - params.width); params.y = (sy + dy.toInt()).coerceIn(dp(24f), sh - params.height - dp(24f)); try { wm.updateViewLayout(root, params) } catch (_: Exception) {} }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    closeBtn.visibility = View.GONE
                    if (abs(e.rawX - tx) < 15f && abs(e.rawY - ty) < 15f) {
                        tts?.stop()
                        speaking = false
                        restartAfterSpeech = false
                        if (docked) undock()
                        activateListeningNow()
                    } else if (drag) {
                        if (e.rawY > sh - dp(125f) && abs(e.rawX - sw / 2f) < dp(75f)) stopSelf()
                        else if (e.rawX < 65 || e.rawX > sw - 65) { right = e.rawX >= sw / 2f; dock() }
                    }
                    true
                }
                else -> false
            }
        }
    }
    private fun initSr() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            android.util.Log.e("NEO", "A készüléken nem érhető el hangfelismerő")
            showBubble("Nincs elérhető hangfelismerő.")
            return
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        sr = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p0: Bundle?) {
                if (sr !== recognizer) return
                lastSpeechEventAt = SystemClock.elapsedRealtime()
                h.removeCallbacks(recognitionStartTimeout)
                listening = true
                icon.alpha = 0.7f
                updateHudAnimation()
                showBubble("Hallgatlak...")
            }
            override fun onBeginningOfSpeech() {
                lastSpeechEventAt = SystemClock.elapsedRealtime()
            }
            override fun onRmsChanged(p0: Float) {
                lastSpeechEventAt = SystemClock.elapsedRealtime()
                icon.setRms(p0)
                sendHudUpdate(rms = p0)
            }
            override fun onBufferReceived(p0: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (sr !== recognizer) return
                lastSpeechEventAt = SystemClock.elapsedRealtime()
                listening = false
                icon.alpha = 1f
                updateHudAnimation()
            }
            override fun onError(p0: Int) {
                if (sr !== recognizer) return
                h.removeCallbacks(recognitionStartTimeout)
                lastSpeechEventAt = SystemClock.elapsedRealtime()
                listening = false
                recognitionActive = false
                icon.alpha = 1f
                updateHudAnimation()
                android.util.Log.w("NEO", "A hangfelismerés leállt hibával: $p0")
                showBubble("Mikrofon hiba: $p0")
                scheduleRecognitionRetry()
            }
            override fun onResults(b: Bundle?) {
                if (sr !== recognizer) return
                h.removeCallbacks(recognitionStartTimeout)
                listening = false
                recognitionActive = false
                consecutiveRecognitionErrors = 0
                icon.alpha = 1f
                updateHudAnimation()
                val result = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (result != null) handleRecognition(result) else scheduleWakeListening()
            }
            override fun onPartialResults(results: Bundle?) {
                lastSpeechEventAt = SystemClock.elapsedRealtime()
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.let(::showBubble)
            }
            override fun onEvent(p0: Int, p1: Bundle?) {
                lastSpeechEventAt = SystemClock.elapsedRealtime()
            }
        })
    }
    private fun initMediaSession() {
        mediaSession = MediaSession(this, "NEO").apply {
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
                        tts?.stop()
                        speaking = false
                        restartAfterSpeech = false
                        activateListeningNow()
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
            android.util.Log.e("NEO", "Hangfelismerés nem indítható mikrofonengedély nélkül")
            showBubble("Mikrofonengedély szükséges.")
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
            android.util.Log.e("NEO", "Bluetooth hangútvonal nem érhető el", e)
            beginSr()
        }
    }

    private fun restartSr() {
        pendingSrStart = false
        h.removeCallbacks(scoStartTimeout)
        h.removeCallbacks(recognitionStartTimeout)
        if (listening) {
            try { sr?.cancel() } catch (e: Exception) {
                android.util.Log.w("NEO", "A korábbi hangfelismerés megszakítása sikertelen", e)
            }
        }
        sr?.destroy()
        sr = null
        listening = false
        recognitionActive = false
        lastSpeechEventAt = SystemClock.elapsedRealtime()
        initSr()
        startSr()
        updateHudAnimation()
    }

    private fun handleRecognition(raw: String) {
        val command = NeoAssistantRules.stripWakeWord(raw)
        if (command != null) {
            if (command.isEmpty()) {
                activateListeningNow()
                return
            }
            runCmd(command)
            scheduleWakeListening(recreateRecognizer = true)
            return
        }
        if (activeListening) runCmd(raw)
        scheduleWakeListening()
    }

    private fun activateListeningNow() {
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
        thinking = false
        lastSpeechEventAt = SystemClock.elapsedRealtime()
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
        val delays = longArrayOf(400L, 800L, 1_500L)
        val delayMillis = delays[consecutiveRecognitionErrors.coerceAtMost(delays.lastIndex)]
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
            android.util.Log.e("NEO", "Hangfelismerő nem áll rendelkezésre")
            return
        }
        try {
            recognitionActive = true
            lastSpeechEventAt = SystemClock.elapsedRealtime()
            updateHudAnimation()
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hu-HU")
            })
            h.removeCallbacks(recognitionStartTimeout)
            h.postDelayed(recognitionStartTimeout, 10_000L)
        }
        catch (e: Exception) {
            h.removeCallbacks(recognitionStartTimeout)
            recognitionActive = false
            android.util.Log.e("NEO", "Hangfelismerés indítása sikertelen", e)
            showBubble("Mikrofon hiba: ${e.message ?: "indítás sikertelen"}")
            scheduleRecognitionRetry()
        }
    }
    private fun say(t: String) {
        h.post {
            showBubble(t)
            val speech = t.trim().takeIf { it.isNotEmpty() } ?: return@post
            if (ttsReady) enqueueSpeech(speech) else pendingSpeech.addLast(speech)
        }
    }

    private fun enqueueSpeech(speech: String) {
        val sentences = Regex("[^.!?]+[.!?]+|[^.!?]+$")
            .findAll(speech)
            .map { it.value.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        sentences.forEachIndexed { index, sentence ->
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = tts?.speak(sentence, queueMode, null, "neo-${System.nanoTime()}")
            if (result == TextToSpeech.ERROR) {
                android.util.Log.e("NEO", "A TTS nem tudta sorba állítani a beszédet")
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
            h.postDelayed(hideBubble, 5_000L)
        } catch (e: WindowManager.BadTokenException) {
            android.util.Log.w("NEO", "A HUD szövegbuborék nem jeleníthető meg", e)
        }
    }
    private fun captureImageAndAsk() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            say("A kamerához engedélyezd a hozzáférést a vezérlőpulton.")
            return
        }
        val apiKey = try {
            ApiKeyStore.read(this).orEmpty()
        } catch (error: Exception) {
            android.util.Log.e("NEO", "A mentett API-kulcs nem olvasható", error)
            say("A mentett API-kulcs nem olvasható.")
            return
        }
        if (!apiKey.startsWith("sk-or-")) {
            say("A kamera AI-látásához OpenRouter API-kulcs szükséges.")
            return
        }
        try {
            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("NEO aktív")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } catch (error: SecurityException) {
            android.util.Log.e("NEO", "A kamerás előtérszolgáltatás nem indítható", error)
            say("A kamerához szükséges szolgáltatásengedély hiányzik.")
            return
        }
        showBubble("Képet készítek...")
        captureCameraImage { jpeg ->
            val encoded = Base64.encodeToString(jpeg, Base64.NO_WRAP)
            thread {
                try {
                    val answer = GeminiApi.generateVisionContent(
                        apiKey,
                        "Írd le röviden magyarul, mi látható a képen. Ne találj ki részleteket.",
                        encoded
                    )
                    say(answer)
                } catch (error: Exception) {
                    android.util.Log.e("NEO", "A kamerakép elemzése sikertelen", error)
                    say("A képet nem sikerült elemezni: ${error.message ?: "AI-hiba"}.")
                }
            }
        }
    }

    private fun captureCameraImage(onCaptured: (ByteArray) -> Unit) {
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull()
        if (cameraId == null) {
            say("Kamera nem érhető el.")
            return
        }

        val outputSize = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(android.graphics.ImageFormat.JPEG)
            ?.filter { it.width <= 1280 && it.height <= 720 }
            ?.maxByOrNull { it.width.toLong() * it.height }
            ?: android.util.Size(1280, 720)
        val cameraThread = HandlerThread("NEO-camera").apply { start() }
        val cameraHandler = Handler(cameraThread.looper)
        val reader = ImageReader.newInstance(
            outputSize.width,
            outputSize.height,
            android.graphics.ImageFormat.JPEG,
            1
        )
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var delivered = false
        fun cleanup() {
            session?.close()
            camera?.close()
            reader.close()
            cameraThread.quitSafely()
        }
        reader.setOnImageAvailableListener({ imageReader ->
            val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val bytes = image.planes[0].buffer.let { buffer ->
                ByteArray(buffer.remaining()).also(buffer::get)
            }
            image.close()
            if (!delivered) {
                delivered = true
                cleanup()
                onCaptured(bytes)
            }
        }, cameraHandler)
        cameraHandler.postDelayed({
            if (!delivered) {
                delivered = true
                cleanup()
                say("A kamera időtúllépés miatt nem készített képet.")
            }
        }, 8_000L)
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(openedCamera: CameraDevice) {
                    camera = openedCamera
                    openedCamera.createCaptureSession(
                        listOf(reader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configuredSession: CameraCaptureSession) {
                                session = configuredSession
                                val request = openedCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                    .apply { addTarget(reader.surface) }
                                    .build()
                                configuredSession.capture(request, null, cameraHandler)
                            }

                            override fun onConfigureFailed(failedSession: CameraCaptureSession) {
                                if (!delivered) {
                                    delivered = true
                                    cleanup()
                                    say("A kamera előkészítése sikertelen.")
                                }
                            }
                        },
                        cameraHandler
                    )
                }

                override fun onDisconnected(disconnectedCamera: CameraDevice) {
                    disconnectedCamera.close()
                    if (!delivered) {
                        delivered = true
                        cleanup()
                        say("A kamera megszakadt.")
                    }
                }

                override fun onError(errorCamera: CameraDevice, errorCode: Int) {
                    errorCamera.close()
                    if (!delivered) {
                        delivered = true
                        cleanup()
                        say("Kamera hiba: $errorCode.")
                    }
                }
            }, cameraHandler)
        } catch (error: Exception) {
            cleanup()
            android.util.Log.e("NEO", "Kamerakép készítése sikertelen", error)
            say("A kamera nem indítható.")
        }
    }

    private fun app(pkg: String, n: String) { val i = packageManager.getLaunchIntentForPackage(pkg); if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); say("$n megnyitása.") } else say("$n nincs telepítve.") }
    private fun torch(on: Boolean) {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            say("A zseblámpához engedélyezd a kamera-hozzáférést a vezérlőpulton.")
            return
        }
        try {
            val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId == null) {
                say("Ezen a készüléken nem érhető el zseblámpa.")
                return
            }
            cameraManager.setTorchMode(cameraId, on)
            say(if (on) "Zseblámpa bekapcsolva." else "Zseblámpa kikapcsolva.")
        } catch (error: Exception) {
            android.util.Log.e("NEO", "A zseblámpa nem kapcsolható", error)
            say("A zseblámpa nem kapcsolható.")
        }
    }
    private fun runCmd(raw: String) {
        val c = raw.lowercase(Locale("hu", "HU"))
        when {
            c.startsWith("jegyezd meg ") -> {
                val fact = raw.trim().substringAfter(' ').substringAfter(' ').trim()
                if (fact.isEmpty()) {
                    say("Mit jegyezzek meg?")
                } else {
                    val preferences = getSharedPreferences("neo_prefs", MODE_PRIVATE)
                    val facts = preferences.getString("facts", "").orEmpty()
                        .lines().filter(String::isNotBlank).toMutableList()
                    facts.add(fact)
                    if (!preferences.edit().putString("facts", facts.distinct().joinToString("\n")).commit()) {
                        say("A tényt nem sikerült elmenteni.")
                    } else say("Megjegyeztem: $fact")
                }
            }
            "nézd meg" in c || "kamera" in c || "mit látsz" in c -> captureImageAndAsk()
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
                        android.util.Log.w("NEO", "Navigáció nem indítható", e)
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
                        android.util.Log.e("NEO", "Az időzítő nem indítható", error)
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
                        android.util.Log.e("NEO", "Az ébresztő nem indítható", error)
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
                    android.util.Log.e("NEO", "Az emlékeztető nem indítható", error)
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
            android.util.Log.e("NEO", "A mentett API-kulcs nem olvasható", error)
            say("A mentett API-kulcs nem olvasható. Nyisd meg a vezérlőpultot.")
            return
        }
        if (k.isEmpty()) { say("Ezt hallottam: $q. Add meg az API-kulcsot a vezérlőpulton."); return }
        thinking = true
        updateHudAnimation()
        say("Pillanat...")
        thread {
            try {
                val preferences = getSharedPreferences("neo_prefs", MODE_PRIVATE)
                val facts = preferences.getString("facts", "").orEmpty().trim()
                val (prompt, conversationMemory) = synchronized(hist) {
                    val now = System.currentTimeMillis()
                    if (now - historyUpdatedAt > 10 * 60 * 1000L) hist.clear()
                    hist.addLast("Felhasználó: $q")
                    historyUpdatedAt = now
                    Pair(q, hist.toList().dropLast(1))
                }
                val childFriendly = NeoAssistantRules.isChildFriendlyTopic(q)
                val systemPrompt = buildString {
                    append(
                        if (childFriendly) {
                            "Te NEO vagy. Egy 6–8 éves gyerekhez beszélsz: légy vidám, kedves, egyszerű, " +
                                "szakszavak nélküli, rövid és biztonságos. Markdown nélkül válaszolj."
                        } else {
                            "Te NEO vagy, egy magyar, tárgyilagos és gyakorlatias hangasszisztens. " +
                                "Alapértelmezésben adj rövid, 1–3 mondatos választ. " +
                                "Markdown és felsorolás nélkül írj, hogy a magyar TTS természetesen olvassa fel."
                        }
                    )
                    if (facts.isNotEmpty()) append("\nTartósan megjegyzett tények:\n").append(facts)
                    if (conversationMemory.isNotEmpty()) {
                        append("\nAz előző legfeljebb hat üzenetpár:\n")
                            .append(conversationMemory.joinToString("\n"))
                    }
                }
                val ans = GeminiApi.generateContent(k, prompt, systemPrompt).replace("*", "").trim()
                synchronized(hist) {
                    while (hist.size >= 12) {
                        hist.removeFirst()
                        if (hist.isNotEmpty()) hist.removeFirst()
                    }
                    hist.addLast("NEO: $ans")
                    historyUpdatedAt = System.currentTimeMillis()
                }
                h.post { thinking = false; updateHudAnimation() }
                say(ans)
            } catch (e: Exception) {
                android.util.Log.e("NEO", "AI-kérés sikertelen", e)
                h.post {
                    thinking = false
                    updateHudAnimation()
                    Toast.makeText(this, e.message ?: "Gemini kérés sikertelen.", Toast.LENGTH_LONG).show()
                }
                say("Gemini hiba, ellenőrizd a netet vagy a kulcsot.")
            }
        }
    }
    private fun currentHudState(): NeoHudState = when {
        speaking -> NeoHudState.SPEAKING
        thinking -> NeoHudState.THINKING
        activeListening -> NeoHudState.LISTENING
        else -> NeoHudState.IDLE
    }

    private fun updateHudAnimation() {
        synchronized(this) {
            assistantState = when {
                speaking -> AssistantState.SPEAKING
                thinking -> AssistantState.PROCESSING
                activeListening -> AssistantState.ACTIVE_LISTENING
                listening || recognitionActive || pendingSrStart -> AssistantState.WAKE_WORD_LISTENING
                else -> AssistantState.IDLE
            }
        }
        if (::icon.isInitialized) icon.setHudState(docked, currentHudState())
        sendHudUpdate()
    }

    private fun sendHudUpdate(rms: Float? = null) {
        val intent = Intent(ACTION_HUD_UPDATE)
            .setPackage(packageName)
            .putExtra(EXTRA_HUD_STATE, currentHudState().name)
        if (rms != null) intent.putExtra(EXTRA_HUD_RMS, rms)
        sendBroadcast(intent)
    }
    private inner class ReactorHudView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var isDocked = true
        private var hudState = NeoHudState.IDLE
        private var pulse = 0.5f
        private var rms = 0f
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

        fun setHudState(docked: Boolean, state: NeoHudState) {
            isDocked = docked
            hudState = state
            pulseAnimator.duration = when (state) {
                NeoHudState.IDLE -> 1_700L
                NeoHudState.LISTENING -> 620L
                NeoHudState.THINKING -> 1_050L
                NeoHudState.SPEAKING -> 850L
            }
            invalidate()
        }

        fun setRms(value: Float) {
            rms = value.coerceIn(0f, 10f) / 10f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val accent = when (hudState) {
                NeoHudState.IDLE -> Color.rgb(90, 220, 232)
                NeoHudState.LISTENING -> Color.rgb(0, 230, 118)
                NeoHudState.THINKING -> Color.rgb(0, 229, 255)
                NeoHudState.SPEAKING -> Color.rgb(255, 179, 0)
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
                val coreRadius = radius * (0.17f + pulse * 0.035f + rms * 0.04f)
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
        h.removeCallbacks(recognitionStartTimeout)
        h.removeCallbacks(hideBubble)
        h.removeCallbacks(scoStartTimeout)
        h.removeCallbacks(watchdog)
        if (scoReceiverRegistered) {
            try { unregisterReceiver(scoReceiver) } catch (e: IllegalArgumentException) { android.util.Log.w("NEO", "SCO receiver was already unregistered", e) }
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
