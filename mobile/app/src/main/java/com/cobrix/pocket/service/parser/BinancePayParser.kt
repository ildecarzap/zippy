package com.cobrix.pocket.service.parser

import com.cobrix.pocket.data.model.BankCode
import com.cobrix.pocket.data.model.ParsedPayment
import java.util.Locale
import java.util.regex.Pattern

class BinancePayParser : BankParser {
    override val bankCode: BankCode = BankCode.BINANCE

    companion object {
        // Frases de mercado, trading, noticias, campañas o publicidad que NUNCA son pagos reales
        private val MARKETING_NEWS_KEYWORDS = listOf(
            "sube", "baja", "aumenta", "cae", "alcista", "bajista", "all-time high", "ath",
            "alerta de precio", "price alert", "alerta de mercado", "market alert",
            "noticias", "news", "binance square", "top gainer", "volumen",
            "gana hasta", "earn up to", "fondo de premios", "prize pool", "recompensa",
            "cupón", "cupon", "voucher", "cashback", "aprende y gana", "learn & earn",
            "campaña", "campaign", "concurso", "sorteo", "giveaway", "airdrop",
            "nuevo listado", "new listing", "launchpool", "launchpad", "invita a", "referral",
            "trading", "futuros", "futures", "liquidación", "liquidacion", "margen", "apalancamiento",
            "comprar cripto", "compra btc", "invertir", "oportunidad", "criptomoneda", "mercado"
        )

        // Frases obligatorias que confirman un abono o pago entrante real
        private val INCOMING_PAYMENT_PHRASES = listOf(
            "has recibido", "ha recibido", "recibiste",
            "pago recibido", "payment received",
            "you have received", "you received",
            "depósito exitoso", "deposito exitoso",
            "depósito confirmado", "deposito confirmado",
            "deposit successful", "deposit confirmed",
            "abono recibido", "transferencia recibida"
        )
    }

    // 1. Frases explícitas de pago/transferencia recibida con monto
    // Ejemplos reales:
    // - "Has recibido 10.50 USDT de ..."
    // - "You have received 5.00 USDT from John"
    // - "Pago recibido: 15.00 USDT"
    // - "Depósito exitoso: 100 USDT"
    private val receivedAmountPattern = Pattern.compile(
        """(?i)(?:has recibido|ha recibido|recibiste|you have received|you received|pago recibido(?: de)?|payment received(?: of)?|dep[oó]sito exitoso(?: de)?|dep[oó]sito confirmado(?: de)?|deposit successful(?: of)?|deposit confirmed(?: of)?)\s*(?:un pago de\s*)?(?:approx\.?|aprox\.?)?\s*([0-9]+(?:[.,][0-9]{1,8})?|[0-9]*[.,][0-9]+)\s*(?:USDT|USD|BUSD)?"""
    )

    // 2. Campo explícito de monto (típico en correos oficiales de Binance Pay): "Monto: 0.50 USDT", "Amount: 0.50 USDT"
    private val fieldAmountPattern = Pattern.compile(
        """(?i)(?:monto|amount|importe)\s*[:.]?\s*([0-9]+(?:[.,][0-9]{1,8})?|[0-9]*[.,][0-9]+)\s*(?:USDT|USD|BUSD)"""
    )

    // Remitente / Pagador
    private val explicitSenderPattern = Pattern.compile(
        """(?i)(?:remitente|sender)\s*[:.]?\s*([A-Za-z0-9_.-]+)"""
    )
    private val cryptoAfterSenderPattern = Pattern.compile(
        """(?i)(?:USDT|USD|BUSD)\s+(?:de|from)\s+([A-Za-z0-9_.-]+)"""
    )
    private val fallbackSenderPattern = Pattern.compile(
        """(?i)(?:de|from)\s+([A-Za-z][A-Za-z0-9_.-]*)"""
    )

    // Referencia / ID de Orden o Pago: "ID de orden: 123456", "Order ID: 123456", "ID de transacción: ..."
    private val orderPattern = Pattern.compile(
        """(?i)(?:id de orden|order id|id de transacci[oó]n|transaction id|id de pago|pay id|referencia|ref)\s*[:.]?\s*([0-9]{4,24})"""
    )

    private fun isMarketingOrNews(text: String): Boolean {
        return MARKETING_NEWS_KEYWORDS.any { text.contains(it) }
    }

    private fun hasIncomingPaymentPhrase(text: String): Boolean {
        return INCOMING_PAYMENT_PHRASES.any { text.contains(it) }
    }

    override fun canHandle(packageName: String, title: String?, text: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT)
        val combined = "$title $text".lowercase(Locale.ROOT)

        val isBinanceApp = pkg.contains("binance")
        val isBinanceEmail = ParserUtils.isEmailPackage(pkg) && (combined.contains("binance") || combined.contains("do-not-reply@binance.com"))
        if (!isBinanceApp && !isBinanceEmail) return false

