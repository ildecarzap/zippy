package com.cobrix.pocket.service.parser

import com.cobrix.pocket.data.model.BankCode
import com.cobrix.pocket.data.model.ParsedPayment
import java.util.Locale

class BncParser : BankParser {
    override val bankCode: BankCode = BankCode.BNC

    override fun canHandle(packageName: String, title: String?, text: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT)
        if (ParserUtils.isProhibitedMessagingPackage(pkg)) return false

        val isBncApp = pkg.contains("bnc") ||
                BankCode.BNC.knownPackageNames.any { pkg.equals(it, ignoreCase = true) }
        val isSms = ParserUtils.isSmsPackage(pkg) && ParserUtils.isLegitimateBankSmsSender(title) &&
                (title?.contains("2620") == true || title?.contains("2621") == true || title?.lowercase(Locale.ROOT)?.contains("bnc") == true)
        val isEmail = ParserUtils.isEmailPackage(pkg) && title?.lowercase(Locale.ROOT)?.contains("bnc") == true

        if (!isBncApp && !isSms && !isEmail) return false

        val combined = "$title $text".lowercase(Locale.ROOT)
        return combined.contains("bnc") || combined.contains("pago movil bnc") ||
                combined.contains("banco nacional de credito") || combined.contains("banco nacional de crédito") ||
                combined.contains("2620") || combined.contains("2621")
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
        val bankDetected = ParserUtils.extractBankName(combined) ?: "Banco Nacional de Crédito"

        val last4 = if (reference.length >= 4) reference.takeLast(4) else reference
        val isSms = ParserUtils.isSmsPackage(packageName)

        return ParsedPayment(
            monto = amount,
            montoRaw = amountRaw,
            moneda = "VES",
            bancoEmisor = bankDetected,
            bancoCodigo = BankCode.BNC,
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
