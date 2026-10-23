package com.cobrix.pocket.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
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
     * Decodifica el contenido de un Bitmap de imagen usando ZXing.
     */
    fun decodeQrFromBitmap(bitmap: Bitmap): String? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val reader = MultiFormatReader()
            val result = reader.decode(binaryBitmap)
            result.text?.trim()
        } catch (e: Exception) {
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
                    "Para aceptar cobros en USDT y Binance Pay, activa la opción exclusiva en Zippy PRO desde Ajustes > Billeteras Digitales."
                )
            }
            return ValidationResult.Valid
        }
    }
}
