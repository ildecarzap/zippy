package com.cobrix.pocket.data.model

/**
 * Entidad normalizada que contiene los datos críticos extraídos de una notificación bancaria.
 */
data class ParsedPayment(
    val monto: Double,
    val montoRaw: String,
    val moneda: String = "VES",
    val bancoEmisor: String,
    val bancoCodigo: BankCode,
    val referencia: String,
    val referenciaUltimos4: String,
    val telefonoOrigen: String? = null,
    val fechaNotificacion: String? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
    val paqueteOrigen: String,
    val textoCrudo: String,
    val canal: String = "PUSH" // "PUSH" o "SMS"
)
