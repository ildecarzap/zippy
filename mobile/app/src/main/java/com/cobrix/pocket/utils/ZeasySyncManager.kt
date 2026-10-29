package com.cobrix.pocket.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Gestor de sincronización entre la aplicación móvil Zeasy y el backend Zeasy Hub (CIEM).
 * Maneja el registro automático del dispositivo y la validación en tiempo real de licencias PRO.
 */
object ZeasySyncManager {

    private const val TAG = "ZeasySyncManager"
    private const val PREFS_NAME = "zeasy_backend_prefs"
    private const val KEY_SERVER_URL = "custom_server_url"

    // URL oficial de producción en GCP Cloud Run
    const val PRODUCTION_SERVER_URL = "https://zeasy-app-902301530733.europe-west1.run.app"
    const val LOCAL_DEV_LAN_URL = "http://192.168.0.191:8088"
    const val LOCAL_DEV_EMULATOR_URL = "http://10.0.2.2:8088"

    fun getServerUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_URL, PRODUCTION_SERVER_URL) ?: PRODUCTION_SERVER_URL
    }

    fun setServerUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SERVER_URL, url.trim().removeSuffix("/")).apply()
    }

    /**
     * Sincroniza el dispositivo con el backend CIEM:
     * 1. Registra o actualiza el dispositivo (device_id, nombre de negocio, teléfono)
     * 2. Consulta si la licencia PRO está activa o fue extendida en el panel
     * 3. Retorna true si el estado PRO fue actualizado
     */
    suspend fun syncWithBackend(context: Context): Boolean = withContext(Dispatchers.IO) {
        val deviceId = PlanCapabilityManager.getDeviceId(context)
        val profile = PreferencesHelper.getUserProfile(context)
        val baseUrl = getServerUrl(context)

        try {
            // 1. Registro / Actualización silenciosa de dispositivo
            val registerUrl = URL("$baseUrl/api/v1/license/register")
            val regConn = registerUrl.openConnection() as HttpURLConnection
            regConn.requestMethod = "POST"
            regConn.setRequestProperty("Content-Type", "application/json")
            regConn.setRequestProperty("Accept", "application/json")
            regConn.connectTimeout = 4000
            regConn.readTimeout = 4000
            regConn.doOutput = true

            val regPayload = JSONObject().apply {
                put("device_id", deviceId)
                put("merchant_name", profile.merchantName.ifEmpty { "Comercio Zeasy" })
                put("phone", profile.phoneNumber)
                put("doc_id", profile.idCard)
                put("email", profile.email)
                put("app_version", "1.3.3")
            }

            val writer = OutputStreamWriter(regConn.outputStream)
            writer.write(regPayload.toString())
            writer.flush()
            writer.close()

            val regCode = regConn.responseCode
            regConn.disconnect()
            Log.d(TAG, "Registro de dispositivo $deviceId en Zeasy Hub: código $regCode")

            // 2. Consulta y validación remota de estado de licencia
            val checkUrl = URL("$baseUrl/api/v1/license/check?device_id=$deviceId")
            val checkConn = checkUrl.openConnection() as HttpURLConnection
            checkConn.requestMethod = "GET"
            checkConn.setRequestProperty("Accept", "application/json")
            checkConn.connectTimeout = 4000
            checkConn.readTimeout = 4000

            if (checkConn.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(checkConn.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                reader.close()
                checkConn.disconnect()

                val json = JSONObject(sb.toString())

                // Sincronizar precios y paquetes remotos de Zippy PRO configurados en el panel admin
                val pricingJson = json.optJSONObject("pricing")
                if (pricingJson != null) {
                    val p1 = pricingJson.optDouble("price_1m", 2.99)
                    val p3 = pricingJson.optDouble("price_3m", 7.99)
                    val p12 = pricingJson.optDouble("price_12m", 29.99)
                    PreferencesHelper.savePricing(context, p1, p3, p12)
                    Log.d(TAG, "💰 Precios remotos Zippy actualizados: 1m=\$$p1, 3m=\$$p3, 12m=\$$p12")
                }

                // En modo revisión de Google Play, no permitir degradación a plan básico ni marcar vencimiento
                if (PlanCapabilityManager.PLAY_STORE_REVIEW_MODE) {
                    Log.i(TAG, "⭐ Modo Revisión Google Play activo: Conservando estado PRO ilimitado sin degradación.")
                    return@withContext false
                }

                val isPro = json.optBoolean("is_pro", false)
                val remainingDays = json.optInt("remaining_days", 0)
                val proExpiresAt = json.optString("pro_expires_at", "")
                val trialEndsAt = json.optString("trial_ends_at", "")
                val planType = json.optString("plan_type", "BASIC")
                val trialUsed = json.optBoolean("trial_used", false) || trialEndsAt.isNotEmpty()

                if (trialUsed && !isPro) {
                    val expirationMs = parseIsoDateOrAddDays(trialEndsAt, 0)
                    PlanCapabilityManager.markTrialUsed(context, expirationMs)
                }

                val wasPro = PlanCapabilityManager.isPro(context)

                if (isPro && remainingDays > 0) {
                    if (planType.equals("TRIAL", ignoreCase = true)) {
                        val expirationMs = parseIsoDateOrAddDays(trialEndsAt, remainingDays)
                        PlanCapabilityManager.setServerTrialExpiration(context, expirationMs)
                        Log.i(TAG, "⏳ Zippy TRIAL verificado en el servidor: $remainingDays días restantes")
                    } else {
                        val expirationMs = parseIsoDateOrAddDays(proExpiresAt, remainingDays)
                        PlanCapabilityManager.setServerProExpiration(context, expirationMs)
                        Log.i(TAG, "⭐ Zippy PRO verificado en el servidor: $remainingDays días restantes")
                    }
                    return@withContext !wasPro
                } else if (!isPro) {
                    // Si el dispositivo tiene una prueba local activa no sincronizada aún con el backend, intentamos reportarla
                    if (PlanCapabilityManager.isTrial(context)) {
                        val trialSynced = activateTrialOnServer(context)
                        if (trialSynced) {
                            Log.i(TAG, "⏳ Prueba local de 7 días sincronizada exitosamente con Zippy Hub")
                            return@withContext true
                        }
                    } else if (planType.equals("BASIC", ignoreCase = true) && wasPro) {
                        PlanCapabilityManager.resetToFree(context)
                        Log.i(TAG, "⬇️ Dispositivo degradado a Plan Básico por el servidor de Zippy Hub")
                        return@withContext true
                    } else if (wasPro) {
                        PlanCapabilityManager.clearProExpiration(context)
                        return@withContext true
                    }
                }
            } else {
                checkConn.disconnect()
            }
        } catch (e: Exception) {
            // Falla de conexión silenciosa: Modo offline-first garantizado
            Log.w(TAG, "Backend Zippy Hub no disponible en $baseUrl (${e.localizedMessage}). Continuando en modo local.")
        }
        return@withContext false
    }

    data class PaymentLinkResult(
        val success: Boolean,
        val paymentUrl: String?,
        val isReused: Boolean,
        val expiresAt: String?,
        val message: String
    )

    /**
     * Solicita o recupera el enlace oficial de pago de CobrixPay para este dispositivo.
     * Si ya existe un enlace activo no vencido, retorna el existente (anti-spam / protección contra ataques masivos).
     */
    suspend fun requestPaymentLink(context: Context, months: Int = 1): PaymentLinkResult = withContext(Dispatchers.IO) {
        val deviceId = PlanCapabilityManager.getDeviceId(context)
        val baseUrl = getServerUrl(context)

        try {
            val url = URL("$baseUrl/api/v1/license/request-payment-link")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.doOutput = true

            val payload = JSONObject().apply {
                put("device_id", deviceId)
                put("months", months)
            }

            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(payload.toString())
            writer.flush()
            writer.close()

            val code = conn.responseCode
            val inputStream = if (code in 200..299) conn.inputStream else conn.errorStream
            val reader = BufferedReader(InputStreamReader(inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            reader.close()
            conn.disconnect()

            val json = JSONObject(sb.toString())
            if (code in 200..299 && json.optString("status") == "success") {
                return@withContext PaymentLinkResult(
                    success = true,
                    paymentUrl = json.optString("payment_url"),
                    isReused = json.optBoolean("is_reused", false),
                    expiresAt = json.optString("expires_at"),
                    message = json.optString("message", "Enlace listo")
                )
            } else {
                val detail = json.optString("detail", json.optString("message", "Error al generar enlace"))
                return@withContext PaymentLinkResult(
                    success = false,
                    paymentUrl = null,
                    isReused = false,
                    expiresAt = null,
                    message = detail
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error solicitando link de pago: ${e.message}")
            return@withContext PaymentLinkResult(
                success = false,
                paymentUrl = null,
                isReused = false,
                expiresAt = null,
                message = "No se pudo conectar con el servidor Zippy Hub: ${e.localizedMessage}"
            )
        }
    }

    data class RedeemResult(
        val success: Boolean,
        val message: String,
        val remainingDays: Int = 0
    )

    /**
     * Canjea un código de activación manual con el servidor de Zippy Hub.
     * Si no hay internet, valida con las claves locales de soporte como respaldo.
     */
    suspend fun redeemActivationCode(context: Context, rawCode: String): RedeemResult = withContext(Dispatchers.IO) {
        val code = rawCode.trim().uppercase(Locale.US)
        if (code.isEmpty()) {
            return@withContext RedeemResult(success = false, message = "Por favor ingresa un código de activación")
        }

        val deviceId = PlanCapabilityManager.getDeviceId(context)
        val baseUrl = getServerUrl(context)

        try {
            val url = URL("$baseUrl/api/v1/license/redeem")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true

            val payload = JSONObject().apply {
                put("device_id", deviceId)
                put("code", code)
            }

            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(payload.toString())
            writer.flush()
            writer.close()

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val reader = BufferedReader(InputStreamReader(stream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            reader.close()
            conn.disconnect()

            val json = JSONObject(sb.toString())
            if (responseCode in 200..299 && json.optString("status") == "success") {
                val remainingDays = json.optInt("remaining_days", 30)
                val proExpiresAt = json.optString("pro_expires_at", "")
                val expirationMs = parseIsoDateOrAddDays(proExpiresAt, remainingDays)
                PlanCapabilityManager.setServerProExpiration(context, expirationMs)
                return@withContext RedeemResult(
                    success = true,
                    message = json.optString("message", "¡Plan Zeasy PRO activado!"),
                    remainingDays = remainingDays
                )
            } else {
                val detail = json.optString("detail", json.optString("message", "Código inválido o ya utilizado"))
                return@withContext RedeemResult(success = false, message = detail)
            }
        } catch (e: Exception) {
            // Respaldo offline inmediato
            val localOk = PlanCapabilityManager.activateWithLicenseKey(context, code)
            if (localOk) {
                return@withContext RedeemResult(
                    success = true,
                    message = "¡Plan Zeasy PRO activado localmente!",
                    remainingDays = PlanCapabilityManager.getDaysRemaining(context)
                )
            }
            return@withContext RedeemResult(
                success = false,
                message = "No se pudo validar el código: ${e.localizedMessage}"
            )
        }
    }

    /**
     * Activa o sincroniza la prueba gratuita de 7 días (Plan TRIAL) en Zippy Hub.
     */
    suspend fun activateTrialOnServer(context: Context): Boolean = withContext(Dispatchers.IO) {
        val deviceId = PlanCapabilityManager.getDeviceId(context)
        val profile = PreferencesHelper.getUserProfile(context)
        val baseUrl = getServerUrl(context)

        try {
            val url = URL("$baseUrl/api/v1/license/activate-trial")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.doOutput = true

            val payload = JSONObject().apply {
                put("device_id", deviceId)
                put("merchant_name", profile.merchantName.ifEmpty { "Comercio Zeasy" })
                put("phone", profile.phoneNumber)
                put("doc_id", profile.idCard)
                put("email", profile.email)
            }

            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(payload.toString())
            writer.flush()
            writer.close()

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                reader.close()
                conn.disconnect()

                val json = JSONObject(sb.toString())
                val isPro = json.optBoolean("is_pro", false)
                val remainingDays = json.optInt("remaining_days", 7)
                val trialEndsAt = json.optString("trial_ends_at", "")
                val status = json.optString("status")

                if (status == "trial_expired" || (!isPro && remainingDays <= 0)) {
                    PlanCapabilityManager.markTrialUsed(context)
                    PlanCapabilityManager.resetToFree(context)
                    Log.w(TAG, "⛔ Prueba de 7 días ya expiró o fue utilizada previamente en este dispositivo")
                    return@withContext false
                }

                if (isPro && remainingDays > 0) {
                    val expirationMs = parseIsoDateOrAddDays(trialEndsAt, remainingDays)
                    PlanCapabilityManager.setServerTrialExpiration(context, expirationMs)
                    Log.i(TAG, "🎉 Prueba de 7 días sincronizada en Zippy Hub: $remainingDays días restantes")
                    return@withContext true
                }
            } else {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo sincronizar activación de prueba con Zippy Hub (${e.localizedMessage})")
        }
        return@withContext false
    }

    private fun parseIsoDateOrAddDays(isoString: String, remainingDays: Int): Long {
        if (isoString.isNotEmpty()) {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val date = sdf.parse(isoString)
                if (date != null) return date.time
            } catch (_: Exception) {}
        }
        return System.currentTimeMillis() + (remainingDays.toLong() * 24 * 60 * 60 * 1000)
    }
}
