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
