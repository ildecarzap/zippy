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
        Pattern.compile("""(?:bs\.?|bsd\.?|bss\.?|ves|bsf\.?)\s*[:.]?\s*($AMOUNT_NUMBER_REGEX)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""($AMOUNT_NUMBER_REGEX)\s*[:.]?\s*(?:bs\.?|bsd\.?|bss\.?|ves)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""(?:monto|por|valor|importe)\s*[:.]?\s*(?:bs\.?|ves)?\s*($AMOUNT_NUMBER_REGEX)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""(?:recibido|recibiste|abono|acreditado|acreditada)\s*(?:de|por)?\s*[:.]?\s*(?:bs\.?|ves)?\s*($AMOUNT_NUMBER_REGEX)""", Pattern.CASE_INSENSITIVE)
    )

    // Extrae referencias de 4 a 16 dígitos
    private val REFERENCE_PATTERNS = listOf(
        Pattern.compile("""(?:ref\.?|referencia|nro\.?|operaci[oó]n|num|clave|comprobante|recibo|secuencia|aprobaci[oó]n|id|transacci[oó]n)\s*[:.]?\s*([0-9]{4,16})""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""\b(?:ref|nro|id|sec|op)[:#\s]*([0-9]{4,16})\b""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""\b([0-9]{6,16})\b""") // Números de 6+ dígitos si no tienen palabra ref explícita
    )

    // Extrae números venezolanos (0414, 0424, 0412, 0416, 0426)
    private val PHONE_PATTERN = Pattern.compile(
        """(?:\+?58|0)?\s*(414|424|412|416|426)[-.\s]*([0-9]{3})[-.\s]*([0-9]{4})"""
    )

    // Catálogo de bancos para identificación del emisor en el texto (incluyendo números cortos SMS oficiales)
    private val KNOWN_BANKS = mapOf(
        "mercantil" to "Banco Mercantil",
        "24024" to "Banco Mercantil",
        "banesco" to "Banesco",
        "2846" to "Banesco",
        "venezuela" to "Banco de Venezuela",
        "bdv" to "Banco de Venezuela",
        "2661" to "Banco de Venezuela",
        "2662" to "Banco de Venezuela",
        "provincial" to "BBVA Provincial",
        "bbva" to "BBVA Provincial",
        "2282" to "BBVA Provincial",
        "bnc" to "Banco Nacional de Crédito",
        "banco nacional de credito" to "Banco Nacional de Crédito",
        "bancamiga" to "Bancamiga",
        "8234" to "Bancamiga",
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
        val detectedPhone = extractPhone(text)
        for (pattern in REFERENCE_PATTERNS) {
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                val ref = matcher.group(1)?.trim()
                if (!ref.isNullOrBlank() && ref.length >= 4) {
                    // Evitar que un número de teléfono sea catalogado como referencia bancaria
                    if (detectedPhone != null && (ref == detectedPhone || (ref.length >= 7 && detectedPhone.contains(ref)))) {
                        continue
                    }
                    if ((ref.startsWith("0414") || ref.startsWith("0424") || ref.startsWith("0412") ||
                         ref.startsWith("0416") || ref.startsWith("0426") || ref.startsWith("584")) && ref.length in 10..12) {
                        continue
                    }
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

    // Códigos cortos SMS oficiales asignados a la banca en Venezuela
    val OFFICIAL_BANK_SMS_SHORTCODES = setOf(
        "2661", "2662", "26371", // BDV (Banco de Venezuela)
        "2846",                  // Banesco
        "24024",                 // Banco Mercantil
        "2282",                  // BBVA Provincial
        "8234",                  // Bancamiga
        "2620", "2621",          // BNC (Banco Nacional de Crédito)
        "2376",                  // Banco del Tesoro
        "2274",                  // Bancaribe
        "100100",                // 100% Banco
        "8244"                   // Ubii
    )

    fun isSmsPackage(packageName: String): Boolean {
        val lower = packageName.lowercase(Locale.ROOT)
        return lower.contains("mms") ||
               lower.contains("messaging") ||
               lower.contains("message") ||
               lower.contains("telephony")
    }

    /**
     * Valida si el remitente de una notificación SMS es un emisor bancario institucional legítimo.
     * FILTRO ANTI-ESTAFAS: Bloquea inmediatamente mensajes de números celulares personales (0414, 0424, etc.)
     * para evitar que un tercero envíe un SMS falso fingiendo ser el banco.
     */
    fun isLegitimateBankSmsSender(sender: String?): Boolean {
        if (sender.isNullOrBlank()) return false
        val clean = sender.replace("+", "").replace("-", "").replace(" ", "").trim().lowercase(Locale.ROOT)

        // 1. Si el remitente es un número celular personal venezolano o estándar internacional -> BLOQUEAR
        val isMobileNumber = clean.startsWith("58414") || clean.startsWith("58424") || clean.startsWith("58412") ||
                clean.startsWith("58416") || clean.startsWith("58426") ||
                clean.startsWith("0414") || clean.startsWith("0424") || clean.startsWith("0412") ||
                clean.startsWith("0416") || clean.startsWith("0426") ||
                clean.startsWith("414") || clean.startsWith("424") || clean.startsWith("412") ||
                clean.startsWith("416") || clean.startsWith("426")

        if (isMobileNumber) {
            return false // Es un teléfono celular común y corriente de persona, NUNCA un banco
        }

        // Si es una cadena numérica de más de 6 dígitos que no sea un shortcode conocido -> BLOQUEAR
        if (clean.all { it.isDigit() } && clean.length > 6 && !OFFICIAL_BANK_SMS_SHORTCODES.contains(clean)) {
            return false
        }

        // 2. Si es uno de los códigos cortos bancarios oficiales (2661, 2846, 24024, etc.) -> VÁLIDO
        if (OFFICIAL_BANK_SMS_SHORTCODES.contains(clean)) {
            return true
        }

        // 3. Si el remitente es un nombre corporativo / Alphanumeric Sender ID oficial de banco -> VÁLIDO
        val hasBankKeyword = clean.contains("bdv") || clean.contains("venezuela") ||
                clean.contains("banesco") || clean.contains("mercantil") ||
                clean.contains("provincial") || clean.contains("bbva") ||
                clean.contains("bnc") || clean.contains("bancamiga") ||
                clean.contains("bancaribe") || clean.contains("tesoro") ||
                clean.contains("banplus") || clean.contains("plaza") ||
                clean.contains("sofitasa") || clean.contains("ubii") ||
                clean.contains("100% banco") || clean.contains("100%banco") ||
                clean.contains("banco")

        return hasBankKeyword
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

    // Frases explícitas e inequívocas de que la transacción es un débito o pago saliente realizado por el usuario
    private val STRICT_OUTGOING_KEYWORDS = listOf(
        "has enviado",
        "enviaste",
        "has realizado un pago",
        "realizaste un pago",
        "envio de pago movil",
        "envío de pago móvil",
        "envio de pago",
        "envío de pago",
        "pago movil enviado",
        "pago móvil enviado",
        "debito a su cuenta",
        "débito a su cuenta",
        "debito en cuenta",
        "débito en cuenta",
        "cargo en cuenta",
        "transferencia enviada",
        "transferencia realizada a terceros",
        "transferencia a terceros",
        "transferencia saliente",
        "has transferido",
        "transferiste",
        "comprobante de envio",
        "comprobante de envío",
        "compra pos",
        "compra en punto de venta",
        "retiro de cajero",
        "retiro en cajero",
        "retiro cajero",
        "retiro atm",
        "pago de tarjeta",
        "pago tdd",
        "pago tdc",
        "salida de fondos",
        "recarga realizada",
        "pago de servicio"
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
        // Si contiene palabras inequívocas de crédito / dinero recibido, NUNCA es saliente,
        // salvo que explícitamente diga que el usuario lo envió
        if (hasIncomingIndicators(text)) {
            val userSentExplicitly = lower.contains("has enviado") ||
                    lower.contains("enviaste") ||
                    lower.contains("pago movil enviado") ||
                    lower.contains("pago móvil enviado") ||
                    lower.contains("has transferido") ||
                    lower.contains("transferiste")
            return userSentExplicitly
        }
        return STRICT_OUTGOING_KEYWORDS.any { lower.contains(it) }
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
