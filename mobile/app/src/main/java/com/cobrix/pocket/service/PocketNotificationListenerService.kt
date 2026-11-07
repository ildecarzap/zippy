package com.cobrix.pocket.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cobrix.pocket.R
import com.cobrix.pocket.data.db.PocketDatabaseHelper
import com.cobrix.pocket.data.model.BankCatalog
import com.cobrix.pocket.data.model.BankCode
import com.cobrix.pocket.data.model.CountryProfileCatalog
import com.cobrix.pocket.data.model.Transaction
import com.cobrix.pocket.data.model.TransactionType
import com.cobrix.pocket.service.parser.MultibankParserRouter
import com.cobrix.pocket.service.parser.ParserUtils
import com.cobrix.pocket.service.utils.PaymentDeduplicator
import com.cobrix.pocket.ui.MainActivity
import com.cobrix.pocket.utils.LicenseManager
import com.cobrix.pocket.utils.PlanCapabilityManager
import com.cobrix.pocket.utils.PreferencesHelper
import com.cobrix.pocket.utils.SoundHelper
import java.util.Locale

class PocketNotificationListenerService : NotificationListenerService() {

    private lateinit var dbHelper: PocketDatabaseHelper
    private var soundHelper: SoundHelper? = null
    private val parserRouter = MultibankParserRouter()
    private var paymentWakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "PocketNotiListener"
        const val ACTION_PAYMENT_RECEIVED = "com.cobrix.pocket.PAYMENT_RECEIVED"
        const val EXTRA_AMOUNT = "extra_amount"
        const val EXTRA_AMOUNT_USD = "extra_amount_usd"
        const val EXTRA_IS_CRYPTO = "extra_is_crypto"
        const val EXTRA_REF = "extra_ref"
        const val EXTRA_BANK = "extra_bank"
        const val EXTRA_PHONE = "extra_phone"
        const val EXTRA_RECONCILED_DEBTOR = "extra_reconciled_debtor"
        const val EXTRA_IS_OUTGOING = "extra_is_outgoing"
        private const val CHANNEL_ID = "cobrix_pocket_alerts"
        private const val CHANNEL_FOREGROUND_ID = "zeasy_shift_foreground_service"
        private const val FOREGROUND_NOTIFICATION_ID = 9010

        @Volatile
        var instance: PocketNotificationListenerService? = null
            private set

        @Volatile
        var isListenerConnected: Boolean = false
            private set

