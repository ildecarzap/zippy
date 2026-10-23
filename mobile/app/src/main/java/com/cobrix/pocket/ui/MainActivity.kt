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
import com.cobrix.pocket.utils.QrValidator
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
import android.content.res.ColorStateList
import android.text.InputType
import com.cobrix.pocket.data.model.BankCatalogEntry
import com.cobrix.pocket.databinding.DialogPaymentCelebrationBinding
import com.cobrix.pocket.databinding.DialogWhatsappChargeBinding
import com.cobrix.pocket.databinding.DialogSelectBankBinding
import com.cobrix.pocket.databinding.ItemBankSelectBinding
import com.cobrix.pocket.databinding.ItemCashierBankCardBinding
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

    companion object {
        private var isAuthenticatedThisSession = false
    }

    // Banco activo para cobro en pantalla de Caja (seleccionable con tarjetas o picker)
    private var activeCashierBankCode = "0178"
    private var activeCashierBankName = "Ubii App"

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
    private var selectedBankConfigCode = "0178"
    private var selectedBankConfigName = "Ubii App"
    private var targetCropBankCode: String? = null

    // Launcher para recortar la imagen del QR tras seleccionarla de galería con soporte multibanco
    private val cropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val croppedUriString = result.data?.getStringExtra(QrCropActivity.EXTRA_RESULT_URI)
            val bankCode = result.data?.getStringExtra(QrCropActivity.EXTRA_BANK_CODE) ?: targetCropBankCode ?: selectedBankConfigCode
            if (croppedUriString != null) {
                val croppedUri = Uri.parse(croppedUriString)
                val validation = QrValidator.validateForTarget(this, croppedUri, bankCode)
                if (validation is QrValidator.ValidationResult.Rejected) {
                    AlertDialog.Builder(this)
                        .setTitle("⚠️ Código QR Incompatible")
                        .setMessage(validation.reason)
                        .setPositiveButton("Entendido", null)
                        .setNeutralButton("Ver Zippy PRO") { _: android.content.DialogInterface, _: Int ->
                            showProUpgradeDialog("Billeteras Digitales y Cobros en Cripto (Binance Pay)")
                        }
                        .show()
                    return@registerForActivityResult
                }

                if (bankCode == "BINANCE") {
                    PreferencesHelper.setBinanceQrUri(this, croppedUriString)
                    PreferencesHelper.setBinanceEnabled(this, true)
                    updateBinanceSettingsCard()
                    renderCashierBankCards()
                    Toast.makeText(this, "✅ QR de Binance Pay guardado con éxito", Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }
                PreferencesHelper.setBankQrUri(this, bankCode, croppedUriString)
                val bankEntry = BankCatalog.findByCode(bankCode)
                val bankDisplayName = bankEntry?.name ?: bankCode
                if (!PlanCapabilityManager.canUseMultibank(this)) {
                    PreferencesHelper.replaceBasicPlanBank(this, bankCode, bankDisplayName)
                    activeCashierBankCode = bankCode
                    activeCashierBankName = bankDisplayName
                }
                syncSelectedBank(bankCode, bankDisplayName)
                setupCashierBankSelector()
                renderCashierBankCards()
                Toast.makeText(this, "✅ QR para $bankDisplayName guardado con éxito", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Selector de imágenes de galería que pasa a la pantalla de recorte con código de banco
    private val pickImageForCropLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val bankCode = targetCropBankCode ?: selectedBankConfigCode
            val intent = Intent(this, QrCropActivity::class.java).apply {
                putExtra(QrCropActivity.EXTRA_SOURCE_URI, uri.toString())
                putExtra(QrCropActivity.EXTRA_BANK_CODE, bankCode)
            }
            cropLauncher.launch(intent)
        }
    }

    private fun launchCropForBank(bankCode: String) {
        targetCropBankCode = bankCode
        pickImageForCropLauncher.launch("image/*")
    }

    private fun handleUploadQrClick(bankCode: String, bankName: String) {
        if (PreferencesHelper.canUploadQrForBank(this, bankCode)) {
            launchCropForBank(bankCode)
        } else {
            val banksWithQr = PreferencesHelper.getBanksWithQr(this)
            val currentBankCode = banksWithQr.firstOrNull() ?: ""
            val currentBankName = BankCatalog.findByCode(currentBankCode)?.name ?: currentBankCode

            AlertDialog.Builder(this)
                .setTitle("⚠️ Reemplazar Banco y QR")
                .setMessage("Actualmente tienes cargado el código QR de $currentBankName.\n\nEn el Plan Básico puedes tener 1 solo banco activo con QR.\n\n¿Deseas reemplazar $currentBankName por $bankName y cargar su nuevo código QR?\n\n(Para tener múltiples bancos y QRs simultáneos, activa Zippy PRO).")
                .setPositiveButton("Reemplazar y Cargar QR") { _, _ ->
                    PreferencesHelper.replaceBasicPlanBank(this, bankCode, bankName)
                    activeCashierBankCode = bankCode
                    activeCashierBankName = bankName
                    syncSelectedBank(bankCode, bankName)
                    renderCashierBankCards()
                    launchCropForBank(bankCode)
                }
                .setNegativeButton("Cancelar", null)
                .setNeutralButton("Ver Zippy PRO") { _, _ ->
                    showProUpgradeDialog("Multibanco Simultáneo (Varios bancos a la vez)")
                }
                .show()
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
        PreferencesHelper.sanitizeBasicPlanBank(this)
        val initialProfile = PreferencesHelper.getUserProfile(this)
        activeCashierBankCode = initialProfile.bankCode
        activeCashierBankName = initialProfile.bankName
        selectedBankConfigCode = initialProfile.bankCode
        selectedBankConfigName = initialProfile.bankName

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState != null) {
            isAuthenticatedThisSession = savedInstanceState.getBoolean("KEY_AUTH_SESSION", isAuthenticatedThisSession)
        }

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

        // Sincronización proactiva con Zippy Hub al iniciar
        syncLicenseStatus(showToastIfNoChange = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("KEY_AUTH_SESSION", isAuthenticatedThisSession)
    }

    override fun onResume() {
        super.onResume()
        checkNotificationPermission()
        updateShiftUI()
        loadTodayLedger()
        setupCashierBankSelector()
        renderCashierBankCards()
        updateCashierHeaderSummary()
        updateLicenseBadge()

        // Sincronización reactiva inmediata al volver a la app (detecta pago de CobrixPay o activación de soporte)
        syncLicenseStatus(showToastIfNoChange = false)

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
                    renderCashierBankCards()
                    updateCashierHeaderSummary()
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

    // --- ACCIONES DE COBRO Y CAJERO FINTECH ---
    private fun setupCashierActions() {
        // Tarjeta para agregar nuevo QR: navega directamente a Ajustes
        binding.cardCashierAddBank.setOnClickListener {
            binding.bottomNavigation.selectedItemId = R.id.nav_settings
        }

        // Tarjeta destacada de WhatsApp: Toca para abrir teclado numérico y enviar datos con QR
        binding.cardCashierWhatsApp.setOnClickListener {
            showWhatsAppChargeDialog()
        }

        binding.btnCashierOpenWhatsAppDialog.setOnClickListener {
            showWhatsAppChargeDialog()
        }

        // Teclado Tradicional en Pantalla (compatibilidad)
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

            val intent = Intent(this, FullScreenQrActivity::class.java).apply {
                putExtra(FullScreenQrActivity.EXTRA_AMOUNT_VES, amountVes)
                putExtra(FullScreenQrActivity.EXTRA_AMOUNT_USD, amountUsd)
                putExtra(FullScreenQrActivity.EXTRA_BANK_CODE, activeCashierBankCode)
                putExtra(FullScreenQrActivity.EXTRA_BANK_NAME, activeCashierBankName)
            }
            startActivity(intent)
        }

        binding.btnChargeWhatsApp.setOnClickListener {
            val amount = currentInput.toDoubleOrNull() ?: 0.0
            if (amount <= 0.0) {
                showWhatsAppChargeDialog()
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
            } else {
                showWhatsAppChargeDialog()
            }
            true
        }

        setupCashierBankSelector()
        renderCashierBankCards()
        updateCashierHeaderSummary()
    }

    /**
     * Renderiza las tarjetas de bancos configurados en la pantalla de cobro.
     * Solo muestra los bancos que tengan código QR cargado.
     * Al presionar "Quitar", se elimina el QR y la tarjeta desaparece de la pantalla principal,
     * quedando la tarjeta de "Agregar nuevo QR" y la de "WhatsApp".
     */
    private fun renderCashierBankCards() {
        binding.layoutCashierBankCardsContainer.removeAllViews()
        val isMultibank = PlanCapabilityManager.canUseMultibank(this)
        val profile = PreferencesHelper.getUserProfile(this)

        val bankCodesToShow = if (isMultibank) {
            val configured = PreferencesHelper.getConfiguredBankCodes(this)
            configured.filter { PreferencesHelper.getBankQrUri(this, it) != null }
        } else {
            if (PreferencesHelper.getBankQrUri(this, profile.bankCode) != null) {
                listOf(profile.bankCode)
            } else {
                emptyList()
            }
        }

        bankCodesToShow.forEach { code ->
            val cardBinding = ItemCashierBankCardBinding.inflate(layoutInflater, binding.layoutCashierBankCardsContainer, false)
            val bank = BankCatalog.findByCode(code)
            val bankName = bank?.name ?: code
            val brandColor = bank?.getBrandColor() ?: Color.parseColor("#00B2FE")
            val shortName = bank?.getShortName() ?: code.takeLast(2)

            cardBinding.tvCashierBankBadge.text = shortName
            cardBinding.tvCashierBankBadge.backgroundTintList = ColorStateList.valueOf(brandColor)
            cardBinding.tvCashierBankName.text = bankName
            cardBinding.tvCashierBankSubtitle.text = "Toca para mostrar QR a brillo 100%"
            cardBinding.btnCardShowQr.backgroundTintList = ColorStateList.valueOf(brandColor)

            // Abrir pantalla completa de QR al tocar la tarjeta o el botón principal
            val openQrAction = View.OnClickListener {
                activeCashierBankCode = code
                activeCashierBankName = bankName
                val intent = Intent(this, FullScreenQrActivity::class.java).apply {
                    putExtra(FullScreenQrActivity.EXTRA_AMOUNT_VES, 0.0)
                    putExtra(FullScreenQrActivity.EXTRA_AMOUNT_USD, 0.0)
                    putExtra(FullScreenQrActivity.EXTRA_BANK_CODE, code)
                    putExtra(FullScreenQrActivity.EXTRA_BANK_NAME, bankName)
                }
                startActivity(intent)
            }
            cardBinding.cardCashierBank.setOnClickListener(openQrAction)
            cardBinding.btnCardShowQr.setOnClickListener(openQrAction)

            // Botón Quitar: elimina el QR y remueve la tarjeta de la pantalla principal
            cardBinding.btnCardDeleteQr.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Quitar Tarjeta de Banco")
                    .setMessage("¿Deseas quitar la tarjeta y el código QR de $bankName de la pantalla principal?")
                    .setPositiveButton("Quitar") { _, _ ->
                        PreferencesHelper.clearBankQr(this, code)
                        renderCashierBankCards()
                        setupCashierBankSelector()
                        updateSelectedBankConfigCard()
                        Toast.makeText(this, "Tarjeta de $bankName eliminada", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }

            binding.layoutCashierBankCardsContainer.addView(cardBinding.root)
        }

        // Tarjeta de Billetera Digital Binance Pay (Exclusiva Plan PRO)
        if (PlanCapabilityManager.canUseWallets(this) && PreferencesHelper.isBinanceEnabled(this) && PreferencesHelper.getBinanceQrUri(this) != null) {
            val binanceCardBinding = ItemCashierBankCardBinding.inflate(layoutInflater, binding.layoutCashierBankCardsContainer, false)
            val binanceYellow = Color.parseColor("#F0B90B")
            val binanceDark = Color.parseColor("#181A20")

            binanceCardBinding.tvCashierBankBadge.text = "USDT"
            binanceCardBinding.tvCashierBankBadge.backgroundTintList = ColorStateList.valueOf(binanceYellow)
            binanceCardBinding.tvCashierBankBadge.setTextColor(binanceDark)

            binanceCardBinding.tvCashierBankName.text = "Binance Pay"
            val payId = PreferencesHelper.getBinancePayId(this)
            binanceCardBinding.tvCashierBankSubtitle.text = if (payId.isNotBlank()) "Pay ID: $payId • Toca para QR 100%" else "Cripto / USDT • Toca para QR 100%"
            binanceCardBinding.btnCardShowQr.backgroundTintList = ColorStateList.valueOf(binanceYellow)
            binanceCardBinding.btnCardShowQr.iconTint = ColorStateList.valueOf(binanceDark)

            val openBinanceQrAction = View.OnClickListener {
                val intent = Intent(this, FullScreenQrActivity::class.java).apply {
                    putExtra(FullScreenQrActivity.EXTRA_AMOUNT_VES, 0.0)
                    putExtra(FullScreenQrActivity.EXTRA_AMOUNT_USD, 0.0)
                    putExtra(FullScreenQrActivity.EXTRA_BANK_CODE, "BINANCE")
                    putExtra(FullScreenQrActivity.EXTRA_BANK_NAME, "Binance Pay")
                }
                startActivity(intent)
            }
            binanceCardBinding.cardCashierBank.setOnClickListener(openBinanceQrAction)
            binanceCardBinding.btnCardShowQr.setOnClickListener(openBinanceQrAction)

            binanceCardBinding.btnCardDeleteQr.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Quitar Binance Pay")
                    .setMessage("¿Deseas quitar la tarjeta de Binance Pay de la pantalla principal?\n(Podrás volver a activarla cuando quieras desde Ajustes > Billeteras Digitales).")
                    .setPositiveButton("Quitar") { _, _ ->
                        PreferencesHelper.setBinanceEnabled(this, false)
                        renderCashierBankCards()
                        updateBinanceSettingsCard()
                        Toast.makeText(this, "Tarjeta de Binance Pay quitada de caja", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }

            binding.layoutCashierBankCardsContainer.addView(binanceCardBinding.root)
        }
    }

    /**
     * Actualiza el texto descriptivo de cobro por WhatsApp.
     */
    private fun updateCashierHeaderSummary() {
        binding.tvCashierWhatsAppSubtitle.text = "Toca para digitar monto y enviar datos de $activeCashierBankName con QR"
    }

    /**
     * Muestra el modal de cobro por WhatsApp con teclado numérico integrado.
     */
    private fun showWhatsAppChargeDialog() {
        val dialog = Dialog(this)
        val dialogBinding = DialogWhatsappChargeBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val profile = PreferencesHelper.getUserProfile(this)
        var wsBankCode = activeCashierBankCode
        var wsBankName = activeCashierBankName
        var isWsVesMode = true
        var wsInput = if (currentInput != "0") currentInput else "0"

        fun updateWsDisplay() {
            dialogBinding.tvWsDisplayAmount.text = wsInput
            dialogBinding.tvWsCurrencySymbol.text = if (isWsVesMode) "Bs." else "$"

            val amount = wsInput.toDoubleOrNull() ?: 0.0
            if (isWsVesMode) {
                val usd = if (profile.bcvRate > 0) amount / profile.bcvRate else 0.0
                dialogBinding.tvWsConvertedAmount.text = String.format(Locale.US, "≈ $%.2f USD (Tasa BCV: %.2f)", usd, profile.bcvRate)
            } else {
                val ves = amount * profile.bcvRate
                dialogBinding.tvWsConvertedAmount.text = String.format(Locale.US, "≈ Bs. %.2f VES (Tasa BCV: %.2f)", ves, profile.bcvRate)
            }
            if (wsBankCode == "BINANCE") {
                val payId = PreferencesHelper.getBinancePayId(this)
                val displayId = if (payId.isNotBlank()) "Pay ID: $payId" else "Billetera Binance"
                dialogBinding.tvWsSelectedBankSubtitle.text = "🟡 $displayId (Cobro en USDT)"
            } else {
                dialogBinding.tvWsSelectedBankSubtitle.text = "Datos de: $wsBankName ($wsBankCode)"
            }
        }

        updateWsDisplay()

        val isMultibank = PlanCapabilityManager.canUseMultibank(this)
        val canWallets = PlanCapabilityManager.canUseWallets(this)
        val hasBinance = canWallets && PreferencesHelper.isBinanceEnabled(this) && PreferencesHelper.getBinanceQrUri(this) != null
        val configuredCodes = PreferencesHelper.getConfiguredBankCodes(this)
        val totalOptions = (if (isMultibank) configuredCodes.size else 1) + (if (hasBinance) 1 else 0)

        if (totalOptions > 1) {
            dialogBinding.scrollWsBanks.visibility = View.VISIBLE
            dialogBinding.chipGroupWsBanks.removeAllViews()
            val banksToDisplay = if (isMultibank) configuredCodes else listOf(profile.bankCode)
            banksToDisplay.forEach { code ->
                val bank = BankCatalog.findByCode(code)
                val displayName = bank?.name ?: code
                val chip = Chip(this).apply {
                    text = displayName
                    isCheckable = true
                    id = View.generateViewId()
                    isChecked = (code == wsBankCode)
                    setOnClickListener {
                        wsBankCode = code
                        wsBankName = displayName
                        updateWsDisplay()
                    }
                }
                dialogBinding.chipGroupWsBanks.addView(chip)
                if (code == wsBankCode) chip.isChecked = true
            }

            if (hasBinance) {
                val binanceChip = Chip(this).apply {
                    text = "🟡 Binance Pay (USDT)"
                    isCheckable = true
                    id = View.generateViewId()
                    isChecked = (wsBankCode == "BINANCE")
                    setOnClickListener {
                        wsBankCode = "BINANCE"
                        wsBankName = "Binance Pay"
                        if (isWsVesMode) {
                            dialogBinding.toggleGroupWsCurrency.check(R.id.btnWsCurrencyUsd)
                        } else {
                            updateWsDisplay()
                        }
                    }
                }
                dialogBinding.chipGroupWsBanks.addView(binanceChip)
                if (wsBankCode == "BINANCE") binanceChip.isChecked = true
            }
        } else {
            dialogBinding.scrollWsBanks.visibility = View.GONE
        }

        dialogBinding.toggleGroupWsCurrency.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isWsVesMode = (checkedId == R.id.btnWsCurrencyVes)
                updateWsDisplay()
            }
        }

        val numButtons = listOf(
            dialogBinding.btnWsKey0 to "0",
            dialogBinding.btnWsKey1 to "1",
            dialogBinding.btnWsKey2 to "2",
            dialogBinding.btnWsKey3 to "3",
            dialogBinding.btnWsKey4 to "4",
            dialogBinding.btnWsKey5 to "5",
            dialogBinding.btnWsKey6 to "6",
            dialogBinding.btnWsKey7 to "7",
            dialogBinding.btnWsKey8 to "8",
            dialogBinding.btnWsKey9 to "9"
        )
        numButtons.forEach { (btn, digit) ->
            btn.setOnClickListener {
                if (wsInput == "0") {
                    wsInput = digit
                } else if (wsInput.length < 8) {
                    wsInput += digit
                }
                updateWsDisplay()
            }
        }

        dialogBinding.btnWsKeyDot.setOnClickListener {
            if (!wsInput.contains(".")) {
                wsInput += "."
                updateWsDisplay()
            }
        }

        dialogBinding.btnWsKeyClear.setOnClickListener {
            if (wsInput.length > 1) {
                wsInput = wsInput.substring(0, wsInput.length - 1)
            } else {
                wsInput = "0"
            }
            updateWsDisplay()
        }

        dialogBinding.btnWsKeyClear.setOnLongClickListener {
            wsInput = "0"
            updateWsDisplay()
            true
        }

        dialogBinding.btnWsCloseDialog.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnWsSubmitSend.setOnClickListener {
            val amount = wsInput.toDoubleOrNull() ?: 0.0
            if (amount <= 0.0) {
                Toast.makeText(this, "Por favor ingresa un monto mayor a 0", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val amountVes: Double
            val amountUsd: Double
            if (isWsVesMode) {
                amountVes = amount
                amountUsd = if (profile.bcvRate > 0) amount / profile.bcvRate else 0.0
            } else {
                amountUsd = amount
                amountVes = amount * profile.bcvRate
            }

            val clientPhone = dialogBinding.etWsClientPhone.text.toString().trim()

            if (wsBankCode == "BINANCE") {
                val payId = PreferencesHelper.getBinancePayId(this)
                val message = WhatsAppHelper.generateBinancePaymentMessage(payId, amountUsd)
                val qrUri = PreferencesHelper.getBinanceQrUri(this)?.let { Uri.parse(it) }
                WhatsAppHelper.sendViaWhatsApp(this, clientPhone, message, qrUri)
                dialog.dismiss()
                return@setOnClickListener
            }

            val message = WhatsAppHelper.generatePaymentMessage(
                profile = profile,
                amountVes = amountVes,
                amountUsd = amountUsd,
                bankName = wsBankName,
                bankCode = wsBankCode
            )
            val qrUri = PreferencesHelper.getBankQrUri(this, wsBankCode)?.let { Uri.parse(it) }
            WhatsAppHelper.sendViaWhatsApp(this, clientPhone, message, qrUri)
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * Diálogo para seleccionar cualquier banco venezolano (incluyendo Ubii App)
     * En Plan Básico: permite elegir 1 banco y si ya tenía QR en otro, reemplazarlo.
     * En Plan PRO: activa el banco directamente.
     */
    private fun showSelectBankDialog(onBankSelected: ((BankCatalogEntry) -> Unit)? = null) {
        val dialog = Dialog(this)
        val dialogBinding = DialogSelectBankBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val isMultibank = PlanCapabilityManager.canUseMultibank(this)
        val profile = PreferencesHelper.getUserProfile(this)

        dialogBinding.tvSelectBankSubtitle.text = if (isMultibank) {
            "Elige un banco para activar y recibir pagos móviles con QR:"
        } else {
            "Elige tu banco para el Plan Básico (1 banco activo a la vez):"
        }

        dialogBinding.btnSelectBankClose.setOnClickListener {
            dialog.dismiss()
        }

        BankCatalog.ALL_BANKS.forEach { bank ->
            val itemBinding = ItemBankSelectBinding.inflate(layoutInflater, dialogBinding.layoutBankListContainer, false)
            val brandColor = bank.getBrandColor()
            val shortName = bank.getShortName()

            itemBinding.tvBankItemBadge.text = shortName
            itemBinding.tvBankItemBadge.backgroundTintList = ColorStateList.valueOf(brandColor)
            itemBinding.tvBankItemName.text = bank.name
            itemBinding.tvBankItemCode.text = "Código Sudeban: ${bank.code}"

            val isPrimary = (bank.code == profile.bankCode)
            val hasQr = PreferencesHelper.getBankQrUri(this, bank.code) != null

            if (isPrimary) {
                itemBinding.tvBankItemStatus.visibility = View.VISIBLE
                itemBinding.tvBankItemStatus.text = "⭐ Principal"
            } else if (hasQr) {
                itemBinding.tvBankItemStatus.visibility = View.VISIBLE
                itemBinding.tvBankItemStatus.text = "✅ Con QR"
                itemBinding.tvBankItemStatus.setTextColor(ContextCompat.getColor(this, R.color.income_green))
                itemBinding.tvBankItemStatus.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_green_bg)
            } else {
                itemBinding.tvBankItemStatus.visibility = View.GONE
            }

            itemBinding.root.setOnClickListener {
                dialog.dismiss()
                if (onBankSelected != null) {
                    onBankSelected(bank)
                    return@setOnClickListener
                }

                if (!isMultibank) {
                    if (bank.code != profile.bankCode) {
                        val banksWithQr = PreferencesHelper.getBanksWithQr(this@MainActivity)
                        if (banksWithQr.isNotEmpty() && !banksWithQr.contains(bank.code)) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Cambiar Banco Principal")
                                .setMessage("Actualmente tu banco configurado es ${profile.bankName}.\n\n¿Deseas cambiar tu banco a ${bank.name} y configurar su código QR?")
                                .setPositiveButton("Cambiar y Cargar QR") { _, _ ->
                                    PreferencesHelper.replaceBasicPlanBank(this@MainActivity, bank.code, bank.name)
                                    activeCashierBankCode = bank.code
                                    activeCashierBankName = bank.name
                                    syncSelectedBank(bank.code, bank.name)
                                    renderCashierBankCards()
                                    setupCashierBankSelector()
                                    launchCropForBank(bank.code)
                                }
                                .setNegativeButton("Solo Cambiar Banco") { _, _ ->
                                    PreferencesHelper.replaceBasicPlanBank(this@MainActivity, bank.code, bank.name)
                                    activeCashierBankCode = bank.code
                                    activeCashierBankName = bank.name
                                    syncSelectedBank(bank.code, bank.name)
                                    renderCashierBankCards()
                                    setupCashierBankSelector()
                                    Toast.makeText(this@MainActivity, "Banco cambiado a ${bank.name}", Toast.LENGTH_SHORT).show()
                                }
                                .setNeutralButton("Cancelar", null)
                                .show()
                        } else {
                            PreferencesHelper.replaceBasicPlanBank(this@MainActivity, bank.code, bank.name)
                            activeCashierBankCode = bank.code
                            activeCashierBankName = bank.name
                            syncSelectedBank(bank.code, bank.name)
                            renderCashierBankCards()
                            setupCashierBankSelector()
                            Toast.makeText(this@MainActivity, "Banco activo: ${bank.name}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        activeCashierBankCode = bank.code
                        activeCashierBankName = bank.name
                        syncSelectedBank(bank.code, bank.name)
                        renderCashierBankCards()
                    }
                } else {
                    PreferencesHelper.setBankDetectionEnabled(this@MainActivity, bank.code, true)
                    activeCashierBankCode = bank.code
                    activeCashierBankName = bank.name
                    syncSelectedBank(bank.code, bank.name)
                    renderCashierBankCards()
                    setupCashierBankSelector()
                    if (PreferencesHelper.getBankQrUri(this@MainActivity, bank.code) == null) {
                        handleUploadQrClick(bank.code, bank.name)
                    } else {
                        Toast.makeText(this@MainActivity, "Banco seleccionado: ${bank.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            dialogBinding.layoutBankListContainer.addView(itemBinding.root)
        }

        dialog.show()
    }

    /**
     * Permite colocar un monto específico antes de mostrar el QR en pantalla grande.
     */
    private fun showAmountForQrDialog(bankCode: String, bankName: String) {
        val input = EditText(this).apply {
            hint = "Monto en Bolívares (ej. 150.00)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("Mostrar QR con Monto")
            .setMessage("Ingresa el monto a cobrar con $bankName para mostrarlo sobre el código QR:")
            .setView(input)
            .setPositiveButton("Mostrar QR") { _, _ ->
                val amountStr = input.text.toString().replace(',', '.').trim()
                val amount = amountStr.toDoubleOrNull() ?: 0.0
                val profile = PreferencesHelper.getUserProfile(this)
                val amountUsd = if (profile.bcvRate > 0) amount / profile.bcvRate else 0.0

                val intent = Intent(this, FullScreenQrActivity::class.java).apply {
                    putExtra(FullScreenQrActivity.EXTRA_AMOUNT_VES, amount)
                    putExtra(FullScreenQrActivity.EXTRA_AMOUNT_USD, amountUsd)
                    putExtra(FullScreenQrActivity.EXTRA_BANK_CODE, bankCode)
                    putExtra(FullScreenQrActivity.EXTRA_BANK_NAME, bankName)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
                    renderCashierBankCards()
                    updateCashierHeaderSummary()
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
        updateCashierHeaderSummary()

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

    /**
     * Sincroniza de forma unificada el banco seleccionado en toda la pestaña de Ajustes
     * (dropdown, chips de banco, tarjeta interactiva y predeterminado).
     */
    private fun syncSelectedBank(bankCode: String, bankName: String) {
        val entry = BankCatalog.findByCode(bankCode) ?: BankCatalog.findByNameOrCode(bankName)
        val finalCode = entry?.code ?: bankCode
        val finalName = entry?.name ?: bankName

        selectedBankConfigCode = finalCode
        selectedBankConfigName = finalName

        val displayName = entry?.toString() ?: "$finalCode - $finalName"
        binding.actvSettingsBank.setText(displayName, false)

        for (i in 0 until binding.chipGroupSettingsBanks.childCount) {
            val chip = binding.chipGroupSettingsBanks.getChildAt(i) as? Chip
            if (chip != null) {
                val chipCode = chip.tag as? String
                chip.isChecked = (chipCode == finalCode || chip.text == finalName)
            }
        }

        updateSelectedBankConfigCard()
    }

    // --- TAB AJUSTES: GESTOR MULTIBANCO Y CÓDIGOS QR ---
    private fun setupSettingsBankManager() {
        binding.chipGroupSettingsBanks.removeAllViews()
        val allBanks = BankCatalog.ALL_BANKS

        allBanks.forEach { bank ->
            val chip = Chip(this).apply {
                text = bank.name
                tag = bank.code
                isCheckable = true
                id = View.generateViewId()
                isChecked = (bank.code == selectedBankConfigCode)
                setOnClickListener {
                    syncSelectedBank(bank.code, bank.name)
                }
            }
            binding.chipGroupSettingsBanks.addView(chip)
            if (bank.code == selectedBankConfigCode) {
                chip.isChecked = true
            }
        }

        binding.btnUploadSelectedBankQr.setOnClickListener {
            handleUploadQrClick(selectedBankConfigCode, selectedBankConfigName)
        }

        binding.btnDeleteSelectedBankQr.setOnClickListener {
            val qrUri = PreferencesHelper.getBankQrUri(this, selectedBankConfigCode)
            if (qrUri.isNullOrEmpty()) {
                Toast.makeText(this, "No hay código QR cargado para $selectedBankConfigName", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Eliminar Código QR")
                .setMessage("¿Deseas quitar el código QR de $selectedBankConfigName?")
                .setPositiveButton("Eliminar") { _, _ ->
                    PreferencesHelper.clearBankQr(this, selectedBankConfigCode)
                    updateSelectedBankConfigCard()
                    setupCashierBankSelector()
                    renderCashierBankCards()
                    Toast.makeText(this, "Código QR eliminado", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
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
            renderCashierBankCards()
        }

        binding.btnMakeSelectedBankPrimary.setOnClickListener {
            val bankEntry = BankCatalog.findByCode(selectedBankConfigCode)
            val finalCode = bankEntry?.code ?: selectedBankConfigCode
            val finalName = bankEntry?.name ?: selectedBankConfigName
            if (!PlanCapabilityManager.canUseMultibank(this)) {
                PreferencesHelper.replaceBasicPlanBank(this, finalCode, finalName)
            } else {
                val cur = PreferencesHelper.getUserProfile(this)
                val updated = cur.copy(bankCode = finalCode, bankName = finalName)
                PreferencesHelper.saveUserProfile(this, updated)
                PreferencesHelper.setPrimaryBank(this, finalCode, finalName)
            }
            activeCashierBankCode = finalCode
            activeCashierBankName = finalName
            syncSelectedBank(finalCode, finalName)
            setupCashierBankSelector()
            renderCashierBankCards()
            updateCashierHeaderSummary()
            Toast.makeText(this, "⭐ $finalName establecido como banco principal", Toast.LENGTH_SHORT).show()
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
            binding.btnDeleteSelectedBankQr.visibility = View.VISIBLE
        } else {
            binding.tvSettingsSelectedBankQrStatus.text = "⚠️ Sin código QR cargado para este banco"
            binding.tvSettingsSelectedBankQrStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnViewSelectedBankQr.isEnabled = false
            binding.btnDeleteSelectedBankQr.visibility = View.GONE
        }

        binding.btnUploadSelectedBankQr.text = "✂️ Subir QR de $selectedBankConfigName"
        binding.switchSelectedBankDetection.isChecked = PreferencesHelper.isBankDetectionEnabled(this, selectedBankConfigCode)
    }

    /**
     * Configuración de la sección de Billeteras Digitales y Cripto (Binance Pay)
     * EXCLUSIVO DEL PLAN PRO.
     */
    private fun setupSettingsWallets() {
        val isPro = PlanCapabilityManager.canUseWallets(this)

        if (!isPro) {
            // Usuario en Plan Básico: mostrar banner de bloqueo PRO
            binding.cardWalletsProLocked.visibility = View.VISIBLE
            binding.layoutWalletsProContent.visibility = View.GONE
            binding.tvSettingsWalletsProBadge.text = "🔒 PRO"

            val proUpgradeClick = View.OnClickListener {
                showProUpgradeDialog("Billeteras Digitales y Cobros en Cripto (Binance Pay)")
            }
            binding.cardSettingsWalletsSection.setOnClickListener(proUpgradeClick)
            binding.btnUnlockWalletsPro.setOnClickListener(proUpgradeClick)
        } else {
            // Usuario con Zippy PRO activo o prueba PRO: habilitar contenido completo
            binding.cardWalletsProLocked.visibility = View.GONE
            binding.layoutWalletsProContent.visibility = View.VISIBLE
            binding.tvSettingsWalletsProBadge.text = "⭐ PRO"
            binding.cardSettingsWalletsSection.setOnClickListener(null)

            binding.switchBinanceEnabled.setOnCheckedChangeListener { _, isChecked ->
                PreferencesHelper.setBinanceEnabled(this, isChecked)
                renderCashierBankCards()
                val statusText = if (isChecked) "activado en caja" else "desactivado en caja"
                Toast.makeText(this, "Binance Pay $statusText", Toast.LENGTH_SHORT).show()
            }

            binding.btnUploadBinanceQr.setOnClickListener {
                launchCropForBank("BINANCE")
            }

            binding.btnViewBinanceQr.setOnClickListener {
                val intent = Intent(this, FullScreenQrActivity::class.java).apply {
                    putExtra(FullScreenQrActivity.EXTRA_AMOUNT_VES, 0.0)
                    putExtra(FullScreenQrActivity.EXTRA_AMOUNT_USD, 0.0)
                    putExtra(FullScreenQrActivity.EXTRA_BANK_CODE, "BINANCE")
                    putExtra(FullScreenQrActivity.EXTRA_BANK_NAME, "Binance Pay")
                }
                startActivity(intent)
            }

            binding.btnDeleteBinanceQr.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Eliminar QR de Binance Pay")
                    .setMessage("¿Deseas eliminar el código QR de Binance Pay?")
                    .setPositiveButton("Eliminar") { _, _ ->
                        PreferencesHelper.clearBinanceQr(this)
                        updateBinanceSettingsCard()
                        renderCashierBankCards()
                        Toast.makeText(this, "QR de Binance Pay eliminado", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }

        updateBinanceSettingsCard()
    }

    private fun updateBinanceSettingsCard() {
        val isPro = PlanCapabilityManager.canUseWallets(this)
        if (!isPro) {
            binding.cardWalletsProLocked.visibility = View.VISIBLE
            binding.layoutWalletsProContent.visibility = View.GONE
            return
        }

        binding.cardWalletsProLocked.visibility = View.GONE
        binding.layoutWalletsProContent.visibility = View.VISIBLE

        val isEnabled = PreferencesHelper.isBinanceEnabled(this)
        binding.switchBinanceEnabled.isChecked = isEnabled

        val payId = PreferencesHelper.getBinancePayId(this)
        if (binding.etBinancePayId.text.toString().isEmpty() && payId.isNotEmpty()) {
            binding.etBinancePayId.setText(payId)
        }

        val qrUri = PreferencesHelper.getBinanceQrUri(this)
        if (qrUri != null) {
            binding.tvBinanceQrStatus.text = "✅ QR de Binance Pay cargado y listo"
            binding.tvBinanceQrStatus.setTextColor(ContextCompat.getColor(this, R.color.income_green))
            binding.btnViewBinanceQr.isEnabled = true
            binding.btnDeleteBinanceQr.visibility = View.VISIBLE
        } else {
            binding.tvBinanceQrStatus.text = "⚠️ Sin código QR cargado"
            binding.tvBinanceQrStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnViewBinanceQr.isEnabled = false
            binding.btnDeleteBinanceQr.visibility = View.GONE
        }
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
        binding.actvSettingsBank.setOnClickListener {
            binding.actvSettingsBank.showDropDown()
        }

        binding.actvSettingsBank.setOnItemClickListener { parent, _, position, _ ->
            val itemText = parent.getItemAtPosition(position).toString()
            val selected = BankCatalog.findByNameOrCode(itemText)
                ?: bankEntries.getOrNull(position)
                ?: bankEntries.first()
            syncSelectedBank(selected.code, selected.name)
        }

        // Gestor Multibanco y Códigos QR
        setupSettingsBankManager()
        setupSettingsWallets()

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

        // Guardar Ajustes (con preservación estricta de tasa BCV y sincronización unificada de banco)
        binding.btnSaveSettings.setOnClickListener {
            val current = PreferencesHelper.getUserProfile(this)

            val bankInput = binding.actvSettingsBank.text.toString().trim()
            val bankEntry = BankCatalog.findByNameOrCode(bankInput)
                ?: BankCatalog.findByCode(selectedBankConfigCode)
                ?: BankCatalog.findByCode("0178")
                ?: BankCatalog.ALL_BANKS.first()

            val finalBankCode = bankEntry.code
            val finalBankName = bankEntry.name

            syncSelectedBank(finalBankCode, finalBankName)

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
                bankCode = finalBankCode,
                bankName = finalBankName,
                phoneNumber = binding.etSettingsPhone.text.toString().trim(),
                idCard = binding.etSettingsId.text.toString().trim(),
                email = binding.etSettingsEmail.text.toString().trim(),
                bcvRate = parsedBcvRate,
                soundboxMode = soundboxMode,
                voiceAnnouncementEnabled = soundboxMode == "VOICE",
                soundNotificationEnabled = soundboxMode != "MUTED",
                biometricSecurityEnabled = binding.switchBiometric.isChecked
            )

            if (!PlanCapabilityManager.canUseMultibank(this)) {
                PreferencesHelper.replaceBasicPlanBank(this, finalBankCode, finalBankName)
            } else {
                PreferencesHelper.setPrimaryBank(this, finalBankCode, finalBankName)
            }
            PreferencesHelper.saveUserProfile(this, updated)

            // Guardar Binance Pay ID si PRO está disponible
            if (PlanCapabilityManager.canUseWallets(this)) {
                PreferencesHelper.setBinancePayId(this, binding.etBinancePayId.text.toString().trim())
            }

            activeCashierBankCode = finalBankCode
            activeCashierBankName = finalBankName
            setupCashierBankSelector()
            renderCashierBankCards()
            updateCashierHeaderSummary()
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

        syncSelectedBank(profile.bankCode, profile.bankName)

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
        setupSettingsWallets()

        // Estado del Plan Zippy PRO y seguimiento de prueba / renovación
        val planType = PlanCapabilityManager.getPlanType(this)
        val daysRemaining = PlanCapabilityManager.getDaysRemaining(this)
        val hasUsedTrial = PlanCapabilityManager.hasUsedTrial(this)
        val hasHadPaidPro = PlanCapabilityManager.hasHadPaidPro(this)

        binding.tvSettingsPlanBadge.text = when (planType) {
            PlanType.FREE -> if (hasHadPaidPro) "Plan Básico (PRO Vencido)" else "Plan Básico Gratuito"
            PlanType.PRO_TRIAL -> "Prueba PRO ($daysRemaining días)"
            PlanType.PRO_ACTIVE -> "Zippy PRO Activo ⭐"
        }
        binding.tvSettingsPlanDescription.text = when (planType) {
            PlanType.FREE -> if (hasHadPaidPro) "Tu mes de Zippy PRO culminó. Continúas en modo básico gratuito con 1 banco activo." else "Cobro básico ilimitado gratuito con 1 banco activo."
            PlanType.PRO_TRIAL -> "Disfrutas de todas las funciones PRO por cortesía de prueba ($daysRemaining días restantes)."
            PlanType.PRO_ACTIVE -> "Suscripción activa con acceso ilimitado a todas las funciones ($daysRemaining días restantes)."
        }

        when (planType) {
            PlanType.PRO_ACTIVE -> {
                if (daysRemaining <= 3) {
                    // Aviso preventivo para renovar antes de que expire el mes
                    binding.cardTrialPaymentAlert.visibility = View.VISIBLE
                    binding.tvTrialPaymentAlertMessage.text = "⚠️ Tu suscripción Zippy PRO vence en $daysRemaining día(s). Puedes renovar tu mes con anticipación para continuar sin interrupciones."
                    binding.btnTrialRequestPaymentLink.text = "💳 Renovar Suscripción Zippy PRO"
                    binding.btnManagePlanPro.visibility = View.VISIBLE
                    binding.btnManagePlanPro.text = "💳 Renovar Suscripción PRO"
                } else {
                    binding.cardTrialPaymentAlert.visibility = View.GONE
                    binding.btnManagePlanPro.visibility = View.GONE
                }
            }
            PlanType.PRO_TRIAL -> {
                if (daysRemaining <= 2) {
                    binding.cardTrialPaymentAlert.visibility = View.VISIBLE
                    binding.tvTrialPaymentAlertMessage.text = "⚠️ Tu prueba Zippy PRO vence en $daysRemaining días. Solicita tu enlace de pago para no perder la sincronización ni el multibanco."
                    binding.btnTrialRequestPaymentLink.text = "💳 Solicitar Link de Pago PRO"
                    binding.btnManagePlanPro.visibility = View.VISIBLE
                    binding.btnManagePlanPro.text = "✨ Activar Zippy PRO"
                } else {
                    binding.cardTrialPaymentAlert.visibility = View.GONE
                    binding.btnManagePlanPro.visibility = View.VISIBLE
                    binding.btnManagePlanPro.text = "✨ Ver Beneficios o Activar Zippy PRO"
                }
            }
            PlanType.FREE -> {
                if (hasHadPaidPro) {
                    // Cliente cuya suscripción mensual acaba de expirar
                    binding.cardTrialPaymentAlert.visibility = View.VISIBLE
                    binding.tvTrialPaymentAlertMessage.text = "⏰ Tu suscripción mensual Zippy PRO ha finalizado. Tu app sigue operando en modo Básico. Solicita tu enlace para reactivar PRO."
                    binding.btnTrialRequestPaymentLink.text = "💳 Renovar Suscripción Zippy PRO"
                    binding.btnManagePlanPro.visibility = View.VISIBLE
                    binding.btnManagePlanPro.text = "💳 Renovar Suscripción Zippy PRO"
                } else if (hasUsedTrial) {
                    // Usuario cuya prueba gratis terminó
                    binding.cardTrialPaymentAlert.visibility = View.VISIBLE
                    binding.tvTrialPaymentAlertMessage.text = "ℹ️ Tu prueba Zippy PRO ha finalizado. Solicita tu enlace de pago para reactivar todas las funciones avanzadas."
                    binding.btnTrialRequestPaymentLink.text = "💳 Activar Plan Zippy PRO"
                    binding.btnManagePlanPro.visibility = View.VISIBLE
                    binding.btnManagePlanPro.text = "✨ Activar Zippy PRO"
                } else {
                    binding.cardTrialPaymentAlert.visibility = View.GONE
                    binding.btnManagePlanPro.visibility = View.VISIBLE
                    binding.btnManagePlanPro.text = "✨ Ver Beneficios o Activar Zippy PRO"
                }
            }
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
        val planType = PlanCapabilityManager.getPlanType(this)
        val daysRemaining = PlanCapabilityManager.getDaysRemaining(this)
        if (planType == PlanType.PRO_ACTIVE && daysRemaining > 3) {
            Toast.makeText(this, "⭐ Tu plan Zippy PRO está activo ($daysRemaining días restantes). Podrás renovar cuando falten 3 días.", Toast.LENGTH_SHORT).show()
            return
        }

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

    private fun showSubscriptionExpiredPrompt() {
        AlertDialog.Builder(this)
            .setTitle("⏰ Tu período Zippy PRO ha finalizado")
            .setMessage("Tu aplicación continúa funcionando con normalidad en el Plan Básico Gratuito (Pago Móvil, tasa BCV y cuadre de caja).\n\nPara reactivar la detección multibanco simultánea, dictado por voz y respaldo en la nube, renueva tu suscripción.")
            .setPositiveButton("💳 Renovar Suscripción") { _, _ ->
                handleRequestPaymentLinkOption()
            }
            .setNegativeButton("Continuar en Básico", null)
            .show()
    }

    private fun handleRequestPaymentLinkOption() {
        val planType = PlanCapabilityManager.getPlanType(this)
        val daysRemaining = PlanCapabilityManager.getDaysRemaining(this)
        if (planType == PlanType.PRO_ACTIVE && daysRemaining > 3) {
            Toast.makeText(this, "⭐ Tu plan Zippy PRO está activo ($daysRemaining días restantes). Podrás renovar cuando falten 3 días.", Toast.LENGTH_SHORT).show()
            return
        }

        // Abre directamente el contacto de WhatsApp con CIEM Tech para solicitar el enlace oficial de CobrixPay
        openSupportWhatsAppForPro()
    }

    private fun openSupportWhatsAppForPro() {
        val profile = PreferencesHelper.getUserProfile(this)
        val deviceId = PlanCapabilityManager.getDeviceId(this)
        val businessName = profile.merchantName.ifEmpty { "Mi Negocio" }
        val rif = if (profile.idCard.isNotEmpty()) " (${profile.idCard})" else ""
        val text = Uri.encode("Hola Ciem tech solutions C.A, deseo activar o renovar el Plan Zippy PRO (\$2.99) para mi negocio '$businessName'$rif (ID Dispositivo: $deviceId). Por favor envíenme el link de pago oficial de CobrixPay.")
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=${PlanCapabilityManager.SUPPORT_PHONE_CLEAN}&text=$text")
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun showRedeemCodeDialog() {
        val input = EditText(this).apply {
            hint = "Ej: ZIP-XXXX-YYYY"
            setPadding(40, 30, 40, 30)
            isSingleLine = true
        }

        AlertDialog.Builder(this)
            .setTitle("🔑 Activar Zippy PRO con Código")
            .setMessage("Ingresa el código de activación asignado a tu dispositivo por el equipo de soporte de CIEM:")
            .setView(input)
            .setPositiveButton("Activar") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isEmpty()) return@setPositiveButton

                Toast.makeText(this, "Validando código de activación...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val result = ZippySyncManager.redeemActivationCode(this@MainActivity, code)
                    runOnUiThread {
                        if (result.success) {
                            showProActivatedCelebrationDialog(result.remainingDays)
                        } else {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Código Inválido")
                                .setMessage(result.message)
                                .setPositiveButton("Aceptar", null)
                                .setNeutralButton("Solicitar Código") { _, _ ->
                                    openSupportWhatsAppForPro()
                                }
                                .show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun syncLicenseStatus(showToastIfNoChange: Boolean = false) {
        lifecycleScope.launch {
            val wasPro = PlanCapabilityManager.isPro(this@MainActivity)
            val updated = ZippySyncManager.syncWithBackend(this@MainActivity)
            val isProNow = PlanCapabilityManager.isPro(this@MainActivity)
            val daysRemaining = PlanCapabilityManager.getDaysRemaining(this@MainActivity)

            runOnUiThread {
                loadSettings()
                updateLicenseBadge()
                updateShiftUI()

                if (!wasPro && isProNow) {
                    showProActivatedCelebrationDialog(daysRemaining)
                } else if (wasPro && !isProNow && PlanCapabilityManager.hasHadPaidPro(this@MainActivity)) {
                    showSubscriptionExpiredPrompt()
                } else if (showToastIfNoChange) {
                    Toast.makeText(this@MainActivity, "Estado de suscripción verificado (${PlanCapabilityManager.getPlanType(this@MainActivity)})", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showProActivatedCelebrationDialog(daysRemaining: Int) {
        try {
            soundHelper?.playCashRegisterSound()
        } catch (_: Exception) {}

        AlertDialog.Builder(this)
            .setTitle("🎉 ¡Plan Zippy PRO Activado! ⚡")
            .setMessage(
                "¡Tu suscripción ha sido confirmada con éxito!\n\n" +
                "📅 Vigencia: $daysRemaining día${if (daysRemaining > 1) "s" else ""} restante${if (daysRemaining > 1) "s" else ""}.\n\n" +
                "🚀 Ya puedes disfrutar de todas las funciones PRO:\n" +
                "• Detección y confirmación Multibanco simultánea\n" +
                "• Cobro y conversión de Divisas con Tasa BCV\n" +
                "• Soundbox con voz parlante y altavoz Bluetooth\n" +
                "• Carga rápida de ventas y gastos por voz\n" +
                "• Respaldo en la nube y exportación de reportes\n\n" +
                "¡Gracias por confiar en Ciem tech solutions C.A!"
            )
            .setPositiveButton("¡Empezar a Usar PRO!") { dialog, _ ->
                dialog.dismiss()
                loadSettings()
                loadTodayLedger()
                setupCashierBankSelector()
                updateLicenseBadge()
            }
            .setCancelable(false)
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
