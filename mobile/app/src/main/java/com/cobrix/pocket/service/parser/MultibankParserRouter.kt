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
        UbiiParser(),
        MercantilParser(),
        BanescoParser(),
        BdvParser(),
        ProvincialParser(),
        BncParser(),
        BancamigaParser(),
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
        val parsersToUse = if (enabledBankCodes.isEmpty()) {
            allParsers
        } else {
            allParsers.filter { enabledBankCodes.contains(it.bankCode) }
        }

        val combined = "$title $text"

        // 1. FILTRO DE SALIDA: Descartar inmediatamente notificaciones de débito, compras o envíos salientes
        if (ParserUtils.isOutgoingTransaction(combined)) {
            Log.d(TAG, "Notificación descartada: es un pago saliente/débito ($packageName)")
            return null
        }

        // 2. FILTRO DE CORREO: Si proviene de un cliente de correo (Gmail, Outlook), exigir indicador explícito de abono/recibido
        val isEmail = ParserUtils.isEmailPackage(packageName)
        if (isEmail && !ParserUtils.hasIncomingIndicators(combined)) {
            Log.d(TAG, "Email descartado: no contiene indicadores de crédito/abono/pago recibido ($packageName)")
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
