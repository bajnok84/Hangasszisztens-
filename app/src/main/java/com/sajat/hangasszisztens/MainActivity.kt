package com.sajat.hangasszisztens

import android.Manifest
import android.content.Context
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
import androidx.core.app.ActivityCompat
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_PERMISSION_SETUP = 1
        private const val REQUEST_START_PERMISSIONS = 2
        private const val REQUEST_OVERLAY_PERMISSION = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
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
            setOnClickListener { onClick() }
        }

        content.addView(TextView(this).apply {
            text = "J.A.R.V.I.S.\nVEZÉRLŐPULT"
            textSize = 24f
            letterSpacing = 0.08f
            setTextColor(Color.parseColor("#E8FFF5"))
            setShadowLayer(dp(4).toFloat(), 0f, dp(2).toFloat(), Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        })

        content.addView(button("1. MIKROFON + BLUETOOTH + ÉRTESÍTÉS") {
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

        val input = EditText(this).apply {
            hint = "API-kulcs (OpenRouter sk-or-... / Gemini / OpenAI)"
            setHintTextColor(Color.parseColor("#91A7B8"))
            setTextColor(Color.WHITE)
            setShadowLayer(dp(1).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
            setText(preferences.getString("gemini_key", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
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
            preferences.edit().putString("gemini_key", key).apply()
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
    }

    private fun startupPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun startAssistantWhenReady() {
        val missingPermissions = startupPermissions().filter {
            ActivityCompat.checkSelfPermission(this, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
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
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_START_PERMISSIONS) return

        if (startupPermissions().all {
                ActivityCompat.checkSelfPermission(this, it) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        ) {
            startAssistantWhenReady()
        } else {
            Toast.makeText(this, "Az asszisztens indításához szükséges engedélyek hiányoznak.", Toast.LENGTH_LONG).show()
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