        /**
         * Verifica si el usuario ya concedió el permiso de acceso a notificaciones en Ajustes de Android.
         */
        fun isPermissionGranted(context: Context): Boolean {
            return try {
                val enabledListeners = Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                )
                enabledListeners != null && enabledListeners.contains(context.packageName)
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Fuerza la reconexión inmediata del servicio si el permiso ya está otorgado pero Android
         * suspendió el proceso o dejó el binder en estado zombie tras actualizar o abrir la app.
         */
        fun ensureServiceBound(context: Context) {
            if (!isPermissionGranted(context)) return
            try {
                val componentName = ComponentName(context, PocketNotificationListenerService::class.java)
                if (!isListenerConnected) {
                    val pm = context.packageManager
                    pm.setComponentEnabledSetting(
                        componentName,
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP
                    )
                    pm.setComponentEnabledSetting(
                        componentName,
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP
                    )
                }
                requestRebind(componentName)
                val serviceIntent = Intent(context, PocketNotificationListenerService::class.java)
                context.startService(serviceIntent)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo forzar rebind de servicio: ${e.message}")
            }
        }

        /**
         * Sincroniza la notificación fija de segundo plano cuando el comercio activa o pausa la Jornada.
         */
        fun updateForegroundShiftState(context: Context) {
            if (!isPermissionGranted(context)) return
            ensureServiceBound(context)
            instance?.applyForegroundState()
        }

        /**
         * Re-escanea todas las notificaciones activas en la barra de Android.
         * Retorna la cantidad de cobros nuevos detectados y procesados.
         */
        fun rescanActiveNotifications(playSound: Boolean = true): Int {
            return instance?.rescanInternal(playSound) ?: 0
        }

        fun isServiceRunning(): Boolean = instance != null && isListenerConnected
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        dbHelper = PocketDatabaseHelper.getInstance(this)
        soundHelper = SoundHelper(this)
        createNotificationChannel()
        applyForegroundState()
        Log.i(TAG, "Servicio de escucha Zeasy iniciado.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        applyForegroundState()
        return START_STICKY
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        isListenerConnected = true
        applyForegroundState()
        Log.i(TAG, "Notification listener connected.")
        // Al reconectar (por ejemplo al despertar en segundo plano), escanear cobros pendientes en barra
        try {
            rescanInternal(playSound = true)
        } catch (e: Exception) {
            Log.w(TAG, "Error en rescan al conectar listener: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isListenerConnected = false
        if (instance == this) {
            instance = null
        }
        Log.i(TAG, "Notification listener disconnected. Solicitando reconexión inmediata...")
        try {
            requestRebind(ComponentName(this, PocketNotificationListenerService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Error solicitando rebind en onListenerDisconnected: ${e.message}")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Si el usuario cierra la app deslizándola de recientes, mantener vivo el listener
        if (isPermissionGranted(this)) {
            try {
                requestRebind(ComponentName(this, PocketNotificationListenerService::class.java))
                applyForegroundState()
            } catch (e: Exception) {
                Log.w(TAG, "Error protegiendo servicio en onTaskRemoved: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        isListenerConnected = false
        if (instance == this) {
            instance = null
        }
        releaseWakeLock()
        soundHelper?.shutdown()
        super.onDestroy()
    }

    private fun isPackageAllowedForActiveBanks(
        pkg: String,
        activeBankCodes: Set<String>,
        isSmsEnabled: Boolean,
        isBinanceEnabled: Boolean
    ): Boolean {
        if (ParserUtils.isProhibitedMessagingPackage(pkg)) return false
        if (pkg == packageName.lowercase(Locale.ROOT) || pkg.contains("cobrix") || pkg.contains("zeasy") || pkg.contains("zippy")) {
            return false
        }
        if (isSmsEnabled && ParserUtils.isSmsPackage(pkg)) return true
        if ((activeBankCodes.isNotEmpty() || isBinanceEnabled) && ParserUtils.isEmailPackage(pkg)) return true
        if (isBinanceEnabled && BankCode.BINANCE.matchesPackage(pkg)) return true
        return activeBankCodes.any { code ->
            BankCode.fromSudeban(code)?.matchesPackage(pkg) == true
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName?.lowercase(Locale.ROOT) ?: return

        // 1. GUARD CLAUSE INMEDIATO: Blacklist inquebrantable de mensajería (Anti-Spoofing WhatsApp/Telegram)
        if (ParserUtils.isProhibitedMessagingPackage(pkg)) {
            return
        }

        // 2. GUARD CLAUSE INMEDIATO: Whitelist inteligente de bancos autorizados y canales activos
        val activeBankCodes = PreferencesHelper.getActiveListeningBankCodes(this)
        val isSmsEnabled = PreferencesHelper.isSmsDetectionEnabled(this)
        val isBinanceEnabled = PreferencesHelper.isBinanceEnabled(this) && PlanCapabilityManager.canUseWallets(this)

        if (!isPackageAllowedForActiveBanks(pkg, activeBankCodes, isSmsEnabled, isBinanceEnabled)) {
            return
        }

        processStatusBarNotification(sbn, playSound = true)
    }

    /**
     * Adquiere un WakeLock parcial de 8 segundos para evitar que el procesador entre en sueño profundo
     * mientras el teléfono está bloqueado y se guarda/anuncia el cobro.
     */
    private fun acquirePaymentWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (paymentWakeLock == null) {
                paymentWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Zeasy:PaymentProcessingWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            paymentWakeLock?.acquire(8000L)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo adquirir WakeLock temporal: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (paymentWakeLock?.isHeld == true) {
                paymentWakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    /**
     * Mantiene vinculado el servicio del sistema NotificationListenerService cuando la Jornada está activa,
     * sin requerir permisos restringidos de Foreground Service en Google Play.
     */
    fun applyForegroundState() {
        try {
            if (PreferencesHelper.isShiftActive(this) && isPermissionGranted(this)) {
                requestRebind(ComponentName(this, PocketNotificationListenerService::class.java))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Aviso al sincronizar estado de escucha: ${e.message}")
        }
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
        val sbnPackageName = sbn.packageName?.lowercase(Locale.ROOT) ?: return false

        // 0. CAPA DE SEGURIDAD 1: Blacklist estricta de mensajería (Anti-Spoofing WhatsApp/Telegram)
        if (ParserUtils.isProhibitedMessagingPackage(sbnPackageName)) {
            Log.w(TAG, "Notificación descartada por seguridad: paquete prohibido de mensajería ($sbnPackageName)")
            return false
        }

        // CAPA DE SEGURIDAD 2: Whitelist inteligente de bancos autorizados y canales activos
        val activeBankCodes = PreferencesHelper.getActiveListeningBankCodes(this)
        val isSmsEnabled = PreferencesHelper.isSmsDetectionEnabled(this)
        val isBinanceEnabled = PreferencesHelper.isBinanceEnabled(this) && PlanCapabilityManager.canUseWallets(this)

        if (!isPackageAllowedForActiveBanks(sbnPackageName, activeBankCodes, isSmsEnabled, isBinanceEnabled)) {
            Log.d(TAG, "Notificación descartada: paquete $sbnPackageName no pertenece a los bancos configurados y activos.")
            return false
        }

        // Descartar notificaciones emitidas por Zeasy (evita feedback loop y falsos cobros de Binance Pay)
        if (sbnPackageName == packageName.lowercase(Locale.ROOT) || sbnPackageName.contains("cobrix") || sbnPackageName.contains("zeasy") || sbnPackageName.contains("zippy")) {
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

        val extras = sbn.notification?.extras ?: return false
        val title = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            ?: ""
        val text = extractFullNotificationText(extras)
        val postTime = sbn.postTime

        // 2. Deduplicación por firma de notificación (incluye clave + postTime + hash del contenido
        // para que cuando BNC, Mercado Pago, BDV o Ubii reutilicen el mismo notificationId horas después,
        // el nuevo cobro NUNCA sea bloqueado por la firma de la mañana)
        val notificationSignature = "${sbn.key}|$postTime|${title.hashCode()}|${text.hashCode()}"
        if (PaymentDeduplicator.isNotificationKeyAlreadyProcessed(notificationSignature)) {
            return false
        }

        val isPro = PlanCapabilityManager.isPro(this)
        val profile = PreferencesHelper.getUserProfile(this)

        // 3. Mapear códigos de bancos activos a BankCode enums para restringir los parsers
        val enabledBankEnums = mutableSetOf<BankCode>()
        activeBankCodes.forEach { code ->
            BankCode.fromSudeban(code)?.let { enabledBankEnums.add(it) }
        }
        if (activeBankCodes.contains("BINANCE")) {
            enabledBankEnums.add(BankCode.BINANCE)
        }
        if (PreferencesHelper.isSmsDetectionEnabled(this)) {
            enabledBankEnums.add(BankCode.SMS)
        }

        // Si no hay bancos configurados para escuchar, omitir
        if (enabledBankEnums.isEmpty()) {
            return false
        }

        val countryProfile = PreferencesHelper.getCountryProfile(this)
        val isBiCurrency = countryProfile.supportsExchangeRate
        val effectiveRate = if (isBiCurrency && profile.bcvRate > 0) profile.bcvRate else 1.0

        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis

        // Candidatos de texto: primero el texto completo, y si Android apiló notificaciones antiguas
        // junto con una nueva en InboxStyle/MessagingStyle, evaluar también cada línea individual (de más reciente a más antigua)
        val candidateTexts = LinkedHashSet<String>().apply {
            add(text)
            addAll(extractIndividualNotificationLines(extras))
        }

        var selectedPayment: com.cobrix.pocket.data.model.ParsedPayment? = null
        for (candidateText in candidateTexts) {
            val candidateParsed = parserRouter.routeAndParse(
                packageName = sbnPackageName,
                title = title,
                text = candidateText,
                postTimeMillis = postTime,
                enabledBankCodes = enabledBankEnums
            ) ?: continue

            val isBankAllowed = when (candidateParsed.bancoCodigo) {
                BankCode.BINANCE -> activeBankCodes.contains("BINANCE")
                BankCode.SMS -> {
                    val detectedSudeban = ParserUtils.extractBankSudebanCode(candidateParsed.textoCrudo, title)
                        ?: BankCatalog.findByNameOrCode(candidateParsed.bancoEmisor)?.code
                    if (detectedSudeban != null) {
                        activeBankCodes.contains(detectedSudeban)
                    } else {
                        false
                    }
                }
                else -> activeBankCodes.contains(candidateParsed.bancoCodigo.sudebanCode)
            }

            if (!isBankAllowed) continue

            val candIsUsdt = (candidateParsed.moneda == "USD")
            val candAmountVes = if (candIsUsdt) (candidateParsed.monto * effectiveRate) else candidateParsed.monto
            val candAmountUsd = if (candIsUsdt) {
                candidateParsed.monto
            } else if (isBiCurrency) {
                if (effectiveRate > 0) candidateParsed.monto / effectiveRate else 0.0
            } else {
                candidateParsed.monto
            }
            val candTxType = if (candidateParsed.isOutgoing) TransactionType.EXPENSE else TransactionType.INCOME

            // Verificar primero en BD y luego en memoria para no descartar un segundo cobro apilado en la misma notificación
            if (dbHelper.hasDuplicateIncome(
                    reference = candidateParsed.referenciaUltimos4,
                    amountVes = candAmountVes,
                    amountUsd = candAmountUsd,
                    sinceTimestamp = startOfDay,
                    isCrypto = candIsUsdt,
                    sender = candidateParsed.telefonoOrigen,
                    txType = candTxType
                )) {
                continue
            }

            if (PaymentDeduplicator.isPaymentDuplicate(candidateParsed)) {
                continue
            }

            selectedPayment = candidateParsed
            break
        }

        val parsedPayment = selectedPayment ?: run {
            PaymentDeduplicator.markNotificationKeyProcessed(notificationSignature)
            return false
        }

        val isUsdt = (parsedPayment.moneda == "USD")
        val amountVes = if (isUsdt) (parsedPayment.monto * effectiveRate) else parsedPayment.monto
        val amountUsd = if (isUsdt) {
            parsedPayment.monto
        } else if (isBiCurrency) {
            if (effectiveRate > 0) parsedPayment.monto / effectiveRate else 0.0
        } else {
            parsedPayment.monto
        }
        val isOutgoing = parsedPayment.isOutgoing

        PaymentDeduplicator.markNotificationKeyProcessed(notificationSignature)

        // Si es una SALIDA / DÉBITO de un banco configurado: registrar silenciosamente en Jornada como EXPENSE
        if (isOutgoing) {
            Log.i(TAG, "📤 Salida bancaria detectada en silencio: Banco=${parsedPayment.bancoCodigo.displayName}, Monto=${parsedPayment.monto}, Ref=${parsedPayment.referenciaUltimos4}")
            val outTx = Transaction(
                amount = amountVes,
                amountUsd = amountUsd,
                reference = parsedPayment.referenciaUltimos4,
                bankCode = parsedPayment.bancoCodigo.sudebanCode,
                bankName = parsedPayment.bancoCodigo.displayName,
                senderName = parsedPayment.telefonoOrigen.orEmpty(),
                type = TransactionType.EXPENSE,
                category = com.cobrix.pocket.data.model.TransactionCategories.EXPENSE_AUTO_BANK,
                note = "Salida detectada automáticamente de ${parsedPayment.bancoCodigo.displayName} (${parsedPayment.canal})",
                timestamp = System.currentTimeMillis(),
                isManual = false
            )
            dbHelper.insertTransaction(outTx)

            val outBroadcast = Intent(ACTION_PAYMENT_RECEIVED).apply {
                setPackage(this@PocketNotificationListenerService.packageName)
                putExtra(EXTRA_AMOUNT, amountVes)
                putExtra(EXTRA_AMOUNT_USD, amountUsd)
                putExtra(EXTRA_IS_CRYPTO, isUsdt)
                putExtra(EXTRA_REF, parsedPayment.referenciaUltimos4)
                putExtra(EXTRA_BANK, parsedPayment.bancoCodigo.displayName)
                putExtra(EXTRA_IS_OUTGOING, true)
            }
            sendBroadcast(outBroadcast)
            return true
        }

        acquirePaymentWakeLock()

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

        // 6. Verificar Conciliación Automática con Libreta de Fiados (por monto exacto con céntimos adicionales - Función PRO)
        val concilMatch = if (!isUsdt && PlanCapabilityManager.canUseAutoConciliation(this)) {
            dbHelper.findDebtorForAutoConciliation(
                context = this,
                amountVes = amountVes,
                bcvRate = effectiveRate,
                senderPhone = parsedPayment.telefonoOrigen
            )
        } else null
        val matchedDebtor = concilMatch?.debtor

        val finalSenderDetails = if (matchedDebtor != null) {
            if (senderDetails.isNotBlank()) "${matchedDebtor.name} ($senderDetails)" else matchedDebtor.name
        } else {
            senderDetails
        }

        val finalNoteText = if (matchedDebtor != null) {
            val tipoConcil = if (concilMatch?.isPartialAgreed == true) "Abono acordado conciliado" else "Fiado conciliado"
            "$noteText • ✅ $tipoConcil: ${matchedDebtor.name}"
        } else {
            noteText
        }

        val tx = Transaction(
            amount = amountVes,
            amountUsd = amountUsd,
            reference = parsedPayment.referenciaUltimos4,
            bankCode = parsedPayment.bancoCodigo.sudebanCode,
            bankName = parsedPayment.bancoCodigo.displayName,
            senderName = finalSenderDetails,
            type = TransactionType.INCOME,
            category = when {
                isUsdt -> "Cripto / Binance Pay"
                matchedDebtor != null -> com.cobrix.pocket.data.model.TransactionCategories.INCOME_ABONO_FIADO
                else -> com.cobrix.pocket.data.model.TransactionCategories.INCOME_AUTO_PAGO_MOVIL
            },
            note = finalNoteText,
            timestamp = System.currentTimeMillis(),
            isManual = false
        )
        val insertedTxId = dbHelper.insertTransaction(tx)

        if (matchedDebtor != null && concilMatch != null && insertedTxId > 0L) {
            // Descontar deuda total o el monto del abono parcial acordado
            val discountUsd = concilMatch.discountUsd
            val concilMethodName = if (countryProfile.supportsExchangeRate) "Pago Móvil" else countryProfile.instantPaySystemName
            dbHelper.addPayment(
                debtorId = matchedDebtor.id,
                amountUsd = discountUsd,
                amountVes = amountVes,
                bcvRate = effectiveRate,
                matchedTxId = insertedTxId,
                notes = "Conciliación automática $concilMethodName ${parsedPayment.bancoCodigo.displayName} (Ref: ${parsedPayment.referenciaUltimos4})"
            )
            PreferencesHelper.clearPendingDebtorConciliation(this, matchedDebtor.id)
            Log.i(TAG, "✅ Deuda de ${matchedDebtor.name} ($discountUsd ${if (countryProfile.supportsExchangeRate) "USD" else countryProfile.localCurrencyCode}) conciliada y descontada automáticamente con $concilMethodName por $amountVes")
        }

        // 7. Alerta Sonora y Voz según el modo de Soundbox configurado y capacidades del plan
        if (playSound) {
            if (profile.soundboxMode == "VOICE" && PlanCapabilityManager.canUseVoiceSoundbox(this)) {
                when {
                    isUsdt -> soundHelper?.announceCryptoPayment(parsedPayment.monto, "dólares")
                    matchedDebtor != null -> soundHelper?.announceReconciledPayment(parsedPayment.monto, matchedDebtor.name)
                    else -> soundHelper?.announcePayment(parsedPayment.monto)
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
            banco = displayBankName,
            reconciledDebtorName = matchedDebtor?.name
        )

        // 9. Guardar para celebración si la app se abre justo después
        PreferencesHelper.savePendingCelebration(
            context = this,
            amount = amountVes,
            ref = parsedPayment.referenciaUltimos4,
            bank = displayBankName,
            phone = finalSenderDetails,
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
            putExtra(EXTRA_PHONE, finalSenderDetails)
            putExtra(EXTRA_RECONCILED_DEBTOR, matchedDebtor?.name ?: "")
        }
        sendBroadcast(broadcastIntent)
        return true
    }

    /**
     * Extrae todo el contenido textual disponible de una notificación,
     * combinando BigTextStyle, InboxStyle y líneas múltiples (crucial para Gmail y apps bancarias).
     * Ordena los arreglos de mensajes/líneas de más reciente a más antiguo para que, si hay varias
     * notificaciones apiladas en la barra, el último pago recibido tenga prioridad inmediata.
     */
    private fun extractFullNotificationText(extras: Bundle): String {
        val parts = LinkedHashSet<String>()

        // 1. android.bigText (BigTextStyle completo sin truncar tiene prioridad sobre android.text)
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parts.add(it)
        }

        // 2. android.text
        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parts.add(it)
        }

        // 3. android.messages (MessagingStyle: ordenar del más reciente al más antiguo; NO incluir historicMessages antiguos)
        try {
            @Suppress("DEPRECATION")
            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (messages != null) {
                for (i in messages.indices.reversed()) {
                    val msg = messages[i]
                    if (msg is Bundle) {
                        val msgText = msg.getCharSequence("text")?.toString()?.trim()
                        if (!msgText.isNullOrEmpty()) {
                            parts.add(msgText)
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        // 4. android.textLines (InboxStyle: ordenar del más reciente al más antiguo)
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (textLines != null) {
            for (i in textLines.indices.reversed()) {
                val lineStr = textLines[i]?.toString()?.trim()
                if (!lineStr.isNullOrEmpty()) {
                    parts.add(lineStr)
                }
            }
        }

        // 5. android.subText
        extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parts.add(it)
        }

        // 6. android.summaryText
        extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parts.add(it)
        }

        return parts.joinToString("\n")
    }

    /**
     * Extrae cada mensaje o línea individual de notificaciones apiladas (de más reciente a más antigua),
     * permitiendo procesar un cobro nuevo aunque esté agrupado con notificaciones previas ya registradas.
     */
    private fun extractIndividualNotificationLines(extras: Bundle): List<String> {
        val lines = LinkedHashSet<String>()
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            lines.add(it)
        }
        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            lines.add(it)
        }
        try {
            @Suppress("DEPRECATION")
            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (messages != null) {
                for (i in messages.indices.reversed()) {
                    val msg = messages[i]
                    if (msg is Bundle) {
                        val msgText = msg.getCharSequence("text")?.toString()?.trim()
                        if (!msgText.isNullOrEmpty()) {
                            lines.add(msgText)
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (textLines != null) {
            for (i in textLines.indices.reversed()) {
                val lineStr = textLines[i]?.toString()?.trim()
                if (!lineStr.isNullOrEmpty()) {
                    lines.add(lineStr)
                }
            }
        }
        return lines.toList()
    }

    private fun showLocalPaymentNotification(
        montoVes: Double,
        montoUsd: Double,
        isUsdt: Boolean,
        ref4: String,
        banco: String,
        reconciledDebtorName: String? = null
    ) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val title = when {
            !reconciledDebtorName.isNullOrBlank() ->
                "✅ Fiado Pagado ($reconciledDebtorName): Bs. ${String.format(Locale.US, "%.2f", montoVes)}"
            isUsdt ->
                "✅ Pago Recibido: $${String.format(Locale.US, "%.2f", montoUsd)} USDT"
            else ->
                "✅ Pago Recibido: Bs. ${String.format(Locale.US, "%.2f", montoVes)}"
        }

        val content = when {
            !reconciledDebtorName.isNullOrBlank() ->
                "Deuda descontada en Libreta • $banco (Ref: $ref4)"
            isUsdt ->
                "$banco • Ref: $ref4 (Bs. ${String.format(Locale.US, "%.2f", montoVes)})"
            else ->
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
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val alertChannel = NotificationChannel(
                CHANNEL_ID,
                "Cobros Recibidos Zeasy",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de cobros recibidos y confirmados con Zeasy"
                // SoundHelper controla la voz, el sonido de caja y el patrón de vibración distintivo
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(alertChannel)

            val fgChannel = NotificationChannel(
                CHANNEL_FOREGROUND_ID,
                "Zeasy Escucha en Jornada",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene activa la detección de cobros en segundo plano durante la jornada"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(fgChannel)
        }
    }
}
