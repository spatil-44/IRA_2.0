package com.neurovox.ira

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

internal class IraAssistantOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardRect = RectF()
    private var animationProgress = 0f
    private var audioLevel = 0f
    private var title = "IRA"
    private var subtitle = "Listening for your command"
    private val animation = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            animationProgress = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        contentDescription = "Ira assistant overlay"
    }

    fun show(title: String, subtitle: String, listening: Boolean) {
        this.title = title
        this.subtitle = subtitle
        if (!animation.isStarted) animation.start()
        if (!listening) audioLevel = 0f
        contentDescription = "$title. $subtitle"
        invalidate()
    }

    fun setAudioLevel(level: Float) {
        audioLevel = level.coerceIn(0f, 1f)
        invalidate()
    }

    fun stop() {
        animation.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cardMargin = 18f * density
        val cardHeight = 88f * density
        val left = cardMargin
        val top = (height - cardHeight) / 2f
        val right = width - cardMargin
        val bottom = top + cardHeight
        cardRect.set(left, top, right, bottom)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(23, 24, 39)
        canvas.drawRoundRect(cardRect, 28f * density, 28f * density, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.shader = SweepGradient(
            cardRect.centerX(),
            cardRect.centerY(),
            intArrayOf(
                Color.rgb(156, 245, 213),
                Color.rgb(193, 173, 255),
                Color.rgb(92, 219, 232),
                Color.rgb(156, 245, 213),
            ),
            null,
        )
        canvas.drawRoundRect(cardRect, 28f * density, 28f * density, paint)
        paint.shader = null
        paint.style = Paint.Style.FILL

        drawOrb(canvas, left + 49f * density, cardRect.centerY())
        drawText(canvas, title, left + 91f * density, cardRect.centerY() - 4f * density, 13f, true)
        drawText(
            canvas,
            subtitle,
            left + 91f * density,
            cardRect.centerY() + 19f * density,
            11f,
            false,
        )
    }

    private fun drawOrb(canvas: Canvas, centerX: Float, centerY: Float) {
        val pulse = (sin(animationProgress * Math.PI * 2).toFloat() + 1f) / 2f
        val radius = (22f + pulse * 2.5f + audioLevel * 5f) * density

        paint.shader = android.graphics.RadialGradient(
            centerX,
            centerY,
            radius * 2.3f,
            intArrayOf(
                Color.argb(115, 156, 245, 213),
                Color.argb(35, 193, 173, 255),
                Color.TRANSPARENT,
            ),
            null,
            android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(centerX, centerY, radius * 2.3f, paint)
        paint.shader = null

        canvas.save()
        canvas.rotate(animationProgress * 360f, centerX, centerY)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = Color.argb(185, 156, 245, 213)
        val orbit = RectF(
            centerX - radius * 1.48f,
            centerY - radius * 1.08f,
            centerX + radius * 1.48f,
            centerY + radius * 1.08f,
        )
        canvas.drawArc(orbit, 195f, 245f, false, paint)
        paint.color = Color.argb(190, 193, 173, 255)
        canvas.drawArc(orbit, 15f, 112f, false, paint)
        canvas.restore()

        paint.style = Paint.Style.FILL
        paint.shader = android.graphics.RadialGradient(
            centerX - radius * 0.28f,
            centerY - radius * 0.35f,
            radius * 1.5f,
            intArrayOf(Color.WHITE, Color.rgb(156, 245, 213), Color.rgb(113, 126, 209)),
            null,
            android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(centerX, centerY, radius, paint)
        paint.shader = null
    }

    private fun drawText(
        canvas: Canvas,
        value: String,
        x: Float,
        baseline: Float,
        textSize: Float,
        strong: Boolean,
    ) {
        paint.color = if (strong) Color.WHITE else Color.rgb(190, 191, 207)
        paint.textSize = textSize * density
        paint.typeface = if (strong) {
            android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        } else {
            android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        }
        paint.style = Paint.Style.FILL
        paint.shader = null
        val maxWidth = width - x - 34f * density
        val visibleText = android.text.TextUtils.ellipsize(
            value,
            android.text.TextPaint(paint),
            maxWidth.coerceAtLeast(0f),
            android.text.TextUtils.TruncateAt.END,
        )
        canvas.drawText(visibleText.toString(), x, baseline, paint)
    }
}
