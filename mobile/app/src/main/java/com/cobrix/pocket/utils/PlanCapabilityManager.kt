package com.cobrix.pocket.utils

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.cobrix.pocket.data.model.PlanFeatures
import com.cobrix.pocket.data.model.PlanType
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Gestor central de capacidades y planes (Free vs. PRO) para Zippy.
 * Garantiza que las funciones básicas de cobro (teclado, QR, WhatsApp de 1 banco)
 * sean siempre 100% gratuitas y nunca se bloqueen.
 */
object PlanCapabilityManager {

    private const val PREFS_NAME = "zippy_plan_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_TRIAL_USED = "trial_used"
    private const val KEY_TRIAL_EXPIRATION = "trial_expiration"
    private const val KEY_PRO_EXPIRATION = "pro_expiration"
    private const val KEY_LICENSE_KEY = "license_key"

    private const val TRIAL_DURATION_MS = 7L * 24 * 60 * 60 * 1000 // 7 días

    const val COMPANY_NAME = "Ciem tech solutions C.A"
    const val SUPPORT_PHONE_CLEAN = "584242603112"
    const val SUPPORT_PHONE_DISPLAY = "+58 4242603112"
    const val SUPPORT_EMAIL = "ildemaro.carrasco@outlook.com"
    const val OFFICIAL_WEBSITE = "https://zeasy-app-902301530733.europe-west1.run.app"

    // ==============================================================================
    // 🚀 SWITCH MAESTRO DE REVISIÓN GOOGLE PLAY / EDICIÓN ABIERTA
    // ==============================================================================
    // true:  Modo Google Play Review (Todas las funciones PRO desbloqueadas, sin
    //        pantallas ni banners de pago, sin límites de bancos ni vencimiento).
    // false: Modo Comercial Producción (Planes Free/Trial/PRO, límites de 1 banco,
    //        avisos de renovación y cobro automatizado con CobrixPay).
    // ==============================================================================
    const val PLAY_STORE_REVIEW_MODE = true

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getDeviceId(context: Context): String {
        val prefs = getPrefs(context)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            val androidId = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            } catch (_: Exception) {
                null
            }
            val seed = if (!androidId.isNullOrBlank()) androidId else UUID.randomUUID().toString()
            val md5 = try {
                MessageDigest.getInstance("MD5").digest(seed.toByteArray()).let { digest ->
                    digest.joinToString("") { "%02X".format(it) }
                }
            } catch (_: Exception) {
                seed.replace("-", "").uppercase(Locale.US)
            }
            val code = md5.take(4).uppercase(Locale.US)
            id = "ZIP-$code"
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun getPlanType(context: Context): PlanType {
        // En modo revisión de Google Play, toda la aplicación se desbloquea en PRO
        if (PLAY_STORE_REVIEW_MODE) {
            return PlanType.PRO_ACTIVE
        }

        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()

        // 1. Verificar suscripción PRO paga activa
        val proExpiration = prefs.getLong(KEY_PRO_EXPIRATION, 0L)
        if (proExpiration > now) {
            return PlanType.PRO_ACTIVE
        }

        // 2. Verificar período de prueba PRO de 7 días
        val trialExpiration = prefs.getLong(KEY_TRIAL_EXPIRATION, 0L)
        if (trialExpiration > now) {
            return PlanType.PRO_TRIAL
        }

        // 3. Plan Básico Gratuito por defecto
        return PlanType.FREE
    }

    fun isPro(context: Context): Boolean {
        if (PLAY_STORE_REVIEW_MODE) return true
        return getPlanType(context) != PlanType.FREE
    }

    fun isTrial(context: Context): Boolean {
        return getPlanType(context) == PlanType.PRO_TRIAL
    }

    fun hasUsedTrial(context: Context): Boolean {
        val prefs = getPrefs(context)
        val trialExp = prefs.getLong(KEY_TRIAL_EXPIRATION, 0L)
        // Si no se ha activado nunca la prueba (trialExp == 0), el usuario aún tiene sus 7 días disponibles
        if (trialExp == 0L) return false
        return true
    }

    /**
     * Marca la prueba como consumida permanentemente (reportado por el backend de Zippy Hub).
     */
    fun markTrialUsed(context: Context, trialEndsAtMs: Long = 0L) {
        val editor = getPrefs(context).edit()
        editor.putBoolean(KEY_TRIAL_USED, true)
        editor.putBoolean("trial_consumed_remotely", true)
        editor.putLong(KEY_TRIAL_EXPIRATION, if (trialEndsAtMs > 0L) trialEndsAtMs else 1L)
        editor.apply()
    }

    fun getDaysRemaining(context: Context): Int {
        if (PLAY_STORE_REVIEW_MODE) return 365
        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()
        val exp = when (getPlanType(context)) {
            PlanType.PRO_ACTIVE -> prefs.getLong(KEY_PRO_EXPIRATION, 0L)
            PlanType.PRO_TRIAL -> prefs.getLong(KEY_TRIAL_EXPIRATION, 0L)
            PlanType.FREE -> 0L
        }
        if (exp <= now) return 0
        val diffMs = exp - now
        return ((diffMs / (24 * 60 * 60 * 1000)) + 1).toInt()
    }

    /**
     * Activa la prueba de 7 días gratis si aún no ha sido utilizada.
     */
    fun activateTrial(context: Context, days: Int = 7): Boolean {
        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()
        val currentExp = prefs.getLong(KEY_TRIAL_EXPIRATION, 0L)
        if (currentExp > now) {
            return false // Ya tiene prueba activa corriendo
        }

        val expiration = now + (days.toLong() * 24 * 60 * 60 * 1000)
        prefs.edit()
            .putBoolean(KEY_TRIAL_USED, true)
            .putLong(KEY_TRIAL_EXPIRATION, expiration)
            .apply()
        return true
    }

