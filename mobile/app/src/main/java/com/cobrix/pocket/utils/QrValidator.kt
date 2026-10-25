package com.cobrix.pocket.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

object QrValidator {

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Rejected(val reason: String) : ValidationResult()
        object Undetermined : ValidationResult()
    }

    /**
     * Decodifica el texto contenido en el código QR a partir de su Uri.
     */
    fun decodeQrContent(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val bitmap = BitmapFactory.decodeStream(inputStream) ?: return null
                decodeQrFromBitmap(bitmap)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decodifica el contenido de un Bitmap de imagen usando ZXing con múltiples intentos,
     * binarizadores alternativos y margen de zona de silencio (Quiet Zone).
     */
    fun decodeQrFromBitmap(bitmap: Bitmap): String? {
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
        )
        val reader = MultiFormatReader()

        fun tryDecode(bmp: Bitmap): String? {
            val width = bmp.width
            val height = bmp.height
            val pixels = IntArray(width * height)
            bmp.getPixels(pixels, 0, width, 0, 0, width, height)

            // Intento 1: HybridBinarizer (típico para fotos con gradientes de luz)
            try {
                val source = RGBLuminanceSource(width, height, pixels)
                val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                val result = reader.decode(binaryBitmap, hints)
                val text = result.text?.trim()
                if (!text.isNullOrEmpty()) return text
            } catch (_: Exception) {}

            // Intento 2: GlobalHistogramBinarizer (óptimo para capturas de pantalla y contrastes nítidos)
            try {
                val source = RGBLuminanceSource(width, height, pixels)
                val binaryBitmap = BinaryBitmap(GlobalHistogramBinarizer(source))
                val result = reader.decode(binaryBitmap, hints)
                val text = result.text?.trim()
                if (!text.isNullOrEmpty()) return text
            } catch (_: Exception) {}

            return null
        }

        // Primer intento: imagen directa
        val directResult = tryDecode(bitmap)
        if (directResult != null) return directResult

        // Segundo intento: agregar borde blanco de Quiet Zone (las especificaciones ISO de QR requieren 4 módulos de margen)
        return try {
            val margin = (minOf(bitmap.width, bitmap.height) * 0.12f).toInt().coerceIn(20, 60)
            val paddedBitmap = Bitmap.createBitmap(bitmap.width + margin * 2, bitmap.height + margin * 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(paddedBitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(bitmap, margin.toFloat(), margin.toFloat(), null)
            tryDecode(paddedBitmap)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Verifica si el texto decodificado corresponde a una billetera digital cripto (Binance / Zinli, etc.)
     */
    fun isCryptoWalletPayload(content: String): Boolean {
        val lower = content.lowercase()
        return lower.contains("binance.com") ||
                lower.contains("binance.me") ||
                lower.contains("binance://") ||
                lower.contains("pay.binance") ||
                lower.contains("bpay") ||
                lower.contains("zinli.me") ||
                lower.contains("zinli://") ||
                lower.contains("tether") ||
                lower.contains("usdt") ||
                lower.contains("ethereum:") ||
                lower.contains("bitcoin:")
    }

    /**
     * Valida si un QR recortado es adecuado para la ranura destino (Banco tradicional vs Billetera Binance).
     */
    fun validateForTarget(context: Context, uri: Uri, targetBankCode: String): ValidationResult {
        val content = decodeQrContent(context, uri) ?: return ValidationResult.Undetermined

        if (targetBankCode == "BINANCE") {
            return ValidationResult.Valid
        } else {
            // El destino es un banco tradicional (Banesco, BDV, Ubii, etc.)
            if (isCryptoWalletPayload(content)) {
                return ValidationResult.Rejected(
                    "El código QR seleccionado pertenece a una Billetera Digital (Binance Pay / Cripto) y no corresponde a los datos de Pago Móvil de este banco.\n\n" +
                    "Para aceptar cobros en USDT y Binance Pay, activa la opción exclusiva en Zeasy PRO desde Ajustes > Billeteras Digitales."
                )
            }
            return ValidationResult.Valid
        }
    }
}