        // Filtro anti noticias, cotizaciones y promociones
        if (isMarketingOrNews(combined)) return false

        // Obligatorio: debe contener una frase explícita de recepción de fondos
        return hasIncomingPaymentPhrase(combined)
    }

    override fun parse(
        packageName: String,
        title: String?,
        text: String,
        postTimeMillis: Long
    ): ParsedPayment? {
        val combined = "$title $text"
        if (ParserUtils.isOutgoingTransaction(combined)) return null

        val lowerCombined = combined.lowercase(Locale.ROOT)
        // Binance Pay NUNCA procesa transacciones en bolívares (VES / Bs)
        if (lowerCombined.contains("bs.") || lowerCombined.contains("bs ") || lowerCombined.contains("ves") ||
            lowerCombined.contains("bolívares") || lowerCombined.contains("bolivares") || lowerCombined.contains("bsd")) {
            return null
        }

        // Filtro estricto anti noticias y cotizaciones (ej: "USDT sube 100$")
        if (isMarketingOrNews(lowerCombined)) {
            return null
        }

        // Debe tener frase explícita de pago recibido
        if (!hasIncomingPaymentPhrase(lowerCombined)) {
            return null
        }

        var amount: Double? = null
        var amountRaw = ""

        // Intentar primero con frase de pago recibido explícita
        val recMatcher = receivedAmountPattern.matcher(combined)
        if (recMatcher.find()) {
            var raw = recMatcher.group(1)?.replace(',', '.') ?: ""
            if (raw.startsWith(".")) raw = "0$raw"
            val parsed = raw.toDoubleOrNull()
            if (parsed != null && parsed > 0.0) {
                amount = parsed
                amountRaw = raw
            }
        }

        // Intentar con campo explícito de monto (ej: "Monto: 0.50 USDT")
        if (amount == null) {
            val fieldMatcher = fieldAmountPattern.matcher(combined)
            if (fieldMatcher.find()) {
                var raw = fieldMatcher.group(1)?.replace(',', '.') ?: ""
                if (raw.startsWith(".")) raw = "0$raw"
                val parsed = raw.toDoubleOrNull()
                if (parsed != null && parsed > 0.0) {
                    amount = parsed
                    amountRaw = raw
                }
            }
        }

        // Si no se pudo asociar el monto a una recepción explícita, descartar (NUNCA usar fallbacks genéricos de montos)
        if (amount == null || amount <= 0.0) return null

        // Extraer remitente evitando capturar montos o números
        var sender: String? = null
        val explicitMatcher = explicitSenderPattern.matcher(combined)
        if (explicitMatcher.find()) {
            val candidate = explicitMatcher.group(1)?.trim()
            if (candidate != null && !candidate.matches(Regex("^[0-9.]+$"))) {
                sender = candidate
            }
        }
        if (sender == null) {
            val cryptoAfterMatcher = cryptoAfterSenderPattern.matcher(combined)
            if (cryptoAfterMatcher.find()) {
                val candidate = cryptoAfterMatcher.group(1)?.trim()
                if (candidate != null && !candidate.matches(Regex("^[0-9.]+$"))) {
                    sender = candidate
                }
            }
        }
        if (sender == null) {
            val fallbackMatcher = fallbackSenderPattern.matcher(combined)
            if (fallbackMatcher.find()) {
                val candidate = fallbackMatcher.group(1)?.trim()
                if (candidate != null && !candidate.matches(Regex("^[0-9.]+$"))) {
                    sender = candidate
                }
            }
        }

        // Extraer referencia u orden
        val orderMatcher = orderPattern.matcher(combined)
        val explicitRef = if (orderMatcher.find()) orderMatcher.group(1)?.trim() else null

        val reference = when {
            !explicitRef.isNullOrBlank() -> explicitRef
            !ParserUtils.extractReference(combined).isNullOrBlank() -> ParserUtils.extractReference(combined)!!
            sender != null && sender.filter { it.isDigit() }.length >= 4 -> sender.filter { it.isDigit() }
            sender != null && sender.length >= 4 -> sender
            else -> (postTimeMillis % 10000).toString().padStart(4, '0')
        }

        val last4 = if (reference.length >= 4) reference.takeLast(4) else reference

        return ParsedPayment(
            monto = amount,
            montoRaw = "$amountRaw USDT",
            moneda = "USD",
            bancoEmisor = "Binance Pay",
            bancoCodigo = BankCode.BINANCE,
            referencia = reference,
            referenciaUltimos4 = last4,
            telefonoOrigen = sender,
            timestampMillis = postTimeMillis,
            paqueteOrigen = packageName,
            textoCrudo = combined.trim(),
            canal = if (ParserUtils.isEmailPackage(packageName)) "EMAIL" else "PUSH"
        )
    }
}
