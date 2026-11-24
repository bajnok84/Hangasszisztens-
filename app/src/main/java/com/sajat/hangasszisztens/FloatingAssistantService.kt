package com.sajat.hangasszisztens

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class FloatingAssistantService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var root: FrameLayout
    private lateinit var icon: ImageView
    private lateinit var closeBtn: TextView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var closeParams: WindowManager.LayoutParams
    private var sr: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isDocked = true
    private var isRight = true
    private var listening = false
    private var sw = 1080
    private var sh = 2400

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotif()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sw = resources.displayMetrics.widthPixels
        sh = resources.displayMetrics.heightPixels

        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale("hu", "HU") }
        initSpeech()

        root = FrameLayout(this)
        icon = ImageView(this)
        root.addView(icon)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        params = WindowManager.LayoutParams(dp(14f), dp(145f), type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sw - dp(14f)
            y = sh / 3
        }

        closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E53935"))
            }
            visibility = android.view.View.GONE
        }
        closeParams = WindowManager.LayoutParams(dp(58f), dp(58f), type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(48f)
        }

        wm.addView(closeBtn, closeParams)
        wm.addView(root, params)
        applyDockedState()
        setupTouch()
    }

    private fun applyDockedState() {
        isDocked = true
        icon.setImageDrawable(GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14f).toFloat()
            setColor(Color.parseColor("#00E5FF"))
            setStroke(dp(1f), Color.WHITE)
        })
        params.width = dp(14f)
        params.height = dp(145f)
        params.x = if (isRight) sw - dp(14f) else 0
        try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
    }

    private fun applyIconState() {
        isDocked = false
        icon.setImageResource(R.drawable.jarvis_3d_icon)
        params.width = dp(76f)
        params.height = dp(76f)
        if (params.x > sw - dp(80f)) params.x = sw - dp(92f)
        if (params.x < dp(10f)) params.x = dp(16f)
        try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
    }

    private fun setupTouch() {
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var drag = false
        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY; drag = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx; val dy = e.rawY - ty
                    if (!drag && (abs(dx) > 35 || abs(dy) > 35)) {
                        drag = true
                        if (isDocked) applyIconState()
                        closeBtn.visibility = android.view.View.VISIBLE
                    }
                    if (drag) {
                        params.x = (sx + dx.toInt()).coerceIn(0, sw - params.width)
                        params.y = (sy + dy.toInt()).coerceIn(dp(24f), sh - params.height - dp(24f))
                        try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
                        val nearClose = e.rawY > sh - dp(125f) && abs(e.rawX - sw / 2f) < dp(75f)
                        closeBtn.scaleX = if (nearClose) 1.25f else 1.0f
                        closeBtn.scaleY = if (nearClose) 1.25f else 1.0f
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    closeBtn.visibility = android.view.View.GONE
                    closeBtn.scaleX = 1.0f; closeBtn.scaleY = 1.0f
                    if (drag) {
                        if (e.rawY > sh - dp(125f) && abs(e.rawX - sw / 2f) < dp(75f)) {
                            stopSelf()
                        } else if (e.rawX < 65 || e.rawX > sw - 65) {
                            isRight = e.rawX >= sw / 2f
                            applyDockedState()
                        }
                    } else {
                        if (isDocked) {
                            applyIconState()
                            startListening()
                        } else {
                            if (listening) stopListening() else startListening()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun initSpeech() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        sr = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p0: Bundle?) { listening = true; icon.alpha = 0.75f }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(p0: Float) {}
                override fun onBufferReceived(p0: ByteArray?) {}
                override fun onEndOfSpeech() { listening = false; icon.alpha = 1.0f }
                override fun onError(p0: Int) { listening = false; icon.alpha = 1.0f }
                override fun onResults(b: Bundle?) {
                    listening = false; icon.alpha = 1.0f
                    val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.lowercase(Locale("hu", "HU")) ?: return
                    handleCommand(text)
                }
                override fun onPartialResults(p0: Bundle?) {}
                override fun onEvent(p0: Int, p1: Bundle?) {}
            })
        }
    }

    private fun startListening() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hu-HU")
        }
        try { sr?.startListening(i) } catch (_: Exception) {}
    }

    private fun stopListening() {
        listening = false; icon.alpha = 1.0f
        try { sr?.stopListening() } catch (_: Exception) {}
    }

    private fun handleCommand(cmd: String) {
        val reply = when {
            "idő" in cmd || "hány óra" in cmd -> "Pontos idő: " + SimpleDateFormat("HH:mm", Locale("hu", "HU")).format(Date())
            "dátum" in cmd || "milyen nap" in cmd -> "Ma " + SimpleDateFormat("yyyy. MMMM d., EEEE", Locale("hu", "HU")).format(Date()) + " van."
            else -> "Ezt hallottam: $cmd"
        }
        tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private fun startForegroundNotif() {
        val chId = "jarvis_ch"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(chId, "HangAsszisztens", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(this, chId).setContentTitle("J.A.R.V.I.S. aktív").setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        startForeground(1, n)
    }

    override fun onDestroy() {
        try { wm.removeView(root); wm.removeView(closeBtn) } catch (_: Exception) {}
        sr?.destroy(); tts?.shutdown()
        super.onDestroy()
    }
}
