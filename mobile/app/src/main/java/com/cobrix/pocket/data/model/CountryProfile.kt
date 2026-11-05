package com.cobrix.pocket.data.model

import java.util.Locale

/**
 * Países soportados por Zeasy con configuración monetaria, bancaria y de voz específica.
 */
enum class CountryCode(
    val isoCode: String,
    val displayName: String,
    val flagEmoji: String,
    val phonePrefix: String
) {
    VE("VE", "Venezuela", "🇻🇪", "58"),
    CO("CO", "Colombia", "🇨🇴", "57"),
    AR("AR", "Argentina", "🇦🇷", "54");

    override fun toString(): String = "$flagEmoji $displayName ($isoCode)"

    companion object {
        fun fromIso(code: String?): CountryCode {
            return values().firstOrNull { it.isoCode.equals(code?.trim(), ignoreCase = true) } ?: VE
        }
    }
}

enum class CurrencyMode {
    BI_CURRENCY_USD_LOCAL, // Venezuela: Indexado en USD + cobro en Bs. con tasa oficial BCV
    SINGLE_LOCAL_CURRENCY  // Colombia (COP) y Argentina (ARS): Moneda local directa
}

enum class PrimaryBillingStrategy {
    EXTERNAL_CODE_FIRST,       // VE: Prioriza Código de Licencia / Soporte Comercial
    GOOGLE_PLAY_BILLING_FIRST  // CO, AR: Google Play Billing + Soporte Comercial / Código
}

data class CountryProfile(
    val country: CountryCode,
    val localCurrencyCode: String,       // "VES", "COP", "ARS"
    val localCurrencySymbol: String,     // "Bs.", "$", "$"
    val localCurrencyDisplay: String,    // "Bolívares (Bs.)", "Pesos (COP $)", "Pesos (ARS $)"
    val localCurrencyVoiceName: String,  // "bolívares", "pesos", "pesos"
    val ttsLocale: Locale,               // es_VE, es_CO, es_AR
    val currencyMode: CurrencyMode,
    val requiresExchangeRate: Boolean,   // true en VE (BCV), false en CO/AR
    val idDocumentLabel: String,         // VE: "Cédula / RIF", CO: "Cédula / NIT", AR: "DNI / CUIT / Alias"
    val idDocumentHint: String,          // Ejemplo visual adaptado al país
    val phoneHint: String,               // Ejemplo de teléfono local
    val instantPaySystemName: String,    // VE: "Pago Móvil", CO: "Transferencia / QR", AR: "Transferencias 3.0"
    val billingStrategy: PrimaryBillingStrategy
) {
    val code: CountryCode get() = country
    val flagEmoji: String get() = country.flagEmoji
    val displayName: String get() = country.displayName
    val supportsExchangeRate: Boolean get() = requiresExchangeRate
    val instantPaymentSystemName: String get() = instantPaySystemName
    val phonePlaceholder: String get() = phoneHint
    val nationalIdLabel: String get() = idDocumentLabel
    val nationalIdPlaceholder: String get() = idDocumentHint
}

object CountryProfileCatalog {

    val VENEZUELA = CountryProfile(
        country = CountryCode.VE,
        localCurrencyCode = "VES",
        localCurrencySymbol = "Bs.",
        localCurrencyDisplay = "Bolívares (Bs.)",
        localCurrencyVoiceName = "bolívares",
        ttsLocale = Locale("es", "VE"),
        currencyMode = CurrencyMode.BI_CURRENCY_USD_LOCAL,
        requiresExchangeRate = true,
        idDocumentLabel = "Cédula o RIF",
        idDocumentHint = "V12345678 o J123456789",
        phoneHint = "04141234567",
        instantPaySystemName = "Pago Móvil",
        billingStrategy = PrimaryBillingStrategy.EXTERNAL_CODE_FIRST
    )

    val COLOMBIA = CountryProfile(
        country = CountryCode.CO,
        localCurrencyCode = "COP",
        localCurrencySymbol = "COP $",
        localCurrencyDisplay = "Pesos Colombianos (COP $)",
        localCurrencyVoiceName = "pesos",
        ttsLocale = Locale("es", "CO"),
        currencyMode = CurrencyMode.SINGLE_LOCAL_CURRENCY,
        requiresExchangeRate = false,
        idDocumentLabel = "Cédula o NIT",
        idDocumentHint = "1020304050 o 900123456-1",
        phoneHint = "3001234567",
        instantPaySystemName = "Transferencia (Nequi / Bancos)",
        billingStrategy = PrimaryBillingStrategy.GOOGLE_PLAY_BILLING_FIRST
    )

    val ARGENTINA = CountryProfile(
        country = CountryCode.AR,
        localCurrencyCode = "ARS",
        localCurrencySymbol = "ARS $",
        localCurrencyDisplay = "Pesos Argentinos (ARS $)",
        localCurrencyVoiceName = "pesos",
        ttsLocale = Locale("es", "AR"),
        currencyMode = CurrencyMode.SINGLE_LOCAL_CURRENCY,
        requiresExchangeRate = false,
        idDocumentLabel = "CUIT o DNI",
        idDocumentHint = "20-12345678-9 o DNI",
        phoneHint = "mi.negocio.mp o 1123456789",
        instantPaySystemName = "Transferencia (Alias / CVU)",
        billingStrategy = PrimaryBillingStrategy.GOOGLE_PLAY_BILLING_FIRST
    )

    fun getProfile(code: CountryCode): CountryProfile {
        return when (code) {
            CountryCode.VE -> VENEZUELA
            CountryCode.CO -> COLOMBIA
            CountryCode.AR -> ARGENTINA
        }
    }

    fun getAllProfiles(): List<CountryProfile> = listOf(VENEZUELA, COLOMBIA, ARGENTINA)

    /**
     * Retorna la lista blanca estricta de packageNames autorizados para operar en el país indicado.
     * Ninguna notificación proveniente de una app ajena a esta lista (ej. mensajería o redes sociales)
     * debe ser procesada por Zeasy.
     */
    fun getAllowedPackagesForCountry(
        country: CountryCode,
        includeSms: Boolean = true,
        includeBinance: Boolean = true
    ): Set<String> {
        val packages = mutableSetOf<String>()

        // 1. Bancos y billeteras oficiales del país activo
        val countryBanks = BankCode.values().filter { it.country == country }
        countryBanks.forEach { bank ->
            bank.knownPackageNames.forEach { pkg ->
                packages.add(pkg.lowercase(Locale.ROOT))
            }
        }

        // 2. Mensajería SMS bancaria del sistema (Sólo en Venezuela y si el usuario activó SMS en Ajustes)
        if (country == CountryCode.VE && includeSms) {
            BankCode.SMS.knownPackageNames.forEach { pkg ->
                packages.add(pkg.lowercase(Locale.ROOT))
            }
            packages.add("com.motorola.messaging")
            packages.add("com.sonyericsson.conversations")
            packages.add("com.asus.message")
            packages.add("com.android.messaging")
        }

        // 3. Binance Pay (Transversal, si está activo)
        if (includeBinance) {
            BankCode.BINANCE.knownPackageNames.forEach { pkg ->
                packages.add(pkg.lowercase(Locale.ROOT))
            }
        }

        return packages
    }
}

