package com.cobrix.pocket.service.parser

import com.cobrix.pocket.data.model.BankCode
import com.cobrix.pocket.data.model.ParsedPayment
import java.util.Locale

class UbiiParser : BankParser {
    override val bankCode: BankCode = BankCode.UBII

    override fun canHandle(packageName: String, title: String?, text: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT)
        if (ParserUtils.isProhibitedMessagingPackage(pkg)) return false

        val combined = "$title $text".lowercase(Locale.ROOT)

        val isUbiiApp = BankCode.UBII.matchesPackage(pkg)
        val isOtherKnownBank = pkg.contains("mercantil") || pkg.contains("banesco") ||
                pkg.contains("provincial") || pkg.contains("bbva") || pkg.contains("bnc") ||
                pkg.contains("bdv") || pkg.contains("bancodevenezuela") || pkg.contains("bancamiga")
        val hasExactUbiiPushSignature = !isOtherKnownBank && !ParserUtils.isSmsPackage(pkg) && !ParserUtils.isEmailPackage(pkg) &&
                (combined.contains("transacción recibida") || combined.contains("transaccion recibida")) &&
                (combined.contains("por pago móvil del número") || combined.contains("por pago movil del numero"))

        val isSms = ParserUtils.isSmsPackage(pkg) && ParserUtils.isLegitimateBankSmsSender(title) &&
                (title?.contains("8244") == true || title?.lowercase(Locale.ROOT)?.contains("ubii") == true)
        val isEmail = ParserUtils.isEmailPackage(pkg) && title?.lowercase(Locale.ROOT)?.contains("ubii") == true

        if (!isUbiiApp && !hasExactUbiiPushSignature && !isSms && !isEmail) return false

        if (isUbiiApp || hasExactUbiiPushSignature) {
            return ParserUtils.hasIncomingIndicators(combined) ||
                    combined.contains("pago") ||
                    combined.contains("recib") ||
                    combined.contains("bs") ||
                    combined.contains("transf") ||
                    combined.contains("ubii") ||
                    ParserUtils.isOutgoingTransaction(combined)
        }

        return combined.contains("ubii") || combined.contains("ubiipagos") ||
                combined.contains("pago movil ubii") || combined.contains("8244")
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
        val bankDetected = ParserUtils.extractBankName(combined) ?: "Ubii App"

        val last4 = if (reference.length >= 4) reference.takeLast(4) else reference
        val isSms = ParserUtils.isSmsPackage(packageName)

        return ParsedPayment(
            monto = amount,
            montoRaw = amountRaw,
            moneda = "VES",
            bancoEmisor = bankDetected,
            bancoCodigo = BankCode.UBII,
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
