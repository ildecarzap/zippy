package com.cobrix.pocket.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class BcvRateResult(
    val rate: Double,
    val updateDate: String,
    val isSuccess: Boolean,
    val errorMessage: String? = null
)

/**
 * Utilidad para sincronización y consulta automática de la tasa oficial del Banco Central de Venezuela (BCV).
 */
object BcvRateHelper {

    private const val BCV_API_URL = "https://ve.dolarapi.com/v1/dolares/oficial"
    private const val CONNECT_TIMEOUT_MS = 6000
    private const val READ_TIMEOUT_MS = 6000

    suspend fun fetchOfficialRate(): BcvRateResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(BCV_API_URL)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Zeasy-Android-Client/1.3")

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                val json = JSONObject(response.toString())
                val promedio = json.optDouble("promedio", 0.0)
                val fecha = json.optString("fechaActualizacion", "")

                if (promedio > 0.0) {
                    BcvRateResult(
                        rate = promedio,
                        updateDate = fecha,
                        isSuccess = true
                    )
                } else {
                    BcvRateResult(0.0, "", false, "Tasa inválida recibida")
                }
            } else {
                BcvRateResult(0.0, "", false, "Servidor BCV respondió con código $responseCode")
            }
        } catch (e: Exception) {
            BcvRateResult(0.0, "", false, e.localizedMessage ?: "Error de conexión")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Aplica la tasa consultada al perfil del usuario y la guarda en SharedPreferences.
     */
    fun applyRateToProfile(context: Context, newRate: Double) {
        val currentProfile = PreferencesHelper.getUserProfile(context)
        val updated = currentProfile.copy(bcvRate = newRate)
        PreferencesHelper.saveUserProfile(context, updated)
    }
}
