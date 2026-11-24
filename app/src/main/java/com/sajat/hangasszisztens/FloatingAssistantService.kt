package com.sajat.hangasszisztens

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class FloatingAssistantService : Service(), TextToSpeech.OnInitListener {
    private lateinit var windowManager: WindowManager
    private lateinit var rootContainer: FrameLayout
    private lateinit var edgeStrip: View
    private lateinit var icon: ImageView
    private lateinit var closeView: TextView
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isListening = false
    private var isDocked = true
    private var isRightEdge = true
    private var screenWidth = 1080
    private var screenHeight = 2400
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var params: WindowManager.LayoutParams

    private val autoDockRunnable = Runnable {
        if (!isListening && !isDocked) if (e.rawX < 70 || e.rawX > screenWidth - 70) dockToEdge()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        tts = TextToSpeech(this, this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(dm)
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels

        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE

        // Also piros X bezaro kor
        closeView = TextView(this).apply {
            text = "✕"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D32F2F"))
                setStroke(4, Color.parseColor("#FF8A80"))
            }
            visibility = View.GONE
        }
        val closeParams = WindowManager.LayoutParams(160, 160, flag, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 120
        }
        windowManager.addView(closeView, closeParams)

        // Fo kontener
        rootContainer = FrameLayout(this)

        // 1. Vekony el-csik (kb. 1.5 mm szeles, 2.5 cm hosszu)
        edgeStrip = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(14f), dp(145f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.parseColor("#CC00E5FF"))
                setStroke(1, Color.parseColor("#80E0F7FA"))
            }
        }

        // 2. Teljes 3D J.A.R.V.I.S. ikon
        icon = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(76f), dp(76f))
            setImageResource(R.drawable.jarvis_3d_icon)
            visibility = View.GONE
        }

        rootContainer.addView(edgeStrip)
        rootContainer.addView(icon)

        params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, flag, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - dp(14f)
            y = screenHeight / 3
        }
        windowManager.addView(rootContainer, params)
        setupSpeechRecognizer()

        rootContainer.setOnTouchListener(object : View.OnTouchListener {
            private var ix = 0; private var iy = 0; private var itx = 0f; private var ity = 0f
            private var isDragging = false
            private var longPressTriggered = false
            private val longPressRunnable = Runnable {
                if (!isDragging) {
                    longPressTriggered = true
                    vibrate(60)
                    startActivity(Intent(this@FloatingAssistantService, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        handler.removeCallbacks(autoDockRunnable)
                        ix = params.x; iy = params.y; itx = e.rawX; ity = e.rawY
                        isDragging = false
                        longPressTriggered = false
                        handler.postDelayed(longPressRunnable, 750)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - itx).toInt()
                        val dy = (e.rawY - ity).toInt()
                        if (abs(dx) > 35 || abs(dy) > 15) {
                            if (!isDragging) {
                                isDragging = true
                                expandIcon(startMic = false)
                            }
                            handler.removeCallbacks(longPressRunnable)
                            closeView.visibility = View.VISIBLE
                        }
                        params.x = ix + dx
                        params.y = iy + dy
                        windowManager.updateViewLayout(rootContainer, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(longPressRunnable)
                        closeView.visibility = View.GONE
                        if (longPressTriggered) return true
                        if (!isDragging) {
                            vibrate(30)
                            if (isDocked) {
                                expandIcon(startMic = true)
                            } else {
                                toggleListening()
                            }
                        } else if (e.rawY > screenHeight - 350 && abs(e.rawX - screenWidth / 2f) < 220) {
                            vibrate(80)
                            Toast.makeText(this@FloatingAssistantService, "Asszisztens bezárva", Toast.LENGTH_SHORT).show()
                            stopSelf()
                        } else {
                            isRightEdge = (e.rawX >= screenWidth / 2f)
                            if (e.rawX < 70 || e.rawX > screenWidth - 70) dockToEdge()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun expandIcon(startMic: Boolean) {
        isDocked = false
        edgeStrip.visibility = View.GONE
        icon.visibility = View.VISIBLE
        params.x = if (isRightEdge) screenWidth - dp(84f) else dp(8f)
        windowManager.updateViewLayout(rootContainer, params)
        if (startMic) {
            toggleListening()
        } else {
            handler.postDelayed(autoDockRunnable, 86400000)
        }
    }

    private fun if (e.rawX < 70 || e.rawX > screenWidth - 70) dockToEdge() {
        isDocked = true
        icon.visibility = View.GONE
        edgeStrip.visibility = View.VISIBLE
        params.x = if (isRightEdge) screenWidth - dp(14f) else 0
        windowManager.updateViewLayout(rootContainer, params)
    }

    private fun vibrate(ms: Long) {
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib?.vibrate(ms)
        }
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {
                    icon.setColorFilter(Color.parseColor("#FF5252"))
                    Toast.makeText(this@FloatingAssistantService, "Hallgatlak...", Toast.LENGTH_SHORT).show()
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {
                    icon.clearColorFilter()
                    isListening = false
                    handler.postDelayed(autoDockRunnable, 86400000)
                }
                override fun onError(err: Int) {
                    icon.clearColorFilter()
                    isListening = false
                    if (err != SpeechRecognizer.ERROR_NO_MATCH && err != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        Toast.makeText(this@FloatingAssistantService, "Mikrofon hiba ($err)", Toast.LENGTH_SHORT).show()
                    }
                    handler.postDelayed(autoDockRunnable, 86400000)
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                    handleVoiceCommand(text)
                    handler.postDelayed(autoDockRunnable, 86400000)
                }
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
        }
    }

    private fun handleVoiceCommand(rawText: String) {
        val lower = rawText.lowercase(Locale("hu", "HU"))
        val reply = when {
            lower.contains("mennyi az idő") || lower.contains("hány óra") -> {
                val time = SimpleDateFormat("HH:mm", Locale("hu", "HU")).format(Date())
                "Pontos idő: $time."
            }
            lower.contains("mai dátum") || lower.contains("milyen nap") -> {
                val date = SimpleDateFormat("yyyy. MMMM d., EEEE", Locale("hu", "HU")).format(Date())
                "Ma $date van."
            }
            lower.contains("zseblámpa be") || lower.contains("kapcsold be a lámpát") -> {
                setTorch(true)
                "Zseblámpa bekapcsolva."
            }
            lower.contains("zseblámpa ki") || lower.contains("kapcsold ki a lámpát") -> {
                setTorch(false)
                "Zseblámpa kikapcsolva."
            }
            else -> "Ezt mondtad: $rawText"
        }
        Toast.makeText(this, reply, Toast.LENGTH_LONG).show()
        speak(reply)
    }

    private fun setTorch(state: Boolean) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camId = cm.cameraIdList.firstOrNull() ?: return
            cm.setTorchMode(camId, state)
        } catch (_: Exception) {}
    }

    private fun toggleListening() {
        if (isListening) {
            speechRecognizer?.stopListening(); isListening = false; icon.clearColorFilter(); return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hu-HU")
        }
        isListening = true
        speechRecognizer?.startListening(intent)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale("hu", "HU")
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt1")
    }

    private fun startForegroundNotification() {
        val chId = "assistant_ch"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(chId, "Asszisztens", NotificationManager.IMPORTANCE_LOW))
        }
        val notif = NotificationCompat.Builder(this, chId)
            .setContentTitle("J.A.R.V.I.S. aktív")
            .setContentText("Érintsd meg a szélső kék csíkot a beszédhez")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(1, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        tts?.shutdown()
        if (::rootContainer.isInitialized) windowManager.removeView(rootContainer)
        if (::closeView.isInitialized) windowManager.removeView(closeView)
    }
}
