package com.cobrix.pocket.service.parser

import com.cobrix.pocket.data.model.BankCode
import com.cobrix.pocket.data.model.ParsedPayment
import java.util.Locale

class BanescoParser : BankParser {
    override val bankCode: BankCode = BankCode.BANESCO

    override fun canHandle(packageName: String, title: String?, text: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT)
        if (ParserUtils.isProhibitedMessagingPackage(pkg)) return false

        val isBanescoApp = pkg.contains("banesco") ||
                BankCode.BANESCO.knownPackageNames.any { pkg.equals(it, ignoreCase = true) }
        val isSms = ParserUtils.isSmsPackage(pkg) && ParserUtils.isLegitimateBankSmsSender(title) &&
                (title?.contains("2661") == true || title?.contains("2662") == true || title?.contains("2846") == true || title?.lowercase(Locale.ROOT)?.contains("banesco") == true)
        val isEmail = ParserUtils.isEmailPackage(pkg) && title?.lowercase(Locale.ROOT)?.contains("banesco") == true

        if (!isBanescoApp && !isSms && !isEmail) return false

        val combined = "$title $text".lowercase(Locale.ROOT)
        return combined.contains("banesco") || combined.contains("pago movil banesco") ||
                combined.contains("pago móvil banesco") || combined.contains("2846") ||
                combined.contains("2661") || combined.contains("2662")
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
        val bankDetected = ParserUtils.extractBankName(combined) ?: "Banesco"

        val last4 = if (reference.length >= 4) reference.takeLast(4) else reference
        val isSms = ParserUtils.isSmsPackage(packageName)

        return ParsedPayment(
            monto = amount,
            montoRaw = amountRaw,
            moneda = "VES",
            bancoEmisor = bankDetected,
            bancoCodigo = BankCode.BANESCO,
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
