package com.cobrix.pocket.service.parser

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.regex.Pattern

/**
 * Utilidades compartidas de expresiones regulares optimizadas para la banca venezolana.
 */
object ParserUtils {

    private const val AMOUNT_NUMBER_REGEX =
        """(?:[0-9]{1,3}(?:[.,][0-9]{3})+(?:[.,][0-9]{1,2})?|[0-9]+(?:[.,][0-9]{1,2})?)(?!\d)"""

    // Extrae montos en formatos: 1.250,50 | 1.800,00 | 1800,00 | 1800.00 | 1.800 | 1800 | 50,00 | 50.00 precedidos o seguidos de Bs / VES
    private val AMOUNT_PATTERNS = listOf(
        Pattern.compile("""(?:bs\.?|bsd\.?|ves|bsf\.?)\s*($AMOUNT_NUMBER_REGEX)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""($AMOUNT_NUMBER_REGEX)\s*(?:bs\.?|bsd\.?|ves)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""(?:monto|por|valor|importe)\s*[:.]?\s*(?:bs\.?|ves)?\s*($AMOUNT_NUMBER_REGEX)""", Pattern.CASE_INSENSITIVE)
    )

    // Extrae referencias de 4 a 16 dígitos
    private val REFERENCE_PATTERNS = listOf(
        Pattern.compile("""(?:ref\.?|referencia|nro\.?|operaci[oó]n|num|clave)\s*[:.]?\s*([0-9]{4,16})""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""\b(?:ref|nro)[:#\s]*([0-9]{4,16})\b""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""\b([0-9]{8,16})\b""") // Números largos de 8+ dígitos si no tienen palabra ref explícita
    )

    // Extrae números venezolanos (0414, 0424, 0412, 0416, 0426)
    private val PHONE_PATTERN = Pattern.compile(
        """(?:\+?58|0)?\s*(414|424|412|416|426)[-.\s]*([0-9]{3})[-.\s]*([0-9]{4})"""
    )

    // Catálogo de bancos para identificación del emisor en el texto
    private val KNOWN_BANKS = mapOf(
        "mercantil" to "Banco Mercantil",
        "banesco" to "Banesco",
        "venezuela" to "Banco de Venezuela",
        "bdv" to "Banco de Venezuela",
        "provincial" to "BBVA Provincial",
        "bbva" to "BBVA Provincial",
        "bnc" to "Banco Nacional de Crédito",
        "banco nacional de credito" to "Banco Nacional de Crédito",
        "bancaribe" to "Bancaribe",
        "bancamiga" to "Bancamiga",
        "banplus" to "Banplus",
        "plaza" to "Banco Plaza",
        "tesoro" to "Banco del Tesoro",
        "bancaribe" to "Bancaribe",
        "exterior" to "Banco Exterior",
        "100% banco" to "100% Banco",
        "activo" to "Banco Activo",
        "sofitasa" to "Banco Sofitasa",
        "ubii" to "Ubii App",
        "ubiipagos" to "Ubii App"
    )

