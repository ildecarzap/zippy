package com.cobrix.pocket.service.parser

import com.cobrix.pocket.data.model.BankCode
import com.cobrix.pocket.data.model.ParsedPayment
import java.util.Locale

class ProvincialParser : BankParser {
    override val bankCode: BankCode = BankCode.PROVINCIAL

    override fun canHandle(packageName: String, title: String?, text: String): Boolean {
        val combined = "$title $text"
        if (ParserUtils.isOutgoingTransaction(combined)) return false
        if (ParserUtils.isEmailPackage(packageName) && !ParserUtils.hasIncomingIndicators(combined)) return false

        val pkg = packageName.lowercase(Locale.ROOT)
        if (pkg.contains("provincial") || pkg.contains("bbva")) return true
        if (pkg.contains("mercantil") || pkg.contains("banesco") || pkg.contains("bnc") ||
            pkg.contains("bdv") || pkg.contains("bancodevenezuela") || pkg.contains("bancamiga") ||
            pkg.contains("ubii")) return false

        val lowerCombined = combined.lowercase(Locale.ROOT)
        return lowerCombined.contains("provincial") || lowerCombined.contains("bbva")
    }

    override fun parse(
        packageName: String,
        title: String?,
        text: String,
        postTimeMillis: Long
    ): ParsedPayment? {
        val combined = "$title $text"
        if (ParserUtils.isOutgoingTransaction(combined)) return null
        if (ParserUtils.isEmailPackage(packageName) && !ParserUtils.hasIncomingIndicators(combined)) return null

        val (amount, amountRaw) = ParserUtils.extractAmount(combined) ?: return null
        val reference = ParserUtils.extractReference(combined) ?: return null
        val phone = ParserUtils.extractPhone(combined)
        val bankDetected = ParserUtils.extractBankName(combined) ?: "BBVA Provincial"

        val last4 = if (reference.length >= 4) reference.takeLast(4) else reference
        val isSms = ParserUtils.isSmsPackage(packageName)

        return ParsedPayment(
            monto = amount,
            montoRaw = amountRaw,
            moneda = "VES",
            bancoEmisor = bankDetected,
            bancoCodigo = BankCode.PROVINCIAL,
            referencia = reference,
            referenciaUltimos4 = last4,
            telefonoOrigen = phone,
            timestampMillis = postTimeMillis,
            paqueteOrigen = packageName,
            textoCrudo = combined.trim(),
            canal = if (isSms) "SMS" else "PUSH"
        )
    }
}
