package com.cobrix.pocket.utils

import android.content.Context
import com.cobrix.pocket.data.model.CountryCode
import com.cobrix.pocket.data.model.CountryProfileCatalog
import com.cobrix.pocket.data.model.PlanType
import com.cobrix.pocket.data.model.PrimaryBillingStrategy

/**
 * Repositorio Híbrido de Licencias (Fase 1).
 * Unifica de forma limpia y no destructiva:
 * 1. ExternalCodeLicenseProvider: El sistema existente de códigos (ZeasySyncManager + PlanCapabilityManager).
 * 2. GooglePlayBillingProvider: Soporte para suscripciones de Google Play Billing en CO, AR y VE.
 *
 * Garantiza cumplimiento estricto de políticas de Google Play utilizando terminología neutral
 * ("Soporte comercial", "Atención al cliente", "Canjear código de licencia").
 */
object LicenseRepository {

    private const val PREFS_PLAY_BILLING = "zeasy_play_billing_prefs"
    private const val KEY_PLAY_SUB_ACTIVE = "play_sub_active"
    private const val KEY_PLAY_SUB_EXPIRATION = "play_sub_expiration"
    private const val KEY_PLAY_PURCHASE_TOKEN = "play_purchase_token"

    /**
     * Evalúa mediante condición OR si el dispositivo cuenta con Plan PRO activo
     * ya sea por el proveedor externo existente (Zeasy Hub) o por Google Play Billing.
     */
    fun isProActive(context: Context): Boolean {
        if (PlanCapabilityManager.isPro(context)) return true
        return isGooglePlaySubscriptionActive(context)
    }

    fun getEffectivePlanType(context: Context): PlanType {
        val basePlan = PlanCapabilityManager.getPlanType(context)
        if (basePlan != PlanType.FREE) return basePlan
        if (isGooglePlaySubscriptionActive(context)) return PlanType.PRO_ACTIVE
        return PlanType.FREE
    }

    /**
     * Verifica si existe una suscripción activa adquirida mediante Google Play Billing.
     */
    fun isGooglePlaySubscriptionActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_PLAY_BILLING, Context.MODE_PRIVATE)
        val active = prefs.getBoolean(KEY_PLAY_SUB_ACTIVE, false)
        val exp = prefs.getLong(KEY_PLAY_SUB_EXPIRATION, 0L)
        return active && exp > System.currentTimeMillis()
    }

    /**
     * Registra localmente una suscripción validada de Google Play Billing y la sincroniza con PlanCapabilityManager.
     */
    fun registerGooglePlaySubscription(
        context: Context,
        purchaseToken: String,
        expirationTimeMillis: Long
    ) {
        context.getSharedPreferences(PREFS_PLAY_BILLING, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PLAY_SUB_ACTIVE, true)
            .putLong(KEY_PLAY_SUB_EXPIRATION, expirationTimeMillis)
            .putString(KEY_PLAY_PURCHASE_TOKEN, purchaseToken)
            .apply()

        PlanCapabilityManager.setServerProExpiration(context, expirationTimeMillis)
    }

    /**
     * Canjea un código de licencia delegando al 100% en el flujo existente de ZeasySyncManager.
     */
    suspend fun redeemLicenseCode(context: Context, rawCode: String): ZeasySyncManager.RedeemResult {
        return ZeasySyncManager.redeemActivationCode(context, rawCode)
    }

    suspend fun redeemExternalLicenseCode(context: Context, rawCode: String): ZeasySyncManager.RedeemResult {
        return redeemLicenseCode(context, rawCode)
    }

    /**
     * Determina la estrategia de activación primaria según el país configurado en la app.
     */
    fun getPrimaryBillingStrategy(context: Context): PrimaryBillingStrategy {
        val country = PreferencesHelper.getCountryCode(context)
        return CountryProfileCatalog.getProfile(country).billingStrategy
    }

    fun shouldShowPlayBillingOption(context: Context): Boolean {
        val country = PreferencesHelper.getCountryCode(context)
        return country == CountryCode.CO || country == CountryCode.AR
    }
}