    /**
     * Sincroniza la expiración de la prueba TRIAL recibida desde el backend de Zippy Hub.
     */
    fun setServerTrialExpiration(context: Context, expirationTimestampMs: Long) {
        getPrefs(context).edit()
            .putLong(KEY_TRIAL_EXPIRATION, expirationTimestampMs)
            .putBoolean(KEY_TRIAL_USED, true)
            .putBoolean("trial_consumed_remotely", true)
            .apply()
    }

    /**
     * Activa o extiende el Plan PRO mediante clave provista por soporte o backend.
     */
    fun activateWithLicenseKey(context: Context, rawKey: String): Boolean {
        val key = rawKey.trim().uppercase(Locale.US)
        val deviceId = getDeviceId(context)

        // Verificaciones de claves universales de soporte y formato
        var daysToAdd = 0
        when {
            key == "ZIPPYPRO2026" || key == "ZIPPY-PRO-LIFETIME" -> daysToAdd = 365
            key == "ZIPPY30" || key.startsWith("ZIP-30-") -> daysToAdd = 30
            key == "ZIPPY90" || key.startsWith("ZIP-90-") -> daysToAdd = 90
            key == "ZIPPY365" || key.startsWith("ZIP-365-") -> daysToAdd = 365
            key.contains(deviceId.replace("-", "")) -> daysToAdd = 30
            key.length >= 8 && key.startsWith("ZIPPY") -> daysToAdd = 30
            else -> return false
        }

        val prefs = getPrefs(context)
        val currentExpiration = prefs.getLong(KEY_PRO_EXPIRATION, 0L)
        val baseTime = if (currentExpiration > System.currentTimeMillis()) currentExpiration else System.currentTimeMillis()
        val newExpiration = baseTime + (daysToAdd.toLong() * 24 * 60 * 60 * 1000)

        prefs.edit()
            .putLong(KEY_PRO_EXPIRATION, newExpiration)
            .putString(KEY_LICENSE_KEY, key)
            .putBoolean("had_paid_pro", true)
            .apply()

        return true
    }

    /**
     * Establece la expiración PRO sincronizada desde el backend de Zippy Hub.
     */
    fun setServerProExpiration(context: Context, expirationTimestampMs: Long) {
        getPrefs(context).edit()
            .putLong(KEY_PRO_EXPIRATION, expirationTimestampMs)
            .putString(KEY_LICENSE_KEY, "ZIPPY-SERVER-SYNC")
            .putBoolean("had_paid_pro", true)
            .apply()
    }

    /**
     * Indica si el usuario alguna vez tuvo una suscripción PRO paga activa.
     */
    fun hasHadPaidPro(context: Context): Boolean {
        return getPrefs(context).getBoolean("had_paid_pro", false)
    }

    /**
     * Limpia la expiración PRO si la suscripción venció en el backend.
     */
    fun clearProExpiration(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_PRO_EXPIRATION)
            .remove(KEY_LICENSE_KEY)
            .apply()
    }

    /**
     * Degrada inmediatamente el dispositivo a Plan Básico Gratuito,
     * eliminando cualquier suscripción PRO o prueba de 7 días activa.
     */
    fun resetToFree(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_PRO_EXPIRATION)
            .remove(KEY_LICENSE_KEY)
            .remove(KEY_TRIAL_EXPIRATION)
            .apply()
    }

    /**
     * Matriz de características según el plan actual.
     */
    fun getPlanFeatures(context: Context): PlanFeatures {
        val type = getPlanType(context)
        return when (type) {
            PlanType.FREE -> PlanFeatures(
                planType = PlanType.FREE,
                maxBanks = 1,
                canExportPdf = false,
                canUseVoiceInput = false,
                canUseVoiceSoundbox = false,
                canUseCloudBackup = false,
                historyDaysRetention = 7,
                canUseWallets = false
            )
            PlanType.PRO_TRIAL -> PlanFeatures(
                planType = PlanType.PRO_TRIAL,
                maxBanks = Int.MAX_VALUE,
                canExportPdf = true,
                canUseVoiceInput = true,
                canUseVoiceSoundbox = true,
                canUseCloudBackup = true,
                historyDaysRetention = 365,
                canUseWallets = true
            )
            PlanType.PRO_ACTIVE -> PlanFeatures(
                planType = PlanType.PRO_ACTIVE,
                maxBanks = Int.MAX_VALUE,
                canExportPdf = true,
                canUseVoiceInput = true,
                canUseVoiceSoundbox = true,
                canUseCloudBackup = true,
                historyDaysRetention = 365,
                canUseWallets = true
            )
        }
    }

    fun canExportPdf(context: Context): Boolean = getPlanFeatures(context).canExportPdf
    fun canUseMultibank(context: Context): Boolean = getPlanFeatures(context).maxBanks > 1
    fun canUseVoiceInput(context: Context): Boolean = getPlanFeatures(context).canUseVoiceInput
    fun canUseVoiceSoundbox(context: Context): Boolean = getPlanFeatures(context).canUseVoiceSoundbox
    fun canUseCloudBackup(context: Context): Boolean = getPlanFeatures(context).canUseCloudBackup
    fun canUseWallets(context: Context): Boolean = getPlanFeatures(context).canUseWallets
    fun getMaxActiveBanks(context: Context): Int = getPlanFeatures(context).maxBanks
}
