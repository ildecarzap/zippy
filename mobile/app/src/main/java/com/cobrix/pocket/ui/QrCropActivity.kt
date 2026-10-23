package com.cobrix.pocket.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cobrix.pocket.databinding.ActivityQrCropBinding
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

class QrCropActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrCropBinding
    private var sourceBitmap: Bitmap? = null

    private val matrix = Matrix()
    private val savedMatrix = Matrix()

    private var mode = NONE
    private val startPoint = PointF()
    private val midPoint = PointF()
    private var oldDist = 1f

    private lateinit var scaleDetector: ScaleGestureDetector
    private var scaleFactor = 1.0f

    companion object {
        const val EXTRA_SOURCE_URI = "extra_source_uri"
        const val EXTRA_RESULT_URI = "extra_result_uri"
        const val EXTRA_BANK_CODE = "extra_bank_code"
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrCropBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriString = intent.getStringExtra(EXTRA_SOURCE_URI)
        if (uriString == null) {
            finish()
            return
        }

        loadBitmapFromUri(Uri.parse(uriString))
        setupTouchGestures()

        binding.btnCancelCrop.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        binding.btnSaveCrop.setOnClickListener {
            saveCroppedQr()
        }
    }

    private fun loadBitmapFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                sourceBitmap = BitmapFactory.decodeStream(stream)
            }
            sourceBitmap?.let { bmp ->
                binding.ivSourceImage.setImageBitmap(bmp)
                binding.ivSourceImage.post {
                    centerImageInitial(bmp)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error al cargar imagen", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun centerImageInitial(bmp: Bitmap) {
        val viewWidth = binding.ivSourceImage.width.toFloat()
        val viewHeight = binding.ivSourceImage.height.toFloat()
        val bmpWidth = bmp.width.toFloat()
        val bmpHeight = bmp.height.toFloat()

        if (viewWidth <= 0 || viewHeight <= 0) return

        val scale = (viewWidth / bmpWidth).coerceAtLeast(viewHeight / bmpHeight)
        matrix.setScale(scale, scale)
        val dx = (viewWidth - bmpWidth * scale) / 2f
        val dy = (viewHeight - bmpHeight * scale) / 2f
        matrix.postTranslate(dx, dy)
        binding.ivSourceImage.imageMatrix = matrix
    }

    private fun setupTouchGestures() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(0.4f, 6.0f)
                matrix.postScale(detector.scaleFactor, detector.scaleFactor, detector.focusX, detector.focusY)
                binding.ivSourceImage.imageMatrix = matrix
                return true
            }
        })

        binding.ivSourceImage.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)

            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    savedMatrix.set(matrix)
                    startPoint.set(event.x, event.y)
                    mode = DRAG
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    oldDist = spacing(event)
                    if (oldDist > 10f) {
                        savedMatrix.set(matrix)
                        midPoint(midPoint, event)
                        mode = ZOOM
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    mode = NONE
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == DRAG) {
                        matrix.set(savedMatrix)
                        matrix.postTranslate(event.x - startPoint.x, event.y - startPoint.y)
                    }
                }
            }
            binding.ivSourceImage.imageMatrix = matrix
            true
        }
    }

    private fun spacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return sqrt((x * x + y * y).toDouble()).toFloat()
    }

    private fun midPoint(point: PointF, event: MotionEvent) {
        val x = event.getX(0) + event.getX(1)
        val y = event.getY(0) + event.getY(1)
        point.set(x / 2, y / 2)
    }

    private fun saveCroppedQr() {
        val src = sourceBitmap ?: return
        val cropRect = binding.cropOverlay.cropRect
        if (cropRect.width() <= 0 || cropRect.height() <= 0) {
            Toast.makeText(this, "Error al calcular marco de recorte", Toast.LENGTH_SHORT).show()
            return
        }

        // Mapear coordenadas de pantalla al bitmap original en alta definición usando la inversa de la matriz
        val inverse = Matrix()
        if (!matrix.invert(inverse)) {
            Toast.makeText(this, "Error al procesar matriz de transformación", Toast.LENGTH_SHORT).show()
            return
        }

        val pts = floatArrayOf(
            cropRect.left, cropRect.top,
            cropRect.right, cropRect.bottom
        )
        inverse.mapPoints(pts)

        val x1 = pts[0].coerceIn(0f, src.width.toFloat())
        val y1 = pts[1].coerceIn(0f, src.height.toFloat())
        val x2 = pts[2].coerceIn(0f, src.width.toFloat())
        val y2 = pts[3].coerceIn(0f, src.height.toFloat())

        val cropX = minOf(x1, x2).toInt()
        val cropY = minOf(y1, y2).toInt()
        val cropW = (maxOf(x1, x2) - cropX).toInt().coerceAtMost(src.width - cropX)
        val cropH = (maxOf(y1, y2) - cropY).toInt().coerceAtMost(src.height - cropY)

        if (cropW <= 20 || cropH <= 20) {
            Toast.makeText(this, "Encuadra el código QR dentro del recuadro", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val croppedBitmap = Bitmap.createBitmap(src, cropX, cropY, cropW, cropH)
            val bankCode = intent.getStringExtra(EXTRA_BANK_CODE)?.takeIf { it.isNotEmpty() } ?: "clean"
            val tempFile = File(cacheDir, "crop_${bankCode}_${System.currentTimeMillis()}.png")
            FileOutputStream(tempFile).use { out ->
                croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val resultUri = Uri.fromFile(tempFile)
            val resultIntent = Intent().apply {
                putExtra(EXTRA_RESULT_URI, resultUri.toString())
                putExtra(EXTRA_BANK_CODE, bankCode)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            Toast.makeText(this, "✅ QR encuadrado en alta resolución", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Error al guardar recorte: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
