package com.cobrix.pocket.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cobrix.pocket.R
import com.cobrix.pocket.data.db.PocketDatabaseHelper
import com.cobrix.pocket.data.model.Transaction
import com.cobrix.pocket.data.model.TransactionType
import com.cobrix.pocket.service.parser.MultibankParserRouter
import com.cobrix.pocket.service.parser.ParserUtils
import com.cobrix.pocket.service.utils.PaymentDeduplicator
import com.cobrix.pocket.utils.LicenseManager
import com.cobrix.pocket.utils.PlanCapabilityManager
import com.cobrix.pocket.utils.PreferencesHelper
import com.cobrix.pocket.utils.SoundHelper
import java.util.Locale

class PocketNotificationListenerService : NotificationListenerService() {

    private lateinit var dbHelper: PocketDatabaseHelper
    private var soundHelper: SoundHelper? = null
    private val parserRouter = MultibankParserRouter()

    companion object {
        private const val TAG = "PocketNotiListener"
        const val ACTION_PAYMENT_RECEIVED = "com.cobrix.pocket.PAYMENT_RECEIVED"
        const val EXTRA_AMOUNT = "extra_amount"
        const val EXTRA_AMOUNT_USD = "extra_amount_usd"
        const val EXTRA_IS_CRYPTO = "extra_is_crypto"
        const val EXTRA_REF = "extra_ref"
        const val EXTRA_BANK = "extra_bank"
        const val EXTRA_PHONE = "extra_phone"
        private const val CHANNEL_ID = "cobrix_pocket_alerts"

        @Volatile
        var instance: PocketNotificationListenerService? = null
            private set

        /**
         * Re-escanea todas las notificaciones activas en la barra de Android.
         * Retorna la cantidad de cobros nuevos detectados y procesados.
         */
        fun rescanActiveNotifications(playSound: Boolean = true): Int {
            return instance?.rescanInternal(playSound) ?: 0
        }

        fun isServiceRunning(): Boolean = instance != null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        dbHelper = PocketDatabaseHelper.getInstance(this)
        soundHelper = SoundHelper(this)
        createNotificationChannel()
        Log.i(TAG, "Servicio de escucha Zeasy iniciado.")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Notification listener connected.")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance == this) {
            instance = null
        }
        Log.i(TAG, "Notification listener disconnected.")
    }

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        soundHelper?.shutdown()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        processStatusBarNotification(sbn, playSound = true)
    }

    /**
     * Consulta la barra de notificaciones del sistema Android en busca de cobros pendientes
     * que no hayan sido procesados previamente.
     */
    fun rescanInternal(playSound: Boolean): Int {
        val activeNotifs = try {
            activeNotifications
        } catch (e: Exception) {
            Log.e(TAG, "Error consultando notificaciones activas: ${e.message}")
            null
        } ?: return 0

        var newPaymentsCount = 0
        for (sbn in activeNotifs) {
            try {
                if (processStatusBarNotification(sbn, playSound = playSound)) {
                    newPaymentsCount++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando sbn en rescan: ${e.message}")
            }
        }
        return newPaymentsCount
    }

    private fun processStatusBarNotification(sbn: StatusBarNotification, playSound: Boolean): Boolean {
        // 0. Filtros de seguridad del sistema Android:
        // Descartar notificaciones emitidas por Zeasy (evita feedback loop y falsos cobros de Binance Pay)
        val sbnPackageName = sbn.packageName
        if (sbnPackageName == packageName || sbnPackageName.contains("cobrix") || sbnPackageName.contains("zeasy") || sbnPackageName.contains("zippy")) {
            return false
        }

        // Si la notificación proviene de un gestor de SMS y el usuario desactivó la detección de SMS en Ajustes, omitir
        if (ParserUtils.isSmsPackage(sbnPackageName) && !PreferencesHelper.isSmsDetectionEnabled(this)) {
            Log.d(TAG, "Notificación SMS ignorada porque la detección de SMS está desactivada en Ajustes.")
            return false
        }

        // Descartar notificaciones persistentes (servicios foreground, etc.)
        if (sbn.isOngoing) {
            return false
        }

        // Descartar notificaciones resumen de grupo (FLAG_GROUP_SUMMARY) para evitar mezclar textos
        if ((sbn.notification?.flags ?: 0) and Notification.FLAG_GROUP_SUMMARY != 0) {
            return false
        }

        // 1. Validar jornada de trabajo: Si la jornada está finalizada o en pausa, inactivar detección y soundbox
        if (!PreferencesHelper.isShiftActive(this)) {
            Log.d(TAG, "Jornada pausada/finalizada. Se omite detección de notificación.")
            return false
        }

        // 2. Deduplicación por clave de notificación ya procesada como cobro
        if (PaymentDeduplicator.isNotificationKeyAlreadyProcessed(sbn.key)) {
            return false
        }

        val extras = sbn.notification?.extras ?: return false
        val title = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            ?: ""
        val text = extractFullNotificationText(extras)
        val postTime = sbn.postTime

        val isPro = PlanCapabilityManager.isPro(this)
        val profile = PreferencesHelper.getUserProfile(this)

        // 3. Parseo mediante router bancario: Detecta cualquier banco o SMS que llegue al dispositivo del usuario
        val parsedPayment = parserRouter.routeAndParse(
            packageName = sbnPackageName,
            title = title,
            text = text,
            postTimeMillis = postTime,
            enabledBankCodes = emptySet()
        ) ?: return false

        // 4. Deduplicación profunda en memoria (Banco + Ref 4 dígitos + Monto)
        if (PaymentDeduplicator.isPaymentDuplicate(parsedPayment)) {
            Log.w(TAG, "Pago duplicado detectado en memoria: ${parsedPayment.referenciaUltimos4} por ${parsedPayment.monto}")
            PaymentDeduplicator.markNotificationKeyProcessed(sbn.key)
            return false
        }

        // 5. Deduplicación en Base de Datos SQLite (garantiza que si la app se abre o reinicia no vuelva a duplicar)
        val isUsdt = (parsedPayment.moneda == "USD")
        val amountVes = if (isUsdt) (parsedPayment.monto * profile.bcvRate) else parsedPayment.monto
        val amountUsd = if (isUsdt) parsedPayment.monto else (if (profile.bcvRate > 0) parsedPayment.monto / profile.bcvRate else 0.0)

        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis

        if (dbHelper.hasDuplicateIncome(
                reference = parsedPayment.referenciaUltimos4,
                amountVes = amountVes,
                amountUsd = amountUsd,
                sinceTimestamp = startOfDay,
                isCrypto = isUsdt,
                sender = parsedPayment.telefonoOrigen
            )) {
            Log.w(TAG, "Pago ya registrado previamente en BD para la jornada: ref=${parsedPayment.referenciaUltimos4}, monto=$amountVes")
            PaymentDeduplicator.markNotificationKeyProcessed(sbn.key)
            return false
        }

        PaymentDeduplicator.markNotificationKeyProcessed(sbn.key)

        Log.i(TAG, "¡Pago válido detectado! Banco: ${parsedPayment.bancoCodigo.displayName}, Monto: ${parsedPayment.monto}, Ref: ${parsedPayment.referenciaUltimos4}")

        // 6. Guardar en Base de Datos Local
        val hasExternalOrigin = parsedPayment.bancoEmisor != parsedPayment.bancoCodigo.displayName &&
                parsedPayment.bancoEmisor != "SMS Bancario" &&
                parsedPayment.bancoEmisor.isNotBlank()

        val senderDetails = when {
            hasExternalOrigin && !parsedPayment.telefonoOrigen.isNullOrBlank() ->
                "${parsedPayment.bancoEmisor} • ${parsedPayment.telefonoOrigen}"
            hasExternalOrigin ->
                parsedPayment.bancoEmisor
            !parsedPayment.telefonoOrigen.isNullOrBlank() ->
                parsedPayment.telefonoOrigen
            else -> ""
        }

        val noteText = if (hasExternalOrigin) {
            "Detectado automáticamente en ${parsedPayment.bancoCodigo.displayName} (Origen: ${parsedPayment.bancoEmisor})"
        } else {
            "Detectado automáticamente de ${parsedPayment.bancoCodigo.displayName}"
        }

        val tx = Transaction(
            amount = amountVes,
            amountUsd = amountUsd,
            reference = parsedPayment.referenciaUltimos4,
            bankCode = parsedPayment.bancoCodigo.sudebanCode,
            bankName = parsedPayment.bancoCodigo.displayName,
            senderName = senderDetails,
            type = TransactionType.INCOME,
            category = if (isUsdt) "Cripto / Binance Pay" else com.cobrix.pocket.data.model.TransactionCategories.INCOME_AUTO_PAGO_MOVIL,
            note = noteText,
            timestamp = System.currentTimeMillis(),
            isManual = false
        )
        dbHelper.insertTransaction(tx)

        // 7. Alerta Sonora y Voz según el modo de Soundbox configurado y capacidades del plan
        if (playSound) {
            if (profile.soundboxMode == "VOICE" && PlanCapabilityManager.canUseVoiceSoundbox(this)) {
                if (isUsdt) {
                    soundHelper?.announceCryptoPayment(parsedPayment.monto, "dólares")
                } else {
                    soundHelper?.announcePayment(parsedPayment.monto)
                }
            } else if (profile.soundboxMode != "MUTED") {
                soundHelper?.playCashRegisterSound()
            }
        }

        // 8. Notificación visual en Android
        val displayBankName = if (hasExternalOrigin) {
            "${parsedPayment.bancoCodigo.displayName} (de ${parsedPayment.bancoEmisor})"
        } else {
            parsedPayment.bancoCodigo.displayName
        }

        showLocalPaymentNotification(
            montoVes = amountVes,
            montoUsd = amountUsd,
            isUsdt = isUsdt,
            ref4 = parsedPayment.referenciaUltimos4,
            banco = displayBankName
        )

        // 9. Guardar para celebración si la app se abre justo después
        PreferencesHelper.savePendingCelebration(
            context = this,
            amount = amountVes,
            ref = parsedPayment.referenciaUltimos4,
            bank = displayBankName,
            phone = senderDetails,
            amountUsd = amountUsd,
            isCrypto = isUsdt
        )

        // 10. Notificar a la UI (MainActivity / FullScreenQrActivity) con package explícito para Android 14+
        val broadcastIntent = Intent(ACTION_PAYMENT_RECEIVED).apply {
            setPackage(this@PocketNotificationListenerService.packageName)
            putExtra(EXTRA_AMOUNT, amountVes)
            putExtra(EXTRA_AMOUNT_USD, amountUsd)
            putExtra(EXTRA_IS_CRYPTO, isUsdt)
            putExtra(EXTRA_REF, parsedPayment.referenciaUltimos4)
            putExtra(EXTRA_BANK, displayBankName)
            putExtra(EXTRA_PHONE, senderDetails)
        }
        sendBroadcast(broadcastIntent)
        return true
    }

    /**
     * Extrae todo el contenido textual disponible de una notificación,
     * combinando BigTextStyle, InboxStyle y líneas múltiples (crucial para Gmail y apps bancarias).
     */
    private fun extractFullNotificationText(extras: Bundle): String {
        val parts = LinkedHashSet<String>()

        // 1. android.text
        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parts.add(it)
        }

        // 2. android.bigText (BigTextStyle para correos o notificaciones largas)
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parts.add(it)
        }

        // 3. android.textLines (InboxStyle con múltiples líneas)
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (textLines != null) {
            for (line in textLines) {
                val lineStr = line?.toString()?.trim()
                if (!lineStr.isNullOrEmpty()) {
                    parts.add(lineStr)
                }
            }
        }

        // 4. android.subText
        extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parts.add(it)
        }

        // 5. android.summaryText
        extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parts.add(it)
        }

        // 6. android.messages & historicMessages (MessagingStyle para Google Messages / Samsung Messages)
        try {
            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (messages != null) {
                for (msg in messages) {
                    if (msg is Bundle) {
                        val msgText = msg.getCharSequence("text")?.toString()?.trim()
                        if (!msgText.isNullOrEmpty()) {
                            parts.add(msgText)
                        }
                    }
                }
            }

            val historic = extras.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES)
            if (historic != null) {
                for (msg in historic) {
                    if (msg is Bundle) {
                        val msgText = msg.getCharSequence("text")?.toString()?.trim()
                        if (!msgText.isNullOrEmpty()) {
                            parts.add(msgText)
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        return parts.joinToString("\n")
    }

    private fun showLocalPaymentNotification(
        montoVes: Double,
        montoUsd: Double,
        isUsdt: Boolean,
        ref4: String,
        banco: String
    ) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val title = if (isUsdt) {
            "✅ Pago Recibido: $${String.format(Locale.US, "%.2f", montoUsd)} USDT"
        } else {
            "✅ Pago Recibido: Bs. ${String.format(Locale.US, "%.2f", montoVes)}"
        }

        val content = if (isUsdt) {
            "$banco • Ref: $ref4 (Bs. ${String.format(Locale.US, "%.2f", montoVes)})"
        } else {
            "$banco • Ref terminada en $ref4"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cashier)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cobros Recibidos Zeasy",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de cobros recibidos y confirmados con Zeasy"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
