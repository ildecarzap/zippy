package com.cobrix.pocket.service.parser

import com.cobrix.pocket.data.model.BankCode
import com.cobrix.pocket.data.model.ParsedPayment
import java.util.Locale

/**
 * Parser oficial para notificaciones de Mercado Pago (Argentina — Transferencias 3.0 y QR).
 * Soporta formatos: "Te transfirieron $ 12.500", "Recibiste $ 8.400 de...", "Cobro aprobado por $ 5.000"
 */
class MercadoPagoParser : BankParser {
    override val bankCode = BankCode.AR_MERCADOPAGO

    override fun canHandle(packageName: String, title: String?, text: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT)
        val combined = "${title.orEmpty()} $text".lowercase(Locale.ROOT)
        val isMpOrigin = pkg == "com.mercadopago.wallet" ||
                ((ParserUtils.isSmsPackage(pkg) || ParserUtils.isEmailPackage(pkg)) && (combined.contains("mercado pago") || combined.contains("mercadopago")))
        val isIncoming = combined.contains("te transfirieron") ||
                combined.contains("recibiste $") ||
                combined.contains("recibiste ars") ||
                combined.contains("recibiste una transferencia") ||
                combined.contains("recibiste un pago") ||
                combined.contains("cobro aprobado") ||
                combined.contains("cobraste $") ||
                combined.contains("te enviaron $") ||
                combined.contains("te ingresaron")
        val isOutgoing = ParserUtils.isOutgoingTransaction(combined)
        return isMpOrigin &&
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
            moneda = "ARS",
            bancoEmisor = "Mercado Pago",
            bancoCodigo = BankCode.AR_MERCADOPAGO,
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
 * Parser oficial para notificaciones de Cuenta DNI — Banco Provincia (Argentina).
 */
class CuentaDniParser : BankParser {
    override val bankCode = BankCode.AR_CUENTADNI

    override fun canHandle(packageName: String, title: String?, text: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT)
        val combined = "${title.orEmpty()} $text".lowercase(Locale.ROOT)
        val isCdniOrigin = pkg == "ar.com.bapro.cuentadni" ||
                ((ParserUtils.isSmsPackage(pkg) || ParserUtils.isEmailPackage(pkg)) && combined.contains("cuenta dni"))
        val isIncoming = combined.contains("recibiste una transferencia") ||
                combined.contains("recibiste $") ||
                combined.contains("se acreditó") ||
                combined.contains("se acredito") ||
                combined.contains("transferencia recibida")
        val isOutgoing = ParserUtils.isOutgoingTransaction(combined)
        return isCdniOrigin &&
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
            moneda = "ARS",
            bancoEmisor = "Cuenta DNI",
            bancoCodigo = BankCode.AR_CUENTADNI,
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
 * Parser oficial para notificaciones de BNA+ — Banco de la Nación Argentina / MODO.
 */
class BnaMasParser : BankParser {
    override val bankCode = BankCode.AR_BNA_MAS

    override fun canHandle(packageName: String, title: String?, text: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT)
        val combined = "${title.orEmpty()} $text".lowercase(Locale.ROOT)
        val isBnaOrigin = pkg == "com.bna.bnamas" ||
                ((ParserUtils.isSmsPackage(pkg) || ParserUtils.isEmailPackage(pkg)) && (combined.contains("bna+") || combined.contains("banco nacion") || combined.contains("banco nación")))
        val isIncoming = combined.contains("recibiste una transferencia") ||
                combined.contains("recibiste $") ||
                combined.contains("transferencia recibida") ||
                combined.contains("se acreditó") ||
                combined.contains("se acredito")
        val isOutgoing = ParserUtils.isOutgoingTransaction(combined)
        return isBnaOrigin &&
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
            moneda = "ARS",
            bancoEmisor = "BNA+",
            bancoCodigo = BankCode.AR_BNA_MAS,
            referencia = ref,
            referenciaUltimos4 = ref.takeLast(4),
            paqueteOrigen = packageName,
            textoCrudo = combined,
            timestampMillis = postTimeMillis,
            isOutgoing = isOutgoing
        )
    }
}

