package com.cobrix.pocket.service.parser

import com.cobrix.pocket.data.model.BankCode
import com.cobrix.pocket.data.model.ParsedPayment
import java.util.Locale

class BdvParser : BankParser {
    override val bankCode: BankCode = BankCode.BDV

    override fun canHandle(packageName: String, title: String?, text: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT)
        if (ParserUtils.isProhibitedMessagingPackage(pkg)) return false

        val isBdvApp = pkg.contains("bdv") || pkg.contains("bancodevenezuela") ||
                BankCode.BDV.knownPackageNames.any { pkg.equals(it, ignoreCase = true) }
        val isSms = ParserUtils.isSmsPackage(pkg) && ParserUtils.isLegitimateBankSmsSender(title) &&
                (title?.contains("26371") == true || title?.contains("772") == true || title?.lowercase(Locale.ROOT)?.contains("bdv") == true || title?.lowercase(Locale.ROOT)?.contains("venezuela") == true)
        val isEmail = ParserUtils.isEmailPackage(pkg) && (title?.lowercase(Locale.ROOT)?.contains("venezuela") == true || title?.lowercase(Locale.ROOT)?.contains("bdv") == true)

        if (!isBdvApp && !isSms && !isEmail) return false

        val combined = "$title $text".lowercase(Locale.ROOT)
        return combined.contains("pagoclave") || combined.contains("pago clave") ||
                combined.contains("bdv") || combined.contains("banco de venezuela") ||
                combined.contains("pagomovil") || combined.contains("pago movil") ||
                combined.contains("pagomóvil") || combined.contains("pago móvil") ||
                combined.contains("bdvapp") || combined.contains("bdv digital") ||
                combined.contains("26371") || combined.contains("772")
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
        val bankDetected = ParserUtils.extractBankName(combined) ?: "Banco de Venezuela"

        val last4 = if (reference.length >= 4) reference.takeLast(4) else reference
        val isSms = ParserUtils.isSmsPackage(packageName)

        return ParsedPayment(
            monto = amount,
            montoRaw = amountRaw,
            moneda = "VES",
            bancoEmisor = bankDetected,
            bancoCodigo = BankCode.BDV,
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
