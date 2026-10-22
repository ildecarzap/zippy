package com.cobrix.pocket.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#B3000000") // 70% negro semitransparente
    }

    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#00B4D8") // Cyan eléctrico del logo
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#00B4D8")
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    val cropRect = RectF()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = (w.coerceAtMost(h) * 0.75f).coerceAtLeast(260f)
        val left = (w - size) / 2f
        val top = (h - size) / 2f
        cropRect.set(left, top, left + size, top + size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Fondo oscuro exterior
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        // 2. Ventana transparente en el centro
        canvas.drawRoundRect(cropRect, 16f, 16f, clearPaint)

        // 3. Borde cyan
        canvas.drawRoundRect(cropRect, 16f, 16f, borderPaint)

        // 4. Esquinas resaltadas de encuadre (como escáner)
        val cornerLen = 36f
        // Superior Izquierda
        canvas.drawLine(cropRect.left, cropRect.top + cornerLen, cropRect.left, cropRect.top, cornerPaint)
        canvas.drawLine(cropRect.left, cropRect.top, cropRect.left + cornerLen, cropRect.top, cornerPaint)
        // Superior Derecha
        canvas.drawLine(cropRect.right - cornerLen, cropRect.top, cropRect.right, cropRect.top, cornerPaint)
        canvas.drawLine(cropRect.right, cropRect.top, cropRect.right, cropRect.top + cornerLen, cornerPaint)
        // Inferior Izquierda
        canvas.drawLine(cropRect.left, cropRect.bottom - cornerLen, cropRect.left, cropRect.bottom, cornerPaint)
        canvas.drawLine(cropRect.left, cropRect.bottom, cropRect.left + cornerLen, cropRect.bottom, cornerPaint)
        // Inferior Derecha
        canvas.drawLine(cropRect.right - cornerLen, cropRect.bottom, cropRect.right, cropRect.bottom, cornerPaint)
        canvas.drawLine(cropRect.right, cropRect.bottom - cornerLen, cropRect.right, cropRect.bottom, cornerPaint)
    }
}
