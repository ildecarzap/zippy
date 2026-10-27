package com.cobrix.pocket.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
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
    private const val KEY_EMAIL = "merchant_email"
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

    // Billeteras Digitales y Cripto (Exclusivo Plan PRO)
    private const val KEY_BINANCE_PAY_ID = "wallet_binance_pay_id"
    private const val KEY_BINANCE_QR_URI = "wallet_binance_qr_uri"
    private const val KEY_BINANCE_ENABLED = "wallet_binance_enabled"
    private const val KEY_ZINLI_EMAIL = "wallet_zinli_email"
    private const val KEY_ZINLI_QR_URI = "wallet_zinli_qr_uri"
    private const val KEY_ZINLI_ENABLED = "wallet_zinli_enabled"

    // Paquetes y Precios Dinámicos de Zippy PRO
    private const val KEY_PRICE_1M = "pricing_pro_1m"
    private const val KEY_PRICE_3M = "pricing_pro_3m"
    private const val KEY_PRICE_12M = "pricing_pro_12m"
    private const val KEY_DETECT_SMS = "pref_detect_sms_enabled"
    private const val KEY_FIRST_LAUNCH = "pref_is_first_launch"

    val DEFAULT_ENABLED_BANKS = emptySet<String>()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getPrice1m(context: Context): Double = getPrefs(context).getFloat(KEY_PRICE_1M, 2.99f).toDouble()
    fun getPrice3m(context: Context): Double = getPrefs(context).getFloat(KEY_PRICE_3M, 7.99f).toDouble()
    fun getPrice12m(context: Context): Double = getPrefs(context).getFloat(KEY_PRICE_12M, 29.99f).toDouble()

    fun savePricing(context: Context, price1m: Double, price3m: Double, price12m: Double) {
        getPrefs(context).edit()
            .putFloat(KEY_PRICE_1M, price1m.toFloat())
            .putFloat(KEY_PRICE_3M, price3m.toFloat())
            .putFloat(KEY_PRICE_12M, price12m.toFloat())
            .apply()
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

    fun getPrimaryBankCode(context: Context): String {
        return getPrefs(context).getString(KEY_BANK_CODE, "") ?: ""
    }

    fun setPrimaryBank(context: Context, bankCode: String, bankName: String) {
        val prefs = getPrefs(context)
        val editor = prefs.edit()
            .putString(KEY_BANK_CODE, bankCode)
            .putString(KEY_BANK_NAME, bankName)

        val qr = prefs.getString("qr_image_uri_$bankCode", null)
        if (qr != null) {
            editor.putString(KEY_QR_URI, qr)
        } else {
            editor.remove(KEY_QR_URI)
        }

        // En Plan Básico, mantener sólo este banco activo
        if (!PlanCapabilityManager.canUseMultibank(context)) {
            editor.putStringSet(KEY_ENABLED_BANKS, setOf(bankCode))
        } else {
            val current = getEnabledBankCodes(context).toMutableSet()
            current.add(bankCode)
            editor.putStringSet(KEY_ENABLED_BANKS, current)
        }
        editor.apply()
    }

    fun getEnabledBankCodes(context: Context): Set<String> {
        val prefs = getPrefs(context)
        val primary = prefs.getString(KEY_BANK_CODE, "") ?: ""

        // Si el usuario está en el Plan Básico (gratis), solo tiene acceso a 1 banco activo (el que elija libremente)
        if (!PlanCapabilityManager.canUseMultibank(context)) {
            return if (primary.isNotEmpty()) setOf(primary) else emptySet()
        }

        val saved = prefs.getStringSet(KEY_ENABLED_BANKS, null)
        return saved ?: (if (primary.isNotEmpty()) setOf(primary) else DEFAULT_ENABLED_BANKS)
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
        return getPrefs(context).getBoolean(KEY_DETECT_SMS, true)
    }

    fun setSmsDetectionEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DETECT_SMS, enabled).apply()
        setBankDetectionEnabled(context, "0000", enabled)
    }

    fun isFirstLaunch(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_FIRST_LAUNCH, true)
    }

    fun setFirstLaunch(context: Context, isFirst: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_FIRST_LAUNCH, isFirst).apply()
    }

    /**
     * Agrega un banco a la lista de bancos configurados del usuario.
     */
    fun addConfiguredBank(context: Context, bankCode: String, bankName: String) {
        val prefs = getPrefs(context)
        val current = getEnabledBankCodes(context).toMutableSet()
        current.add(bankCode)
        prefs.edit().putStringSet(KEY_ENABLED_BANKS, current).apply()
        // Si no había banco principal o estaba en default sin QR, asegurar que sea utilizable
        if (prefs.getString(KEY_BANK_CODE, null).isNullOrEmpty()) {
            setPrimaryBank(context, bankCode, bankName)
        }
    }

    /**
     * Remueve un banco de la lista y borra su código QR asociado.
     */
    fun removeConfiguredBank(context: Context, bankCode: String) {
        val prefs = getPrefs(context)
        clearBankQr(context, bankCode)
        val current = getEnabledBankCodes(context).toMutableSet()
        current.remove(bankCode)
        val editor = prefs.edit().putStringSet(KEY_ENABLED_BANKS, current)
        // Si se removió el banco principal, asignar el siguiente banco disponible si existe
        val primary = prefs.getString(KEY_BANK_CODE, "")
        if (primary == bankCode) {
            val remaining = current.filter { it != "0000" && BankCatalog.findByCode(it) != null }
            val nextCode = remaining.firstOrNull() ?: ""
            val nextName = if (nextCode.isNotEmpty()) BankCatalog.findByCode(nextCode)?.name ?: "" else ""
            editor.putString(KEY_BANK_CODE, nextCode)
            editor.putString(KEY_BANK_NAME, nextName)
            val nextQr = if (nextCode.isNotEmpty()) getBankQrUri(context, nextCode) else null
            if (nextQr != null) {
                editor.putString(KEY_QR_URI, nextQr)
            } else {
                editor.remove(KEY_QR_URI)
            }
        }
        editor.apply()
    }

    /**
     * Obtiene la lista de códigos de bancos actualmente activos y configurados por el usuario.
     * Solo retorna bancos habilitados en getEnabledBankCodes() y que existan en BankCatalog.ALL_BANKS.
     */
    fun getConfiguredBankCodes(context: Context): List<String> {
        val validCatalogCodes = BankCatalog.ALL_BANKS.map { it.code }.toSet()
        val enabled = getEnabledBankCodes(context).filter { it in validCatalogCodes }

        if (enabled.isNotEmpty()) {
            return enabled
        }

        // Fallback de emergencia: banco principal configurado si existe
        val prefs = getPrefs(context)
        val primary = prefs.getString(KEY_BANK_CODE, "") ?: ""
        if (primary.isNotEmpty() && primary in validCatalogCodes) {
            return listOf(primary)
        }
        return emptyList()
    }

    /**
     * Obtiene los códigos bancarios y de billeteras que deben ser escuchados activamente por el servicio de notificaciones.
     * En Plan Básico: Exclusivamente el banco receptor seleccionado por el usuario (ej: 0134 Banesco).
     * En Plan PRO: Exclusivamente los bancos que el usuario ha configurado y que tienen un QR cargado (o el principal),
     * más Binance Pay sólo si fue activado explícitamente en Ajustes.
     */
    fun getActiveListeningBankCodes(context: Context): Set<String> {
        val isMultibank = PlanCapabilityManager.canUseMultibank(context)
        val profile = getUserProfile(context)
        val result = mutableSetOf<String>()

        if (!isMultibank) {
            // Plan Básico Gratuito: Sólo 1 banco activo
            if (profile.bankCode.isNotEmpty()) {
                result.add(profile.bankCode)
            }
        } else {
            // Plan PRO: Bancos configurados en el panel
            val configured = getConfiguredBankCodes(context)
            val withQr = configured.filter { getBankQrUri(context, it) != null }
            if (withQr.isNotEmpty()) {
                result.addAll(withQr)
            } else if (configured.isNotEmpty()) {
                result.addAll(configured)
            } else if (profile.bankCode.isNotEmpty()) {
                result.add(profile.bankCode)
            }

            // Billetera Binance Pay (solo si el usuario la activó explícitamente)
            if (isBinanceEnabled(context) && PlanCapabilityManager.canUseWallets(context)) {
                result.add("BINANCE")
            }
        }
        return result
    }

    /**
     * Obtiene el URI del código QR exclusivo asignado al código bancario o billetera especificada.
     */
    fun getBankQrUri(context: Context, bankCode: String): String? {
        if (bankCode.equals("BINANCE", ignoreCase = true)) {
            return getBinanceQrUri(context)
        }
        if (bankCode.equals("ZINLI", ignoreCase = true)) {
            return getZinliQrUri(context)
        }
        val prefs = getPrefs(context)
        val specific = prefs.getString("qr_image_uri_$bankCode", null)
        if (!specific.isNullOrEmpty()) return specific
        val activeBank = prefs.getString(KEY_BANK_CODE, "")
        if (bankCode == activeBank && !activeBank.isNullOrEmpty()) {
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
            val currentEnabled = getEnabledBankCodes(context).toMutableSet()
            currentEnabled.add(bankCode)
            editor.putStringSet(KEY_ENABLED_BANKS, currentEnabled)
        } else {
            editor.remove("qr_image_uri_$bankCode")
        }
        val activeBank = prefs.getString(KEY_BANK_CODE, "")
        if (bankCode == activeBank && !activeBank.isNullOrEmpty()) {
            if (uri != null) {
                editor.putString(KEY_QR_URI, uri)
            } else {
                editor.remove(KEY_QR_URI)
            }
        }
        editor.apply()
    }

    /**
     * Devuelve los códigos de todos los bancos que tienen un QR cargado actualmente.
     */
    fun getBanksWithQr(context: Context): List<String> {
        return BankCatalog.ALL_BANKS.map { it.code }.filter { code ->
            !getBankQrUri(context, code).isNullOrEmpty()
        }
    }

    /**
     * En Plan Básico solo se permite 1 banco activo con QR.
     * Retorna true si puede subir QR para este banco directamente, o false si ya tiene otro banco con QR.
     */
    fun canUploadQrForBank(context: Context, bankCode: String): Boolean {
        if (PlanCapabilityManager.canUseMultibank(context)) return true
        val banksWithQr = getBanksWithQr(context)
        if (banksWithQr.isEmpty()) return true
        if (banksWithQr.size == 1 && banksWithQr.contains(bankCode)) return true
        return false
    }

    /**
     * Reemplaza el banco del Plan Básico eliminando el QR previo y configurando el nuevo banco.
     */
    fun replaceBasicPlanBank(context: Context, newBankCode: String, newBankName: String) {
        val prefs = getPrefs(context)
        val editor = prefs.edit()

        // Si el nuevo banco no tiene QR propio aún, verificar si existía un QR cargado en otro banco nacional
        val binanceQr = prefs.getString(KEY_BINANCE_QR_URI, null)
        var existingQr = prefs.getString("qr_image_uri_$newBankCode", null)
        if (existingQr == null) {
            val candidate = prefs.getString(KEY_QR_URI, null)
            if (candidate != null && candidate != binanceQr) {
                existingQr = candidate
            } else {
                existingQr = BankCatalog.ALL_BANKS.firstNotNullOfOrNull { b ->
                    val q = prefs.getString("qr_image_uri_${b.code}", null)
                    if (q != null && q != binanceQr) q else null
                }
            }
        }

        // Limpiar QRs de otros bancos para garantizar que solo exista 1 en Plan Básico
        BankCatalog.ALL_BANKS.forEach { bank ->
            if (bank.code != newBankCode) {
                editor.remove("qr_image_uri_${bank.code}")
            }
        }

        if (existingQr != null) {
            editor.putString("qr_image_uri_$newBankCode", existingQr)
            editor.putString(KEY_QR_URI, existingQr)
        } else {
            editor.remove(KEY_QR_URI)
        }

        editor.putString(KEY_BANK_CODE, newBankCode)
        editor.putString(KEY_BANK_NAME, newBankName)
        editor.putStringSet(KEY_ENABLED_BANKS, setOf(newBankCode))
        editor.apply()
    }

    /**
     * Sanitiza el estado del banco principal y billeteras para corregir automáticamente
     * desincronizaciones previas (ej. código QR de Binance Pay asignado erróneamente a Ubii App u otro banco).
     */
    fun sanitizeBasicPlanBank(context: Context) {
        val prefs = getPrefs(context)
        var binanceQr = prefs.getString(KEY_BINANCE_QR_URI, null)

        // 1. Revisar si el QR genérico legacy (KEY_QR_URI) contiene un QR de cripto
        val legacyQr = prefs.getString(KEY_QR_URI, null)
        if (!legacyQr.isNullOrEmpty()) {
            val isCrypto = try {
                val uri = Uri.parse(legacyQr)
                val content = QrValidator.decodeQrContent(context, uri)
                content != null && QrValidator.isCryptoWalletPayload(content)
            } catch (_: Exception) {
                false
            }
            if (isCrypto || legacyQr == binanceQr) {
                if (binanceQr.isNullOrEmpty()) {
                    setBinanceQrUri(context, legacyQr)
                    setBinanceEnabled(context, true)
                    binanceQr = legacyQr
                }
                prefs.edit().remove(KEY_QR_URI).apply()
            }
        }

        // 2. Revisar si alguna ranura de banco tradicional (incluyendo Ubii 0178) contiene un QR de Binance
        BankCatalog.ALL_BANKS.forEach { bank ->
            val bQr = prefs.getString("qr_image_uri_${bank.code}", null)
            if (!bQr.isNullOrEmpty()) {
                val isCrypto = try {
                    val uri = Uri.parse(bQr)
                    val content = QrValidator.decodeQrContent(context, uri)
                    content != null && QrValidator.isCryptoWalletPayload(content)
                } catch (_: Exception) {
                    false
                }

                if (isCrypto || (!binanceQr.isNullOrEmpty() && bQr == binanceQr)) {
                    // Mover el QR a la ranura de Binance Pay si aún no está asignado
                    if (binanceQr.isNullOrEmpty()) {
                        setBinanceQrUri(context, bQr)
                        setBinanceEnabled(context, true)
                        binanceQr = bQr
                    }
                    // Eliminarlo de la ranura bancaria
                    val editor = prefs.edit()
                    editor.remove("qr_image_uri_${bank.code}")
                    if (prefs.getString(KEY_QR_URI, null) == bQr) {
                        editor.remove(KEY_QR_URI)
                    }
                    editor.apply()
                }
            }
        }

        if (PlanCapabilityManager.canUseMultibank(context)) return

        val banescoQr = prefs.getString("qr_image_uri_0134", null)
        val activeBank = prefs.getString(KEY_BANK_CODE, null)

        // Si se subió el QR de Ubii App pero el banco activo quedó como Banesco (o Banesco tiene el QR de Ubii)
        val refreshedUbii = prefs.getString("qr_image_uri_0178", null)
        if (!refreshedUbii.isNullOrEmpty() && (activeBank == "0134" || banescoQr == refreshedUbii)) {
            replaceBasicPlanBank(context, "0178", "Ubii App")
        }
    }

    fun clearBankQr(context: Context, bankCode: String) {
        setBankQrUri(context, bankCode, null)
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
        val bankCode = prefs.getString(KEY_BANK_CODE, "") ?: ""
        val bankEntry = if (bankCode.isNotEmpty()) BankCatalog.findByCode(bankCode) else null
        val defaultBankName = bankEntry?.name ?: ""
        val bankName = prefs.getString(KEY_BANK_NAME, defaultBankName) ?: defaultBankName
        val bankQr = if (bankCode.isNotEmpty()) getBankQrUri(context, bankCode) else null

        var merchantName = prefs.getString(KEY_MERCHANT_NAME, "") ?: ""
        if (merchantName == "Mi Negocio / Taxi" || merchantName == "Mi Negocio") {
            merchantName = ""
        }

        var phoneNumber = prefs.getString(KEY_PHONE, "") ?: ""
        if (phoneNumber == "04141234567") {
            phoneNumber = ""
        }

        var idCard = prefs.getString(KEY_ID_CARD, "") ?: ""
        if (idCard == "V-18456789") {
            idCard = ""
        }

        return UserProfile(
            merchantName = merchantName,
            businessCategory = prefs.getString(KEY_BUSINESS_CATEGORY, "Transporte / Taxi") ?: "Transporte / Taxi",
            bankCode = bankCode,
            bankName = bankName,
            phoneNumber = phoneNumber,
            idCard = idCard,
            email = prefs.getString(KEY_EMAIL, "") ?: "",
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
            .putString(KEY_EMAIL, profile.email)
            .putFloat(KEY_BCV_RATE, profile.bcvRate.toFloat())
            .putBoolean(KEY_SOUND_ENABLED, profile.soundboxMode != "MUTED")
            .putBoolean(KEY_VOICE_ENABLED, profile.soundboxMode == "VOICE")
            .putString(KEY_SOUNDBOX_MODE, profile.soundboxMode)
            .putStringSet(KEY_ENABLED_BANKS, profile.enabledBankCodes)
            .putBoolean(KEY_BIOMETRIC_ENABLED, profile.biometricSecurityEnabled)

        // Sincronizar el QR activo únicamente con el QR que le pertenezca a este banco específico si existe
        val bankQr = if (profile.bankCode.isNotEmpty()) getBankQrUri(context, profile.bankCode) else null
        if (bankQr != null) {
            editor.putString(KEY_QR_URI, bankQr)
        } else {
            editor.remove(KEY_QR_URI)
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
        val timestamp: Long,
        val amountUsd: Double = 0.0,
        val isCrypto: Boolean = false
    )

    fun savePendingCelebration(
        context: Context,
        amount: Double,
        ref: String,
        bank: String,
        phone: String,
        amountUsd: Double = 0.0,
        isCrypto: Boolean = false
    ) {
        getPrefs(context).edit()
            .putFloat("pending_celeb_amount", amount.toFloat())
            .putFloat("pending_celeb_amount_usd", amountUsd.toFloat())
            .putBoolean("pending_celeb_is_crypto", isCrypto)
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
        val amountUsd = prefs.getFloat("pending_celeb_amount_usd", 0f).toDouble()
        val isCrypto = prefs.getBoolean("pending_celeb_is_crypto", false)
        val ref = prefs.getString("pending_celeb_ref", "") ?: ""
        val bank = prefs.getString("pending_celeb_bank", "") ?: ""
        val phone = prefs.getString("pending_celeb_phone", "") ?: ""

        prefs.edit().remove("pending_celeb_time").apply()
        return CelebrationData(amount, ref, bank, phone, time, amountUsd, isCrypto)
    }

    // --- BILLETERAS DIGITALES Y CRIPTO (EXCLUSIVO PLAN PRO) ---

    fun getBinancePayId(context: Context): String {
        return getPrefs(context).getString(KEY_BINANCE_PAY_ID, "") ?: ""
    }

    fun setBinancePayId(context: Context, payId: String) {
        getPrefs(context).edit().putString(KEY_BINANCE_PAY_ID, payId.trim()).apply()
    }

    fun getBinanceQrUri(context: Context): String? {
        return getPrefs(context).getString(KEY_BINANCE_QR_URI, null)
    }

    fun setBinanceQrUri(context: Context, uri: String?) {
        val editor = getPrefs(context).edit()
        if (uri != null) {
            editor.putString(KEY_BINANCE_QR_URI, uri)
        } else {
            editor.remove(KEY_BINANCE_QR_URI)
        }
        editor.apply()
    }

    fun clearBinanceQr(context: Context) {
        setBinanceQrUri(context, null)
    }

    fun isBinanceEnabled(context: Context): Boolean {
        // En Plan Básico Gratuito, las billeteras no se activan
        if (!PlanCapabilityManager.canUseWallets(context)) return false
        return getPrefs(context).getBoolean(KEY_BINANCE_ENABLED, false)
    }

    fun setBinanceEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BINANCE_ENABLED, enabled).apply()
    }

    fun hasConfiguredBinance(context: Context): Boolean {
        val qr = getBinanceQrUri(context)
        val payId = getBinancePayId(context)
        return !qr.isNullOrEmpty() || payId.isNotEmpty()
    }

    fun removeBinancePay(context: Context) {
        val editor = getPrefs(context).edit()
        editor.remove(KEY_BINANCE_QR_URI)
        editor.remove(KEY_BINANCE_PAY_ID)
        editor.putBoolean(KEY_BINANCE_ENABLED, false)
        editor.apply()
    }

    fun getZinliEmail(context: Context): String {
        return getPrefs(context).getString(KEY_ZINLI_EMAIL, "") ?: ""
    }

    fun setZinliEmail(context: Context, email: String) {
        getPrefs(context).edit().putString(KEY_ZINLI_EMAIL, email.trim()).apply()
    }

    fun getZinliQrUri(context: Context): String? {
        return getPrefs(context).getString(KEY_ZINLI_QR_URI, null)
    }

    fun setZinliQrUri(context: Context, uri: String?) {
        val editor = getPrefs(context).edit()
        if (uri != null) {
            editor.putString(KEY_ZINLI_QR_URI, uri)
        } else {
            editor.remove(KEY_ZINLI_QR_URI)
        }
        editor.apply()
    }

    fun isZinliEnabled(context: Context): Boolean {
        if (!PlanCapabilityManager.canUseWallets(context)) return false
        return getPrefs(context).getBoolean(KEY_ZINLI_ENABLED, false)
    }

    fun setZinliEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ZINLI_ENABLED, enabled).apply()
    }

    // Borrador temporal de Ajustes (evita pérdida de datos cuando el usuario sale a dar permisos)
    private const val KEY_DRAFT_MERCHANT_NAME = "draft_merchant_name"
    private const val KEY_DRAFT_BUSINESS_CATEGORY = "draft_business_category"
    private const val KEY_DRAFT_PHONE = "draft_phone"
    private const val KEY_DRAFT_ID_CARD = "draft_id_card"
    private const val KEY_DRAFT_EMAIL = "draft_email"
    private const val KEY_DRAFT_BCV_RATE = "draft_bcv_rate"

    fun saveSettingsDraft(
        context: Context,
        merchantName: String,
        category: String,
        phone: String,
        idCard: String,
        email: String,
        bcvRate: String
    ) {
        getPrefs(context).edit()
            .putString(KEY_DRAFT_MERCHANT_NAME, merchantName)
            .putString(KEY_DRAFT_BUSINESS_CATEGORY, category)
            .putString(KEY_DRAFT_PHONE, phone)
            .putString(KEY_DRAFT_ID_CARD, idCard)
            .putString(KEY_DRAFT_EMAIL, email)
            .putString(KEY_DRAFT_BCV_RATE, bcvRate)
            .apply()
    }

    fun clearSettingsDraft(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_DRAFT_MERCHANT_NAME)
            .remove(KEY_DRAFT_BUSINESS_CATEGORY)
            .remove(KEY_DRAFT_PHONE)
            .remove(KEY_DRAFT_ID_CARD)
            .remove(KEY_DRAFT_EMAIL)
            .remove(KEY_DRAFT_BCV_RATE)
            .apply()
    }

    fun hasSettingsDraft(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.contains(KEY_DRAFT_PHONE) || prefs.contains(KEY_DRAFT_ID_CARD) || prefs.contains(KEY_DRAFT_MERCHANT_NAME)
    }

    fun getSettingsDraft(context: Context): Map<String, String> {
        val prefs = getPrefs(context)
        return mapOf(
            KEY_DRAFT_MERCHANT_NAME to (prefs.getString(KEY_DRAFT_MERCHANT_NAME, "") ?: ""),
            KEY_DRAFT_BUSINESS_CATEGORY to (prefs.getString(KEY_DRAFT_BUSINESS_CATEGORY, "") ?: ""),
            KEY_DRAFT_PHONE to (prefs.getString(KEY_DRAFT_PHONE, "") ?: ""),
            KEY_DRAFT_ID_CARD to (prefs.getString(KEY_DRAFT_ID_CARD, "") ?: ""),
            KEY_DRAFT_EMAIL to (prefs.getString(KEY_DRAFT_EMAIL, "") ?: ""),
            KEY_DRAFT_BCV_RATE to (prefs.getString(KEY_DRAFT_BCV_RATE, "") ?: "")
        )
    }
}
