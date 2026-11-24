package com.sajat.hangasszisztens
import android.animation.ValueAnimator; import android.app.*; import android.content.*; import android.graphics.*; import android.graphics.drawable.GradientDrawable; import android.hardware.camera2.CameraManager; import android.net.Uri; import android.os.*; import android.provider.*; import android.speech.*; import android.speech.tts.TextToSpeech; import android.view.*; import android.view.animation.AccelerateDecelerateInterpolator; import android.widget.*; import androidx.core.app.NotificationCompat; import java.text.SimpleDateFormat; import java.util.*; import kotlin.concurrent.thread; import kotlin.math.abs; import kotlin.math.min
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.core.content.ContextCompat
class FloatingAssistantService : Service() {
    private lateinit var wm: WindowManager; private lateinit var root: FrameLayout; private lateinit var icon: ReactorHudView; private lateinit var closeBtn: TextView
    private lateinit var params: WindowManager.LayoutParams; private lateinit var closeParams: WindowManager.LayoutParams
    private var sr: SpeechRecognizer? = null; private var tts: TextToSpeech? = null; private val h = Handler(Looper.getMainLooper()); private val hist = ArrayDeque<String>()
    private lateinit var audioManager: AudioManager
    private var mediaSession: MediaSession? = null
    private var pendingSrStart = false
    private var originalAudioMode = AudioManager.MODE_NORMAL
    private var originalSpeakerphoneOn = false
    private var originalCommunicationDevice: AudioDeviceInfo? = null
    private var docked = true; private var right = true; private var listening = false; private var speaking = false; private var sw = 1080; private var sh = 2400
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
    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        originalAudioMode = audioManager.mode
        originalSpeakerphoneOn = audioManager.isSpeakerphoneOn
        originalCommunicationDevice = if (Build.VERSION.SDK_INT >= 31) audioManager.communicationDevice else null
        ContextCompat.registerReceiver(this, scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED)
        val ch = "jarvis_ch"; if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(ch, "J.A.R.V.I.S.", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, NotificationCompat.Builder(this, ch).setContentTitle("J.A.R.V.I.S. aktív").setSmallIcon(android.R.drawable.ic_btn_speak_now).build())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager; sw = resources.displayMetrics.widthPixels; sh = resources.displayMetrics.heightPixels
        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                tts?.apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    language = Locale("hu", "HU")
                    setOnUtteranceProgressListener(object : TextToSpeech.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            h.post { speaking = true; updateHudAnimation() }
                        }
                        override fun onDone(utteranceId: String?) {
                            h.post { speaking = false; updateHudAnimation() }
                        }
                        override fun onError(utteranceId: String?) {
                            h.post { speaking = false; updateHudAnimation() }
                        }
                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            h.post { speaking = false; updateHudAnimation() }
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
        closeParams = WindowManager.LayoutParams(dp(58f), dp(58f), t, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(48f) }
        wm.addView(closeBtn, closeParams); wm.addView(root, params); dock(); touch(); initSr(); initMediaSession()
    }
    private fun hudBackground() = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(Color.parseColor("#AA060B19")); setStroke(dp(1f), Color.parseColor("#7700E676")) }
    private fun dock() { docked = true; root.background = null; icon.setHudState(true, listening || speaking || pendingSrStart); params.width = dp(22f); params.height = dp(145f); params.x = if (right) sw - dp(22f) else 0; try { wm.updateViewLayout(root, params) } catch (_: Exception) {} }
    private fun undock() {
        docked = false
        root.background = hudBackground()
        icon.setHudState(false, listening || speaking || pendingSrStart)
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
                    } else { tts?.stop(); if (docked) { undock(); startSr() } else if (listening) stopSr() else startSr() }
                    true
                }
                else -> false
            }
        }
    }
    private fun initSr() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        sr = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p0: Bundle?) { listening = true; icon.alpha = 0.7f; updateHudAnimation() }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(p0: Float) {}
                override fun onBufferReceived(p0: ByteArray?) {}
                override fun onEndOfSpeech() { listening = false; icon.alpha = 1f; updateHudAnimation() }
                override fun onError(p0: Int) { listening = false; icon.alpha = 1f; updateHudAnimation() }
                override fun onResults(b: Bundle?) { listening = false; icon.alpha = 1f; updateHudAnimation(); b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { runCmd(it) } }
                override fun onPartialResults(p0: Bundle?) {}
                override fun onEvent(p0: Int, p1: Bundle?) {}
            })
        }
    }
    private fun initMediaSession() {
        mediaSession = MediaSession(this, "J.A.R.V.I.S.").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (event != null && event.keyCode in listOf(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY) && event.action == KeyEvent.ACTION_DOWN) {
                        startSr()
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
                override fun onPlay() = startSr()
                override fun onPause() = startSr()
            })
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE).setState(PlaybackState.STATE_PAUSED, 0, 0f).build())
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
        if (listening || pendingSrStart) return
        tts?.stop()
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
    private fun beginSr() {
        try { sr?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hu-HU") }) }
        catch (e: Exception) { android.util.Log.e("J.A.R.V.I.S.", "Hangfelismerés indítása sikertelen", e) }
    }
    private fun stopSr() { pendingSrStart = false; h.removeCallbacks(scoStartTimeout); listening = false; icon.alpha = 1f; updateHudAnimation(); try { sr?.stopListening() } catch (_: Exception) {} }
    private fun say(t: String) { h.post { tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "j") } }
    private fun app(pkg: String, n: String) { val i = packageManager.getLaunchIntentForPackage(pkg); if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); say("$n megnyitása.") } else say("$n nincs telepítve.") }
    private fun torch(on: Boolean) { try { val cm = getSystemService(CAMERA_SERVICE) as CameraManager; cm.setTorchMode(cm.cameraIdList[0], on); say(if (on) "Zseblámpa bekapcsolva." else "Zseblámpa kikapcsolva.") } catch (_: Exception) { say("Zseblámpa hiba.") } }
    private fun runCmd(raw: String) {
        val c = raw.lowercase(Locale("hu", "HU"))
        when {
            "idő" in c || "hány óra" in c -> say("Pontos idő: " + SimpleDateFormat("HH:mm", Locale("hu", "HU")).format(Date()))
            "dátum" in c || "milyen nap" in c -> say("Ma " + SimpleDateFormat("yyyy. MMMM d., EEEE", Locale("hu", "HU")).format(Date()) + " van.")
            "zseblámpa" in c -> torch("ki" !in c && "le" !in c)
            "youtube" in c -> app("com.google.android.youtube", "YouTube")
            "spotify" in c -> app("com.spotify.music", "Spotify")
            "waze" in c -> app("com.waze", "Waze")
            "térkép" in c || "maps" in c -> app("com.google.android.apps.maps", "Térkép")
            "naptár" in c && "emlékeztess" !in c -> app("com.google.android.calendar", "Naptár")
            "beállítások" in c -> { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); say("Beállítások.") }
            "navigálj" in c || "útvonal" in c -> { val d = raw.replace(Regex("(?i)(navigálj|útvonal|ide|oda|:)"), "").trim(); try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(d))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); say("Navigáció: $d") } catch (_: Exception) { say("Nincs navigáció.") } }
            "tárcsáz" in c || "hívd" in c -> { startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); say("Tárcsázó.") }
            "időzítő" in c -> { val m = Regex("\\d+").find(c)?.value?.toIntOrNull() ?: 5; try { startActivity(Intent(AlarmClock.ACTION_SET_TIMER).apply { putExtra(AlarmClock.EXTRA_LENGTH, m * 60); putExtra(AlarmClock.EXTRA_SKIP_UI, true); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }); say("$m perces időzítő.") } catch (_: Exception) {} }
            "ébresztő" in c || "ébressz" in c -> { val n = Regex("\\d+").findAll(c).map { it.value.toInt() }.toList(); try { startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply { putExtra(AlarmClock.EXTRA_HOUR, n.getOrNull(0) ?: 7); putExtra(AlarmClock.EXTRA_MINUTES, n.getOrNull(1) ?: 0); putExtra(AlarmClock.EXTRA_SKIP_UI, true); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }); say("Ébresztő beállítva.") } catch (_: Exception) {} }
            "emlékeztess" in c || "jegyzet" in c -> { val t = raw.replace(Regex("(?i)(emlékeztess|hogy|jegyzet|:)"), "").trim().ifEmpty { raw }; try { startActivity(Intent(Intent.ACTION_INSERT).apply { data = CalendarContract.Events.CONTENT_URI; putExtra(CalendarContract.Events.TITLE, t); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }); say("Emlékeztető: $t") } catch (_: Exception) {} }
            "keress rá" in c -> { val q = raw.replace(Regex("(?i)(keress rá|:)"), "").trim(); startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); say("Keresés: $q") }
            else -> askAi(raw)
        }
    }
    private fun askAi(q: String) {
        val k = getSharedPreferences("jarvis_prefs", MODE_PRIVATE).getString("gemini_key", "") ?: ""
        if (k.isEmpty()) { say("Ezt hallottam: $q. Add meg az API-kulcsot a vezérlőpulton."); return }
        say("Pillanat...")
        thread {
            try {
                if (hist.size >= 6) hist.removeFirst(); hist.addLast("F: $q")
                val prompt = "Te J.A.R.V.I.S. magyar hangasszisztens vagy. Válaszolj 2 rövid, tömör mondatban, formázás nélkül.\n" + hist.joinToString("\n")
                val ans = GeminiApi.generateContent(k, prompt).replace("*", "").trim()
                if (hist.size >= 6) hist.removeFirst(); hist.addLast("A: $ans"); say(ans)
            } catch (e: Exception) {
                android.util.Log.e("J.A.R.V.I.S.", "Gemini kérés sikertelen az összes modellen", e)
                h.post { Toast.makeText(this, e.message ?: "Gemini kérés sikertelen.", Toast.LENGTH_LONG).show() }
                say("Gemini hiba, ellenőrizd a netet vagy a kulcsot.")
            }
        }
    }
    private fun updateHudAnimation() {
        if (::icon.isInitialized) icon.setHudState(docked, listening || speaking || pendingSrStart)
    }
    private inner class ReactorHudView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var isDocked = true
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

        fun setHudState(docked: Boolean, active: Boolean) {
            isDocked = docked
            pulseAnimator.duration = if (active) 620L else 1_700L
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val cyan = Color.rgb(0, 229, 255)
            val green = Color.rgb(0, 230, 118)
            val alpha = (70 + 75 * pulse).toInt().coerceIn(0, 255)

            paint.style = Paint.Style.FILL
            paint.shader = RadialGradient(
                cx, cy, dp(if (isDocked) 24f else 42f) * (0.72f + pulse * 0.45f),
                intArrayOf(Color.argb(alpha, 0, 230, 118), Color.argb(0, 0, 229, 255)),
                null,
                Shader.TileMode.CLAMP
            )
            if (isDocked) {
                val track = RectF(cx - dp(4f), dp(8f).toFloat(), cx + dp(4f), height - dp(8f).toFloat())
                paint.shader = null
                paint.color = Color.argb(50, 0, 230, 118)
                canvas.drawRoundRect(track, dp(5f).toFloat(), dp(5f).toFloat(), paint)
                paint.shader = RadialGradient(
                    cx, cy, dp(24f) * (0.72f + pulse * 0.45f),
                    intArrayOf(Color.argb(alpha, 0, 230, 118), Color.argb(0, 0, 229, 255)),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, dp(24f) * (0.72f + pulse * 0.45f), paint)
                paint.shader = null
                paint.color = Color.argb(alpha, 0, 230, 118)
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
                paint.color = Color.argb(alpha, 0, 229, 255)
                canvas.drawArc(bounds, -65f, 105f, false, paint)
                canvas.drawArc(bounds, 112f, 112f, false, paint)
                canvas.drawArc(bounds, 245f, 55f, false, paint)
                paint.style = Paint.Style.FILL
                val coreRadius = radius * (0.17f + pulse * 0.035f)
                paint.shader = RadialGradient(
                    cx, cy, coreRadius * 1.8f,
                    intArrayOf(Color.WHITE, green, cyan, Color.argb(0, 0, 229, 255)),
                    floatArrayOf(0f, 0.35f, 0.62f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, coreRadius * 1.8f, paint)
                paint.shader = null
                paint.color = Color.argb(alpha, 0, 230, 118)
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
        h.removeCallbacks(scoStartTimeout)
        try { unregisterReceiver(scoReceiver) } catch (e: IllegalArgumentException) { android.util.Log.w("J.A.R.V.I.S.", "SCO receiver was already unregistered", e) }
        mediaSession?.run { isActive = false; release() }
        mediaSession = null
        if (Build.VERSION.SDK_INT >= 31) {
            val previousDevice = originalCommunicationDevice
            if (previousDevice == null) audioManager.clearCommunicationDevice() else audioManager.setCommunicationDevice(previousDevice)
        }
        else { audioManager.isBluetoothScoOn = false; audioManager.stopBluetoothSco() }
        audioManager.mode = originalAudioMode
        audioManager.isSpeakerphoneOn = originalSpeakerphoneOn
        icon.stopAnimation()
        try { wm.removeView(root); wm.removeView(closeBtn) } catch (_: Exception) {}
        sr?.destroy(); tts?.shutdown(); super.onDestroy()
    }
}
