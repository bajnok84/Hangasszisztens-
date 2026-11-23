package com.sajat.hangasszisztens

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.view.*
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.Locale
import kotlin.math.abs

class FloatingAssistantService : Service(), TextToSpeech.OnInitListener {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var icon: ImageView
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isListening = false

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        tts = TextToSpeech(this, this)
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_widget, null)
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, flag, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = 50; y = 300
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.addView(floatingView, params)
        icon = floatingView.findViewById(R.id.floating_icon)
        setupSpeechRecognizer()

        icon.setOnTouchListener(object : View.OnTouchListener {
            private var ix = 0; private var iy = 0; private var itx = 0f; private var ity = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; itx = e.rawX; ity = e.rawY; return true }
                    MotionEvent.ACTION_MOVE -> { params.x = ix + (e.rawX - itx).toInt(); params.y = iy + (e.rawY - ity).toInt(); windowManager.updateViewLayout(floatingView, params); return true }
                    MotionEvent.ACTION_UP -> { if (abs(e.rawX - itx) < 15 && abs(e.rawY - ity) < 15) toggleListening(); return true }
                }
                return false
            }
        })
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
                }
                override fun onError(err: Int) {
                    icon.clearColorFilter()
                    isListening = false
                    if (err != SpeechRecognizer.ERROR_NO_MATCH && err != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        Toast.makeText(this@FloatingAssistantService, "Mikrofon hiba ($err)", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                    Toast.makeText(this@FloatingAssistantService, "Ezt mondtad: $text", Toast.LENGTH_LONG).show()
                    speak("Ezt mondtad: $text")
                }
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
        }
    }

    private fun toggleListening() {
        if (isListening) {
            speechRecognizer?.stopListening()
            isListening = false
            icon.clearColorFilter()
            return
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
            .setContentTitle("HangAsszisztens aktív")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(1, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        tts?.shutdown()
        if (::floatingView.isInitialized) windowManager.removeView(floatingView)
    }
}
