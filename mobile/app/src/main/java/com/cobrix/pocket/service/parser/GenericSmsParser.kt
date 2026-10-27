package com.cobrix.pocket.service.parser

import com.cobrix.pocket.data.model.BankCatalog
import com.cobrix.pocket.data.model.BankCode
import com.cobrix.pocket.data.model.ParsedPayment
import java.util.Locale

class GenericSmsParser : BankParser {
    override val bankCode: BankCode = BankCode.SMS

    override fun canHandle(packageName: String, title: String?, text: String): Boolean {
        val combined = "$title $text"
        if (ParserUtils.isOutgoingTransaction(combined)) return false

        val isSms = ParserUtils.isSmsPackage(packageName)
        if (!isSms) return false

        val lower = combined.lowercase(Locale.ROOT)
        val hasPaymentWords = ParserUtils.hasIncomingIndicators(combined) ||
                lower.contains("recibiste") ||
                lower.contains("recibido") ||
                lower.contains("recibio") ||
                lower.contains("recibió") ||
                lower.contains("tpago") ||
                lower.contains("pago movil") ||
                lower.contains("pago móvil") ||
                lower.contains("pagoclave") ||
                lower.contains("pago clave") ||
                lower.contains("abono") ||
                lower.contains("acreditado") ||
                lower.contains("acreditada") ||
                lower.contains("credito") ||
                lower.contains("crédito") ||
                lower.contains("dinero rapido") ||
                lower.contains("dinero rápido")
        return hasPaymentWords
    }

    override fun parse(
        packageName: String,
        title: String?,
        text: String,
        postTimeMillis: Long
    ): ParsedPayment? {
        val combined = "$title $text"
        if (ParserUtils.isOutgoingTransaction(combined)) return null

        val (amount, amountRaw) = ParserUtils.extractAmount(combined) ?: return null
        val reference = ParserUtils.extractReference(combined) ?: return null
        val phone = ParserUtils.extractPhone(combined)
        val detectedSudeban = ParserUtils.extractBankSudebanCode(combined, title)
        val bankDetected = if (detectedSudeban != null) {
            BankCatalog.findByCode(detectedSudeban)?.name ?: "SMS Bancario"
        } else {
            ParserUtils.extractBankName(combined) ?: "SMS Bancario"
        }

        val last4 = if (reference.length >= 4) reference.takeLast(4) else reference

        return ParsedPayment(
            monto = amount,
            montoRaw = amountRaw,
            moneda = "VES",
            bancoEmisor = bankDetected,
            bancoCodigo = BankCode.SMS,
            referencia = reference,
            referenciaUltimos4 = last4,
            telefonoOrigen = phone,
            timestampMillis = postTimeMillis,
            paqueteOrigen = packageName,
            textoCrudo = combined.trim(),
            canal = "SMS"
        )
    }
}
