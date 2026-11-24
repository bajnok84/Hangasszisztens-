package com.sajat.hangasszisztens
import android.app.*; import android.content.*; import android.graphics.*; import android.graphics.drawable.GradientDrawable; import android.hardware.camera2.CameraManager; import android.net.Uri; import android.os.*; import android.provider.*; import android.speech.*; import android.speech.tts.TextToSpeech; import android.view.*; import android.widget.*; import androidx.core.app.NotificationCompat; import org.json.*; import java.net.*; import java.text.SimpleDateFormat; import java.util.*; import kotlin.concurrent.thread; import kotlin.math.abs
class FloatingAssistantService : Service() {
    private lateinit var wm: WindowManager; private lateinit var root: FrameLayout; private lateinit var icon: ImageView; private lateinit var closeBtn: TextView
    private lateinit var params: WindowManager.LayoutParams; private lateinit var closeParams: WindowManager.LayoutParams
    private var sr: SpeechRecognizer? = null; private var tts: TextToSpeech? = null; private val h = Handler(Looper.getMainLooper()); private val hist = ArrayDeque<String>()
    private var docked = true; private var right = true; private var listening = false; private var sw = 1080; private var sh = 2400
    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()
    override fun onBind(i: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val ch = "jarvis_ch"; if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(ch, "J.A.R.V.I.S.", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, NotificationCompat.Builder(this, ch).setContentTitle("J.A.R.V.I.S. aktív").setSmallIcon(android.R.drawable.ic_btn_speak_now).build())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager; sw = resources.displayMetrics.widthPixels; sh = resources.displayMetrics.heightPixels
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale("hu", "HU") }
        root = FrameLayout(this); icon = ImageView(this); root.addView(icon)
        val t = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        params = WindowManager.LayoutParams(dp(14f), dp(145f), t, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = sw - dp(14f); y = sh / 3 }
        closeBtn = TextView(this).apply { text = "✕"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#E53935")) }; visibility = View.GONE }
        closeParams = WindowManager.LayoutParams(dp(58f), dp(58f), t, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(48f) }
        wm.addView(closeBtn, closeParams); wm.addView(root, params); dock(); touch(); initSr()
    }
    private fun dock() { docked = true; icon.setImageDrawable(GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(Color.parseColor("#00E5FF")); setStroke(dp(1f), Color.WHITE) }); params.width = dp(14f); params.height = dp(145f); params.x = if (right) sw - dp(14f) else


0; try { wm.updateViewLayout(root, params) } catch (_: Exception) {} }
    private fun undock() {
        docked = false
        icon.setImageResource(R.drawable.jarvis_3d_icon)
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
                override fun onReadyForSpeech(p0: Bundle?) { listening = true; icon.alpha = 0.7f }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(p0: Float) {}
                override fun onBufferReceived(p0: ByteArray?) {}
                override fun onEndOfSpeech() { listening = false; icon.alpha = 1f }
                override fun onError(p0: Int) { listening = false; icon.alpha = 1f }
                override fun onResults(b: Bundle?) { listening = false; icon.alpha = 1f; b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { runCmd(it) } }
                override fun onPartialResults(p0: Bundle?) {}
                override fun onEvent(p0: Int, p1: Bundle?) {}
            })
        }
    }
    private fun startSr() { tts?.stop(); try { sr?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hu-HU") }) } catch (_: Exception) {} }
    private fun stopSr() { listening = false; icon.alpha = 1f; try { sr?.stopListening() } catch (_: Exception) {} }
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
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
                val u = URL(if (k.startsWith("AIza")) "$endpoint?key=$k" else endpoint)
                val conn = (u.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    if (!k.startsWith("AIza")) setRequestProperty("x-goog-api-key", k)
                    doOutput = true
                }
                val prompt = "Te J.A.R.V.I.S. magyar hangasszisztens vagy. Válaszolj 2 rövid, tömör mondatban, formázás nélkül.\n" + hist.joinToString("\n")
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    val response = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    val message = "Gemini HTTP $responseCode: $response"
                    android.util.Log.e("J.A.R.V.I.S.", message)
                    h.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
                    say("Gemini HTTP hiba: $responseCode")
                    return@thread
                }
                val ans = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").replace("*", "").trim()
                if (hist.size >= 6) hist.removeFirst(); hist.addLast("A: $ans"); say(ans)
            } catch (e: Exception) {
                android.util.Log.e("J.A.R.V.I.S.", "Gemini kérés sikertelen", e)
                say("Gemini hiba, ellenőrizd a netet vagy a kulcsot.")
            }
        }
    }
    override fun onDestroy() { try { wm.removeView(root); wm.removeView(closeBtn) } catch (_: Exception) {}; sr?.destroy(); tts?.shutdown(); super.onDestroy() }
}
