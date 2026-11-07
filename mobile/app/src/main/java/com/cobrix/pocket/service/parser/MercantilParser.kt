package com.cobrix.pocket.service.parser

import com.cobrix.pocket.data.model.BankCode
import com.cobrix.pocket.data.model.ParsedPayment
import java.util.Locale

class MercantilParser : BankParser {
    override val bankCode: BankCode = BankCode.MERCANTIL

    override fun canHandle(packageName: String, title: String?, text: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT)
        if (ParserUtils.isProhibitedMessagingPackage(pkg)) return false

        val isMercantilApp = pkg.contains("mercantil") ||
                BankCode.MERCANTIL.knownPackageNames.any { pkg.equals(it, ignoreCase = true) }
        val isSms = ParserUtils.isSmsPackage(pkg) && ParserUtils.isLegitimateBankSmsSender(title) &&
                (title?.contains("24024") == true || title?.lowercase(Locale.ROOT)?.contains("mercantil") == true)
        val isEmail = ParserUtils.isEmailPackage(pkg) && title?.lowercase(Locale.ROOT)?.contains("mercantil") == true

        if (!isMercantilApp && !isSms && !isEmail) return false

        val combined = "$title $text".lowercase(Locale.ROOT)
        if (isMercantilApp) {
            return ParserUtils.hasIncomingIndicators(combined) ||
                    combined.contains("tpago") ||
                    combined.contains("pago") ||
                    combined.contains("recib") ||
                    combined.contains("bs") ||
                    combined.contains("mercantil") ||
                    combined.contains("operaci") ||
                    ParserUtils.isOutgoingTransaction(combined)
        }

        return combined.contains("tpago") || combined.contains("mercantil") || combined.contains("24024")
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
        val reference = ParserUtils.extractReference(combined)
            ?: (postTimeMillis % 10000).toString().padStart(4, '0')
        val phone = ParserUtils.extractPhone(combined)
        val bankDetected = ParserUtils.extractBankName(combined) ?: "Banco Mercantil"

        val last4 = if (reference.length >= 4) reference.takeLast(4) else reference
        val isSms = ParserUtils.isSmsPackage(packageName)

        return ParsedPayment(
            monto = amount,
            montoRaw = amountRaw,
            moneda = "VES",
            bancoEmisor = bankDetected,
            bancoCodigo = BankCode.MERCANTIL,
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
