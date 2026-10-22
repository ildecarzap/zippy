package com.cobrix.pocket.utils

import android.content.Context
import android.content.SharedPreferences
import com.cobrix.pocket.data.model.LicenseState
import com.cobrix.pocket.data.model.SubscriptionInfo
import java.util.UUID

object LicenseManager {

    private const val PREFS_NAME = "cobrix_pocket_license"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_VALID_UNTIL = "valid_until"
    private const val KEY_FIRST_INSTALL = "first_install"

    // 48 horas de período de gracia
    private const val GRACE_PERIOD_MS = 48 * 60 * 60 * 1000L

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getDeviceId(context: Context): String {
        val prefs = getPrefs(context)
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString().substring(0, 8).uppercase()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }

    fun initializeFirstInstall(context: Context) {
        val prefs = getPrefs(context)
        if (!prefs.contains(KEY_FIRST_INSTALL)) {
            // Plan Básico Gratuito vitalicio por defecto al instalar
            prefs.edit()
                .putBoolean(KEY_FIRST_INSTALL, true)
                .putLong(KEY_VALID_UNTIL, 0L)
                .apply()
        }
    }

    fun getSubscriptionInfo(context: Context): SubscriptionInfo {
        initializeFirstInstall(context)
        val prefs = getPrefs(context)
        val deviceId = getDeviceId(context)
        val validUntil = prefs.getLong(KEY_VALID_UNTIL, 0L)
        val graceUntil = validUntil + GRACE_PERIOD_MS
        val now = System.currentTimeMillis()

        val state = when {
            now <= validUntil -> LicenseState.ACTIVE
            now <= graceUntil -> LicenseState.GRACE_PERIOD
            else -> LicenseState.EXPIRED
        }

        return SubscriptionInfo(
            deviceId = deviceId,
            state = state,
            validUntilTimestamp = validUntil,
            graceUntilTimestamp = graceUntil
        )
    }

    fun isServiceAllowed(context: Context): Boolean {
        // En Zippy, los servicios esenciales y cobro son 100% gratuitos y permitidos de por vida
        return true
    }

    fun extendSubscription(context: Context, days: Int = 30) {
        val prefs = getPrefs(context)
        val currentValidUntil = prefs.getLong(KEY_VALID_UNTIL, System.currentTimeMillis())
        val baseTime = if (currentValidUntil > System.currentTimeMillis()) currentValidUntil else System.currentTimeMillis()
        val newValidUntil = baseTime + (days.toLong() * 24 * 60 * 60 * 1000)
        prefs.edit().putLong(KEY_VALID_UNTIL, newValidUntil).apply()
    }
}
