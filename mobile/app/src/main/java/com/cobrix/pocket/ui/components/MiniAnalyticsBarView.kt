package com.cobrix.pocket.ui.components

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.cobrix.pocket.R
import kotlin.math.max

/**
 * MiniAnalyticsBarView - Gráfico de barras nativo ligero estilo Soft Fintech.
 * Muestra la actividad de cobros por periodos con bordes redondeados y gradientes suaves.
 */
class MiniAnalyticsBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var dataValues = floatArrayOf(40f, 65f, 30f, 85f, 50f, 95f, 70f)
    private var dataLabels = arrayOf("L", "M", "M", "J", "V", "S", "D")

    private var barColorStart: Int = Color.parseColor("#3B82F6") // Azul Zafiro
    private var barColorEnd: Int = Color.parseColor("#1D4ED8")
    private var barTrackColor: Int = Color.parseColor("#EEF2F6")
    private var labelColor: Int = Color.parseColor("#64748B")

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 28f
    }

    private val barRect = RectF()

    init {
        try {
            val typeface = ResourcesCompat.getFont(context, R.font.outfit_medium)
            labelPaint.typeface = typeface ?: Typeface.DEFAULT
        } catch (_: Exception) {
            labelPaint.typeface = Typeface.DEFAULT
        }
        trackPaint.color = barTrackColor
    }

    fun setData(values: FloatArray, labels: Array<String>? = null) {
        if (values.isNotEmpty()) {
            dataValues = values
            if (labels != null && labels.size == values.size) {
                dataLabels = labels
            }
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val count = dataValues.size
        if (count == 0) return

        val labelMarginBottom = 40f
        val chartHeight = h - labelMarginBottom
        val totalSpacing = w * 0.4f
        val gap = totalSpacing / (count + 1)
        val barWidth = (w - totalSpacing) / count
        val cornerRadius = barWidth / 2f

        var maxVal = 10f
        for (v in dataValues) {
            if (v > maxVal) maxVal = v
        }

        for (i in 0 until count) {
            val left = gap + i * (barWidth + gap)
            val right = left + barWidth

            // Fondo sutil de pista completa
            barRect.set(left, 10f, right, chartHeight)
            trackPaint.color = barTrackColor
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, trackPaint)

            // Barra de valor activa
            val valHeight = (dataValues[i] / maxVal) * (chartHeight - 10f)
            val top = chartHeight - max(valHeight, cornerRadius * 2)

            barRect.set(left, top, right, chartHeight)
            barPaint.shader = LinearGradient(
                left, top, right, chartHeight,
                barColorStart, barColorEnd, Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint)

            // Etiqueta del día
            if (i < dataLabels.size) {
                labelPaint.color = labelColor
                canvas.drawText(dataLabels[i], (left + right) / 2f, h - 8f, labelPaint)
            }
        }
    }
}

