package com.cobrix.pocket.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.cobrix.pocket.data.model.UserProfile
import java.net.URLEncoder
import java.util.Locale

object WhatsAppHelper {

    fun generatePaymentMessage(
        profile: UserProfile,
        amountVes: Double,
        amountUsd: Double = 0.0,
        bankName: String = profile.bankName,
        bankCode: String = profile.bankCode
    ): String {
        val vesFormatted = String.format(Locale.US, "%.2f", amountVes)

        return """
            💸 *Datos para Pago Móvil*
            ---------------------------------
            📌 *Monto a transferir:*
            👉 *Bs. $vesFormatted*
            
            🏦 *Banco:* $bankName ($bankCode)
            📱 *Teléfono:* ${profile.phoneNumber}
            🪪 *Cédula/RIF:* ${profile.idCard}
            ---------------------------------
            ⚡ *Cobro solicitado con Zeasy*
        """.trimIndent()
    }

    fun generateBinancePaymentMessage(
        payId: String,
        amountUsd: Double
    ): String {
        val usdFormatted = String.format(Locale.US, "%.2f", amountUsd)
        val idText = if (payId.isNotBlank()) payId else "(Consultar al comercio)"

        return """
            🟡 *Datos para Pago con Binance Pay (USDT)*
            ---------------------------------
            💵 *Monto a transferir:*
            👉 *$ $usdFormatted USDT*
            
            🆔 *Binance Pay ID / Correo:*
            👉 *$idText*
            ---------------------------------
            📸 *Te adjunto el código QR de Binance Pay para escanearlo directo desde tu App de Binance.*
            ⚡ *Cobro solicitado con Zeasy PRO*
        """.trimIndent()
    }

    /**
     * Detecta si WhatsApp estándar o WhatsApp Business está instalado en el dispositivo.
     */
    fun getWhatsAppPackage(context: Context): String? {
        val pm = context.packageManager
        return try {
            pm.getPackageInfo("com.whatsapp", 0)
            "com.whatsapp"
        } catch (_: Exception) {
            try {
                pm.getPackageInfo("com.whatsapp.w4b", 0)
                "com.whatsapp.w4b"
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Convierte de forma segura cualquier URI local (file:// o ruta) en un URI content://
     * accesible por aplicaciones externas a través de FileProvider, evitando FileUriExposedException.
     */
    private fun getShareableUri(context: Context, rawUri: Uri?): Uri? {
        if (rawUri == null) return null
        return try {
            if (rawUri.scheme == "content") {
                rawUri
            } else if (rawUri.scheme == "file") {
                val filePath = rawUri.path ?: return null
                val file = java.io.File(filePath)
                if (file.exists()) {
                    androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                } else {
                    null
                }
            } else {
                val file = java.io.File(rawUri.toString())
                if (file.exists()) {
                    androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                } else {
                    rawUri
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("WhatsAppHelper", "Error convirtiendo URI compartible: ${e.message}")
            null
        }
    }

    fun sendViaWhatsApp(
        context: Context,
        customerPhone: String,
        message: String,
        imageUri: Uri? = null
    ) {
        val targetPackage = getWhatsAppPackage(context)
        val shareableUri = getShareableUri(context, imageUri)

        // Limpiar teléfono (ej. de 04141234567 a 584141234567 si es de Venezuela)
        var cleanPhone = customerPhone.replace(Regex("[^0-9]"), "")
        if (cleanPhone.startsWith("0")) {
            cleanPhone = "58" + cleanPhone.substring(1)
        } else if (!cleanPhone.startsWith("58") && cleanPhone.length == 10) {
            cleanPhone = "58$cleanPhone"
        }

        try {
            if (cleanPhone.isNotEmpty()) {
                // Si el usuario ingresó teléfono específico
                if (shareableUri != null) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/*"
                        putExtra(Intent.EXTRA_STREAM, shareableUri)
                        putExtra(Intent.EXTRA_TEXT, message)
                        putExtra("jid", "$cleanPhone@s.whatsapp.net")
                        if (targetPackage != null) {
                            setPackage(targetPackage)
                        }
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } else {
                    val encodedMessage = URLEncoder.encode(message, "UTF-8")
                    val url = "https://api.whatsapp.com/send?phone=$cleanPhone&text=$encodedMessage"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        if (targetPackage != null) {
                            setPackage(targetPackage)
                        }
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            } else {
                // Sin teléfono (flujo rápido de caja para que el comerciante elija el chat del cliente o grupo)
                if (shareableUri != null) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/*"
                        putExtra(Intent.EXTRA_STREAM, shareableUri)
                        putExtra(Intent.EXTRA_TEXT, message)
                        if (targetPackage != null) {
                            setPackage(targetPackage)
                        }
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } else {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, message)
                        if (targetPackage != null) {
                            setPackage(targetPackage)
                        }
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("WhatsAppHelper", "Error abriendo WhatsApp directo: ${e.message}", e)
            try {
                val genericIntent = Intent(Intent.ACTION_SEND).apply {
                    if (shareableUri != null) {
                        type = "image/*"
                        putExtra(Intent.EXTRA_STREAM, shareableUri)
                        putExtra(Intent.EXTRA_TEXT, message)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } else {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, message)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(genericIntent, "Compartir datos de pago"))
            } catch (ex: Exception) {
                android.util.Log.e("WhatsAppHelper", "Error en chooser genérico: ${ex.message}", ex)
                Toast.makeText(context, "No se pudo compartir la información", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun generateDailySummaryMessage(
        incomeVes: Double,
        expenseVes: Double,
        netVes: Double,
        carrerasCount: Int,
        bcvRate: Double
    ): String {
        val incomeUsd = if (bcvRate > 0) incomeVes / bcvRate else 0.0
        val expenseUsd = if (bcvRate > 0) expenseVes / bcvRate else 0.0
        val netUsd = if (bcvRate > 0) netVes / bcvRate else 0.0

        return """
            📊 *CIERRE DE JORNADA - ZIPPY*
            ---------------------------------
            🚕 *Servicios / Carreras cobradas:* $carrerasCount
            
            🟢 *Total Cobrado:* Bs. ${String.format(Locale.US, "%.2f", incomeVes)} ($${String.format(Locale.US, "%.2f", incomeUsd)})
            🔴 *Total Gastos:* Bs. ${String.format(Locale.US, "%.2f", expenseVes)} ($${String.format(Locale.US, "%.2f", expenseUsd)})
            ---------------------------------
            🏁 *GANANCIA NETA REAL:*
            👉 *Bs. ${String.format(Locale.US, "%.2f", netVes)} ($${String.format(Locale.US, "%.2f", netUsd)})*
            ---------------------------------
            _Reporte generado con Zeasy_
        """.trimIndent()
    }
}
