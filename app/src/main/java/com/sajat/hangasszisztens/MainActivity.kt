package com.sajat.hangasszisztens
import android.Manifest; import android.content.*; import android.graphics.Color; import android.graphics.drawable.GradientDrawable; import android.net.Uri; import android.os.*; import android.provider.Settings; import android.text.InputType; import android.view.Gravity; import android.widget.*; import androidx.appcompat.app.AppCompatActivity; import androidx.core.app.ActivityCompat; import kotlin.concurrent.thread
class MainActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val p = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 56, 48, 56); setBackgroundColor(Color.parseColor("#0A0F1C")) }
        fun btn(t: String, c: String, f: () -> Unit) = Button(this).apply { text = t; setTextColor(Color.WHITE); background = GradientDrawable().apply { cornerRadius = 20f; setColor(Color.parseColor(c)) }; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 12, 0, 12) }; setOnClickListener { f() } }
        root.addView(TextView(this).apply { text = "J.A.R.V.I.S. Vezérlőpult"; textSize = 22f; setTextColor(Color.parseColor("#00E5FF")); gravity = Gravity.CENTER })
        root.addView(btn("1. Mikrofon + Bluetooth + Értesítés", "#1E293B") {
            val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS, "android.permission.POST_NOTIFICATIONS")
            if (Build.VERSION.SDK_INT >= 31) permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)
        })
        root.addView(btn("2. Lebegő ablak engedély", "#1E293B") { if (!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) })
        val inp = EditText(this).apply { hint = "Gemini API-kulcs"; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE); setText(p.getString("gemini_key", "")); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD }
        root.addView(inp)
        val status = TextView(this).apply { text = ""; textSize = 14f; setTextColor(Color.parseColor("#38BDF8")); setPadding(0, 12, 0, 12) }
        root.addView(btn("API-kulcs mentése és tesztelése", "#0284C7") {
            val k = inp.text.toString().trim()
            if (k.length < 10) { status.text = "Hiba: Érvénytelen Gemini API-kulcs."; return@btn }
            p.edit().putString("gemini_key", k).apply()
            status.text = "Mentve! Kapcsolat tesztelése..."
            thread {
                try {
                    val ans = GeminiApi.generateContent(k, "Mondd magyarul 3 szóban: Működik a kapcsolat.")
                    runOnUiThread { status.text = "SIKER: $ans" }
                } catch (e: Exception) { runOnUiThread { status.text = "HIBA: ${e.message}" } }
            }
        })
        root.addView(status)
        root.addView(btn("▶ ASSZISZTENS INDÍTÁSA", "#059669") { val i = Intent(this, FloatingAssistantService::class.java); if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i) })
        root.addView(btn("■ LEÁLLÍTÁS", "#B91C1C") { stopService(Intent(this, FloatingAssistantService::class.java)) })
        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(Color.parseColor("#0A0F1C")) })
    }
}
