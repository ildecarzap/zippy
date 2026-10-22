package com.cobrix.pocket.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.random.Random

/**
 * Vista ligera de animación de confetis para celebrar recepciones de pago en tiempo real.
 * Funciona de manera autónoma con aceleración por hardware y 60fps sin dependencias externas.
 */
class ConfettiView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Particle(
        var x: Float,
        var y: Float,
        var size: Float,
        var speedY: Float,
        var speedX: Float,
        var rotation: Float,
        var rotationSpeed: Float,
        var color: Int,
        var isCircle: Boolean,
        var alpha: Int = 255
    )

    private val particles = mutableListOf<Particle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private val rectF = RectF()

    private val confettiColors = intArrayOf(
        Color.parseColor("#00D084"), // Emerald Green
        Color.parseColor("#00B4D8"), // Cyan / Blue
        Color.parseColor("#FFB020"), // Gold / Yellow
        Color.parseColor("#FF6B6B"), // Coral / Red
        Color.parseColor("#9D4EDD"), // Violet
        Color.parseColor("#48CAE4"), // Sky blue
        Color.parseColor("#FFD166")  // Warm Amber
    )

    fun startConfetti(count: Int = 55) {
        post {
            particles.clear()
            val w = width.toFloat().coerceAtLeast(400f)
            val h = height.toFloat().coerceAtLeast(600f)

            for (i in 0 until count) {
                particles.add(
                    Particle(
                        x = Random.nextFloat() * w,
                        y = -Random.nextFloat() * (h * 0.4f),
                        size = 14f + Random.nextFloat() * 14f,
                        speedY = 4f + Random.nextFloat() * 7f,
                        speedX = -2f + Random.nextFloat() * 4f,
                        rotation = Random.nextFloat() * 360f,
                        rotationSpeed = -5f + Random.nextFloat() * 10f,
                        color = confettiColors[Random.nextInt(confettiColors.size)],
                        isCircle = Random.nextBoolean()
                    )
                )
            }

            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 4500
                interpolator = LinearInterpolator()
                addUpdateListener {
                    updateParticles()
                    invalidate()
                }
                start()
            }
        }
    }

    private fun updateParticles() {
        val w = width.toFloat()
        val h = height.toFloat()

        for (p in particles) {
            p.y += p.speedY
            p.x += p.speedX
            p.rotation += p.rotationSpeed

            // Efecto de ondulación lateral
            p.speedX += (-0.2f + Random.nextFloat() * 0.4f)
            p.speedX = p.speedX.coerceIn(-3.5f, 3.5f)

            // Desvanecimiento suave hacia el final
            if (p.y > h * 0.75f) {
                val remainingRatio = (h - p.y) / (h * 0.25f)
                p.alpha = (remainingRatio.coerceIn(0f, 1f) * 255).toInt()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (particles.isEmpty()) return

        for (p in particles) {
            if (p.alpha <= 0) continue
            paint.color = p.color
            paint.alpha = p.alpha

            canvas.save()
            canvas.translate(p.x, p.y)
            canvas.rotate(p.rotation)

            if (p.isCircle) {
                canvas.drawCircle(0f, 0f, p.size / 2, paint)
            } else {
                rectF.set(-p.size / 2, -p.size / 4, p.size / 2, p.size / 4)
                canvas.drawRoundRect(rectF, 4f, 4f, paint)
            }

            canvas.restore()
        }
    }

    fun stopConfetti() {
        animator?.cancel()
        animator = null
        particles.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopConfetti()
    }
}
