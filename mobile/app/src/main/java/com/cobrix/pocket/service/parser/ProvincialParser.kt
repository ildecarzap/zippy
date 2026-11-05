package com.cobrix.pocket.service.parser

import com.cobrix.pocket.data.model.BankCode
import com.cobrix.pocket.data.model.ParsedPayment
import java.util.Locale

class ProvincialParser : BankParser {
    override val bankCode: BankCode = BankCode.PROVINCIAL

    override fun canHandle(packageName: String, title: String?, text: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT)
        if (ParserUtils.isProhibitedMessagingPackage(pkg)) return false

        val isProvincialApp = pkg.contains("provincial") || pkg.contains("bbva") ||
                BankCode.PROVINCIAL.knownPackageNames.any { pkg.equals(it, ignoreCase = true) }
        val isSms = ParserUtils.isSmsPackage(pkg) && ParserUtils.isLegitimateBankSmsSender(title) &&
                (title?.contains("2282") == true || title?.lowercase(Locale.ROOT)?.contains("bbva") == true || title?.lowercase(Locale.ROOT)?.contains("provincial") == true)
        val isEmail = ParserUtils.isEmailPackage(pkg) && (title?.lowercase(Locale.ROOT)?.contains("bbva") == true || title?.lowercase(Locale.ROOT)?.contains("provincial") == true)

        if (!isProvincialApp && !isSms && !isEmail) return false

        val combined = "$title $text"
        val isOutgoing = ParserUtils.isOutgoingTransaction(combined)
        if (ParserUtils.isEmailPackage(packageName) && !ParserUtils.hasIncomingIndicators(combined) && !isOutgoing) return false

        val lowerCombined = combined.lowercase(Locale.ROOT)
        return lowerCombined.contains("provincial") || lowerCombined.contains("bbva") ||
                lowerCombined.contains("dinero rapido") || lowerCombined.contains("dinero rápido") ||
                lowerCombined.contains("2282")
    }

    override fun parse(
        packageName: String,
        title: String?,
        text: String,
        postTimeMillis: Long
    ): ParsedPayment? {
        val combined = "$title $text"
        val isOutgoing = ParserUtils.isOutgoingTransaction(combined)
        if (ParserUtils.isEmailPackage(packageName) && !ParserUtils.hasIncomingIndicators(combined) && !isOutgoing) return null

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
            canal = if (isSms) "SMS" else "PUSH",
            isOutgoing = isOutgoing
        )
    }
}