    fun extractAmount(text: String): Pair<Double, String>? {
        for (pattern in AMOUNT_PATTERNS) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val rawAmount = matcher.group(1)?.trim() ?: continue
                val parsed = parseAmountStringToDouble(rawAmount)
                if (parsed != null && parsed > 0.0) {
                    return Pair(parsed, rawAmount)
                }
            }
        }
        return null
    }

    fun extractReference(text: String): String? {
        for (pattern in REFERENCE_PATTERNS) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val ref = matcher.group(1)?.trim()
                if (!ref.isNullOrBlank() && ref.length >= 4) {
                    return ref
                }
            }
        }
        return null
    }

    fun extractPhone(text: String): String? {
        val matcher = PHONE_PATTERN.matcher(text)
        if (matcher.find()) {
            val operator = matcher.group(1)
            val part1 = matcher.group(2)
            val part2 = matcher.group(3)
            return "0$operator$part1$part2"
        }
        return null
    }

    fun extractBankName(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        // Priorizar bancos emisores específicos sobre Ubii si ambos están presentes en el texto
        for ((keyword, bankFullName) in KNOWN_BANKS) {
            if (keyword != "ubii" && keyword != "ubiipagos" && lower.contains(keyword)) {
                return bankFullName
            }
        }
        if (lower.contains("ubii") || lower.contains("ubiipagos")) {
            return "Ubii App"
        }
        return null
    }

    fun isSmsPackage(packageName: String): Boolean {
        val lower = packageName.lowercase(Locale.ROOT)
        return lower.contains("mms") ||
               lower.contains("messaging") ||
               lower.contains("message") ||
               lower.contains("telephony")
    }

    /**
     * Determina si el paquete origen pertenece a un gestor de correo electrónico (Gmail, Outlook, etc.).
     */
    fun isEmailPackage(packageName: String): Boolean {
        val lower = packageName.lowercase(Locale.ROOT)
        return lower.contains("gm") ||              // com.google.android.gm (Gmail)
               lower.contains("mail") ||            // apps de email estándar
               lower.contains("outlook") ||         // Microsoft Outlook
               lower.contains("email")
    }

    // Palabras inequívocas de que la transacción es un débito o pago saliente realizado por el usuario
    private val OUTGOING_KEYWORDS = listOf(
        "has realizado",
        "realizaste",
        "has enviado",
        "enviaste",
        "envio de pago movil",
        "envío de pago móvil",
        "envio de pago",
        "envío de pago",
        "envio exitoso",
        "envío exitoso",
        "pago movil enviado",
        "pago móvil enviado",
        "pago movil exitoso",
        "pago móvil exitoso",
        "debito",
        "débito",
        "cargo en cuenta",
        "cargado",
        "transferencia realizada",
        "transferencia enviada",
        "has transferido",
        "transferiste",
        "comprobante de envio",
        "comprobante de envío",
        "pago realizado",
        "pago emitido",
        "operacion realizada",
        "operación realizada",
        "compra realizada",
        "compra en",
        "compra pos",
        "punto de venta",
        "pago en comercio",
        "cargo por comision",
        "cargo por comisión",
        "comision",
        "comisión",
        "retiro de cajero",
        "retiro cajero",
        "retiro atm",
        "retiro",
        "pago de tarjeta",
        "pago tdd",
        "pago tdc",
        "transferencia a terceros",
        "transferencia saliente",
        "egreso",
        "salida de fondos",
        "recarga realizada",
        "pago de servicio",
        "consumo"
    )

    // Palabras inequívocas de que la transacción es un crédito o dinero recibido
    private val INCOMING_KEYWORDS = listOf(
        "recibido",
        "recibiste",
        "recibida",
        "recibio",
        "recibió",
        "has recibido",
        "ha recibido",
        "abono",
        "credito",
        "crédito",
        "acreditado",
        "acreditada",
        "te ha transferido",
        "te transfirio",
        "te transfirió",
        "pago recibido",
        "pago confirmado",
        "transferencia recibida",
        "tpago recibido",
        "pagoclave recibido",
        "dinero rapido recibido",
        "dinero rápido recibido",
        // English keywords (Binance Pay, Zinli, Gmail notification headers)
        "received",
        "payment received",
        "payment of",
        "you received",
        "you have received",
        "deposit confirmed",
        "transfer received",
        "credit",
        "credited"
    )

    /**
     * Detecta si una notificación corresponde a un pago saliente (débito / envío de dinero).
     * Estas notificaciones NUNCA deben procesarse como cobro.
     */
    fun isOutgoingTransaction(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return OUTGOING_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * Detecta si una notificación contiene palabras clave que confirman que es un pago recibido (crédito / abono).
     */
    fun hasIncomingIndicators(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return INCOMING_KEYWORDS.any { lower.contains(it) }
    }

    fun parseAmountStringToDouble(amountStr: String): Double? {
        return try {
            var clean = amountStr.trim()
            if (clean.contains(".") && clean.contains(",")) {
                val lastDot = clean.lastIndexOf('.')
                val lastComma = clean.lastIndexOf(',')
                clean = if (lastComma > lastDot) {
                    // Formato venezolano: 1.250,50 o 1.800,00 -> 1250.50
                    clean.replace(".", "").replace(",", ".")
                } else {
                    // Formato anglosajón: 1,800.00 -> 1800.00
                    clean.replace(",", "")
                }
            } else if (clean.contains(",")) {
                val parts = clean.split(",")
                clean = if (parts.size == 2 && parts[1].length == 3 && parts[0].length in 1..3) {
                    // Miles con coma sin decimales (1,800) -> 1800
                    parts[0] + parts[1]
                } else {
                    // Decimal con coma (50,00 o 1800,00) -> 50.00 o 1800.00
                    clean.replace(",", ".")
                }
            } else if (clean.contains(".")) {
                val parts = clean.split(".")
                clean = if (parts.size > 2) {
                    // Múltiples separadores de miles (1.000.000)
                    clean.replace(".", "")
                } else if (parts.size == 2 && parts[1].length == 3 && parts[0].length in 1..3) {
                    // Separador de miles venezolano sin decimales: 1.800 -> 1800
                    parts[0] + parts[1]
                } else {
                    // Decimal estándar: 50.00 o 1800.00
                    clean
                }
            }
            clean.toDoubleOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
