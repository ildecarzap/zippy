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
 * Gestor de sincronización entre la aplicación móvil Zippy y el backend Zippy Hub (CIEM).
 * Maneja el registro automático del dispositivo y la validación en tiempo real de licencias PRO.
 */
object ZippySyncManager {

    private const val TAG = "ZippySyncManager"
    private const val PREFS_NAME = "zippy_backend_prefs"
    private const val KEY_SERVER_URL = "custom_server_url"

    // URL oficial de producción en GCP Cloud Run
    const val PRODUCTION_SERVER_URL = "https://zippy-git-902301530733.europe-west1.run.app"
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
                put("merchant_name", profile.merchantName.ifEmpty { "Comercio Zippy" })
                put("phone", profile.phoneNumber)
                put("doc_id", profile.idCard)
                put("email", profile.email)
                put("app_version", "1.2.0")
            }

            val writer = OutputStreamWriter(regConn.outputStream)
            writer.write(regPayload.toString())
            writer.flush()
            writer.close()

            val regCode = regConn.responseCode
            regConn.disconnect()
            Log.d(TAG, "Registro de dispositivo $deviceId en Zippy Hub: código $regCode")

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
                val isPro = json.optBoolean("is_pro", false)
                val remainingDays = json.optInt("remaining_days", 0)
                val proExpiresAt = json.optString("pro_expires_at", "")

                val wasPro = PlanCapabilityManager.isPro(context)
                val planType = json.optString("plan_type", "BASIC")

                if (isPro && remainingDays > 0) {
                    val expirationMs = parseIsoDateOrAddDays(proExpiresAt, remainingDays)
                    PlanCapabilityManager.setServerProExpiration(context, expirationMs)
                    Log.i(TAG, "⭐ Zippy PRO verificado en el servidor: $remainingDays días restantes")
                    return@withContext !wasPro
                } else if (!isPro) {
                    // Si el panel de administración degradó el dispositivo a Básico o la suscripción no fue pagada
                    if (planType.equals("BASIC", ignoreCase = true) && wasPro) {
                        PlanCapabilityManager.resetToFree(context)
                        Log.i(TAG, "⬇️ Dispositivo degradado a Plan Básico por el servidor de Zippy Hub")
                        return@withContext true
                    } else if (wasPro && !PlanCapabilityManager.isTrial(context)) {
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
                    message = json.optString("message", "¡Plan Zippy PRO activado!"),
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
                    message = "¡Plan Zippy PRO activado localmente!",
                    remainingDays = PlanCapabilityManager.getDaysRemaining(context)
                )
            }
            return@withContext RedeemResult(
                success = false,
                message = "No se pudo validar el código: ${e.localizedMessage}"
            )
        }
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
