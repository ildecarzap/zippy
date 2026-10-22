package com.cobrix.pocket.ui

import android.app.Activity
import android.app.DatePickerDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.cobrix.pocket.R
import com.cobrix.pocket.data.db.PocketDatabaseHelper
import com.cobrix.pocket.data.model.BankCatalog
import com.cobrix.pocket.data.model.LicenseState
import com.cobrix.pocket.data.model.Transaction
import com.cobrix.pocket.data.model.TransactionCategories
import com.cobrix.pocket.data.model.TransactionType
import com.cobrix.pocket.databinding.ActivityMainBinding
import com.cobrix.pocket.databinding.DialogAddExpenseBinding
import com.cobrix.pocket.databinding.DialogAddIncomeBinding
import com.cobrix.pocket.databinding.DialogEditTransactionBinding
import com.cobrix.pocket.databinding.DialogShareReportBinding
import com.cobrix.pocket.databinding.DialogVoiceConfirmationBinding
import com.cobrix.pocket.databinding.ItemTransactionBinding
import com.cobrix.pocket.service.PocketNotificationListenerService
import com.cobrix.pocket.utils.BiometricHelper
import com.cobrix.pocket.utils.LicenseManager
import com.cobrix.pocket.utils.PreferencesHelper
import com.cobrix.pocket.utils.ReportHelper
import com.cobrix.pocket.utils.SoundHelper
import com.cobrix.pocket.utils.WhatsAppHelper
import com.google.android.material.chip.Chip
import androidx.lifecycle.lifecycleScope
import com.cobrix.pocket.data.model.PlanType
import com.cobrix.pocket.databinding.DialogProUpgradeBinding
import com.cobrix.pocket.utils.BackupManager
import com.cobrix.pocket.utils.BcvRateHelper
import com.cobrix.pocket.utils.CloudSyncService
import com.cobrix.pocket.utils.PlanCapabilityManager
import com.cobrix.pocket.utils.ZippySyncManager
import kotlinx.coroutines.launch
import android.os.Handler
import android.os.Looper
import com.cobrix.pocket.databinding.DialogPaymentCelebrationBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var dbHelper: PocketDatabaseHelper
    private var soundHelper: SoundHelper? = null

    private var currentInput = "0"
    private var isVesMode = true // Por defecto: Bolívares (VES)
    private var isAuthenticatedThisSession = false

    // Banco activo para cobro en pantalla de Caja (seleccionable con botones/chips)
    private var activeCashierBankCode = "0134"
    private var activeCashierBankName = "Banesco"

    private val restoreBackupLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val result = BackupManager.restoreFromUri(this, uri)
            if (result.success) {
                Toast.makeText(this, "✅ ${result.message} (${result.transactionsRestored} movimientos)", Toast.LENGTH_LONG).show()
                loadSettings()
                loadTodayLedger()
                updateDisplayAmount()
            } else {
                Toast.makeText(this, "❌ ${result.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Banco seleccionado para configurar en la pestaña de Ajustes
    private var selectedBankConfigCode = "0134"
    private var selectedBankConfigName = "Banesco"

    // Launcher para recortar la imagen del QR tras seleccionarla de galería con soporte multibanco
    private val cropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val croppedUriString = result.data?.getStringExtra(QrCropActivity.EXTRA_RESULT_URI)
            val bankCode = result.data?.getStringExtra(QrCropActivity.EXTRA_BANK_CODE) ?: selectedBankConfigCode
            if (croppedUriString != null) {
                PreferencesHelper.setBankQrUri(this, bankCode, croppedUriString)
                val bankEntry = BankCatalog.findByCode(bankCode)
                val bankDisplayName = bankEntry?.name ?: bankCode
                updateSelectedBankConfigCard()
                setupCashierBankSelector()
                Toast.makeText(this, "✅ QR para $bankDisplayName guardado con éxito", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Selector de imágenes de galería que pasa a la pantalla de recorte con código de banco
    private val pickImageForCropLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val intent = Intent(this, QrCropActivity::class.java).apply {
                putExtra(QrCropActivity.EXTRA_SOURCE_URI, uri.toString())
                putExtra(QrCropActivity.EXTRA_BANK_CODE, selectedBankConfigCode)
            }
            cropLauncher.launch(intent)
        }
    }

    // Launcher de reconocimiento por voz para ingresos y egresos
    private val speechRecognizerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = matches?.firstOrNull()?.trim() ?: ""
            if (spokenText.isNotEmpty()) {
                processVoiceLedgerInput(spokenText)
            } else {
                Toast.makeText(this, "No se detectó audio, intenta de nuevo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Receptor en tiempo real para cuando el servicio captura un pago
    private val paymentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PocketNotificationListenerService.ACTION_PAYMENT_RECEIVED) {
                val amount = intent.getDoubleExtra(PocketNotificationListenerService.EXTRA_AMOUNT, 0.0)
                val ref = intent.getStringExtra(PocketNotificationListenerService.EXTRA_REF) ?: ""
                val bank = intent.getStringExtra(PocketNotificationListenerService.EXTRA_BANK) ?: ""
                val phone = intent.getStringExtra(PocketNotificationListenerService.EXTRA_PHONE) ?: ""

                runOnUiThread {
                    loadTodayLedger()
                    PreferencesHelper.getAndClearPendingCelebration(this@MainActivity)
                    if (PreferencesHelper.isCelebrationScreenEnabled(this@MainActivity) && amount > 0.0) {
                        showPaymentCelebrationDialog(amount, ref, bank, phone)
                    } else {
                        Toast.makeText(this@MainActivity, "🎉 ¡Nuevo pago registrado en caja!", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val savedTheme = PreferencesHelper.getThemeMode(this)
        if (savedTheme == "DARK") {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        } else if (savedTheme == "LIGHT") {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
        }

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dbHelper = PocketDatabaseHelper.getInstance(this)
        soundHelper = SoundHelper(this)

        setupShiftControl()
        setupCurrencyToggle()
        setupNavigation()
        setupKeypad()
        setupCashierActions()
        setupSettings()
        setupLedgerActions()
        updateLicenseBadge()
        checkNotificationPermission()
        checkBiometricAuthOnStart()

        // Sincronización en segundo plano con Zippy Hub (Registro automático y validación PRO)
        lifecycleScope.launch {
            val updated = ZippySyncManager.syncWithBackend(this@MainActivity)
            if (updated) {
                runOnUiThread {
                    updateLicenseBadge()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkNotificationPermission()
        updateShiftUI()
        loadTodayLedger()
        setupCashierBankSelector()
        updateLicenseBadge()

        // Verificar si llegó un pago mientras la pantalla estaba apagada o en otra app
        if (PreferencesHelper.isCelebrationScreenEnabled(this)) {
            val pending = PreferencesHelper.getAndClearPendingCelebration(this)
            if (pending != null && pending.amount > 0.0) {
                showPaymentCelebrationDialog(pending.amount, pending.ref, pending.bank, pending.phone)
            }
        }

        val filter = IntentFilter(PocketNotificationListenerService.ACTION_PAYMENT_RECEIVED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(paymentReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(paymentReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(paymentReceiver)
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        celebrationDismissHandler.removeCallbacksAndMessages(null)
        celebrationDialog?.dismiss()
        soundHelper?.shutdown()
        super.onDestroy()
    }

    // --- CONTROL DE JORNADA (BOTÓN DE ENCABEZADO CON POPUP DE CONFIRMACIÓN) ---
    private fun setupShiftControl() {
        updateShiftUI()

        binding.btnHeaderShiftToggle.setOnClickListener {
            val isCurrentlyActive = PreferencesHelper.isShiftActive(this)
            if (isCurrentlyActive) {
                AlertDialog.Builder(this)
                    .setTitle("⏸️ ¿Finalizar Jornada?")
                    .setMessage("Al finalizar la jornada se pausará la detección automática de notificaciones bancarias y las alertas sonoras del Soundbox.\n\n¿Deseas finalizar e ir al cuadre del día?")
                    .setPositiveButton("Finalizar y Ver Cuadre") { _, _ ->
                        PreferencesHelper.setShiftActive(this, false)
                        updateShiftUI()
                        binding.bottomNavigation.selectedItemId = R.id.nav_ledger
                        Toast.makeText(this, "Jornada pausada. Soundbox y detección inactivos.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Continuar Trabajando", null)
                    .show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("▶️ ¿Iniciar Nueva Jornada?")
                    .setMessage("Se activará la captura en tiempo real de pagos móviles y las alertas de Soundbox.")
                    .setPositiveButton("Iniciar Jornada") { _, _ ->
                        PreferencesHelper.setShiftActive(this, true)
                        updateShiftUI()
                        Toast.makeText(this, "🟢 ¡Jornada iniciada! Soundbox y detección activos.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
    }

    private fun updateShiftUI() {
        val isActive = PreferencesHelper.isShiftActive(this)
        if (isActive) {
            binding.btnHeaderShiftToggle.text = "🟢 En Jornada"
            binding.btnHeaderShiftToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00875A"))
            binding.btnHeaderShiftToggle.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#34D399"))
            binding.btnHeaderShiftToggle.setTextColor(Color.WHITE)
        } else {
            binding.btnHeaderShiftToggle.text = "⏸️ Iniciar Jornada"
            binding.btnHeaderShiftToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#DE350B"))
            binding.btnHeaderShiftToggle.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#FCA5A5"))
            binding.btnHeaderShiftToggle.setTextColor(Color.WHITE)
        }
    }

    // --- SEGURIDAD BIOMÉTRICA (HUELLA / PIN) ---
    private fun checkBiometricAuthOnStart() {
        val profile = PreferencesHelper.getUserProfile(this)
        if (profile.biometricSecurityEnabled && !isAuthenticatedThisSession) {
            if (BiometricHelper.isBiometricAvailable(this)) {
                BiometricHelper.showBiometricPrompt(
                    activity = this,
                    onSuccess = {
                        isAuthenticatedThisSession = true
                    },
                    onError = {
                        finish() // Cierra la app si falla o cancela la autenticación
                    }
                )
            }
        }
    }

    // --- SELECTOR DE MONEDA (BS / $) CON TOGGLEGROUP EXCLUSIVO ---
    private fun setupCurrencyToggle() {
        binding.toggleGroupCurrency.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isVesMode = (checkedId == R.id.btnCurrencyVes)
                currentInput = "0"
                if (isVesMode) {
                    binding.tvDisplayLabel.text = "MONTO EN BOLÍVARES"
                    binding.tvCurrencySymbol.text = "Bs."
                } else {
                    binding.tvDisplayLabel.text = "MONTO EN DÓLARES"
                    binding.tvCurrencySymbol.text = "$"
                }
                updateDisplayAmount()
            }
        }
    }

    // --- NAVEGACIÓN ENTRE TABS ---
    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_cashier -> {
                    binding.viewCashier.visibility = View.VISIBLE
                    binding.viewLedger.visibility = View.GONE
                    binding.viewSettings.visibility = View.GONE
                    setupCashierBankSelector()
                    true
                }
                R.id.nav_ledger -> {
                    binding.viewCashier.visibility = View.GONE
                    binding.viewLedger.visibility = View.VISIBLE
                    binding.viewSettings.visibility = View.GONE
                    loadTodayLedger()
                    true
                }
                R.id.nav_settings -> {
                    binding.viewCashier.visibility = View.GONE
                    binding.viewLedger.visibility = View.GONE
                    binding.viewSettings.visibility = View.VISIBLE
                    loadSettings()
                    true
                }
                else -> false
            }
        }
    }

    // --- TECLADO DE CAJA ---
    private fun setupKeypad() {
        val numButtons = listOf(
            binding.btnKey0 to "0",
            binding.btnKey1 to "1",
            binding.btnKey2 to "2",
            binding.btnKey3 to "3",
            binding.btnKey4 to "4",
            binding.btnKey5 to "5",
            binding.btnKey6 to "6",
            binding.btnKey7 to "7",
            binding.btnKey8 to "8",
            binding.btnKey9 to "9"
        )

        numButtons.forEach { (btn, digit) ->
            btn.setOnClickListener {
                if (currentInput == "0") {
                    currentInput = digit
                } else if (currentInput.length < 8) {
                    currentInput += digit
                }
                updateDisplayAmount()
            }
        }

        binding.btnKeyDot.setOnClickListener {
            if (!currentInput.contains(".")) {
                currentInput += "."
                updateDisplayAmount()
            }
        }

        binding.btnKeyClear.setOnClickListener {
            if (currentInput.length > 1) {
                currentInput = currentInput.substring(0, currentInput.length - 1)
            } else {
                currentInput = "0"
            }
            updateDisplayAmount()
        }

        binding.btnKeyClear.setOnLongClickListener {
            currentInput = "0"
            updateDisplayAmount()
            true
        }
    }

    private fun updateDisplayAmount() {
        val amount = currentInput.toDoubleOrNull() ?: 0.0
        val profile = PreferencesHelper.getUserProfile(this)

        binding.tvDisplayAmount.text = currentInput

        if (isVesMode) {
            val amountUsd = if (profile.bcvRate > 0) amount / profile.bcvRate else 0.0
            binding.tvConvertedAmount.text = String.format(
                Locale.US,
                "≈ $%.2f USD (Tasa %.2f)",
                amountUsd,
                profile.bcvRate
            )
        } else {
            val amountVes = amount * profile.bcvRate
            binding.tvConvertedAmount.text = String.format(
                Locale.US,
                "≈ Bs. %.2f (Tasa %.2f)",
                amountVes,
                profile.bcvRate
            )
        }
    }

    // --- ACCIONES DE COBRO ---
    private fun setupCashierActions() {
        // Mostrar QR en Pantalla: Permite mostrarlo CON o SIN monto ingresado (disponible en Básico y PRO)
        binding.btnChargeInPerson.setOnClickListener {
            val amount = currentInput.toDoubleOrNull() ?: 0.0
            val profile = PreferencesHelper.getUserProfile(this)
            val amountVes: Double
            val amountUsd: Double

            if (amount > 0.0) {
                if (isVesMode) {
                    amountVes = amount
                    amountUsd = if (profile.bcvRate > 0) amount / profile.bcvRate else 0.0
                } else {
                    amountUsd = amount
                    amountVes = amount * profile.bcvRate
                }
            } else {
                amountVes = 0.0
                amountUsd = 0.0
            }

            // Abre pantalla inmersiva a brillo 100% con QR gigante del banco activo seleccionado
            val intent = Intent(this, FullScreenQrActivity::class.java).apply {
                putExtra(FullScreenQrActivity.EXTRA_AMOUNT_VES, amountVes)
                putExtra(FullScreenQrActivity.EXTRA_AMOUNT_USD, amountUsd)
                putExtra(FullScreenQrActivity.EXTRA_BANK_CODE, activeCashierBankCode)
                putExtra(FullScreenQrActivity.EXTRA_BANK_NAME, activeCashierBankName)
            }
            startActivity(intent)
        }

        // WhatsApp: Exige monto obligatorio antes de enviar los datos
        binding.btnChargeWhatsApp.setOnClickListener {
            val amount = currentInput.toDoubleOrNull() ?: 0.0
            if (amount <= 0.0) {
                Toast.makeText(this, "Ingresa un monto en el teclado para cobrar por WhatsApp", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val profile = PreferencesHelper.getUserProfile(this)
            val amountVes: Double
            val amountUsd: Double

            if (isVesMode) {
                amountVes = amount
                amountUsd = if (profile.bcvRate > 0) amount / profile.bcvRate else 0.0
            } else {
                amountUsd = amount
                amountVes = amount * profile.bcvRate
            }

            // Inmediatamente compartir por WhatsApp / selector nativo con QR sin diálogo intermediario redundante
            val message = WhatsAppHelper.generatePaymentMessage(
                profile = profile,
                amountVes = amountVes,
                amountUsd = amountUsd,
                bankName = activeCashierBankName,
                bankCode = activeCashierBankCode
            )
            val qrUri = PreferencesHelper.getBankQrUri(this, activeCashierBankCode)?.let { Uri.parse(it) }
            WhatsAppHelper.sendViaWhatsApp(this, "", message, qrUri)
        }

        binding.btnChargeWhatsApp.setOnLongClickListener {
            val amount = currentInput.toDoubleOrNull() ?: 0.0
            if (amount > 0.0) {
                val profile = PreferencesHelper.getUserProfile(this)
                val amountVes = if (isVesMode) amount else amount * profile.bcvRate
                val amountUsd = if (isVesMode) (if (profile.bcvRate > 0) amount / profile.bcvRate else 0.0) else amount
                showWhatsAppPromptDialog(amountVes, amountUsd)
            }
            true
        }

        setupCashierBankSelector()
    }

    private fun setupCashierBankSelector() {
        val configuredCodes = PreferencesHelper.getConfiguredBankCodes(this)
        val profile = PreferencesHelper.getUserProfile(this)
        val codesToShow = if (configuredCodes.isEmpty()) listOf(profile.bankCode) else configuredCodes

        if (!codesToShow.contains(activeCashierBankCode)) {
            activeCashierBankCode = codesToShow.firstOrNull() ?: profile.bankCode
            activeCashierBankName = BankCatalog.findByCode(activeCashierBankCode)?.name ?: activeCashierBankCode
        }

        binding.chipGroupCashierBanks.removeAllViews()
        codesToShow.forEach { code ->
            val bank = BankCatalog.findByCode(code)
            val displayName = bank?.name ?: code
            val chip = Chip(this).apply {
                text = displayName
                isCheckable = true
                id = View.generateViewId()
                isChecked = (code == activeCashierBankCode)
                setOnClickListener {
                    activeCashierBankCode = code
                    activeCashierBankName = displayName
                    updateCashierBankQrIndicator()
                }
            }
            binding.chipGroupCashierBanks.addView(chip)
            if (code == activeCashierBankCode) {
                chip.isChecked = true
            }
        }
        updateCashierBankQrIndicator()
    }

    private fun updateCashierBankQrIndicator() {
        val qrUri = PreferencesHelper.getBankQrUri(this, activeCashierBankCode)
        if (!qrUri.isNullOrEmpty()) {
            binding.tvCashierBankQrIndicator.text = "QR Listo ✅"
            binding.tvCashierBankQrIndicator.setTextColor(ContextCompat.getColor(this, R.color.income_green))
        } else {
            binding.tvCashierBankQrIndicator.text = "⚠️ Sin QR (Configurar en Ajustes)"
            binding.tvCashierBankQrIndicator.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        }
    }

    private fun showWhatsAppPromptDialog(amountVes: Double, amountUsd: Double) {
        val profile = PreferencesHelper.getUserProfile(this)

        val input = EditText(this).apply {
            hint = "04141234567 (o dejar vacío)"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("Enviar Cobro por WhatsApp")
            .setMessage("Coloca el teléfono del cliente para abrir el chat directamente con los datos de $activeCashierBankName:")
            .setView(input)
            .setPositiveButton("Enviar") { _, _ ->
                val phone = input.text.toString().trim()
                val message = WhatsAppHelper.generatePaymentMessage(
                    profile = profile,
                    amountVes = amountVes,
                    amountUsd = amountUsd,
                    bankName = activeCashierBankName,
                    bankCode = activeCashierBankCode
                )
                val qrUri = PreferencesHelper.getBankQrUri(this, activeCashierBankCode)?.let { Uri.parse(it) }
                WhatsAppHelper.sendViaWhatsApp(this, phone, message, qrUri)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // --- TAB JORNADA / CIERRE DE CAJA ---
    private fun setupLedgerActions() {
        binding.btnAddIncome.setOnClickListener {
            showAddIncomeDialog()
        }

        binding.btnAddExpense.setOnClickListener {
            showAddExpenseDialog()
        }

        binding.btnShareSummary.setOnClickListener {
            showShareReportDialog()
        }

        binding.btnVoiceRecordMovement.setOnClickListener {
            if (!PlanCapabilityManager.canUseVoiceInput(this)) {
                showProUpgradeDialog("Carga de Ingresos y Gastos por Dictado de Voz")
                return@setOnClickListener
            }
            startVoiceRecognition()
        }

        binding.chipGroupLedgerFilter.setOnCheckedStateChangeListener { _, _ ->
            loadTodayLedger()
        }
    }

    private fun getYesterdayTimeRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }
        return getTimeRangeForDay(cal)
    }

    private fun loadTodayLedger() {
        val checkedFilterId = binding.chipGroupLedgerFilter.checkedChipId
        val (startTime, endTime) = when (checkedFilterId) {
            R.id.chipLedgerYesterday -> getYesterdayTimeRange()
            R.id.chipLedgerAll -> Pair(0L, Long.MAX_VALUE)
            else -> getTodayTimeRange()
        }

        binding.tvLedgerListTitle.text = when (checkedFilterId) {
            R.id.chipLedgerYesterday -> "MOVIMIENTOS DE AYER"
            R.id.chipLedgerAll -> "TODOS LOS MOVIMIENTOS"
            else -> "MOVIMIENTOS DE HOY"
        }

        val (income, expense, count) = dbHelper.getTodaySummary(startTime, endTime)
        val net = income - expense
        val profile = PreferencesHelper.getUserProfile(this)

        val netUsd = if (profile.bcvRate > 0) net / profile.bcvRate else 0.0

        binding.tvTotalIncomeVes.text = String.format(Locale.US, "Bs. %.2f", income)
        binding.tvIncomeCount.text = "🟢 Cobrado ($count)"
        binding.tvTotalExpenseVes.text = String.format(Locale.US, "Bs. %.2f", expense)
        binding.tvNetProfitVes.text = String.format(Locale.US, "Bs. %.2f", net)
        binding.tvNetProfitUsd.text = String.format(Locale.US, "≈ $%.2f USD", netUsd)

        val transactions = dbHelper.getTransactionsBetween(startTime, endTime)
        binding.layoutTransactionsContainer.removeAllViews()

        if (transactions.isEmpty()) {
            binding.tvEmptyTransactions.visibility = View.VISIBLE
            binding.tvEmptyTransactions.text = when (checkedFilterId) {
                R.id.chipLedgerYesterday -> "No se registraron cobros ni gastos ayer"
                R.id.chipLedgerAll -> "Aún no hay movimientos en el historial"
                else -> "Aún no hay cobros ni gastos registrados hoy"
            }
        } else {
            binding.tvEmptyTransactions.visibility = View.GONE
            val timeFormat = if (checkedFilterId == R.id.chipLedgerToday) {
                SimpleDateFormat("HH:mm", Locale.US)
            } else {
                SimpleDateFormat("dd/MM HH:mm", Locale.US)
            }

            transactions.forEach { tx ->
                val itemBinding = ItemTransactionBinding.inflate(layoutInflater, binding.layoutTransactionsContainer, false)
                val isIncome = tx.type == TransactionType.INCOME

                if (isIncome) {
                    itemBinding.ivTxIcon.setImageResource(R.drawable.ic_cashier)
                    itemBinding.ivTxIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent_green))
                    itemBinding.ivTxIcon.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_green_bg)
                    val catDisplay = TransactionCategories.getCategoryDisplayName(tx.category)
                    itemBinding.tvTxTitle.text = if (tx.isManual) "Ingreso: $catDisplay" else "Pago Móvil ${tx.bankName}"
                    itemBinding.tvTxAmount.text = String.format(Locale.US, "+ Bs. %.2f", tx.amount)
                    itemBinding.tvTxAmount.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                } else {
                    itemBinding.ivTxIcon.setImageResource(R.drawable.ic_ledger)
                    itemBinding.ivTxIcon.setColorFilter(ContextCompat.getColor(this, R.color.expense_red))
                    itemBinding.ivTxIcon.backgroundTintList = ContextCompat.getColorStateList(this, R.color.expense_red_bg)
                    val catDisplay = TransactionCategories.getCategoryDisplayName(tx.category)
                    itemBinding.tvTxTitle.text = "Gasto: $catDisplay"
                    itemBinding.tvTxAmount.text = String.format(Locale.US, "- Bs. %.2f", tx.amount)
                    itemBinding.tvTxAmount.setTextColor(ContextCompat.getColor(this, R.color.expense_red))
                }

                val refText = if (tx.reference.isNotEmpty()) " • Ref: ${tx.reference}" else ""
                val noteText = if (tx.note.isNotEmpty()) " • ${tx.note}" else ""
                val manualTag = if (tx.isManual) " (Manual)" else ""
                itemBinding.tvTxSubtitle.text = "${timeFormat.format(Date(tx.timestamp))}$manualTag$refText$noteText"

                // Click en cualquier transacción para editar o eliminar registro
                itemBinding.root.setOnClickListener {
                    showEditTransactionDialog(tx)
                }

                binding.layoutTransactionsContainer.addView(itemBinding.root)
            }
        }
    }

    private fun showAddIncomeDialog() {
        val dialog = Dialog(this)
        val dialogBinding = DialogAddIncomeBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val profile = PreferencesHelper.getUserProfile(this)
        var isIncomeVes = true

        val updateConversion = {
            val raw = dialogBinding.etIncomeAmount.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            if (isIncomeVes) {
                val usd = if (profile.bcvRate > 0) raw / profile.bcvRate else 0.0
                dialogBinding.tvIncomeConverted.text = String.format(Locale.US, "≈ $%.2f USD (Tasa %.2f)", usd, profile.bcvRate)
            } else {
                val ves = raw * profile.bcvRate
                dialogBinding.tvIncomeConverted.text = String.format(Locale.US, "≈ Bs. %.2f (Tasa %.2f)", ves, profile.bcvRate)
            }
        }

        dialogBinding.toggleIncomeCurrency.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isIncomeVes = (checkedId == R.id.btnIncomeVes)
                updateConversion()
            }
        }

        dialogBinding.etIncomeAmount.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updateConversion() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        dialogBinding.btnSaveIncome.setOnClickListener {
            val rawAmount = dialogBinding.etIncomeAmount.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            if (rawAmount <= 0.0) {
                Toast.makeText(this, "Ingresa un monto válido para el ingreso", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val amountVes: Double
            val amountUsd: Double
            if (isIncomeVes) {
                amountVes = rawAmount
                amountUsd = if (profile.bcvRate > 0) rawAmount / profile.bcvRate else 0.0
            } else {
                amountUsd = rawAmount
                amountVes = rawAmount * profile.bcvRate
            }

            val category = when (dialogBinding.chipGroupIncomeType.checkedChipId) {
                R.id.chipEfectivoBs -> TransactionCategories.INCOME_MANUAL_EFECTIVO_BS
                R.id.chipEfectivoUsd -> TransactionCategories.INCOME_MANUAL_EFECTIVO_USD
                R.id.chipPagoMovilManual -> TransactionCategories.INCOME_MANUAL_PAGO_MOVIL
                R.id.chipPuntoVenta -> TransactionCategories.INCOME_MANUAL_PUNTO
                R.id.chipTransferencia -> TransactionCategories.INCOME_MANUAL_TRANSFERENCIA
                else -> TransactionCategories.INCOME_MANUAL_OTRO
            }

            val ref = dialogBinding.etIncomeRef.text.toString().trim()
            val note = dialogBinding.etIncomeNote.text.toString().trim()

            val tx = Transaction(
                amount = amountVes,
                amountUsd = amountUsd,
                reference = ref,
                type = TransactionType.INCOME,
                category = category,
                note = note,
                timestamp = System.currentTimeMillis(),
                isManual = true
            )

            dbHelper.insertTransaction(tx)
            dialog.dismiss()
            loadTodayLedger()
            Toast.makeText(this, "✅ Ingreso manual registrado", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun showAddExpenseDialog() {
        val dialog = Dialog(this)
        val dialogBinding = DialogAddExpenseBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialogBinding.btnSaveExpense.setOnClickListener {
            val amount = dialogBinding.etExpenseAmount.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            if (amount <= 0.0) {
                Toast.makeText(this, "Ingresa un monto válido para el gasto", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val selectedChipId = dialogBinding.chipGroupCategory.checkedChipId
            val category = when (selectedChipId) {
                R.id.chipGasolina -> TransactionCategories.EXPENSE_GASOLINA
                R.id.chipAlmuerzo -> TransactionCategories.EXPENSE_COMIDA
                R.id.chipRepuesto -> TransactionCategories.EXPENSE_REPUESTO
                else -> TransactionCategories.EXPENSE_OTRO
            }

            val note = dialogBinding.etExpenseNote.text.toString().trim()
            val profile = PreferencesHelper.getUserProfile(this)
            val amountUsd = if (profile.bcvRate > 0) amount / profile.bcvRate else 0.0

            val tx = Transaction(
                amount = amount,
                amountUsd = amountUsd,
                type = TransactionType.EXPENSE,
                category = category,
                note = note,
                timestamp = System.currentTimeMillis(),
                isManual = true
            )

            dbHelper.insertTransaction(tx)
            dialog.dismiss()
            loadTodayLedger()
            Toast.makeText(this, "Gasto registrado", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun showEditTransactionDialog(tx: Transaction) {
        val dialog = Dialog(this)
        val dialogBinding = DialogEditTransactionBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val isIncome = tx.type == TransactionType.INCOME
        dialogBinding.tvEditTxTitle.text = if (isIncome) "Editar Ingreso" else "Editar Gasto"
        dialogBinding.tvEditTxSubtitle.text = if (tx.isManual) "Registro manual agregado por ti" else "Detectado automáticamente de ${tx.bankName}"
        dialogBinding.etEditTxAmount.setText(String.format(Locale.US, "%.2f", tx.amount))
        dialogBinding.etEditTxCategory.setText(TransactionCategories.getCategoryDisplayName(tx.category))
        dialogBinding.etEditTxReference.setText(tx.reference)
        dialogBinding.etEditTxNote.setText(tx.note)

        dialogBinding.btnSaveEditTx.setOnClickListener {
            val amount = dialogBinding.etEditTxAmount.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            if (amount <= 0.0) {
                Toast.makeText(this, "Ingresa un monto válido", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val profile = PreferencesHelper.getUserProfile(this)
            val amountUsd = if (profile.bcvRate > 0) amount / profile.bcvRate else 0.0
            val updated = tx.copy(
                amount = amount,
                amountUsd = amountUsd,
                reference = dialogBinding.etEditTxReference.text.toString().trim(),
                note = dialogBinding.etEditTxNote.text.toString().trim()
            )
            dbHelper.updateTransaction(updated)
            dialog.dismiss()
            loadTodayLedger()
            Toast.makeText(this, "Registro actualizado con éxito", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.btnDeleteTx.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("¿Eliminar Movimiento?")
                .setMessage("Esta acción eliminará el registro de Bs. ${String.format(Locale.US, "%.2f", tx.amount)} del balance de hoy.")
                .setPositiveButton("Eliminar") { _, _ ->
                    dbHelper.deleteTransaction(tx.id)
                    dialog.dismiss()
                    loadTodayLedger()
                    Toast.makeText(this, "Movimiento eliminado", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        dialog.show()
    }

    private fun getTimeRangeForDay(cal: Calendar): Pair<Long, Long> {
        val startCal = Calendar.getInstance().apply {
            timeInMillis = cal.timeInMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endCal = Calendar.getInstance().apply {
            timeInMillis = cal.timeInMillis
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        return Pair(startCal.timeInMillis, endCal.timeInMillis)
    }

    private fun showShareReportDialog() {
        val profile = PreferencesHelper.getUserProfile(this)

        val dialog = Dialog(this)
        val dialogBinding = DialogShareReportBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.US)

        val (initialStart, initialEnd) = getTodayTimeRange()
        var filterStartTime: Long = initialStart
        var filterEndTime: Long = initialEnd
        var filterPeriodLabel: String = "Hoy"

        // Calendarios para rango personalizado
        val customFromCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val customToCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }

        var currentFilteredTransactions = listOf<Transaction>()

        fun refreshReportPreview() {
            val checkedId = dialogBinding.chipGroupReportPeriod.checkedChipId
            when (checkedId) {
                R.id.chipReportYesterday -> {
                    dialogBinding.layoutReportCustomRange.visibility = View.GONE
                    val yCal = Calendar.getInstance().apply {
                        add(Calendar.DAY_OF_YEAR, -1)
                    }
                    val (yStart, yEnd) = getTimeRangeForDay(yCal)
                    filterStartTime = yStart
                    filterEndTime = yEnd
                    filterPeriodLabel = "Ayer (${dateFormat.format(yCal.time)})"
                }
                R.id.chipReportThisMonth -> {
                    dialogBinding.layoutReportCustomRange.visibility = View.GONE
                    val mCalStart = Calendar.getInstance().apply {
                        set(Calendar.DAY_OF_MONTH, 1)
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val mCalEnd = Calendar.getInstance().apply {
                        set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
                        set(Calendar.HOUR_OF_DAY, 23)
                        set(Calendar.MINUTE, 59)
                        set(Calendar.SECOND, 59)
                        set(Calendar.MILLISECOND, 999)
                    }
                    filterStartTime = mCalStart.timeInMillis
                    filterEndTime = mCalEnd.timeInMillis
                    val monthName = SimpleDateFormat("MMMM yyyy", Locale("es", "VE")).format(Date())
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("es", "VE")) else it.toString() }
                    filterPeriodLabel = "Mes de $monthName"
                }
                R.id.chipReportCustomRange -> {
                    dialogBinding.layoutReportCustomRange.visibility = View.VISIBLE
                    filterStartTime = customFromCalendar.timeInMillis
                    filterEndTime = customToCalendar.timeInMillis
                    val fromStr = dateFormat.format(customFromCalendar.time)
                    val toStr = dateFormat.format(customToCalendar.time)
                    filterPeriodLabel = "$fromStr al $toStr"
                    dialogBinding.btnReportDateFrom.text = "Desde: $fromStr"
                    dialogBinding.btnReportDateTo.text = "Hasta: $toStr"
                }
                else -> { // chipReportToday
                    dialogBinding.layoutReportCustomRange.visibility = View.GONE
                    val (todayStart, todayEnd) = getTodayTimeRange()
                    filterStartTime = todayStart
                    filterEndTime = todayEnd
                    filterPeriodLabel = "Hoy (${dateFormat.format(Date())})"
                }
            }

            currentFilteredTransactions = dbHelper.getTransactionsBetween(filterStartTime, filterEndTime)
            dialogBinding.tvReportPeriodLabel.text = "📅 Periodo: $filterPeriodLabel"

            val financials = ReportHelper.calculateFinancials(currentFilteredTransactions, profile.bcvRate)
            dialogBinding.tvReportSummaryBadge.text =
                "${currentFilteredTransactions.size} movimientos • Bs. ${String.format(Locale.US, "%.2f", financials.totalIncomeVes)} ($${String.format(Locale.US, "%.2f", financials.totalIncomeUsd)}) • Tasa BCV: Bs. ${String.format(Locale.US, "%.2f", profile.bcvRate)}"
        }

        dialogBinding.chipGroupReportPeriod.setOnCheckedStateChangeListener { _, _ ->
            refreshReportPreview()
        }

        dialogBinding.btnReportDateFrom.setOnClickListener {
            val c = customFromCalendar
            DatePickerDialog(this, { _, year, month, dayOfMonth ->
                customFromCalendar.set(year, month, dayOfMonth, 0, 0, 0)
                customFromCalendar.set(Calendar.MILLISECOND, 0)
                if (customFromCalendar.after(customToCalendar)) {
                    customToCalendar.timeInMillis = customFromCalendar.timeInMillis
                    customToCalendar.set(Calendar.HOUR_OF_DAY, 23)
                    customToCalendar.set(Calendar.MINUTE, 59)
                    customToCalendar.set(Calendar.SECOND, 59)
                    customToCalendar.set(Calendar.MILLISECOND, 999)
                }
                refreshReportPreview()
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }

        dialogBinding.btnReportDateTo.setOnClickListener {
            val c = customToCalendar
            DatePickerDialog(this, { _, year, month, dayOfMonth ->
                customToCalendar.set(year, month, dayOfMonth, 23, 59, 59)
                customToCalendar.set(Calendar.MILLISECOND, 999)
                refreshReportPreview()
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }

        refreshReportPreview()

        dialogBinding.btnShareOptionWhatsApp.setOnClickListener {
            dialog.dismiss()
            val fullTextReport = ReportHelper.generateDetailedTextReport(profile, currentFilteredTransactions, filterPeriodLabel)
            WhatsAppHelper.sendViaWhatsApp(this, "", fullTextReport)
        }

        dialogBinding.btnShareOptionEmail.setOnClickListener {
            dialog.dismiss()
            val fullTextReport = ReportHelper.generateDetailedTextReport(profile, currentFilteredTransactions, filterPeriodLabel)
            ReportHelper.shareViaEmail(this, profile, fullTextReport, filterPeriodLabel)
        }

        dialogBinding.btnShareOptionPdf.setOnClickListener {
            if (!PlanCapabilityManager.canExportPdf(this)) {
                showProUpgradeDialog("Exportación de Reportes PDF Formales con Membrete y Firmas")
                return@setOnClickListener
            }
            dialog.dismiss()
            val pdfFile = ReportHelper.generatePdfReport(this, profile, currentFilteredTransactions, filterPeriodLabel)
            if (pdfFile != null) {
                ReportHelper.sharePdf(this, pdfFile)
            } else {
                Toast.makeText(this, "No se pudo generar el archivo PDF", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    // --- MÓDULO DE RECONOCIMIENTO POR VOZ (INGRESOS Y EGRESOS) ---
    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-VE")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Di por ejemplo: 'Ingreso 150 efectivo' o 'Gasto 20 gasolina'")
        }
        try {
            speechRecognizerLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Tu dispositivo no cuenta con reconocimiento de voz de Google habilitado", Toast.LENGTH_LONG).show()
        }
    }

    private fun processVoiceLedgerInput(spokenText: String) {
        val lower = spokenText.lowercase().trim()

        // 1. Determinar Tipo (Ingreso o Gasto) con vocabulario amplio
        val hasExpenseKeywords = lower.contains("gasto") || lower.contains("gasté") || lower.contains("gastamos") ||
                lower.contains("egreso") || lower.contains("egresos") || lower.contains("pagué") || lower.contains("pago de") ||
                lower.contains("pago por") || lower.contains("compré") || lower.contains("compra") || lower.contains("compramos") ||
                lower.contains("gasolina") || lower.contains("gasoil") || lower.contains("combustible") ||
                lower.contains("almuerzo") || lower.contains("desayuno") || lower.contains("cena") || lower.contains("comida") ||
                lower.contains("repuesto") || lower.contains("repuestos") || lower.contains("caucho") || lower.contains("cauchos") ||
                lower.contains("tripa") || lower.contains("aceite") || lower.contains("filtro") ||
                lower.contains("peaje") || lower.contains("salida") || lower.contains("costó") || lower.contains("di ")

        val hasIncomeKeywords = lower.contains("ingreso") || lower.contains("ingresos") || lower.contains("entró") ||
                lower.contains("cobre") || lower.contains("cobré") || lower.contains("me pagaron") || lower.contains("recibí") ||
                lower.contains("carrera") || lower.contains("viaje") || lower.contains("venta") || lower.contains("vendí") ||
                lower.contains("propina") || lower.contains("abono")

        val isExpense = if (hasExpenseKeywords && !hasIncomeKeywords) {
            true
        } else if (hasIncomeKeywords && !hasExpenseKeywords) {
            false
        } else {
            hasExpenseKeywords
        }

        // 2. Determinar Moneda (USD o VES)
        val isUsd = lower.contains("dólar") || lower.contains("dolar") || lower.contains("dólares") ||
                lower.contains("dolares") || lower.contains("usd") || lower.contains("verde") ||
                lower.contains("verdes") || lower.contains("$")

        // 3. Extraer Monto Numérico
        var textForNumber = lower
            .replace("cero", "0")
            .replace("uno", "1")
            .replace("un", "1")
            .replace("dos", "2")
            .replace("tres", "3")
            .replace("cuatro", "4")
            .replace("cinco", "5")
            .replace("seis", "6")
            .replace("siete", "7")
            .replace("ocho", "8")
            .replace("nueve", "9")
            .replace("diez", "10")
            .replace("veinte", "20")
            .replace("veinticinco", "25")
            .replace("treinta", "30")
            .replace("cuarenta", "40")
            .replace("cincuenta", "50")
            .replace("sesenta", "60")
            .replace("setenta", "70")
            .replace("ochenta", "80")
            .replace("noventa", "90")
            .replace("cien", "100")
            .replace("ciento", "100")

        val conRegex = Regex("""(\d+)\s+con\s+(\d+)""")
        textForNumber = conRegex.replace(textForNumber) { matchResult ->
            "${matchResult.groupValues[1]}.${matchResult.groupValues[2]}"
        }

        val numberRegex = Regex("""(\d+(?:[.,]\d+)?)""")
        val numberMatch = numberRegex.find(textForNumber)
        val parsedRawAmount = numberMatch?.value?.replace(',', '.')?.toDoubleOrNull() ?: 0.0

        val profile = PreferencesHelper.getUserProfile(this)
        val bcvRate = if (profile.bcvRate > 0) profile.bcvRate else 45.0

        val amountVes: Double
        val amountUsd: Double
        if (isUsd) {
            amountUsd = parsedRawAmount
            amountVes = parsedRawAmount * bcvRate
        } else {
            amountVes = parsedRawAmount
            amountUsd = if (bcvRate > 0) parsedRawAmount / bcvRate else 0.0
        }

        // 4. Determinar Categoría Inteligente
        val category = if (isExpense) {
            when {
                lower.contains("gasolina") || lower.contains("combustible") || lower.contains("gasoil") -> TransactionCategories.EXPENSE_GASOLINA
                lower.contains("comida") || lower.contains("almuerzo") || lower.contains("desayuno") || lower.contains("cena") -> TransactionCategories.EXPENSE_COMIDA
                lower.contains("repuesto") || lower.contains("caucho") || lower.contains("tripa") || lower.contains("aceite") || lower.contains("filtro") || lower.contains("mantenimiento") || lower.contains("taller") || lower.contains("mecánico") -> TransactionCategories.EXPENSE_MANTENIMIENTO
                lower.contains("peaje") -> TransactionCategories.EXPENSE_PEAJE
                else -> TransactionCategories.EXPENSE_OTRO
            }
        } else {
            when {
                lower.contains("efectivo") && isUsd -> TransactionCategories.INCOME_MANUAL_EFECTIVO_USD
                lower.contains("efectivo") -> TransactionCategories.INCOME_MANUAL_EFECTIVO_BS
                lower.contains("pago móvil") || lower.contains("pagomovil") -> TransactionCategories.INCOME_MANUAL_PAGO_MOVIL
                lower.contains("punto") || lower.contains("tarjeta") -> TransactionCategories.INCOME_MANUAL_PUNTO
                lower.contains("transferencia") -> TransactionCategories.INCOME_MANUAL_TRANSFERENCIA
                else -> TransactionCategories.INCOME_MANUAL_OTRO
            }
        }

        showVoiceConfirmationDialog(
            spokenText = spokenText,
            isExpense = isExpense,
            isUsd = isUsd,
            amountVes = amountVes,
            amountUsd = amountUsd,
            parsedRawAmount = parsedRawAmount,
            category = category
        )
    }

    private fun showVoiceConfirmationDialog(
        spokenText: String,
        isExpense: Boolean,
        isUsd: Boolean,
        amountVes: Double,
        amountUsd: Double,
        parsedRawAmount: Double,
        category: String
    ) {
        val dialog = Dialog(this)
        val dialogBinding = DialogVoiceConfirmationBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        var currentIsExpense = isExpense
        var currentCategory = category

        dialogBinding.tvVoiceRawTranscript.text = "\"$spokenText\""

        fun updateVoiceTypeChip() {
            dialogBinding.chipVoiceType.text = if (currentIsExpense) "🔴 Gasto (Toca para cambiar)" else "🟢 Ingreso (Toca para cambiar)"
            dialogBinding.chipVoiceType.setChipBackgroundColorResource(
                if (currentIsExpense) R.color.expense_red_bg else R.color.accent_green_bg
            )
            dialogBinding.chipVoiceType.setTextColor(
                ContextCompat.getColor(this, if (currentIsExpense) R.color.expense_red else R.color.accent_green)
            )
            if (currentIsExpense && !currentCategory.startsWith("Gasto")) {
                currentCategory = TransactionCategories.EXPENSE_OTRO
            } else if (!currentIsExpense && currentCategory.startsWith("Gasto")) {
                currentCategory = TransactionCategories.INCOME_MANUAL_OTRO
            }
            dialogBinding.tvVoiceParsedCategory.text = "Categoría: $currentCategory"
        }

        updateVoiceTypeChip()

        dialogBinding.chipVoiceType.setOnClickListener {
            currentIsExpense = !currentIsExpense
            updateVoiceTypeChip()
        }

        if (parsedRawAmount > 0.0) {
            if (isUsd) {
                dialogBinding.tvVoiceParsedAmount.text = String.format(Locale.US, "$%.2f USD\n(≈ Bs. %.2f)", amountUsd, amountVes)
            } else {
                dialogBinding.tvVoiceParsedAmount.text = String.format(Locale.US, "Bs. %.2f\n(≈ $%.2f USD)", amountVes, amountUsd)
            }
        } else {
            dialogBinding.tvVoiceParsedAmount.text = "⚠️ No se detectó monto numérico"
        }

        dialogBinding.btnVoiceConfirmSave.setOnClickListener {
            if (amountVes <= 0.0 && amountUsd <= 0.0) {
                Toast.makeText(this, "Ajusta el monto en el formulario antes de guardar", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                if (currentIsExpense) showAddExpenseDialog() else showAddIncomeDialog()
                return@setOnClickListener
            }

            val tx = Transaction(
                amount = amountVes,
                amountUsd = amountUsd,
                reference = "VOZ",
                type = if (currentIsExpense) TransactionType.EXPENSE else TransactionType.INCOME,
                category = currentCategory,
                note = spokenText,
                timestamp = System.currentTimeMillis(),
                isManual = true
            )
            dbHelper.insertTransaction(tx)
            dialog.dismiss()
            loadTodayLedger()

            val profile = PreferencesHelper.getUserProfile(this)
            val spokenAmt = if (isUsd) amountUsd else amountVes
            if (profile.voiceAnnouncementEnabled) {
                if (currentIsExpense) {
                    soundHelper?.announceExpense(spokenAmt)
                } else {
                    soundHelper?.announcePayment(spokenAmt)
                }
            } else if (profile.soundNotificationEnabled) {
                soundHelper?.playCashRegisterSound()
            }

            val tipoMsg = if (currentIsExpense) "Gasto" else "Ingreso"
            Toast.makeText(this, "✅ $tipoMsg registrado por voz exitosamente", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.btnVoiceEditManually.setOnClickListener {
            dialog.dismiss()
            if (currentIsExpense) {
                showAddExpenseDialog()
            } else {
                showAddIncomeDialog()
            }
        }

        dialogBinding.btnVoiceCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // --- TAB AJUSTES: GESTOR MULTIBANCO Y CÓDIGOS QR ---
    private fun setupSettingsBankManager() {
        binding.chipGroupSettingsBanks.removeAllViews()
        val allBanks = BankCatalog.ALL_BANKS

        allBanks.forEach { bank ->
            val chip = Chip(this).apply {
                text = bank.name
                isCheckable = true
                id = View.generateViewId()
                isChecked = (bank.code == selectedBankConfigCode)
                setOnClickListener {
                    selectedBankConfigCode = bank.code
                    selectedBankConfigName = bank.name
                    updateSelectedBankConfigCard()
                }
            }
            binding.chipGroupSettingsBanks.addView(chip)
            if (bank.code == selectedBankConfigCode) {
                chip.isChecked = true
            }
        }

        binding.btnUploadSelectedBankQr.setOnClickListener {
            pickImageForCropLauncher.launch("image/*")
        }

        binding.btnViewSelectedBankQr.setOnClickListener {
            val qrUri = PreferencesHelper.getBankQrUri(this, selectedBankConfigCode)
            if (!qrUri.isNullOrEmpty()) {
                val intent = Intent(this, FullScreenQrActivity::class.java).apply {
                    putExtra(FullScreenQrActivity.EXTRA_AMOUNT_VES, 0.0)
                    putExtra(FullScreenQrActivity.EXTRA_AMOUNT_USD, 0.0)
                    putExtra(FullScreenQrActivity.EXTRA_BANK_CODE, selectedBankConfigCode)
                    putExtra(FullScreenQrActivity.EXTRA_BANK_NAME, selectedBankConfigName)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Aún no has subido un QR para $selectedBankConfigName", Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchSelectedBankDetection.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !PlanCapabilityManager.canUseMultibank(this)) {
                val profile = PreferencesHelper.getUserProfile(this)
                if (selectedBankConfigCode != profile.bankCode) {
                    binding.switchSelectedBankDetection.isChecked = false
                    showProUpgradeDialog("Detección y QR Multibanco Simultáneo")
                    return@setOnCheckedChangeListener
                }
            }
            PreferencesHelper.setBankDetectionEnabled(this, selectedBankConfigCode, isChecked)
            setupCashierBankSelector()
        }

        binding.btnMakeSelectedBankPrimary.setOnClickListener {
            val cur = PreferencesHelper.getUserProfile(this)
            val updated = cur.copy(bankCode = selectedBankConfigCode, bankName = selectedBankConfigName)
            PreferencesHelper.saveUserProfile(this, updated)
            activeCashierBankCode = selectedBankConfigCode
            activeCashierBankName = selectedBankConfigName
            val bankEntry = BankCatalog.findByCode(selectedBankConfigCode)
            binding.actvSettingsBank.setText(bankEntry?.toString() ?: "$selectedBankConfigCode - $selectedBankConfigName", false)
            updateSelectedBankConfigCard()
            setupCashierBankSelector()
            Toast.makeText(this, "⭐ $selectedBankConfigName establecido como banco principal", Toast.LENGTH_SHORT).show()
        }

        binding.switchDetectSms.setOnCheckedChangeListener { _, isChecked ->
            PreferencesHelper.setSmsDetectionEnabled(this, isChecked)
        }

        binding.switchCelebrationScreen.setOnCheckedChangeListener { _, isChecked ->
            PreferencesHelper.setCelebrationScreenEnabled(this, isChecked)
        }

        updateSelectedBankConfigCard()
    }

    private fun updateSelectedBankConfigCard() {
        val profile = PreferencesHelper.getUserProfile(this)
        val isPrimary = (selectedBankConfigCode == profile.bankCode)
        binding.tvSettingsSelectedBankName.text = "$selectedBankConfigName ($selectedBankConfigCode)"
        binding.tvSettingsSelectedBankPrimaryBadge.visibility = if (isPrimary) View.VISIBLE else View.GONE
        binding.btnMakeSelectedBankPrimary.isEnabled = !isPrimary
        binding.btnMakeSelectedBankPrimary.text = if (isPrimary) "✅ Este es tu Banco Principal" else "⭐ Marcar como Banco Principal Predeterminado"

        val qrUri = PreferencesHelper.getBankQrUri(this, selectedBankConfigCode)
        if (!qrUri.isNullOrEmpty()) {
            binding.tvSettingsSelectedBankQrStatus.text = "✅ Código QR guardado y listo para cobrar"
            binding.tvSettingsSelectedBankQrStatus.setTextColor(ContextCompat.getColor(this, R.color.income_green))
            binding.btnViewSelectedBankQr.isEnabled = true
        } else {
            binding.tvSettingsSelectedBankQrStatus.text = "⚠️ Sin código QR cargado para este banco"
            binding.tvSettingsSelectedBankQrStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnViewSelectedBankQr.isEnabled = false
        }

        binding.btnUploadSelectedBankQr.text = "✂️ Subir QR de $selectedBankConfigName"
        binding.switchSelectedBankDetection.isChecked = PreferencesHelper.isBankDetectionEnabled(this, selectedBankConfigCode)
    }

    // --- TAB AJUSTES ---
    private fun setupSettings() {
        binding.toggleGroupTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnThemeLight) {
                    PreferencesHelper.setThemeMode(this, "LIGHT")
                    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
                } else if (checkedId == R.id.btnThemeDark) {
                    PreferencesHelper.setThemeMode(this, "DARK")
                    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
                }
            }
        }

        // Dropdown de Rubros Principales + Opción "Otro"
        val categories = listOf(
            "Transporte / Taxi / Mototaxi",
            "Abasto / Víveres / Bodega",
            "Restaurante / Cafetería / Comida Rápida",
            "Farmacia / Salud / Droguería",
            "Ferretería / Materiales / Construcción",
            "Taller Mecánico / Repuestos / Autolavado",
            "Peluquería / Barbería / Estética",
            "Tienda / Ropa / Calzado",
            "Tecnología / Computación / Celulares",
            "Servicios Profesionales / Freelance",
            "Otro"
        )
        val categoryAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, categories)
        binding.actvSettingsBusinessCategory.setAdapter(categoryAdapter)

        // Dropdown de Principales Bancos (Sincronizado con el gestor multibanco)
        val bankEntries = BankCatalog.ALL_BANKS
        val bankAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, bankEntries.map { it.toString() })
        binding.actvSettingsBank.setAdapter(bankAdapter)

        binding.actvSettingsBank.setOnItemClickListener { _, _, position, _ ->
            val selected = bankEntries[position]
            selectedBankConfigCode = selected.code
            selectedBankConfigName = selected.name
            updateSelectedBankConfigCard()

            for (i in 0 until binding.chipGroupSettingsBanks.childCount) {
                val chip = binding.chipGroupSettingsBanks.getChildAt(i) as? Chip
                if (chip?.text == selected.name) {
                    chip.isChecked = true
                    break
                }
            }
        }

        // Gestor Multibanco y Códigos QR
        setupSettingsBankManager()

        // Consulta Automática de Tasa Oficial BCV
        binding.btnFetchBcvRate.setOnClickListener {
            binding.btnFetchBcvRate.isEnabled = false
            binding.btnFetchBcvRate.text = "⏳ ..."
            lifecycleScope.launch {
                val result = BcvRateHelper.fetchOfficialRate()
                binding.btnFetchBcvRate.isEnabled = true
                binding.btnFetchBcvRate.text = "🔄 BCV Oficial"
                if (result.isSuccess) {
                    val formatted = String.format(Locale.US, "%.2f", result.rate)
                    binding.etSettingsBcvRate.setText(formatted)
                    BcvRateHelper.applyRateToProfile(this@MainActivity, result.rate)
                    updateDisplayAmount()
                    Toast.makeText(this@MainActivity, "✅ Tasa oficial BCV actualizada: Bs. $formatted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "⚠️ No se pudo obtener tasa BCV: ${result.errorMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Restricción de Soundbox con Voz en Plan Básico
        binding.toggleGroupSoundbox.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && checkedId == R.id.btnSoundVoice && !PlanCapabilityManager.canUseVoiceSoundbox(this)) {
                binding.toggleGroupSoundbox.check(R.id.btnSoundChime)
                showProUpgradeDialog("Soundbox Parlante con Síntesis de Voz")
            }
        }

        // Probar Soundbox
        binding.btnTestSound.setOnClickListener {
            val checkedSoundId = binding.toggleGroupSoundbox.checkedButtonId
            if (checkedSoundId == R.id.btnSoundVoice) {
                soundHelper?.announcePayment(150.0)
            } else if (checkedSoundId == R.id.btnSoundChime) {
                soundHelper?.playCashRegisterSound()
            } else {
                Toast.makeText(this, "🔕 Modo silencioso seleccionado (sin sonido)", Toast.LENGTH_SHORT).show()
            }
        }

        // Guardar Ajustes (con preservación estricta de tasa BCV para evitar reset a 45)
        binding.btnSaveSettings.setOnClickListener {
            val current = PreferencesHelper.getUserProfile(this)

            val bankNameInput = binding.actvSettingsBank.text.toString().trim()
            val cleanBankName = if (bankNameInput.contains(" - ")) {
                bankNameInput.substringAfter(" - ")
            } else {
                bankNameInput.ifEmpty { selectedBankConfigName }
            }

            // Tasa BCV: aceptar tanto punto como coma, y si está vacío o inválido, conservar tasa actual (¡NO 45.0!)
            val bcvRateInput = binding.etSettingsBcvRate.text.toString().replace(',', '.').trim()
            val parsedBcvRate = bcvRateInput.toDoubleOrNull() ?: current.bcvRate

            // Modo Soundbox
            val soundboxMode = when (binding.toggleGroupSoundbox.checkedButtonId) {
                R.id.btnSoundChime -> "CHIME"
                R.id.btnSoundMuted -> "MUTED"
                else -> "VOICE"
            }

            val updated = current.copy(
                merchantName = binding.etSettingsMerchantName.text.toString().trim(),
                businessCategory = binding.actvSettingsBusinessCategory.text.toString().trim().ifEmpty { "General" },
                bankCode = selectedBankConfigCode,
                bankName = cleanBankName,
                phoneNumber = binding.etSettingsPhone.text.toString().trim(),
                idCard = binding.etSettingsId.text.toString().trim(),
                email = binding.etSettingsEmail.text.toString().trim(),
                bcvRate = parsedBcvRate,
                soundboxMode = soundboxMode,
                voiceAnnouncementEnabled = soundboxMode == "VOICE",
                soundNotificationEnabled = soundboxMode != "MUTED",
                biometricSecurityEnabled = binding.switchBiometric.isChecked
            )
            PreferencesHelper.saveUserProfile(this, updated)
            activeCashierBankCode = updated.bankCode
            activeCashierBankName = updated.bankName
            setupCashierBankSelector()
            updateSelectedBankConfigCard()
            updateDisplayAmount()

            lifecycleScope.launch {
                val updatedPro = ZippySyncManager.syncWithBackend(this@MainActivity)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "✅ Configuración guardada y vinculada a Zippy Hub", Toast.LENGTH_SHORT).show()
                    if (updatedPro) {
                        updateLicenseBadge()
                    }
                }
            }
        }

        // Plan PRO y Respaldos en Ajustes
        binding.btnManagePlanPro.setOnClickListener {
            showProUpgradeDialog("todas las funciones avanzadas")
        }

        binding.btnCreateBackup.setOnClickListener {
            val file = BackupManager.exportBackupToFile(this)
            if (file != null) {
                BackupManager.shareBackupFile(this, file)
            } else {
                Toast.makeText(this, "No se pudo generar la copia de seguridad", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRestoreBackup.setOnClickListener {
            restoreBackupLauncher.launch("*/*")
        }

        binding.btnSyncCloud.setOnClickListener {
            if (!PlanCapabilityManager.canUseCloudBackup(this)) {
                showProUpgradeDialog("Copia de Seguridad y Sincronización en la Nube")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val res = CloudSyncService.syncBackupToCloud(this@MainActivity)
                Toast.makeText(this@MainActivity, res.message, Toast.LENGTH_SHORT).show()
                binding.tvLastSyncStatus.text = "Último respaldo: ${CloudSyncService.getLastSyncFormatted(this@MainActivity)}"
            }
        }

        // Hipervínculo Web Oficial
        binding.tvAboutWebsite.setOnClickListener {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://zippy-git-902301530733.europe-west1.run.app"))
                startActivity(browserIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "No se pudo abrir el navegador", Toast.LENGTH_SHORT).show()
            }
        }

        // Botón para solicitar Link de Pago PRO (en línea con CobrixPay o por WhatsApp)
        binding.btnTrialRequestPaymentLink.setOnClickListener {
            handleRequestPaymentLinkOption()
        }

        // Botones Acerca de Ciem tech solutions C.A
        binding.btnAboutWhatsapp.setOnClickListener {
            val text = Uri.encode("Hola Ciem tech solutions C.A, requiero asistencia con Zippy")
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=${PlanCapabilityManager.SUPPORT_PHONE_CLEAN}&text=$text")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }

        binding.btnAboutEmail.setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:${PlanCapabilityManager.SUPPORT_EMAIL}")
                putExtra(Intent.EXTRA_SUBJECT, "Contacto / Soporte Zippy - Ciem tech solutions C.A")
            }
            try {
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, PlanCapabilityManager.SUPPORT_EMAIL, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadSettings() {
        val themeMode = PreferencesHelper.getThemeMode(this)
        if (themeMode == "DARK") {
            binding.toggleGroupTheme.check(R.id.btnThemeDark)
        } else {
            binding.toggleGroupTheme.check(R.id.btnThemeLight)
        }

        val profile = PreferencesHelper.getUserProfile(this)
        binding.etSettingsMerchantName.setText(profile.merchantName)
        binding.actvSettingsBusinessCategory.setText(profile.businessCategory, false)

        selectedBankConfigCode = profile.bankCode
        selectedBankConfigName = profile.bankName

        val bankEntry = BankCatalog.findByCode(profile.bankCode)
        if (bankEntry != null) {
            selectedBankConfigName = bankEntry.name
            binding.actvSettingsBank.setText(bankEntry.toString(), false)
        } else {
            binding.actvSettingsBank.setText("${profile.bankCode} - ${profile.bankName}", false)
        }

        binding.etSettingsPhone.setText(profile.phoneNumber)
        binding.etSettingsId.setText(profile.idCard)
        binding.etSettingsEmail.setText(profile.email)
        binding.etSettingsBcvRate.setText(String.format(Locale.US, "%.2f", profile.bcvRate))

        // Modo Soundbox
        when (profile.soundboxMode) {
            "CHIME" -> binding.toggleGroupSoundbox.check(R.id.btnSoundChime)
            "MUTED" -> binding.toggleGroupSoundbox.check(R.id.btnSoundMuted)
            else -> binding.toggleGroupSoundbox.check(R.id.btnSoundVoice)
        }

        binding.switchDetectSms.isChecked = PreferencesHelper.isSmsDetectionEnabled(this)
        binding.switchCelebrationScreen.isChecked = PreferencesHelper.isCelebrationScreenEnabled(this)
        binding.switchBiometric.isChecked = profile.biometricSecurityEnabled

        setupSettingsBankManager()
        setupCashierBankSelector()

        // Estado del Plan Zippy PRO y seguimiento de prueba
        val planType = PlanCapabilityManager.getPlanType(this)
        val daysRemaining = PlanCapabilityManager.getDaysRemaining(this)
        val hasUsedTrial = PlanCapabilityManager.hasUsedTrial(this)

        binding.tvSettingsPlanBadge.text = when (planType) {
            PlanType.FREE -> "Plan Básico Gratuito"
            PlanType.PRO_TRIAL -> "Prueba PRO ($daysRemaining días)"
            PlanType.PRO_ACTIVE -> "Zippy PRO Activo ⭐"
        }
        binding.tvSettingsPlanDescription.text = when (planType) {
            PlanType.FREE -> "Cobro básico ilimitado gratuito con 1 banco activo."
            PlanType.PRO_TRIAL -> "Disfrutas de todas las funciones PRO por cortesía de prueba ($daysRemaining días restantes)."
            PlanType.PRO_ACTIVE -> "Suscripción activa con acceso ilimitado a todas las funciones."
        }

        if (planType == PlanType.PRO_TRIAL) {
            if (daysRemaining <= 2) {
                binding.cardTrialPaymentAlert.visibility = View.VISIBLE
                binding.tvTrialPaymentAlertMessage.text = "⚠️ Tu prueba Zippy PRO vence en $daysRemaining días. Solicita tu enlace de pago para no perder la sincronización ni el multibanco."
            } else {
                binding.cardTrialPaymentAlert.visibility = View.GONE
            }
        } else if (hasUsedTrial && planType == PlanType.FREE) {
            binding.cardTrialPaymentAlert.visibility = View.VISIBLE
            binding.tvTrialPaymentAlertMessage.text = "ℹ️ Tu prueba Zippy PRO ha finalizado. Solicita tu enlace de pago para reactivar todas las funciones avanzadas."
        } else {
            binding.cardTrialPaymentAlert.visibility = View.GONE
        }

        binding.tvLastSyncStatus.text = "Último respaldo: ${CloudSyncService.getLastSyncFormatted(this)}"

        updateLicenseBadge()
    }

    // --- LICENCIAS Y PLANES ---
    private fun updateLicenseBadge() {
        val deviceId = PlanCapabilityManager.getDeviceId(this)
        val planType = PlanCapabilityManager.getPlanType(this)
        val daysRemaining = PlanCapabilityManager.getDaysRemaining(this)

        binding.tvSubscriptionFooter.text = "Zippy v1.2.0 • Ciem tech solutions C.A • ID: $deviceId ($planType)"

        val subtitleText = when (planType) {
            PlanType.FREE -> "⚡ Plan Básico • Toca para PRO"
            PlanType.PRO_TRIAL -> "⭐ Prueba PRO ($daysRemaining d restantes)"
            PlanType.PRO_ACTIVE -> "⭐ Zippy PRO Activo"
        }
        binding.tvHeaderSubtitle.text = subtitleText
        binding.tvHeaderSubtitle.setOnClickListener {
            if (!PlanCapabilityManager.isPro(this)) {
                showProUpgradeDialog("todas las funciones avanzadas de Zippy PRO")
            }
        }
    }

    private fun showProUpgradeDialog(featureName: String) {
        val dialogBinding = DialogProUpgradeBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.tvProReasonSubtitle.text = "Desbloquea $featureName con Zippy PRO ⚡"

        val hasUsedTrial = PlanCapabilityManager.hasUsedTrial(this)
        if (hasUsedTrial) {
            dialogBinding.btnProTrial.visibility = View.GONE
        } else {
            dialogBinding.btnProTrial.setOnClickListener {
                dialog.dismiss()
                val activated = PlanCapabilityManager.activateTrial(this)
                if (activated) {
                    loadSettings()
                    loadTodayLedger()
                    showTrialRegisteredPrompt()
                }
            }
        }

        dialogBinding.btnProWhatsapp.setOnClickListener {
            dialog.dismiss()
            handleRequestPaymentLinkOption()
        }

        dialogBinding.btnProRedeemCode.setOnClickListener {
            dialog.dismiss()
            showRedeemCodeDialog()
        }

        dialogBinding.tvProDismiss.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showTrialRegisteredPrompt() {
        val profile = PreferencesHelper.getUserProfile(this)
        val deviceId = PlanCapabilityManager.getDeviceId(this)
        val businessName = profile.merchantName.ifEmpty { "Mi Negocio" }

        AlertDialog.Builder(this)
            .setTitle("🎉 ¡Prueba de 7 Días Activada!")
            .setMessage("Tienes acceso a todas las funciones PRO (Multibanco, QR simultáneo, Sincronización en la Nube y Exportación).\n\nPara brindarte soporte prioritario y enviarte tu enlace de pago cuando finalice tu prueba, registra tu comercio por WhatsApp con Ciem tech solutions C.A.")
            .setPositiveButton("💬 Registrar por WhatsApp") { dialog, _ ->
                dialog.dismiss()
                val text = Uri.encode("Hola Ciem tech solutions C.A, acabo de activar mi prueba gratis de 7 días de Zippy PRO. Negocio: '$businessName' (ID Dispositivo: $deviceId). Quedo atento a su soporte y al link de pago al terminar.")
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=${PlanCapabilityManager.SUPPORT_PHONE_CLEAN}&text=$text")
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
            .setNegativeButton("Ahora no") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun handleRequestPaymentLinkOption() {
        val options = arrayOf(
            "💳 Pagar en Línea con CobrixPay (Inmediato)",
            "💬 Contactar a Soporte por WhatsApp"
        )
        AlertDialog.Builder(this)
            .setTitle("Suscripción Zippy PRO")
            .setItems(options) { dialog, which ->
                dialog.dismiss()
                if (which == 0) {
                    initiateOnlineCobrixPayment()
                } else {
                    openSupportWhatsAppForPro()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun initiateOnlineCobrixPayment() {
        Toast.makeText(this, "Conectando con CobrixPay...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = ZippySyncManager.requestPaymentLink(this@MainActivity, 1)
            if (result.success && !result.paymentUrl.isNullOrEmpty()) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.paymentUrl))
                    startActivity(intent)
                    if (result.isReused) {
                        Toast.makeText(this@MainActivity, "🛡️ Enlace activo recuperado (válido por 24h)", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "✅ Enlace de pago generado con éxito", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "No se pudo abrir el navegador: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Atención")
                    .setMessage(result.message)
                    .setPositiveButton("Contactar por WhatsApp") { _, _ -> openSupportWhatsAppForPro() }
                    .setNegativeButton("Cerrar", null)
                    .show()
            }
        }
    }

    private fun openSupportWhatsAppForPro() {
        val profile = PreferencesHelper.getUserProfile(this)
        val deviceId = PlanCapabilityManager.getDeviceId(this)
        val businessName = profile.merchantName.ifEmpty { "Mi Negocio" }
        val text = Uri.encode("Hola Ciem tech solutions C.A, deseo activar el Plan Zippy PRO (\$2.99) para mi negocio '$businessName' (ID Dispositivo: $deviceId). Por favor envíenme el link de pago.")
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=${PlanCapabilityManager.SUPPORT_PHONE_CLEAN}&text=$text")
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun showRedeemCodeDialog() {
        val input = EditText(this).apply {
            hint = "ZIPPYPRO2026"
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("🔑 Activar Zippy PRO")
            .setMessage("Ingresa el código proporcionado por soporte para activar tu suscripción:")
            .setView(input)
            .setPositiveButton("Activar") { _, _ ->
                val code = input.text.toString().trim()
                if (PlanCapabilityManager.activateWithLicenseKey(this, code)) {
                    Toast.makeText(this, "🎉 ¡Plan Zippy PRO activado exitosamente!", Toast.LENGTH_LONG).show()
                    loadSettings()
                    loadTodayLedger()
                    setupCashierBankSelector()
                    updateLicenseBadge()
                } else {
                    Toast.makeText(this, "❌ Código inválido o vencido", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showPaywallDialog() {
        showProUpgradeDialog("esta función")
    }

    private fun checkNotificationPermission() {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val isEnabled = enabledListeners != null && enabledListeners.contains(packageName)

        if (!isEnabled) {
            binding.layoutPermissionWarning.visibility = View.VISIBLE
            binding.btnFixPermission.setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        } else {
            binding.layoutPermissionWarning.visibility = View.GONE
        }
    }

    private fun getTodayTimeRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val endOfDay = cal.timeInMillis
        return Pair(startOfDay, endOfDay)
    }

    // --- PANTALLA DE CELEBRACIÓN CON CONFETIS AL RECIBIR PAGO ---
    private var celebrationDialog: AlertDialog? = null
    private val celebrationDismissHandler = Handler(Looper.getMainLooper())

    private fun showPaymentCelebrationDialog(amount: Double, ref: String, bank: String, phone: String) {
        celebrationDialog?.dismiss()
        celebrationDismissHandler.removeCallbacksAndMessages(null)

        val dialogBinding = DialogPaymentCelebrationBinding.inflate(layoutInflater)
        val profile = PreferencesHelper.getUserProfile(this)

        val amountUsd = if (profile.bcvRate > 0) amount / profile.bcvRate else 0.0
        dialogBinding.tvCelebrationAmountVes.text = String.format(Locale.US, "Bs. %.2f", amount)
        dialogBinding.tvCelebrationAmountUsd.text = String.format(Locale.US, "≈ $%.2f USD (Tasa %.2f)", amountUsd, profile.bcvRate)
        dialogBinding.tvCelebrationBank.text = bank.ifEmpty { profile.bankName }
        dialogBinding.tvCelebrationRef.text = if (ref.isNotEmpty()) "Ref: •••• $ref" else "Ref confirmada"

        if (phone.isNotBlank()) {
            dialogBinding.layoutCelebrationPhone.visibility = View.VISIBLE
            dialogBinding.tvCelebrationPhone.text = phone
        } else {
            dialogBinding.layoutCelebrationPhone.visibility = View.GONE
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialogBinding.layoutCelebrationRoot.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            dialogBinding.confettiView.stopConfetti()
            celebrationDismissHandler.removeCallbacksAndMessages(null)
            celebrationDialog = null
        }

        celebrationDialog = dialog
        dialog.show()

        // Disparar lluvia de confetis
        dialogBinding.confettiView.startConfetti()

        // Desaparecer automáticamente tras 3.8 segundos
        celebrationDismissHandler.postDelayed({
            if (!isFinishing && !isDestroyed && dialog.isShowing) {
                dialog.dismiss()
            }
        }, 3800)
    }
}
