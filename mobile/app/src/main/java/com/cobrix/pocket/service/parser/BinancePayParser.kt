package com.cobrix.pocket.service.parser

import com.cobrix.pocket.data.model.BankCode
import com.cobrix.pocket.data.model.ParsedPayment
import java.util.Locale
import java.util.regex.Pattern

class BinancePayParser : BankParser {
    override val bankCode: BankCode = BankCode.BINANCE

    private val binanceAmountPattern = Pattern.compile("(?i)(?:received|recibiste|recibido|has recibido|payment of)\\s*(?:approx\\.?|aprox\\.?)?\\s*([0-9]+(?:[.,][0-9]{1,8})?)\\s*(?:USDT|USD|BUSD)")
    private val genericUsdtPattern = Pattern.compile("(?i)([0-9]+(?:[.,][0-9]{1,8})?)\\s*(?:USDT)")

    override fun canHandle(packageName: String, title: String?, text: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT)
        if (pkg.contains("binance")) return true

        val combined = "$title $text".lowercase(Locale.ROOT)
        return combined.contains("binance") && (combined.contains("pay") || combined.contains("usdt") || combined.contains("received") || combined.contains("recibido"))
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

        val matcher = binanceAmountPattern.matcher(combined)
        if (matcher.find()) {
            amountRaw = matcher.group(1)?.replace(',', '.') ?: ""
            amount = amountRaw.toDoubleOrNull()
        }

        if (amount == null) {
            val genericMatcher = genericUsdtPattern.matcher(combined)
            if (genericMatcher.find()) {
                amountRaw = genericMatcher.group(1)?.replace(',', '.') ?: ""
                amount = amountRaw.toDoubleOrNull()
            }
        }

        if (amount == null) {
            val fallback = ParserUtils.extractAmount(combined)
            if (fallback != null) {
                amount = fallback.first
                amountRaw = fallback.second
            }
        }

        if (amount == null || amount <= 0.0) return null

        val reference = ParserUtils.extractReference(combined) ?: (postTimeMillis % 10000).toString().padStart(4, '0')
        val last4 = if (reference.length >= 4) reference.takeLast(4) else reference

        return ParsedPayment(
            monto = amount,
            montoRaw = "$amountRaw USDT",
            moneda = "USD",
            bancoEmisor = "Binance Pay",
            bancoCodigo = BankCode.BINANCE,
            referencia = reference,
            referenciaUltimos4 = last4,
            telefonoOrigen = null,
            timestampMillis = postTimeMillis,
            paqueteOrigen = packageName,
            textoCrudo = combined.trim(),
            canal = "PUSH"
        )
    }
}
