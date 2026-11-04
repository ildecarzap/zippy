package com.cobrix.pocket.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.cobrix.pocket.data.model.CountryProfile
import com.cobrix.pocket.data.model.CountryProfileCatalog
import com.cobrix.pocket.data.model.UserProfile
import java.net.URLEncoder
import java.util.Locale

object WhatsAppHelper {

    fun generatePaymentMessage(
        profile: UserProfile,
        amountVes: Double,
        amountUsd: Double = 0.0,
        bankName: String = profile.bankName,
        bankCode: String = profile.bankCode,
        countryProfile: CountryProfile = CountryProfileCatalog.VENEZUELA
    ): String {
        val vesFormatted = String.format(Locale.US, "%.2f", amountVes)

        if (!countryProfile.supportsExchangeRate) {
            val sym = countryProfile.localCurrencySymbol
            val (titleLabel, entityLabel, phoneLabel) = when (countryProfile.code) {
                CountryCode.AR -> Triple("Transferir (0% comisión)", "Billetera / Banco:", "Alias / CVU:")
                CountryCode.CO -> Triple("Transferir (Nequi / Bancos)", "Entidad:", "Celular / Cuenta:")
                else -> Triple("Transferencia", "Banco:", "Teléfono / Cuenta:")
            }
            return """
                💸 *Datos para $titleLabel*
                ---------------------------------
                📌 *Monto a transferir:*
                👉 *$sym $vesFormatted*
                
                🏦 *$entityLabel* $bankName
                📱 *$phoneLabel* ${profile.phoneNumber}
                🪪 *${countryProfile.nationalIdLabel}:* ${profile.idCard}
                ---------------------------------
                ⚡ *Cobro solicitado con Zeasy*
            """.trimIndent()
        }

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
     * Mensaje de cobro cordial para cuentas por cobrar / fiados pendientes.
     * Muestra únicamente el saldo en USD (si aplica) y el monto final exacto a transferir,
     * evitando mostrar dos montos en moneda local o textos técnicos de céntimos que confundan al cliente.
     */
    fun generateDebtReminderMessage(
        profile: UserProfile,
        debtorName: String,
        balanceUsd: Double,
        bcvRate: Double,
        recentConcept: String = "",
        bankName: String = profile.bankName,
        bankCode: String = profile.bankCode,
        conciliationCents: Double = 0.01,
        requestedAmountUsd: Double? = null,
        includeConciliationCents: Boolean = true,
        countryProfile: CountryProfile = CountryProfileCatalog.VENEZUELA
    ): String {
        val totalDebtUsd = Math.abs(balanceUsd)
        val targetUsd = (requestedAmountUsd ?: totalDebtUsd).coerceAtLeast(0.01)
        val isPartial = targetUsd < totalDebtUsd - 0.005
        val rate = if (countryProfile.supportsExchangeRate && bcvRate > 0) bcvRate else 1.0

        val totalDebtVes = Math.round(totalDebtUsd * rate * 100.0) / 100.0
        val targetBaseVes = Math.round(targetUsd * rate * 100.0) / 100.0
        val concilVes = if (includeConciliationCents) {
            Math.round((targetBaseVes + conciliationCents) * 100.0) / 100.0
        } else {
            targetBaseVes
        }

        val totalDebtUsdFmt = String.format(Locale.US, "%.2f", totalDebtUsd)
        val totalDebtVesFmt = String.format(Locale.US, "%.2f", totalDebtVes)
        val targetUsdFmt = String.format(Locale.US, "%.2f", targetUsd)
        val concilVesFmt = String.format(Locale.US, "%.2f", concilVes)
        val noteSection = if (recentConcept.isNotBlank()) "📝 *Concepto:* $recentConcept\n" else ""
        val merchant = if (profile.merchantName.isNotBlank()) profile.merchantName else "tu comercio amigo"

        if (!countryProfile.supportsExchangeRate) {
            val sym = countryProfile.localCurrencySymbol
            val amountBlock = if (isPartial) {
                """
                💵 *Saldo total pendiente:* $sym $totalDebtVesFmt
                👉 *Monto exacto a transferir:* *$sym $concilVesFmt*
                """.trimIndent()
            } else {
                """
                👉 *Monto exacto a transferir:* *$sym $concilVesFmt*
                """.trimIndent()
            }

            val (entityTitle, phoneTitle) = when (countryProfile.code) {
                CountryCode.AR -> Pair("Billetera / Banco:", "Alias / CVU:")
                CountryCode.CO -> Pair("Entidad:", "Celular / Cuenta:")
                else -> Pair("Banco:", "Teléfono / Cuenta:")
            }

            return """
                👋 Hola *$debtorName*, un cordial saludo de *$merchant*.
                
                Le compartimos con todo aprecio su detalle de pago:
                $amountBlock
                $noteSection
                🏦 *Datos para ${countryProfile.instantPaymentSystemName}:*
                • *$entityTitle* $bankName
                • *$phoneTitle* ${profile.phoneNumber}
                • *${countryProfile.nationalIdLabel}:* ${profile.idCard}
                
                📸 _Le adjuntamos nuestro código QR para su comodidad al transferir._
                🙏 ¡Muchas gracias por su preferencia!
                ⚡ *Enviado vía Zeasy*
            """.trimIndent()
        }

        val amountBlock = if (isPartial) {
            """
            💵 *Saldo total pendiente:* $$totalDebtUsdFmt USD
            🤝 *Abono acordado hoy:* $$targetUsdFmt USD
            👉 *Monto exacto a transferir:* *Bs. $concilVesFmt*
            """.trimIndent()
        } else {
            """
            💵 *Saldo adeudado:* $$totalDebtUsdFmt USD
            👉 *Monto exacto a transferir:* *Bs. $concilVesFmt*
            """.trimIndent()
        }

        return """
            👋 Hola *$debtorName*, un cordial saludo de *$merchant*.
            
            Le compartimos con todo aprecio su detalle de pago:
            $amountBlock
            $noteSection
            🏦 *Datos para Pago Móvil:*
            • *Banco:* $bankName ($bankCode)
            • *Teléfono:* ${profile.phoneNumber}
            • *Cédula/RIF:* ${profile.idCard}
            
            📸 _Le adjuntamos nuestro código QR para su comodidad al transferir._
            🙏 ¡Muchas gracias por su preferencia!
            ⚡ *Enviado vía Zeasy*
        """.trimIndent()
    }

    /**
     * Notificación informativa cordial para clientes con saldo a favor.
     */
    fun generateCreditNoticeMessage(
        profile: UserProfile,
        debtorName: String,
        creditUsd: Double,
        bcvRate: Double,
        countryProfile: CountryProfile = CountryProfileCatalog.VENEZUELA
    ): String {
        val rate = if (countryProfile.supportsExchangeRate && bcvRate > 0) bcvRate else 1.0
        val vesFormatted = String.format(Locale.US, "%.2f", creditUsd * rate)
        val usdFormatted = String.format(Locale.US, "%.2f", creditUsd)
        val merchant = if (profile.merchantName.isNotBlank()) profile.merchantName else "tu comercio amigo"

        val amountText = if (countryProfile.supportsExchangeRate) {
            "$$usdFormatted USD (≈ Bs. $vesFormatted)"
        } else {
            "${countryProfile.localCurrencySymbol} $vesFormatted"
        }

        return """
            👋 Hola *$debtorName*, un saludo de *$merchant*.
            
            Le informamos amablemente que cuenta con un *saldo a su favor* disponible para sus compras:
            🟢 *Monto a su favor:* $amountText
            
            ¡Puede consumirlo cuando guste en nuestro establecimiento!
            ⚡ *Zeasy*
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
        val countryCode = PreferencesHelper.getCountryCode(context)
        val phonePrefix = countryCode.phonePrefix

        // Limpiar teléfono y anteponer código de país activo (VE=58, CO=57, AR=54)
        var cleanPhone = customerPhone.replace(Regex("[^0-9]"), "")
        if (cleanPhone.isNotEmpty()) {
            if (cleanPhone.startsWith("0")) {
                cleanPhone = phonePrefix + cleanPhone.substring(1)
            } else if (!cleanPhone.startsWith(phonePrefix) && cleanPhone.length == 10) {
                cleanPhone = "$phonePrefix$cleanPhone"
            }
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
