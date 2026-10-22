package com.cobrix.pocket.utils

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import com.cobrix.pocket.data.db.PocketDatabaseHelper
import com.cobrix.pocket.data.model.BankCatalog
import com.cobrix.pocket.data.model.Transaction
import com.cobrix.pocket.data.model.TransactionType
import com.cobrix.pocket.data.model.UserProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BackupRestoreResult(
    val success: Boolean,
    val message: String,
    val transactionsRestored: Int = 0,
    val merchantName: String = ""
)

/**
 * Gestor integral de Respaldo y Restauración para Zippy.
 * Permite empaquetar datos comerciales, transacciones históricas y códigos QR
 * para migrar entre dispositivos o respaldar en la nube (Google Drive, WhatsApp, etc.).
 */
object BackupManager {

    private const val TAG = "BackupManager"
    private const val BACKUP_VERSION = 1

    /**
     * Genera un JSON completo con el perfil, configuración de bancos, imágenes QR y transacciones.
     */
    fun createBackupJson(context: Context): String {
        val dbHelper = PocketDatabaseHelper.getInstance(context)
        val profile = PreferencesHelper.getUserProfile(context)
        val transactions = dbHelper.getAllTransactions()
        val deviceId = PlanCapabilityManager.getDeviceId(context)

        val root = JSONObject()
        root.put("version", BACKUP_VERSION)
        root.put("app", "Zippy")
        root.put("createdAt", System.currentTimeMillis())
        root.put("createdAtFormatted", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        root.put("sourceDeviceId", deviceId)

        // 1. Perfil del Comercio
        val profileJson = JSONObject().apply {
            put("merchantName", profile.merchantName)
            put("businessCategory", profile.businessCategory)
            put("bankCode", profile.bankCode)
            put("bankName", profile.bankName)
            put("phoneNumber", profile.phoneNumber)
            put("idCard", profile.idCard)
            put("email", profile.email)
            put("bcvRate", profile.bcvRate)
            put("soundboxMode", profile.soundboxMode)
            put("biometricSecurityEnabled", profile.biometricSecurityEnabled)
            put("enabledBanks", JSONArray(profile.enabledBankCodes))
        }
        root.put("profile", profileJson)

        // 2. Imágenes de Códigos QR por Banco (codificadas en Base64 para migración real)
        val qrsJson = JSONObject()
        val configuredBanks = PreferencesHelper.getConfiguredBankCodes(context)
        for (bCode in configuredBanks) {
            val qrUriStr = PreferencesHelper.getBankQrUri(context, bCode)
            if (!qrUriStr.isNullOrEmpty()) {
                val base64 = encodeImageUriToBase64(context, Uri.parse(qrUriStr))
                if (base64 != null) {
                    qrsJson.put(bCode, base64)
                }
            }
        }
        root.put("bankQrsBase64", qrsJson)

        // 3. Transacciones Históricas
        val txArray = JSONArray()
        for (tx in transactions) {
            val txJson = JSONObject().apply {
                put("amount", tx.amount)
                put("amountUsd", tx.amountUsd)
                put("reference", tx.reference)
                put("bankCode", tx.bankCode)
                put("bankName", tx.bankName)
                put("senderName", tx.senderName)
                put("type", tx.type.name)
                put("category", tx.category)
                put("note", tx.note)
                put("timestamp", tx.timestamp)
                put("isManual", tx.isManual)
            }
            txArray.put(txJson)
        }
        root.put("transactions", txArray)

        return root.toString(2)
    }

    /**
     * Guarda la copia de seguridad en un archivo JSON en cache y retorna el File.
     */
    fun exportBackupToFile(context: Context): File? {
        return try {
            val json = createBackupJson(context)
            val dir = File(context.cacheDir, "backups")
            if (!dir.exists()) dir.mkdirs()

            val timestamp = SimpleDateFormat("dd-MM-yy_HHmm", Locale.US).format(Date())
            val file = File(dir, "zippy_respaldo_${timestamp}.json")
            FileOutputStream(file).use { it.write(json.toByteArray()) }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Error exportando respaldo a archivo", e)
            null
        }
    }

    /**
     * Lanza el selector nativo de Android para compartir o guardar el archivo en Drive, Gmail, etc.
     */
    fun shareBackupFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Copia de Seguridad Zippy (${file.name})")
            putExtra(Intent.EXTRA_TEXT, "Respaldo comercial de Zippy. Conserva este archivo para restaurar tu información en cualquier teléfono nuevo.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Guardar o compartir copia de seguridad Zippy"))
    }

    /**
     * Restaura la configuración, imágenes de QR y transacciones a partir de una cadena JSON.
     */
    fun restoreBackupFromJson(context: Context, jsonStr: String): BackupRestoreResult {
        return try {
            val root = JSONObject(jsonStr)
            val app = root.optString("app", "")
            if (!app.equals("Zippy", ignoreCase = true) && !app.equals("Cobrix", ignoreCase = true)) {
                return BackupRestoreResult(false, "El archivo seleccionado no es un respaldo válido de Zippy.")
            }

            // 1. Restaurar Perfil
            var merchant = ""
            if (root.has("profile")) {
                val p = root.getJSONObject("profile")
                merchant = p.optString("merchantName", "")

                val enabledSet = mutableSetOf<String>()
                val enabledArr = p.optJSONArray("enabledBanks")
                if (enabledArr != null) {
                    for (i in 0 until enabledArr.length()) {
                        enabledSet.add(enabledArr.getString(i))
                    }
                } else {
                    enabledSet.addAll(PreferencesHelper.DEFAULT_ENABLED_BANKS)
                }

                val profile = UserProfile(
                    merchantName = merchant,
                    businessCategory = p.optString("businessCategory", "Transporte / Taxi"),
                    bankCode = p.optString("bankCode", "0134"),
                    bankName = p.optString("bankName", "Banesco"),
                    phoneNumber = p.optString("phoneNumber", ""),
                    idCard = p.optString("idCard", ""),
                    email = p.optString("email", ""),
                    bcvRate = p.optDouble("bcvRate", 45.0),
                    soundboxMode = p.optString("soundboxMode", "VOICE"),
                    voiceAnnouncementEnabled = p.optString("soundboxMode", "VOICE") == "VOICE",
                    soundNotificationEnabled = p.optString("soundboxMode", "VOICE") != "MUTED",
                    enabledBankCodes = enabledSet,
                    biometricSecurityEnabled = p.optBoolean("biometricSecurityEnabled", false)
                )
                PreferencesHelper.saveUserProfile(context, profile)
            }

            // 2. Restaurar Códigos QR decodificando Base64 y guardándolos localmente
            if (root.has("bankQrsBase64")) {
                val qrs = root.getJSONObject("bankQrsBase64")
                val keys = qrs.keys()
                val qrDir = File(context.filesDir, "bank_qrs")
                if (!qrDir.exists()) qrDir.mkdirs()

                while (keys.hasNext()) {
                    val bCode = keys.next()
                    val b64 = qrs.optString(bCode, "")
                    if (b64.isNotEmpty()) {
                        try {
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            val qrFile = File(qrDir, "qr_bank_${bCode}.png")
                            FileOutputStream(qrFile).use { it.write(bytes) }
                            PreferencesHelper.setBankQrUri(context, bCode, Uri.fromFile(qrFile).toString())
                        } catch (ex: Exception) {
                            Log.e(TAG, "Error restaurando QR para banco $bCode", ex)
                        }
                    }
                }
            }

            // 3. Restaurar Transacciones en SQLite
            var restoredTxsCount = 0
            if (root.has("transactions")) {
                val txArray = root.getJSONArray("transactions")
                val txList = mutableListOf<Transaction>()
                for (i in 0 until txArray.length()) {
                    val t = txArray.getJSONObject(i)
                    val tx = Transaction(
                        amount = t.optDouble("amount", 0.0),
                        amountUsd = t.optDouble("amountUsd", 0.0),
                        reference = t.optString("reference", ""),
                        bankCode = t.optString("bankCode", ""),
                        bankName = t.optString("bankName", ""),
                        senderName = t.optString("senderName", ""),
                        type = try {
                            TransactionType.valueOf(t.optString("type", "INCOME"))
                        } catch (_: Exception) { TransactionType.INCOME },
                        category = t.optString("category", "GENERAL"),
                        note = t.optString("note", ""),
                        timestamp = t.optLong("timestamp", System.currentTimeMillis()),
                        isManual = t.optBoolean("isManual", false)
                    )
                    txList.add(tx)
                }
                val dbHelper = PocketDatabaseHelper.getInstance(context)
                restoredTxsCount = dbHelper.insertTransactionsBatch(txList)
            }

            BackupRestoreResult(
                success = true,
                message = "Respaldo restaurado exitosamente.",
                transactionsRestored = restoredTxsCount,
                merchantName = merchant
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error restaurando respaldo desde JSON", e)
            BackupRestoreResult(false, "Error al procesar el archivo: ${e.localizedMessage}")
        }
    }

    /**
     * Lee un URI de contenido (por ejemplo seleccionado desde Google Drive o Explorador de Archivos).
     */
    fun restoreFromUri(context: Context, uri: Uri): BackupRestoreResult {
        return try {
            val content = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: return BackupRestoreResult(false, "No se pudo leer el archivo seleccionado.")

            restoreBackupFromJson(context, content)
        } catch (e: Exception) {
            Log.e(TAG, "Error leyendo URI de respaldo", e)
            BackupRestoreResult(false, "Error al abrir archivo: ${e.localizedMessage}")
        }
    }

    private fun encodeImageUriToBase64(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream) ?: return null
            val outputStream = ByteArrayOutputStream()
            // Comprimir como PNG para mantener la máxima nitidez del QR
            bitmap.compress(Bitmap.CompressFormat.PNG, 95, outputStream)
            val bytes = outputStream.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo codificar imagen QR a Base64: $uri", e)
            null
        }
    }
}
