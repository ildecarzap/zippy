package com.cobrix.pocket.service.parser

import com.cobrix.pocket.data.model.BankCode
import com.cobrix.pocket.data.model.ParsedPayment
import java.util.Locale
import java.util.regex.Pattern

class BinancePayParser : BankParser {
    override val bankCode: BankCode = BankCode.BINANCE

    // 1. Campo explícito de monto (común en correos de Binance): "Monto:\n0.5 USDT", "Monto: 0.50 USDT", "Amount: 0.50 USDT"
    private val fieldAmountPattern = Pattern.compile(
        """(?i)(?:monto|amount|importe)\s*[:.]?\s*([0-9]+(?:[.,][0-9]{1,8})?|[0-9]*[.,][0-9]+)\s*(?:USDT|USD|BUSD)?"""
    )

    // 2. Frase de pago/transferencia recibida: "Recibiste 0.5 USDT", "Payment received of 0.5 USDT", "Pago recibido: 0.50 USDT"
    private val receivedAmountPattern = Pattern.compile(
        """(?i)(?:received|recibiste|recibido|has recibido|ha recibido|payment of|pago recibido|pago de)\s*(?:un pago de\s*)?(?:approx\.?|aprox\.?)?\s*([0-9]+(?:[.,][0-9]{1,8})?|[0-9]*[.,][0-9]+)\s*(?:USDT|USD|BUSD)?"""
    )

    // 3. Monto seguido de token cripto explícito: "0.5 USDT", ".50 USDT", "0.50 BUSD"
    private val cryptoTokenAmountPattern = Pattern.compile(
        """(?i)([0-9]+(?:[.,][0-9]{1,8})?|[0-9]*[.,][0-9]+)\s*(?:USDT|BUSD)"""
    )

    // 4. Remitente / Pagador: "Remitente:\nMcdh-61869", "Remitente: Mcdh-61869", "Sender: John"
    private val senderPattern = Pattern.compile(
        """(?i)(?:remitente|sender|de|from)\s*[:.]?\s*([A-Za-z0-9_.-]+)"""
    )

    // 5. Referencia / ID de Orden o Pago: "ID de orden: 123456", "Order ID: 123456", "ID de transacción: ..."
    private val orderPattern = Pattern.compile(
        """(?i)(?:id de orden|order id|id de transacci[oó]n|transaction id|id de pago|pay id|referencia|ref)\s*[:.]?\s*([A-Za-z0-9_-]{4,24})"""
    )

    override fun canHandle(packageName: String, title: String?, text: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT)
        if (pkg.contains("binance")) return true

        val combined = "$title $text".lowercase(Locale.ROOT)
        return combined.contains("binance") && (
            combined.contains("pay") ||
            combined.contains("usdt") ||
            combined.contains("busd") ||
            combined.contains("received") ||
            combined.contains("recibido") ||
            combined.contains("recibiste") ||
            combined.contains("transferencia")
        )
    }

    override fun parse(
        packageName: String,
        title: String?,
        text: String,
        postTimeMillis: Long
    ): ParsedPayment? {
        val combined = "$title $text"
        if (ParserUtils.isOutgoingTransaction(combined)) return null

        var amount: Double? = null
        var amountRaw = ""

        // Intentar primero con el campo explícito de monto (ej: "Monto:\n0.5 USDT")
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

        // Intentar con frase de pago recibido (ej: "Recibiste 0.50 USDT")
        if (amount == null) {
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
        }

        // Intentar con patrón de token cripto explícito (ej: "0.5 USDT")
        if (amount == null) {
            val tokenMatcher = cryptoTokenAmountPattern.matcher(combined)
            if (tokenMatcher.find()) {
                var raw = tokenMatcher.group(1)?.replace(',', '.') ?: ""
                if (raw.startsWith(".")) raw = "0$raw"
                val parsed = raw.toDoubleOrNull()
                if (parsed != null && parsed > 0.0) {
                    amount = parsed
                    amountRaw = raw
                }
            }
        }

        // Fallback genérico de utilidades
        if (amount == null) {
            val fallback = ParserUtils.extractAmount(combined)
            if (fallback != null && fallback.first > 0.0) {
                amount = fallback.first
                amountRaw = fallback.second
            }
        }

        if (amount == null || amount <= 0.0) return null

        // Extraer remitente
        val senderMatcher = senderPattern.matcher(combined)
        val sender = if (senderMatcher.find()) senderMatcher.group(1)?.trim() else null

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
