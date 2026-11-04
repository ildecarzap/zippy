package com.cobrix.pocket.service.parser

import com.cobrix.pocket.data.model.BankCode
import com.cobrix.pocket.data.model.ParsedPayment
import java.util.Locale

/**
 * Parser oficial para notificaciones de Nequi (Colombia).
 * Soporta formatos: "Recibiste $45.000,00 de JUAN PEREZ", "¡Te enviaron plata! Recibiste $ 25.000"
 */
class NequiParser : BankParser {
    override val bankCode = BankCode.CO_NEQUI

    override fun canHandle(packageName: String, title: String?, text: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT)
        val combined = "${title.orEmpty()} $text".lowercase(Locale.ROOT)
        val isNequiOrigin = pkg == "com.nequi.mobileapp" ||
                ((ParserUtils.isSmsPackage(pkg) || ParserUtils.isEmailPackage(pkg)) && combined.contains("nequi"))
        val isIncoming = combined.contains("recibiste") ||
                combined.contains("te envió") ||
                combined.contains("te envio") ||
                combined.contains("te enviaron")
        val isOutgoing = ParserUtils.isOutgoingTransaction(combined)
        return isNequiOrigin &&
                (isIncoming || isOutgoing) &&
                !ParserUtils.isPromotionalOrNonPaymentNotification(combined)
    }

    override fun parse(packageName: String, title: String?, text: String, postTimeMillis: Long): ParsedPayment? {
        val combined = "${title.orEmpty()} $text"
        val isOutgoing = ParserUtils.isOutgoingTransaction(combined)
        val (amount, rawAmount) = ParserUtils.extractAmount(combined) ?: return null
        val ref = ParserUtils.extractReference(combined) ?: (postTimeMillis % 10000).toString().padStart(4, '0')
        return ParsedPayment(
            monto = amount,
            montoRaw = rawAmount,
            moneda = "COP",
            bancoEmisor = "Nequi",
            bancoCodigo = BankCode.CO_NEQUI,
            referencia = ref,
            referenciaUltimos4 = ref.takeLast(4),
            paqueteOrigen = packageName,
            textoCrudo = combined,
            timestampMillis = postTimeMillis,
            isOutgoing = isOutgoing
        )
    }
}

/**
 * Parser oficial para notificaciones de DaviPlata (Colombia).
 */
class DaviPlataParser : BankParser {
    override val bankCode = BankCode.CO_DAVIPLATA

    override fun canHandle(packageName: String, title: String?, text: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT)
        val combined = "${title.orEmpty()} $text".lowercase(Locale.ROOT)
        val isDaviOrigin = pkg == "com.davivienda.daviplataapp" ||
                ((ParserUtils.isSmsPackage(pkg) || ParserUtils.isEmailPackage(pkg)) && combined.contains("daviplata"))
        val isIncoming = combined.contains("recibio") ||
                combined.contains("recibió") ||
                combined.contains("recibiste") ||
                combined.contains("te pasaron plata")
        val isOutgoing = ParserUtils.isOutgoingTransaction(combined)
        return isDaviOrigin &&
                (isIncoming || isOutgoing) &&
                !ParserUtils.isPromotionalOrNonPaymentNotification(combined)
    }

    override fun parse(packageName: String, title: String?, text: String, postTimeMillis: Long): ParsedPayment? {
        val combined = "${title.orEmpty()} $text"
        val isOutgoing = ParserUtils.isOutgoingTransaction(combined)
        val (amount, rawAmount) = ParserUtils.extractAmount(combined) ?: return null
        val ref = ParserUtils.extractReference(combined) ?: (postTimeMillis % 10000).toString().padStart(4, '0')
        return ParsedPayment(
            monto = amount,
            montoRaw = rawAmount,
            moneda = "COP",
            bancoEmisor = "DaviPlata",
            bancoCodigo = BankCode.CO_DAVIPLATA,
            referencia = ref,
            referenciaUltimos4 = ref.takeLast(4),
            paqueteOrigen = packageName,
            textoCrudo = combined,
            timestampMillis = postTimeMillis,
            isOutgoing = isOutgoing
        )
    }
}

/**
 * Parser oficial para notificaciones de Bancolombia y Bancolombia A la Mano (Colombia).
 */
class BancolombiaParser : BankParser {
    override val bankCode = BankCode.CO_BANCOLOMBIA

    override fun canHandle(packageName: String, title: String?, text: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT)
        val combined = "${title.orEmpty()} $text".lowercase(Locale.ROOT)
        val isBcolOrigin = pkg == "kt.bancolombia" ||
                pkg == "com.todo1.mobile" ||
                pkg == "com.bancolombia.alamanov2" ||
                ((ParserUtils.isSmsPackage(pkg) || ParserUtils.isEmailPackage(pkg)) && combined.contains("bancolombia"))
        val isIncoming = combined.contains("recibiste una transferencia") ||
                combined.contains("recibiste un pago") ||
                combined.contains("recibiste $") ||
                combined.contains("recibiste cop") ||
                combined.contains("transferencia recibida") ||
                combined.contains("te transfirieron") ||
                combined.contains("abono en tu cuenta")
        val isOutgoing = ParserUtils.isOutgoingTransaction(combined)
        return isBcolOrigin &&
                (isIncoming || isOutgoing) &&
                !ParserUtils.isPromotionalOrNonPaymentNotification(combined)
    }

    override fun parse(packageName: String, title: String?, text: String, postTimeMillis: Long): ParsedPayment? {
        val combined = "${title.orEmpty()} $text"
        val isOutgoing = ParserUtils.isOutgoingTransaction(combined)
        val (amount, rawAmount) = ParserUtils.extractAmount(combined) ?: return null
        val ref = ParserUtils.extractReference(combined) ?: (postTimeMillis % 10000).toString().padStart(4, '0')
        return ParsedPayment(
            monto = amount,
            montoRaw = rawAmount,
            moneda = "COP",
            bancoEmisor = "Bancolombia",
            bancoCodigo = BankCode.CO_BANCOLOMBIA,
            referencia = ref,
            referenciaUltimos4 = ref.takeLast(4),
            paqueteOrigen = packageName,
            textoCrudo = combined,
            timestampMillis = postTimeMillis,
            isOutgoing = isOutgoing
        )
    }
}

