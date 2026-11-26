package com.sajat.hangasszisztens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val diagnosticDots = mutableMapOf<String, View>()
    private lateinit var hudView: NeoHudView
    private var hudReceiverRegistered = false
    private val hudReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != FloatingAssistantService.ACTION_HUD_UPDATE) return
            intent.getStringExtra(FloatingAssistantService.EXTRA_HUD_STATE)
                ?.let { state -> NeoHudState.values().firstOrNull { it.name == state } }
                ?.let(hudView::setState)
            if (intent.hasExtra(FloatingAssistantService.EXTRA_HUD_RMS)) {
                hudView.setRms(intent.getFloatExtra(FloatingAssistantService.EXTRA_HUD_RMS, 0f))
            }
        }
    }

    companion object {
        private const val REQUEST_PERMISSION_SETUP = 1
        private const val REQUEST_START_PERMISSIONS = 2
        private const val REQUEST_OVERLAY_PERMISSION = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#060B19"))
        }
        root.addView(HudBackgroundView(this), FrameLayout.LayoutParams(-1, -1))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(40), dp(28), dp(32))
        }

        fun button(
            label: String,
            borderColor: Int = Color.parseColor("#00E676"),
            fillColor: Int = Color.argb(150, 9, 24, 38),
            textColor: Int = Color.WHITE,
            onClick: () -> Unit
        ) = Button(this).apply {
            text = label
            textSize = 14f
            letterSpacing = 0.035f
            setTextColor(textColor)
            setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
            val shape = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(fillColor)
                setStroke(dp(1), borderColor)
            }
            val mask = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.WHITE)
            }
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(110, 0, 230, 118)),
                shape,
                mask
            )
            layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply {
                setMargins(0, dp(9), 0, dp(9))
            }
            setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> view.animate()
                        .scaleX(0.975f).scaleY(0.975f).setDuration(90L)
                        .setInterpolator(AccelerateDecelerateInterpolator()).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate()
                        .scaleX(1f).scaleY(1f).setDuration(140L)
                        .setInterpolator(AccelerateDecelerateInterpolator()).start()
                }
                false
            }
            setOnClickListener {
                val vibrator = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
                if (vibrator.hasVibrator()) {
                    vibrator.vibrate(
                        android.os.VibrationEffect.createOneShot(
                            35L,
                            android.os.VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )
                }
                onClick()
            }
        }

        content.addView(TextView(this).apply {
            text = "NEO\nVEZÉRLŐPULT"
            textSize = 24f
            letterSpacing = 0.08f
            setTextColor(Color.parseColor("#E8FFF5"))
            setShadowLayer(dp(4).toFloat(), 0f, dp(2).toFloat(), Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        })
        hudView = NeoHudView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(176)).apply {
                setMargins(0, 0, 0, dp(12))
            }
        }
        content.addView(hudView)
        content.addView(diagnosticsPanel())

        content.addView(button(
            "VERZIÓ ${BuildConfig.VERSION_NAME} · FRISSÍTÉSI NAPLÓ",
            borderColor = Color.parseColor("#00E5FF")
        ) {
            AlertDialog.Builder(this)
                .setTitle("Frissítési napló · ${BuildConfig.VERSION_NAME}")
                .setMessage(
                    "1.4.4 (aktuális)\n" +
                    "• A mikrofonfigyelés csak felhasználói indítás után kezdődik; az app megnyitása nem indítja el.\n" +
                    "• Bluetooth-kapcsolat és headset-/médiagomb nem indítja el automatikusan.\n" +
                    "• Induláskori hangos üdvözlés eltávolítva.\n\n" +
                    "1.4.2\n" +
                        "• Android 14+ mikrofonos előtérszolgáltatás a hangfelismeréshez.\n" +
                        "• Javított lebegőgomb-érintés, magyar hallgatási és mikrofonhiba-visszajelzés.\n" +
                        "• A hangfelismerés már nem kényszeríti ki az offline nyelvi modellt.\n" +
                        "• A mikrofon az indításhoz kötelező; a Bluetooth, kamera és értesítések külön engedélyezhetők.\n\n" +
                        "1.4.1\n" +
                        "• Keskeny, dokkolható lebegő vezérlő és 3D HUD-ikon.\n" +
                        "• Bluetooth-hangútvonal támogatása.\n\n" +
                        "1.3\n" +
                        "• API-kulcs titkosított tárolása és kizárása a biztonsági mentésekből.\n" +
                        "• Időzítő- és ébresztőértékek ellenőrzése, hibajelzések.\n" +
                        "• Makró-broadcast tájékoztató és headset-vevő korlátozása.\n\n" +
                        "1.2\n" +
                        "• Gemini-válaszok és modell-visszaesési kezelés.\n" +
                        "• Lebegő HUD és magyar hangasszisztens-parancsok fejlesztése.\n\n" +
                        "1.1\n" +
                        "• APK-aláírási és buildfolyamat-javítások.\n\n" +
                        "1.0\n" +
                        "• Az első NEO hangasszisztens, lebegő ikon és alapvető hangparancsok."
                )
                .setPositiveButton("MAKRÓ-INTEGRÁCIÓ") { _, _ ->
                    showMacroIntegrationInfo()
                }
                .setNegativeButton("BEZÁRÁS", null)
                .show()
        })

        content.addView(button(
            "MAKRÓ BROADCAST BEÁLLÍTÁSA",
            borderColor = Color.parseColor("#00E5FF")
        ) {
            showMacroIntegrationInfo()
        })

        content.addView(button("1. MIKROFON + KAMERA + BLUETOOTH + ÉRTESÍTÉSEK") {
            ActivityCompat.requestPermissions(
                this,
                startupPermissions().toTypedArray(),
                REQUEST_PERMISSION_SETUP
            )
        })
        content.addView(button(
            "2. LEBEGŐ ABLAK ENGEDÉLY",
            borderColor = Color.parseColor("#00E5FF")
        ) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        })
        content.addView(button(
            "3. AKKUMULÁTOR-KORLÁT FELOLDÁSA",
            borderColor = Color.parseColor("#00E5FF")
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                }
            }
        })

        val input = EditText(this).apply {
            hint = "API-kulcs (OpenRouter / Gemini / OpenAI / Groq)"
            setHintTextColor(Color.parseColor("#91A7B8"))
            setTextColor(Color.WHITE)
            setShadowLayer(dp(1).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
            val savedKey = try {
                ApiKeyStore.read(this@MainActivity)
            } catch (error: Exception) {
                android.util.Log.e("NEO", "A mentett API-kulcs nem olvasható", error)
                Toast.makeText(this@MainActivity, "A mentett API-kulcs nem olvasható.", Toast.LENGTH_LONG).show()
                null
            }
            setText(savedKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(190, 8, 17, 33))
                setStroke(dp(1), Color.parseColor("#8800E5FF"))
            }
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(-1, dp(54)).apply {
                setMargins(0, dp(14), 0, dp(8))
            }
        }
        content.addView(input)
        content.addView(TextView(this).apply {
            text = "A kamera AI-látása OpenRouter API-kulccsal működik; a kép elemzésre külső AI-szolgáltatóhoz kerül."
            textSize = 12f
            setTextColor(Color.parseColor("#91A7B8"))
            setPadding(dp(4), dp(2), dp(4), dp(8))
        })

        val status = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(Color.parseColor("#8FFFD0"))
            setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        content.addView(button(
            "API-KULCS MENTÉSE ÉS TESZTELÉSE",
            borderColor = Color.parseColor("#00E5FF")
        ) {
            val key = input.text.toString().trim()
            if (key.length < 10) {
                status.text = "Hiba: Érvénytelen Gemini API-kulcs."
                return@button
            }
            try {
                ApiKeyStore.write(this, key)
            } catch (error: Exception) {
                android.util.Log.e("NEO", "Az API-kulcs mentése sikertelen", error)
                status.text = "Hiba: az API-kulcs biztonságos mentése sikertelen."
                return@button
            }
            status.text = "Mentve! Kapcsolat tesztelése..."
            thread {
                try {
                    val answer = GeminiApi.generateContent(
                        key,
                        "Mondd magyarul 3 szóban: Működik a kapcsolat."
                    )
                    runOnUiThread { status.text = "SIKER: $answer" }
                } catch (error: Exception) {
                    runOnUiThread { status.text = "HIBA: ${error.message}" }
                }
            }
        })
        content.addView(status)
        content.addView(button(
            "▶ ASSZISZTENS INDÍTÁSA",
            fillColor = Color.parseColor("#00E676"),
            textColor = Color.parseColor("#04120C")
        ) {
            startAssistantWhenReady()
        })
        content.addView(button(
            "■ LEÁLLÍTÁS",
            borderColor = Color.parseColor("#FF5277"),
            textColor = Color.parseColor("#FFE7EC")
        ) {
            stopService(Intent(this, FloatingAssistantService::class.java))
        })

        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            child.alpha = 0f
            child.translationY = dp(18).toFloat()
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * 70L)
                .setDuration(320L)
                .start()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!hudReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                hudReceiver,
                android.content.IntentFilter(FloatingAssistantService.ACTION_HUD_UPDATE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            hudReceiverRegistered = true
        }
        refreshDiagnostics()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onPause() {
        if (hudReceiverRegistered) {
            unregisterReceiver(hudReceiver)
            hudReceiverRegistered = false
        }
        super.onPause()
    }

    private fun diagnosticsPanel(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#AA050A18"))
            setStroke(dp(1), Color.parseColor("#5500E5FF"))
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(TextView(this@MainActivity).apply {
            text = "ÉLŐ DIAGNOSZTIKA"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(Color.parseColor("#8FEAFF"))
        })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("MIKROFON", "KAMERA", "HUD", "AKKU").forEach { label ->
                val item = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
                }
                val dot = View(this@MainActivity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#FF5252"))
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                        bottomMargin = dp(4)
                    }
                }
                item.addView(dot)
                item.addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 9f
                    setTextColor(Color.WHITE)
                })
                diagnosticDots[label] = dot
                addView(item)
            }
        })
    }

    private fun refreshDiagnostics() {
        val microphoneReady = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val cameraReady = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hudReady = Settings.canDrawOverlays(this)
        val batteryReady = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            (getSystemService(POWER_SERVICE) as android.os.PowerManager)
                .isIgnoringBatteryOptimizations(packageName)
        mapOf(
            "MIKROFON" to microphoneReady,
            "KAMERA" to cameraReady,
            "HUD" to hudReady,
            "AKKU" to batteryReady
        ).forEach { (label, ready) ->
            (diagnosticDots[label]?.background as? GradientDrawable)?.setColor(
                Color.parseColor(if (ready) "#00E676" else "#FF5252")
            )
        }
    }

    private fun showMacroIntegrationInfo() {
        val action = FloatingAssistantService.ACTION_MACRO_COMMAND
        AlertDialog.Builder(this)
            .setTitle("Makró broadcast")
            .setMessage(
                "Figyelem: a makróparancs broadcastját a készülék más, erre figyelő alkalmazásai is fogadhatják. " +
                    "Csak megbízható automatizáló alkalmazáshoz állíts be fogadót.\n\n" +
                    "A MacroDroidban hozz létre egy „Intent Received” triggert ezzel az eseménnyel. " +
                    "A „command” nevű szöveges extrát használd feltételként, hogy a megfelelő " +
                    "makrót indítsa el. Például: „Neo, makró lámpa fel”.\n\n" +
                    "Esemény: $action\n" +
                    "Extra neve: command\n" +
                    "Extra típusa: szöveg\n\n" +
                    "A NEO ezt az eseményt küldi ki; a MacroDroid-makrókat külön kell " +
                    "létrehozni és az extra értékére szűrni."
            )
            .setNeutralButton("ESEMÉNY MÁSOLÁSA") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Makró broadcast", action))
                Toast.makeText(this, "A broadcast neve a vágólapra került.", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("BEZÁRÁS", null)
            .show()
    }

    private fun startupPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun startAssistantWhenReady() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_START_PERMISSIONS
            )
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ),
                REQUEST_OVERLAY_PERMISSION
            )
            return
        }

        val intent = Intent(this, FloatingAssistantService::class.java)
            .setAction(FloatingAssistantService.ACTION_USER_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION_SETUP) {
            val microphoneGranted = ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            Toast.makeText(
                this,
                if (microphoneGranted) {
                    "A mikrofon engedélyezve. A kamera, Bluetooth és értesítés opcionális."
                } else {
                    "A mikrofonhoz az Android engedélykérése szükséges; ezt az alkalmazás nem kapcsolhatja be helyetted."
                },
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (requestCode != REQUEST_START_PERMISSIONS) return

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            startAssistantWhenReady()
        } else {
            Toast.makeText(
                this,
                "A mikrofon engedélyezése szükséges. Ha az ablak nem jelenik meg, engedélyezd az alkalmazás beállításaiban.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Deprecated("Deprecated in Android, retained for overlay permission settings result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OVERLAY_PERMISSION) return

        if (Settings.canDrawOverlays(this)) {
            startAssistantWhenReady()
        } else {
            Toast.makeText(this, "A lebegő ablak engedélyezése szükséges.", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class HudBackgroundView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#2200E676")
            strokeWidth = dp(1).toFloat()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val centerX = width * 0.82f
            val centerY = height * 0.22f
            val radius = dp(100).toFloat()
            for (scale in floatArrayOf(0.68f, 0.82f, 1f, 1.18f)) {
                canvas.drawCircle(centerX, centerY, radius * scale, paint)
            }
            paint.color = Color.parseColor("#2200E5FF")
            val arcBounds = RectF(
                centerX - radius * 1.18f,
                centerY - radius * 1.18f,
                centerX + radius * 1.18f,
                centerY + radius * 1.18f
            )
            canvas.drawArc(arcBounds, 205f, 72f, false, paint)
            canvas.drawArc(arcBounds, 25f, 48f, false, paint)
            paint.color = Color.parseColor("#1800E676")
            canvas.drawCircle(width * 0.14f, height * 0.78f, dp(78).toFloat(), paint)
        }
    }
}
