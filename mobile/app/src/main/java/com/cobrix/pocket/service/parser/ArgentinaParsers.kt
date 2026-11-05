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
        if (ParserUtils.isProhibitedMessagingPackage(pkg)) return false

        val isMpOrigin = pkg.contains("mercadopago") ||
                pkg.contains("mercadolibre") ||
                BankCode.AR_MERCADOPAGO.knownPackageNames.any { pkg.equals(it, ignoreCase = true) }
        if (!isMpOrigin) return false

        val clean = "${title.orEmpty()} $text"
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .trim()
        val combined = clean.lowercase(Locale.ROOT)

        val isIncoming = combined.contains("recibiste") ||
                combined.contains("te transfirieron") ||
                combined.contains("te enviaron") ||
                combined.contains("te ingresaron") ||
                combined.contains("cobro aprobado") ||
                combined.contains("cobraste") ||
                combined.contains("transferencia recibida") ||
                combined.contains("pago recibido") ||
                combined.contains("ingreso de dinero") ||
                combined.contains("acredit")

        val isOutgoing = ParserUtils.isOutgoingTransaction(combined)
        return isMpOrigin &&
                (isIncoming || isOutgoing) &&
                !ParserUtils.isPromotionalOrNonPaymentNotification(combined)
    }

    override fun parse(packageName: String, title: String?, text: String, postTimeMillis: Long): ParsedPayment? {
        val clean = "${title.orEmpty()} $text"
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .trim()
        val isOutgoing = ParserUtils.isOutgoingTransaction(clean)
        val (amount, rawAmount) = ParserUtils.extractAmount(clean) ?: return null
        val ref = ParserUtils.extractReference(clean) ?: (postTimeMillis % 10000).toString().padStart(4, '0')

        // Extraer remitente de la notificación (ej: "De Ildemaro Antonio Carrasco Zapata desde...")
        val senderMatch = Regex("""(?:De|de)\s+([A-Za-zÁÉÍÓÚáéíóúñÑ\s]+?)\s+(?:desde|por|en|con|\.)""").find(clean)
        val sender = senderMatch?.groupValues?.getOrNull(1)?.trim()

        return ParsedPayment(
            monto = amount,
            montoRaw = rawAmount,
            moneda = "ARS",
            bancoEmisor = "Mercado Pago",
            bancoCodigo = BankCode.AR_MERCADOPAGO,
            referencia = ref,
            referenciaUltimos4 = ref.takeLast(4),
            telefonoOrigen = sender,
            paqueteOrigen = packageName,
            textoCrudo = clean,
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
        if (ParserUtils.isProhibitedMessagingPackage(pkg)) return false

        val isCdniOrigin = pkg.contains("cuentadni") ||
                BankCode.AR_CUENTADNI.knownPackageNames.any { pkg.equals(it, ignoreCase = true) }
        if (!isCdniOrigin) return false

        val clean = "${title.orEmpty()} $text"
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .trim()
        val combined = clean.lowercase(Locale.ROOT)

        val isIncoming = combined.contains("recibiste") ||
                combined.contains("se acreditó") ||
                combined.contains("se acredito") ||
                combined.contains("transferencia recibida") ||
                combined.contains("pago recibido")

        val isOutgoing = ParserUtils.isOutgoingTransaction(combined)
        return isCdniOrigin &&
                (isIncoming || isOutgoing) &&
                !ParserUtils.isPromotionalOrNonPaymentNotification(combined)
    }

    override fun parse(packageName: String, title: String?, text: String, postTimeMillis: Long): ParsedPayment? {
        val clean = "${title.orEmpty()} $text"
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .trim()
        val isOutgoing = ParserUtils.isOutgoingTransaction(clean)
        val (amount, rawAmount) = ParserUtils.extractAmount(clean) ?: return null
        val ref = ParserUtils.extractReference(clean) ?: (postTimeMillis % 10000).toString().padStart(4, '0')
        return ParsedPayment(
            monto = amount,
            montoRaw = rawAmount,
            moneda = "ARS",
            bancoEmisor = "Cuenta DNI",
            bancoCodigo = BankCode.AR_CUENTADNI,
            referencia = ref,
            referenciaUltimos4 = ref.takeLast(4),
            paqueteOrigen = packageName,
            textoCrudo = clean,
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
        if (ParserUtils.isProhibitedMessagingPackage(pkg)) return false

        val isBnaOrigin = pkg.contains("bnamas") || pkg.contains("bna") ||
                BankCode.AR_BNA_MAS.knownPackageNames.any { pkg.equals(it, ignoreCase = true) }
        if (!isBnaOrigin) return false

        val clean = "${title.orEmpty()} $text"
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .trim()
        val combined = clean.lowercase(Locale.ROOT)

        val isIncoming = combined.contains("recibiste") ||
                combined.contains("transferencia recibida") ||
                combined.contains("pago recibido") ||
                combined.contains("se acreditó") ||
                combined.contains("se acredito")

        val isOutgoing = ParserUtils.isOutgoingTransaction(combined)
        return isBnaOrigin &&
                (isIncoming || isOutgoing) &&
                !ParserUtils.isPromotionalOrNonPaymentNotification(combined)
    }

    override fun parse(packageName: String, title: String?, text: String, postTimeMillis: Long): ParsedPayment? {
        val clean = "${title.orEmpty()} $text"
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .trim()
        val isOutgoing = ParserUtils.isOutgoingTransaction(clean)
        val (amount, rawAmount) = ParserUtils.extractAmount(clean) ?: return null
        val ref = ParserUtils.extractReference(clean) ?: (postTimeMillis % 10000).toString().padStart(4, '0')
        return ParsedPayment(
            monto = amount,
            montoRaw = rawAmount,
            moneda = "ARS",
            bancoEmisor = "BNA+",
            bancoCodigo = BankCode.AR_BNA_MAS,
            referencia = ref,
            referenciaUltimos4 = ref.takeLast(4),
            paqueteOrigen = packageName,
            textoCrudo = clean,
            timestampMillis = postTimeMillis,
            isOutgoing = isOutgoing
        )
    }
}

