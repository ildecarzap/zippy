package com.cobrix.pocket.service.parser

import com.cobrix.pocket.data.model.BankCode
import com.cobrix.pocket.data.model.ParsedPayment
import java.util.Locale
import java.util.regex.Pattern

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
        return combined.contains("bnc") ||
                combined.contains("pago movil bnc") ||
                combined.contains("pago móvil bnc") ||
                combined.contains("pago movil recibido") ||
                combined.contains("pago móvil recibido") ||
                combined.contains("banco nacional de credito") ||
                combined.contains("banco nacional de crédito") ||
                combined.contains("2620") ||
                combined.contains("2621")
    }

    override fun parse(
        packageName: String,
        title: String?,
        text: String,
        postTimeMillis: Long
    ): ParsedPayment? {
        val combined = "$title $text"
        val isOutgoing = ParserUtils.isOutgoingTransaction(combined)
        if (isOutgoing) return null // En BNC solo procesamos cobros recibidos
        if (ParserUtils.isEmailPackage(packageName) && !ParserUtils.hasIncomingIndicators(combined)) return null

        val (amount, amountRaw) = ParserUtils.extractAmount(combined) ?: return null

        // 1. Extracción de teléfono (soporta números enmascarados como "Telf.0424***3112" de BNC)
        var phone = ParserUtils.extractPhone(combined)
        if (phone == null) {
            val maskedMatcher = Pattern.compile(
                """(?:telf\.?|tlf\.?|tel\.?|cel\.?)?\s*([0-9]{4}[*xX.\s-]*[0-9]{3,4})""",
                Pattern.CASE_INSENSITIVE
            ).matcher(combined)
            if (maskedMatcher.find()) {
                phone = maskedMatcher.group(1)?.replace(Regex("""\s+"""), "")
            }
        }

        // 2. Extracción de referencia bancaria tolerante a truncamientos de notificación
        var reference = ParserUtils.extractReference(combined)
        if (reference == null) {
            // Regex directo para formatos de BNC: "Ref: 12345678" o "Ref. 123456" o "Ref 1234"
            val refMatcher = Pattern.compile(
                """\b(?:ref\.?|referencia|secuencia|aprobaci[oó]n|op\.?)[:.\s]*([0-9]{4,16})""",
                Pattern.CASE_INSENSITIVE
            ).matcher(combined)
            if (refMatcher.find()) {
                reference = refMatcher.group(1)?.trim()
            }
        }

        // Si Android truncó la notificación al final con "Ref..."
        if (reference.isNullOrBlank()) {
            val dateMatcher = Pattern.compile(
                """Dia:([0-9/]+-[0-9:]+)""",
                Pattern.CASE_INSENSITIVE
            ).matcher(combined)
            if (dateMatcher.find()) {
                val digits = dateMatcher.group(1)?.replace(Regex("[^0-9]"), "") ?: ""
                if (digits.length >= 4) {
                    reference = digits.takeLast(6)
                }
            }
        }

        // Fallback final: timestamp para garantizar que Zeasy cante el cobro de inmediato
        if (reference.isNullOrBlank()) {
            val fallbackDigits = (postTimeMillis % 1000000).toString().padStart(6, '0')
            reference = "BNC$fallbackDigits"
        }

        val last4 = if (reference.length >= 4) reference.takeLast(4) else reference
        val isSms = ParserUtils.isSmsPackage(packageName)

        return ParsedPayment(
            monto = amount,
            montoRaw = amountRaw,
            moneda = "VES",
            bancoEmisor = "Banco Nacional de Crédito",
            bancoCodigo = BankCode.BNC,
            referencia = reference,
            referenciaUltimos4 = last4,
            telefonoOrigen = phone,
            timestampMillis = postTimeMillis,
            paqueteOrigen = packageName,
            textoCrudo = combined.trim(),
            canal = if (isSms) "SMS" else "PUSH",
            isOutgoing = false
        )
    }
}
