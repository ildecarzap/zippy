package com.cobrix.pocket.service.parser

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.regex.Pattern

/**
 * Utilidades compartidas de expresiones regulares optimizadas para la banca venezolana.
 */
object ParserUtils {

    // Extrae montos en formatos: 1.250,50 | 1250,50 | 1250.50 | 50,00 | 50.00 precedidos o seguidos de Bs / VES
    private val AMOUNT_PATTERNS = listOf(
        Pattern.compile("""(?:bs\.?|bsd\.?|ves|bsf\.?)\s*([0-9]{1,3}(?:\.[0-9]{3})*(?:,[0-9]{1,2})?|[0-9]+(?:,[0-9]{1,2})?|[0-9]+\.[0-9]{2})""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""([0-9]{1,3}(?:\.[0-9]{3})*(?:,[0-9]{1,2})?|[0-9]+(?:,[0-9]{1,2})?)\s*(?:bs\.?|bsd\.?|ves)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""(?:monto|por|valor|importe)\s*[:.]?\s*(?:bs\.?|ves)?\s*([0-9]{1,3}(?:\.[0-9]{3})*(?:,[0-9]{1,2})?|[0-9]+(?:,[0-9]{1,2})?|[0-9]+\.[0-9]{2})""", Pattern.CASE_INSENSITIVE)
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
        "sofitasa" to "Banco Sofitasa"
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
        for ((keyword, bankFullName) in KNOWN_BANKS) {
            if (lower.contains(keyword)) {
                return bankFullName
            }
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
        "has recibido",
        "abono",
        "credito",
        "crédito",
        "acreditado",
        "acreditada",
        "te ha transferido",
        "te transfirio",
        "te transfirió",
        "pago recibido",
        "tpago recibido",
        "pagoclave recibido",
        "dinero rapido recibido",
        "dinero rápido recibido"
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

    private fun parseAmountStringToDouble(amountStr: String): Double? {
        return try {
            var clean = amountStr.trim()
            if (clean.contains(".") && clean.contains(",")) {
                // Formato venezolano: 1.250,50 -> 1250.50
                clean = clean.replace(".", "").replace(",", ".")
            } else if (clean.contains(",")) {
                // Formato con coma decimal: 50,00 -> 50.00
                clean = clean.replace(",", ".")
            }
            clean.toDoubleOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
