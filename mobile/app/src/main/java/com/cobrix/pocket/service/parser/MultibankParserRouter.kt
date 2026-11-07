package com.cobrix.pocket.service.parser

import android.util.Log
import com.cobrix.pocket.data.model.BankCode
import com.cobrix.pocket.data.model.ParsedPayment

/**
 * Enrutador inteligente multibanco.
 * Evalúa las notificaciones entrantes contra los bancos habilitados y ejecuta el parser adecuado.
 */
class MultibankParserRouter {

    companion object {
        private const val TAG = "MultibankParserRouter"
    }

    private val allParsers: List<BankParser> = listOf(
        // Venezuela (VE)
        UbiiParser(),
        MercantilParser(),
        BanescoParser(),
        BdvParser(),
        ProvincialParser(),
        BncParser(),
        BancamigaParser(),
        // Colombia (CO)
        NequiParser(),
        DaviPlataParser(),
        BancolombiaParser(),
        // Argentina (AR)
        MercadoPagoParser(),
        CuentaDniParser(),
        BnaMasParser(),
        UalaParser(),
        // Transversales
        BinancePayParser(),
        GenericSmsParser()
    )

    /**
     * Intenta parsear la notificación probando los parsers habilitados.
     * @param enabledBankCodes Conjunto de códigos de banco activos. Si está vacío, prueba todos.
     */
    fun routeAndParse(
        packageName: String,
        title: String?,
        text: String,
        postTimeMillis: Long,
        enabledBankCodes: Set<BankCode> = emptySet()
    ): ParsedPayment? {
        val cleanPkg = packageName.lowercase(java.util.Locale.ROOT)

        // 0. CAPA DE SEGURIDAD DEFENSIVA (ANTI-SPOOFING): Bloqueo total e inmediato de mensajería (WhatsApp, Telegram, etc.)
        if (ParserUtils.isProhibitedMessagingPackage(cleanPkg)) {
            Log.w(TAG, "Bloqueo de seguridad anti-spoofing: paquete de mensajería descartado de inmediato ($packageName)")
            return null
        }

        val parsersToUse = if (enabledBankCodes.isEmpty()) {
            allParsers
        } else {
            allParsers.filter { enabledBankCodes.contains(it.bankCode) }
        }

        val combined = "$title $text"

        // 1. FILTRO ANTI-ESTAFAS PARA SMS: Si la notificación proviene de una app de SMS (Google Messages, etc.),
        // el remitente (title) DEBE ser un código corto bancario oficial o un nombre institucional de banco.
        // Se bloquea de inmediato cualquier SMS proveniente de números celulares personales (0414, 0424, 0412, etc.).
        if (ParserUtils.isSmsPackage(packageName)) {
            if (!ParserUtils.isLegitimateBankSmsSender(title)) {
                Log.w(TAG, "SMS descartado por seguridad anti-fraude: el remitente '$title' es un teléfono personal y no un banco oficial.")
                return null
            }
        }

        // 1. FILTRO ANTI-PUBLICIDAD Y NO-OPERATIVOS: Descartar promociones, préstamos, claves dinámicas o fallos
        if (ParserUtils.isPromotionalOrNonPaymentNotification(combined)) {
            Log.d(TAG, "Notificación descartada: es promocional, clave dinámica o no operativa ($packageName)")
            return null
        }

        // 2. FILTRO DE CORREO: Si proviene de un cliente de correo (Gmail, Outlook), exigir indicador explícito de abono recibido o de salida bancaria
        val isEmail = ParserUtils.isEmailPackage(packageName)
        if (isEmail && !ParserUtils.hasIncomingIndicators(combined) && !ParserUtils.isOutgoingTransaction(combined)) {
            Log.d(TAG, "Email descartado: no contiene indicadores de cobro recibido ni de salida bancaria ($packageName)")
            return null
        }

        // 3. Fase de coincidencia directa (canHandle)
        for (parser in parsersToUse) {
            if (parser.canHandle(packageName, title, text)) {
                val result = parser.parse(packageName, title, text, postTimeMillis)
                if (result != null) {
                    Log.i(TAG, "Notificación parseada con éxito por: ${parser.bankCode.displayName}")
                    return result
                }
            }
        }

        // 4. Si es un paquete de SMS genérico y no encajó en bancos específicos, probar GenericSmsParser
        if (ParserUtils.isSmsPackage(packageName)) {
            val smsParser = allParsers.firstOrNull { it is GenericSmsParser }
            val result = smsParser?.parse(packageName, title, text, postTimeMillis)
            if (result != null) {
                Log.i(TAG, "Notificación parseada como SMS bancario genérico")
                return result
            }
        }

        return null
    }
}
