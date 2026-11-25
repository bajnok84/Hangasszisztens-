package com.sajat.hangasszisztens

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.DashPathEffect
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

enum class NeoHudState { IDLE, LISTENING, THINKING, SPEAKING }

class NeoHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rotation = 0f
    private var pulse = 0f
    private var rms = 0f
    private var hudState = NeoHudState.IDLE
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 7_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotation = it.animatedValue as Float
            pulse = (rotation % 120f) / 120f
            invalidate()
        }
        start()
    }

    fun setState(state: NeoHudState) {
        hudState = state
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
        val radius = min(width, height) * 0.39f
        val stateColor = when (hudState) {
            NeoHudState.IDLE -> Color.rgb(0, 229, 255)
            NeoHudState.LISTENING -> Color.rgb(0, 230, 118)
            NeoHudState.THINKING -> Color.rgb(0, 229, 255)
            NeoHudState.SPEAKING -> Color.rgb(255, 179, 0)
        }
        val glowRadius = radius * (0.25f + rms * 0.35f + pulse * 0.08f)
        paint.style = Paint.Style.FILL
        paint.shader = android.graphics.RadialGradient(
            cx, cy, glowRadius * 2f,
            intArrayOf(stateColor, Color.argb(0, Color.red(stateColor), Color.green(stateColor), Color.blue(stateColor))),
            null,
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius * 2f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        for (index in 0..2) {
            val ringRadius = radius * (0.47f + index * 0.22f)
            paint.strokeWidth = if (index == 1) 3f else 2f
            paint.color = if (index % 2 == 0) Color.rgb(0, 229, 255) else Color.rgb(0, 230, 118)
            paint.pathEffect = DashPathEffect(floatArrayOf(12f + index * 2f, 8f), 0f)
            val bounds = RectF(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
            canvas.save()
            canvas.rotate(if (index % 2 == 0) rotation else -rotation * 0.72f, cx, cy)
            canvas.drawArc(bounds, index * 43f, 248f, false, paint)
            canvas.restore()
        }
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        paint.color = stateColor
        canvas.drawCircle(cx, cy, radius * (0.13f + rms * 0.1f + pulse * 0.015f), paint)
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, radius * 0.055f, paint)
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
