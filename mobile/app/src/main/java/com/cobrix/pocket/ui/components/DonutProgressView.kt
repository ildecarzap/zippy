package com.cobrix.pocket.ui.components

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.cobrix.pocket.R
import kotlin.math.min

/**
 * DonutProgressView - Widget nativo de progreso circular / dona estilo Soft UI Fintech.
 * Muestra un arco suave con extremos redondeados y el porcentaje numérico en tipografía Sora.
 */
class DonutProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progress: Float = 75f
    private var maxProgress: Float = 100f
    private var strokeWidthPx: Float = 24f

    private var trackColor: Int = Color.parseColor("#33FFFFFF")
    private var progressColorStart: Int = Color.parseColor("#2DD4BF") // Teal brillante
    private var progressColorEnd: Int = Color.parseColor("#14B8A6")   // Menta profundo
    private var textColor: Int = Color.WHITE

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        isFakeBoldText = true
    }

    private val textBounds = Rect()
    private val arcRect = RectF()

    init {
        val density = context.resources.displayMetrics.density
        strokeWidthPx = 10f * density
        trackPaint.strokeWidth = strokeWidthPx
        progressPaint.strokeWidth = strokeWidthPx

        try {
            val typeface = ResourcesCompat.getFont(context, R.font.sora_bold)
            textPaint.typeface = typeface ?: Typeface.DEFAULT_BOLD
        } catch (_: Exception) {
            textPaint.typeface = Typeface.DEFAULT_BOLD
        }
    }

    fun setProgress(value: Float, max: Float = 100f) {
        progress = value.coerceIn(0f, max)
        maxProgress = if (max <= 0f) 100f else max
        invalidate()
    }

    fun setColors(track: Int, start: Int, end: Int, text: Int) {
        trackColor = track
        progressColorStart = start
        progressColorEnd = end
        textColor = text
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val diameter = min(w, h).toFloat()
        val halfStroke = strokeWidthPx / 2f
        val padding = halfStroke + 4f
        val left = (w - diameter) / 2f + padding
        val top = (h - diameter) / 2f + padding
        val right = left + diameter - (padding * 2f)
        val bottom = top + diameter - (padding * 2f)
        arcRect.set(left, top, right, bottom)

        progressPaint.shader = LinearGradient(
            arcRect.left, arcRect.top, arcRect.right, arcRect.bottom,
            progressColorStart, progressColorEnd, Shader.TileMode.CLAMP
        )
        textPaint.textSize = diameter * 0.26f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        trackPaint.color = trackColor

        // 1. Dibujar pista de fondo (360 grados completa)
        canvas.drawArc(arcRect, -90f, 360f, false, trackPaint)

        // 2. Dibujar arco de progreso activo
        val sweepAngle = (progress / maxProgress) * 360f
        if (sweepAngle > 0f) {
            canvas.drawArc(arcRect, -90f, sweepAngle, false, progressPaint)
        }

        // 3. Dibujar porcentaje central en Sora Bold
        val percentText = "${progress.toInt()}%"
        textPaint.color = textColor
        textPaint.getTextBounds(percentText, 0, percentText.length, textBounds)
        val textY = arcRect.centerY() - textBounds.exactCenterY()
        canvas.drawText(percentText, arcRect.centerX(), textY, textPaint)
    }
}

