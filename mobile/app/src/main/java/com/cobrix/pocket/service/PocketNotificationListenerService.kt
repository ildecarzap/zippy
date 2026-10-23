package com.cobrix.pocket.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cobrix.pocket.R
import com.cobrix.pocket.data.db.PocketDatabaseHelper
import com.cobrix.pocket.data.model.Transaction
import com.cobrix.pocket.data.model.TransactionType
import com.cobrix.pocket.service.parser.MultibankParserRouter
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
        const val EXTRA_REF = "extra_ref"
        const val EXTRA_BANK = "extra_bank"
        const val EXTRA_PHONE = "extra_phone"
        private const val CHANNEL_ID = "cobrix_pocket_alerts"
    }

    override fun onCreate() {
        super.onCreate()
        dbHelper = PocketDatabaseHelper.getInstance(this)
        soundHelper = SoundHelper(this)
        createNotificationChannel()
        Log.i(TAG, "Servicio de escucha Zippy iniciado.")
    }

    override fun onDestroy() {
        soundHelper?.shutdown()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // 1. Validar jornada de trabajo: Si la jornada está finalizada o en pausa, inactivar detección y soundbox
        if (!PreferencesHelper.isShiftActive(this)) {
            Log.d(TAG, "Jornada pausada/finalizada. Se omite detección de notificación.")
            return
        }

        // 2. Deduplicación por clave de notificación
        if (PaymentDeduplicator.isNotificationKeyDuplicate(sbn.key)) {
            return
        }

        val packageName = sbn.packageName
        val extras = sbn.notification?.extras ?: return
        val title = extras.getString("android.title")
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val postTime = sbn.postTime

        val isPro = PlanCapabilityManager.isPro(this)
        val profile = PreferencesHelper.getUserProfile(this)

        // En Plan Básico Gratuito se detecta siempre el banco principal configurado. En PRO se detectan todos los bancos habilitados.
        val allowedBankCodes = if (isPro) {
            profile.enabledBankCodes
        } else {
            setOf(profile.bankCode)
        }

        val enabledBankEnums = allowedBankCodes.mapNotNull { code ->
            com.cobrix.pocket.data.model.BankCode.fromSudeban(code)
                ?: com.cobrix.pocket.data.model.BankCode.fromCode(code)
        }.toMutableSet()

        // Si el usuario tiene Plan PRO y tiene Binance Pay habilitado, incluirlo en la detección
        if (isPro && PreferencesHelper.isBinanceEnabled(this)) {
            enabledBankEnums.add(com.cobrix.pocket.data.model.BankCode.BINANCE)
        }

        // 3. Parseo mediante router bancario filtrando bancos permitidos
        val parsedPayment = parserRouter.routeAndParse(
            packageName = packageName,
            title = title,
            text = text,
            postTimeMillis = postTime,
            enabledBankCodes = enabledBankEnums
        ) ?: return

        // 4. Deduplicación profunda (Banco + Ref 4 dígitos + Monto)
        if (PaymentDeduplicator.isPaymentDuplicate(parsedPayment)) {
            Log.w(TAG, "Pago duplicado detectado: ${parsedPayment.referenciaUltimos4} por ${parsedPayment.monto}")
            return
        }

        Log.i(TAG, "¡Pago válido detectado! Banco: ${parsedPayment.bancoCodigo.displayName}, Monto: ${parsedPayment.monto}, Ref: ${parsedPayment.referenciaUltimos4}")

        // 5. Guardar en Base de Datos Local
        val isUsdt = (parsedPayment.moneda == "USD")
        val amountVes = if (isUsdt) (parsedPayment.monto * profile.bcvRate) else parsedPayment.monto
        val amountUsd = if (isUsdt) parsedPayment.monto else (if (profile.bcvRate > 0) parsedPayment.monto / profile.bcvRate else 0.0)

        val tx = Transaction(
            amount = amountVes,
            amountUsd = amountUsd,
            reference = parsedPayment.referenciaUltimos4,
            bankCode = parsedPayment.bancoCodigo.sudebanCode,
            bankName = parsedPayment.bancoCodigo.displayName,
            senderName = parsedPayment.telefonoOrigen ?: "",
            type = TransactionType.INCOME,
            category = if (isUsdt) "Cripto / Binance Pay" else com.cobrix.pocket.data.model.TransactionCategories.INCOME_AUTO_PAGO_MOVIL,
            note = "Detectado automáticamente de ${parsedPayment.bancoCodigo.displayName}",
            timestamp = System.currentTimeMillis(),
            isManual = false
        )
        dbHelper.insertTransaction(tx)

        // 6. Alerta Sonora y Voz según el modo de Soundbox configurado y capacidades del plan
        if (profile.soundboxMode == "VOICE" && PlanCapabilityManager.canUseVoiceSoundbox(this)) {
            if (isUsdt) {
                soundHelper?.announceCryptoPayment(parsedPayment.monto, "dólares")
            } else {
                soundHelper?.announcePayment(parsedPayment.monto)
            }
        } else if (profile.soundboxMode != "MUTED") {
            soundHelper?.playCashRegisterSound()
        }

        // 7. Notificación visual en Android
        showLocalPaymentNotification(parsedPayment.monto, parsedPayment.referenciaUltimos4, parsedPayment.bancoCodigo.displayName)

        // 8. Guardar para celebración si la app se abre justo después
        PreferencesHelper.savePendingCelebration(
            context = this,
            amount = parsedPayment.monto,
            ref = parsedPayment.referenciaUltimos4,
            bank = parsedPayment.bancoCodigo.displayName,
            phone = parsedPayment.telefonoOrigen ?: ""
        )

        // 9. Notificar a la UI (MainActivity / FullScreenQrActivity) con package explícito para Android 14+
        val broadcastIntent = Intent(ACTION_PAYMENT_RECEIVED).apply {
            setPackage(packageName)
            putExtra(EXTRA_AMOUNT, parsedPayment.monto)
            putExtra(EXTRA_REF, parsedPayment.referenciaUltimos4)
            putExtra(EXTRA_BANK, parsedPayment.bancoCodigo.displayName)
            putExtra(EXTRA_PHONE, parsedPayment.telefonoOrigen ?: "")
        }
        sendBroadcast(broadcastIntent)
    }

    private fun showLocalPaymentNotification(monto: Double, ref4: String, banco: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val formattedMonto = String.format(Locale.US, "%.2f", monto)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cashier)
            .setContentTitle("✅ Pago Recibido: Bs. $formattedMonto")
            .setContentText("$banco • Ref terminada en $ref4")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cobros Recibidos Zippy",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de cobros recibidos y confirmados con Zippy"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
