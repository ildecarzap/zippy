package com.cobrix.pocket.utils

import android.content.Context
import android.content.SharedPreferences
import com.cobrix.pocket.data.model.BankCatalog
import com.cobrix.pocket.data.model.UserProfile

object PreferencesHelper {

    private const val PREFS_NAME = "cobrix_pocket_user_prefs"
    private const val KEY_MERCHANT_NAME = "merchant_name"
    private const val KEY_BUSINESS_CATEGORY = "business_category"
    private const val KEY_BANK_CODE = "bank_code"
    private const val KEY_BANK_NAME = "bank_name"
    private const val KEY_PHONE = "phone_number"
    private const val KEY_ID_CARD = "id_card"
    private const val KEY_QR_URI = "qr_image_uri"
    private const val KEY_BCV_RATE = "bcv_rate"
    private const val KEY_SOUND_ENABLED = "sound_enabled"
    private const val KEY_VOICE_ENABLED = "voice_enabled"
    private const val KEY_SOUNDBOX_MODE = "soundbox_mode" // "VOICE", "CHIME", "MUTED"
    private const val KEY_ENABLED_BANKS = "enabled_banks"
    private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
    private const val KEY_SHIFT_ACTIVE = "shift_active"
    private const val KEY_THEME_MODE = "theme_mode" // "SYSTEM", "LIGHT", "DARK"
    private const val KEY_CELEBRATION_SCREEN_ENABLED = "celebration_screen_enabled"

