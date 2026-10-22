package com.cobrix.pocket.data.model

data class UserProfile(
    val merchantName: String = "",       // Nombre del negocio o chofer
    val businessCategory: String = "Transporte / Taxi", // Rubro del comercio
    val bankCode: String = "0134",        // Código del banco receptor (ej. 0134 Banesco)
    val bankName: String = "Banesco",     // Nombre del banco receptor
    val phoneNumber: String = "",         // Teléfono registrado en Pago Móvil (ej. 04141234567)
    val idCard: String = "",              // Cédula o RIF (ej. V-18456789)
    val qrImageUri: String? = null,       // URI de la imagen estática del QR descargada del banco
    val bcvRate: Double = 45.0,           // Tasa de cambio VES/USD
    val soundNotificationEnabled: Boolean = true, // Alarma / voz al recibir pago
    val voiceAnnouncementEnabled: Boolean = true, // Anunciar por altavoz/Bluetooth
    val soundboxMode: String = "VOICE",   // "VOICE", "CHIME", "MUTED"
    val enabledBankCodes: Set<String> = setOf("0134", "0102", "0105", "0108", "0191", "0172"), // Bancos a detectar
    val biometricSecurityEnabled: Boolean = false // Bloqueo de apertura con huella o PIN
)

enum class LicenseState {
    ACTIVE,        // Suscripción al día
    GRACE_PERIOD,  // Vencida pero dentro de las 48h de cortesía
    EXPIRED        // Bloqueada
}

data class SubscriptionInfo(
    val deviceId: String,
    val state: LicenseState,
    val validUntilTimestamp: Long,
    val graceUntilTimestamp: Long,
    val monthlyPriceUsd: Double = 2.99
)