    val DEFAULT_ENABLED_BANKS = setOf("0134", "0102", "0105", "0108", "0191", "0172")

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isCelebrationScreenEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_CELEBRATION_SCREEN_ENABLED, true)
    }

    fun setCelebrationScreenEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_CELEBRATION_SCREEN_ENABLED, enabled).apply()
    }

    fun isShiftActive(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHIFT_ACTIVE, true)
    }

    fun setShiftActive(context: Context, active: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHIFT_ACTIVE, active).apply()
    }

    fun getEnabledBankCodes(context: Context): Set<String> {
        val prefs = getPrefs(context)
        val primary = prefs.getString(KEY_BANK_CODE, "0134") ?: "0134"

        // Si el usuario está en el Plan Básico (gratis), solo tiene acceso a 1 banco activo (su banco principal)
        if (!PlanCapabilityManager.canUseMultibank(context)) {
            return setOf(primary)
        }

        val saved = prefs.getStringSet(KEY_ENABLED_BANKS, null)
        return saved ?: DEFAULT_ENABLED_BANKS
    }

    fun setEnabledBankCodes(context: Context, banks: Set<String>) {
        getPrefs(context).edit().putStringSet(KEY_ENABLED_BANKS, banks).apply()
    }

    fun isBankDetectionEnabled(context: Context, bankCode: String): Boolean {
        return getEnabledBankCodes(context).contains(bankCode)
    }

    fun setBankDetectionEnabled(context: Context, bankCode: String, enabled: Boolean) {
        val current = getEnabledBankCodes(context).toMutableSet()
        if (enabled) {
            current.add(bankCode)
        } else {
            current.remove(bankCode)
        }
        setEnabledBankCodes(context, current)
    }

    fun isSmsDetectionEnabled(context: Context): Boolean {
        return isBankDetectionEnabled(context, "0000")
    }

    fun setSmsDetectionEnabled(context: Context, enabled: Boolean) {
        setBankDetectionEnabled(context, "0000", enabled)
    }

    /**
     * Obtiene la lista de códigos de bancos actualmente activos y tildados por el usuario.
     * Solo retorna bancos habilitados en getEnabledBankCodes() y que existan en BankCatalog.ALL_BANKS.
     * Si el usuario destilda un banco, ya NO aparece como opción de QR en la primera página.
     */
    fun getConfiguredBankCodes(context: Context): List<String> {
        val validCatalogCodes = BankCatalog.ALL_BANKS.map { it.code }.toSet()
        val enabled = getEnabledBankCodes(context).filter { it in validCatalogCodes }

        if (enabled.isNotEmpty()) {
            return enabled
        }

        // Fallback de emergencia si destildó absolutamente todos los bancos: solo mostrar el principal
        val prefs = getPrefs(context)
        val primary = prefs.getString(KEY_BANK_CODE, "0134") ?: "0134"
        return listOf(primary)
    }

    /**
     * Obtiene el URI del código QR exclusivo asignado al código bancario especificado.
     */
    fun getBankQrUri(context: Context, bankCode: String): String? {
        val prefs = getPrefs(context)
        val specific = prefs.getString("qr_image_uri_$bankCode", null)
        if (!specific.isNullOrEmpty()) return specific
        // Retrocompatibilidad con la clave general previa si coincide con el banco activo
        val activeBank = prefs.getString(KEY_BANK_CODE, "0134")
        if (bankCode == activeBank) {
            return prefs.getString(KEY_QR_URI, null)
        }
        return null
    }

    /**
     * Guarda el URI del código QR exclusivo para un banco determinado.
     */
    fun setBankQrUri(context: Context, bankCode: String, uri: String?) {
        val prefs = getPrefs(context)
        val editor = prefs.edit()
        if (uri != null) {
            editor.putString("qr_image_uri_$bankCode", uri)
            // Habilitar detección para este banco si no lo estaba
            val currentEnabled = getEnabledBankCodes(context).toMutableSet()
            currentEnabled.add(bankCode)
            editor.putStringSet(KEY_ENABLED_BANKS, currentEnabled)
        } else {
            editor.remove("qr_image_uri_$bankCode")
        }
        val activeBank = prefs.getString(KEY_BANK_CODE, "0134")
        if (bankCode == activeBank) {
            editor.putString(KEY_QR_URI, uri)
        }
        editor.apply()
    }

    fun getUserProfile(context: Context): UserProfile {
        val prefs = getPrefs(context)
        val enabledBanks = getEnabledBankCodes(context)
        val rawSoundboxMode = prefs.getString(KEY_SOUNDBOX_MODE, "CHIME") ?: "CHIME"
        val soundboxMode = if (rawSoundboxMode == "VOICE" && !PlanCapabilityManager.canUseVoiceSoundbox(context)) {
            "CHIME"
        } else {
            rawSoundboxMode
        }
        val bankCode = prefs.getString(KEY_BANK_CODE, "0134") ?: "0134"
        val bankQr = getBankQrUri(context, bankCode)

        return UserProfile(
            merchantName = prefs.getString(KEY_MERCHANT_NAME, "Mi Negocio / Taxi") ?: "",
            businessCategory = prefs.getString(KEY_BUSINESS_CATEGORY, "Transporte / Taxi") ?: "Transporte / Taxi",
            bankCode = bankCode,
            bankName = prefs.getString(KEY_BANK_NAME, "Banesco") ?: "Banesco",
            phoneNumber = prefs.getString(KEY_PHONE, "04141234567") ?: "",
            idCard = prefs.getString(KEY_ID_CARD, "V-18456789") ?: "",
            qrImageUri = bankQr,
            bcvRate = prefs.getFloat(KEY_BCV_RATE, 45.0f).toDouble(),
            soundNotificationEnabled = soundboxMode != "MUTED",
            voiceAnnouncementEnabled = soundboxMode == "VOICE",
            soundboxMode = soundboxMode,
            enabledBankCodes = enabledBanks,
            biometricSecurityEnabled = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
        )
    }

    fun saveUserProfile(context: Context, profile: UserProfile) {
        val editor = getPrefs(context).edit()
            .putString(KEY_MERCHANT_NAME, profile.merchantName)
            .putString(KEY_BUSINESS_CATEGORY, profile.businessCategory)
            .putString(KEY_BANK_CODE, profile.bankCode)
            .putString(KEY_BANK_NAME, profile.bankName)
            .putString(KEY_PHONE, profile.phoneNumber)
            .putString(KEY_ID_CARD, profile.idCard)
            .putString(KEY_QR_URI, profile.qrImageUri)
            .putFloat(KEY_BCV_RATE, profile.bcvRate.toFloat())
            .putBoolean(KEY_SOUND_ENABLED, profile.soundboxMode != "MUTED")
            .putBoolean(KEY_VOICE_ENABLED, profile.soundboxMode == "VOICE")
            .putString(KEY_SOUNDBOX_MODE, profile.soundboxMode)
            .putStringSet(KEY_ENABLED_BANKS, profile.enabledBankCodes)
            .putBoolean(KEY_BIOMETRIC_ENABLED, profile.biometricSecurityEnabled)

        if (profile.qrImageUri != null) {
            editor.putString("qr_image_uri_${profile.bankCode}", profile.qrImageUri)
        }
        editor.apply()
    }

    fun getThemeMode(context: Context): String {
        return getPrefs(context).getString(KEY_THEME_MODE, "SYSTEM") ?: "SYSTEM"
    }

    fun setThemeMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_THEME_MODE, mode).apply()
    }

    data class CelebrationData(
        val amount: Double,
        val ref: String,
        val bank: String,
        val phone: String,
        val timestamp: Long
    )

    fun savePendingCelebration(context: Context, amount: Double, ref: String, bank: String, phone: String) {
        getPrefs(context).edit()
            .putFloat("pending_celeb_amount", amount.toFloat())
            .putString("pending_celeb_ref", ref)
            .putString("pending_celeb_bank", bank)
            .putString("pending_celeb_phone", phone)
            .putLong("pending_celeb_time", System.currentTimeMillis())
            .apply()
    }

    fun getAndClearPendingCelebration(context: Context): CelebrationData? {
        val prefs = getPrefs(context)
        val time = prefs.getLong("pending_celeb_time", 0L)
        if (time == 0L || (System.currentTimeMillis() - time) > 60000L) {
            prefs.edit().remove("pending_celeb_time").apply()
            return null
        }
        val amount = prefs.getFloat("pending_celeb_amount", 0f).toDouble()
        val ref = prefs.getString("pending_celeb_ref", "") ?: ""
        val bank = prefs.getString("pending_celeb_bank", "") ?: ""
        val phone = prefs.getString("pending_celeb_phone", "") ?: ""

        prefs.edit().remove("pending_celeb_time").apply()
        return CelebrationData(amount, ref, bank, phone, time)
    }
}
