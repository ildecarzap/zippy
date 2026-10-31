package com.cobrix.pocket.ui

import android.app.Activity
import java.io.File
import java.io.FileOutputStream
import android.app.DatePickerDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.Manifest
import android.util.Log
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
import com.cobrix.pocket.utils.ZeasySyncManager
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
import com.cobrix.pocket.databinding.ItemConfiguredBankSettingBinding
import android.os.PowerManager
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
    private var activeCashierBankCode = ""
    private var activeCashierBankName = ""

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
    private var selectedBankConfigCode = ""
    private var selectedBankConfigName = ""
    private var targetCropBankCode: String? = null
    private var wasNotificationListenerEnabled: Boolean = false

    private val DUMMY_DEFAULT_VALUES = setOf(
        "Mi Negocio / Taxi", "Mi Negocio", "Taxi",
        "04141234567", "0414-1234567",
        "V-18456789", "V-00000000",
        "Transporte / Taxi"
    )

    // Launcher para recortar la imagen del QR tras seleccionarla de galería con soporte multibanco
    private val cropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val targetBank = targetCropBankCode ?: selectedBankConfigCode
        targetCropBankCode = null
        if (result.resultCode == Activity.RESULT_OK) {
            val croppedUriString = result.data?.getStringExtra(QrCropActivity.EXTRA_RESULT_URI)
            val bankCode = result.data?.getStringExtra(QrCropActivity.EXTRA_BANK_CODE) ?: targetBank
            if (croppedUriString != null) {
                val croppedUri = Uri.parse(croppedUriString)
                val validation = QrValidator.validateForTarget(this, croppedUri, bankCode)
                if (validation is QrValidator.ValidationResult.Rejected) {
                    AlertDialog.Builder(this)
                        .setTitle("⚠️ Código QR Incompatible")
                        .setMessage(validation.reason)
                        .setPositiveButton("Entendido", null)
                        .setNeutralButton("Ver Zeasy PRO") { _: android.content.DialogInterface, _: Int ->
                            showProUpgradeDialog("Billeteras Digitales y Cobros en Cripto (Binance Pay)")
                        }
                        .show()
                    return@registerForActivityResult
                }

                val qrContent = QrValidator.decodeQrContent(this, croppedUri)
                val isCrypto = qrContent != null && QrValidator.isCryptoWalletPayload(qrContent)

                if (bankCode == "BINANCE" || (isCrypto && PlanCapabilityManager.canUseWallets(this) && bankCode != "BINANCE")) {
                    if (bankCode != "BINANCE") {
                        AlertDialog.Builder(this)
                            .setTitle("⚡ QR de Binance Pay Detectado")
                            .setMessage("Detectamos que este código QR corresponde a Binance Pay (cripto) y no a los datos de Pago Móvil del banco seleccionado.\n\n¿Deseas configurarlo directamente como tu billetera Binance Pay?")
                            .setPositiveButton("Sí, guardar en Binance") { _, _ ->
                                saveCroppedQrPermanently(croppedUri, "BINANCE")
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                        return@registerForActivityResult
                    } else {
                        saveCroppedQrPermanently(croppedUri, "BINANCE")
                        return@registerForActivityResult
                    }
                }

                if (isCrypto && !PlanCapabilityManager.canUseWallets(this)) {
                    AlertDialog.Builder(this)
                        .setTitle("⚠️ Código QR Incompatible")
                        .setMessage("El código QR seleccionado pertenece a una Billetera Digital (Binance Pay) y no corresponde a los datos de Pago Móvil de este banco.\n\nPara aceptar cobros en USDT y Binance Pay, activa la opción exclusiva en Zeasy PRO desde Ajustes > Billeteras Digitales.")
                        .setPositiveButton("Entendido", null)
                        .setNeutralButton("Ver Zeasy PRO") { _: android.content.DialogInterface, _: Int ->
                            showProUpgradeDialog("Billeteras Digitales y Cobros en Cripto (Binance Pay)")
                        }
                        .show()
                    return@registerForActivityResult
                }

                saveCroppedQrPermanently(croppedUri, bankCode)
            }
        }
    }

    private fun saveCroppedQrPermanently(sourceUri: Uri, bankCode: String) {
        try {
            val destFile = if (bankCode == "BINANCE") {
                File(filesDir, "wallet_binance.png")
            } else {
                File(filesDir, "bank_qr_${bankCode}.png")
            }
            contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            val permanentUri = Uri.fromFile(destFile).toString()

            if (bankCode == "BINANCE") {
                PreferencesHelper.setBinanceQrUri(this, permanentUri)
                PreferencesHelper.setBinanceEnabled(this, true)
                updateBinanceSettingsCard()
                renderCashierBankCards()
                Toast.makeText(this, "✅ QR de Binance Pay guardado con éxito", Toast.LENGTH_SHORT).show()
                return
            }

            PreferencesHelper.setBankQrUri(this, bankCode, permanentUri)
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
            updateSelectedBankConfigCard()
            Toast.makeText(this, "✅ QR para $bankDisplayName guardado con éxito", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error al guardar imagen de QR: ${e.message}", Toast.LENGTH_SHORT).show()
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
        requireSecurityAuth("Cargar Código QR de $bankName") {
            if (PreferencesHelper.canUploadQrForBank(this, bankCode)) {
                launchCropForBank(bankCode)
            } else {
                val banksWithQr = PreferencesHelper.getBanksWithQr(this)
                val currentBankCode = banksWithQr.firstOrNull() ?: ""
                val currentBankName = BankCatalog.findByCode(currentBankCode)?.name ?: currentBankCode

                AlertDialog.Builder(this)
                    .setTitle("⚠️ Reemplazar Banco y QR")
                    .setMessage("Actualmente tienes cargado el código QR de $currentBankName.\n\nEn el Plan Básico puedes tener 1 solo banco activo con QR.\n\n¿Deseas reemplazar $currentBankName por $bankName y cargar su nuevo código QR?\n\n(Para tener múltiples bancos y QRs simultáneos, activa Zeasy PRO).")
                    .setPositiveButton("Reemplazar y Cargar QR") { _, _ ->
                        PreferencesHelper.replaceBasicPlanBank(this, bankCode, bankName)
                        activeCashierBankCode = bankCode
                        activeCashierBankName = bankName
                        syncSelectedBank(bankCode, bankName)
                        renderCashierBankCards()
                        launchCropForBank(bankCode)
                    }
                    .setNegativeButton("Cancelar", null)
                    .setNeutralButton("Ver Zeasy PRO") { _, _ ->
                        showProUpgradeDialog("Multibanco Simultáneo (Varios bancos a la vez)")
                    }
                    .show()
            }
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
                val amountUsd = intent.getDoubleExtra(PocketNotificationListenerService.EXTRA_AMOUNT_USD, 0.0)
                val isCrypto = intent.getBooleanExtra(PocketNotificationListenerService.EXTRA_IS_CRYPTO, false)
                val ref = intent.getStringExtra(PocketNotificationListenerService.EXTRA_REF) ?: ""
                val bank = intent.getStringExtra(PocketNotificationListenerService.EXTRA_BANK) ?: ""
                val phone = intent.getStringExtra(PocketNotificationListenerService.EXTRA_PHONE) ?: ""

                runOnUiThread {
                    loadTodayLedger()
                    PreferencesHelper.getAndClearPendingCelebration(this@MainActivity)
                    if (PreferencesHelper.isCelebrationScreenEnabled(this@MainActivity) && (amount > 0.0 || amountUsd > 0.0)) {
                        showPaymentCelebrationDialog(amount, ref, bank, phone, amountUsd, isCrypto)
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
        PreferencesHelper.sanitizeBasicPlanBank(this)
        val previouslyEnabled = wasNotificationListenerEnabled
        checkNotificationPermission()

        if (!previouslyEnabled && wasNotificationListenerEnabled) {
            // Acaba de activar el permiso de escucha, sugerir optimización de batería si aún está con restricciones
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (powerManager?.isIgnoringBatteryOptimizations(packageName) != true) {
                    binding.root.postDelayed({
                        requestBatteryOptimizationManual()
                    }, 600)
                }
            }
        }

        if (PreferencesHelper.isFirstLaunch(this)) {
            PreferencesHelper.setFirstLaunch(this, false)
            showWelcomeOnboardingDialog()
        }
        updateShiftUI()
        loadTodayLedger()
        setupCashierBankSelector()
        renderCashierBankCards()
        updateCashierHeaderSummary()
        updateLicenseBadge()

        // Sincronización reactiva inmediata al volver a la app (detecta pago de CobrixPay o activación de soporte)
        syncLicenseStatus(showToastIfNoChange = false)

        // Sincronización periódica automática de Tasa BCV Oficial
        autoSyncBcvRateDaily()

        // Verificar si llegó un pago mientras la pantalla estaba apagada o en otra app
        if (PreferencesHelper.isCelebrationScreenEnabled(this)) {
            val pending = PreferencesHelper.getAndClearPendingCelebration(this)
            if (pending != null && (pending.amount > 0.0 || pending.amountUsd > 0.0)) {
                showPaymentCelebrationDialog(pending.amount, pending.ref, pending.bank, pending.phone, pending.amountUsd, pending.isCrypto)
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
        saveCurrentSettingsDraft()
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

    /**
     * Exige confirmación biométrica (huella) o PIN del teléfono antes de modificar
     * datos bancarios sensibles, cargar/cambiar códigos QR o alterar cuentas de cobro.
     */
    private fun requireSecurityAuth(actionTitle: String, onSuccess: () -> Unit) {
        if (BiometricHelper.isBiometricAvailable(this)) {
            BiometricHelper.showBiometricPrompt(
                activity = this,
                title = actionTitle,
                subtitle = "Confirmación de Seguridad",
                description = "Coloca tu huella digital o ingresa el PIN para confirmar esta acción",
                onSuccess = onSuccess,
                onError = { _ ->
                    Toast.makeText(this, "🔒 Acción cancelada: Se requiere huella o PIN", Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            onSuccess()
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

    // --- GESTIÓN CENTRALIZADA Y SEGURA DE GUARDADO DE AJUSTES ---
    private fun saveCurrentSettingsFromUi(
        requireBiometricIfConfigured: Boolean,
        isExplicitClick: Boolean = false,
        onSaved: (() -> Unit)? = null
    ): Boolean {
        val current = PreferencesHelper.getUserProfile(this)

        val merchantName = binding.etSettingsMerchantName.text.toString().trim()
        val phone = binding.etSettingsPhone.text.toString().trim()
        val idCard = binding.etSettingsId.text.toString().trim()
        val email = binding.etSettingsEmail.text.toString().trim()
        val category = binding.actvSettingsBusinessCategory.text.toString().trim().ifEmpty { "General" }

        val finalBankCode = current.bankCode
        val finalBankName = current.bankName

        // Tasa BCV: aceptar tanto punto como coma, y si está vacío o inválido, conservar tasa actual
        val bcvRateInput = binding.etSettingsBcvRate.text.toString().replace(',', '.').trim()
        val parsedBcvRate = bcvRateInput.toDoubleOrNull() ?: current.bcvRate

        // Modo Soundbox con bloqueo de voz para Plan Básico
        val selectedSoundMode = when (binding.toggleGroupSoundbox.checkedButtonId) {
            R.id.btnSoundChime -> "CHIME"
            R.id.btnSoundMuted -> "MUTED"
            else -> "VOICE"
        }

        val isVoicePro = PlanCapabilityManager.canUseVoiceSoundbox(this)
        val soundboxMode = if (selectedSoundMode == "VOICE" && !isVoicePro) {
            binding.toggleGroupSoundbox.check(R.id.btnSoundChime)
            if (isExplicitClick) showProUpgradeDialog("Soundbox Parlante con Síntesis de Voz (Plan PRO)")
            "CHIME"
        } else {
            selectedSoundMode
        }

        val currentBinancePayId = PreferencesHelper.getBinancePayId(this)
        val newBinancePayId = binding.etBinancePayId.text.toString().trim()

        val hasChanges = (merchantName != current.merchantName) ||
            (category != current.businessCategory) ||
            (phone != current.phoneNumber) ||
            (idCard != current.idCard) ||
            (email != current.email) ||
            (parsedBcvRate != current.bcvRate) ||
            (soundboxMode != current.soundboxMode) ||
            (binding.switchBiometric.isChecked != current.biometricSecurityEnabled) ||
            (PlanCapabilityManager.canUseWallets(this) && newBinancePayId != currentBinancePayId)

        if (!hasChanges) {
            if (isExplicitClick) {
                Toast.makeText(this, "No se detectaron cambios en la configuración", Toast.LENGTH_SHORT).show()
            }
            onSaved?.invoke()
            return false
        }

        // Validación estricta: NO permitir guardar valores por defecto o vacíos
        if (phone.isEmpty() || phone in DUMMY_DEFAULT_VALUES) {
            if (isExplicitClick) {
                Toast.makeText(this, "⚠️ Por favor ingresa tu número de teléfono de Pago Móvil", Toast.LENGTH_LONG).show()
                binding.etSettingsPhone.requestFocus()
            }
            return false
        }

        if (idCard.isEmpty() || idCard in DUMMY_DEFAULT_VALUES) {
            if (isExplicitClick) {
                Toast.makeText(this, "⚠️ Por favor ingresa tu número de Cédula o RIF", Toast.LENGTH_LONG).show()
                binding.etSettingsId.requestFocus()
            }
            return false
        }

        val saveAction = {
            val updated = current.copy(
                merchantName = merchantName,
                businessCategory = category,
                bankCode = finalBankCode,
                bankName = finalBankName,
                phoneNumber = phone,
                idCard = idCard,
                email = email,
                bcvRate = parsedBcvRate,
                soundboxMode = soundboxMode,
                voiceAnnouncementEnabled = soundboxMode == "VOICE",
                soundNotificationEnabled = soundboxMode != "MUTED",
                biometricSecurityEnabled = binding.switchBiometric.isChecked
            )

            if (finalBankCode.isNotEmpty()) {
                if (!PlanCapabilityManager.canUseMultibank(this)) {
                    PreferencesHelper.replaceBasicPlanBank(this, finalBankCode, finalBankName)
                } else {
                    PreferencesHelper.setPrimaryBank(this, finalBankCode, finalBankName)
                }
            }
            PreferencesHelper.saveUserProfile(this, updated)
            PreferencesHelper.clearSettingsDraft(this)

            if (PlanCapabilityManager.canUseWallets(this)) {
                PreferencesHelper.setBinancePayId(this, newBinancePayId)
            }

            activeCashierBankCode = finalBankCode
            activeCashierBankName = finalBankName
            setupCashierBankSelector()
            renderCashierBankCards()
            updateCashierHeaderSummary()
            updateSelectedBankConfigCard()
            updateDisplayAmount()
            loadSettings(forceReload = true)

            lifecycleScope.launch {
                ZeasySyncManager.syncWithBackend(this@MainActivity)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "✅ Configuración guardada correctamente", Toast.LENGTH_SHORT).show()
                    updateLicenseBadge()
                    updateBinanceSettingsCard()
                    renderCashierBankCards()
                    onSaved?.invoke()
                }
            }
        }

        val requiresAuth = requireBiometricIfConfigured && (current.biometricSecurityEnabled || binding.switchBiometric.isChecked)
        if (requiresAuth) {
            requireSecurityAuth("Guardar Cambios de Configuración") {
                saveAction()
            }
        } else {
            saveAction()
        }
        return true
    }

    // --- NAVEGACIÓN ENTRE TABS CON AUTO-GUARDADO REACTIVO ---
    private fun autoPersistSettingsIfChanged() {
        if (binding.viewSettings.visibility == View.VISIBLE) {
            val current = PreferencesHelper.getUserProfile(this)
            val phone = binding.etSettingsPhone.text.toString().trim()
            val idCard = binding.etSettingsId.text.toString().trim()
            if (phone.isNotEmpty() && phone !in DUMMY_DEFAULT_VALUES && idCard.isNotEmpty() && idCard !in DUMMY_DEFAULT_VALUES) {
                // Si la biometría NO está activa, guardamos en silencio para que nunca pierda sus datos
                if (!current.biometricSecurityEnabled && !binding.switchBiometric.isChecked) {
                    saveCurrentSettingsFromUi(requireBiometricIfConfigured = false, isExplicitClick = false)
                } else {
                    saveCurrentSettingsDraft()
                }
            } else {
                saveCurrentSettingsDraft()
            }
        }
    }

    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_cashier -> {
                    autoPersistSettingsIfChanged()
                    binding.viewCashier.visibility = View.VISIBLE
                    binding.viewLedger.visibility = View.GONE
                    binding.viewSettings.visibility = View.GONE
                    setupCashierBankSelector()
                    renderCashierBankCards()
                    updateCashierHeaderSummary()
                    true
                }
                R.id.nav_ledger -> {
                    autoPersistSettingsIfChanged()
                    binding.viewCashier.visibility = View.GONE
                    binding.viewLedger.visibility = View.VISIBLE
                    binding.viewSettings.visibility = View.GONE
                    loadTodayLedger()
                    true
                }
                R.id.nav_settings -> {
                    saveCurrentSettingsDraft()
                    binding.viewCashier.visibility = View.GONE
                    binding.viewLedger.visibility = View.GONE
                    binding.viewSettings.visibility = View.VISIBLE
                    loadSettings(forceReload = false)
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

            if (activeCashierBankCode.isEmpty()) {
                Toast.makeText(this, "Por favor configura tu banco en Ajustes primero", Toast.LENGTH_SHORT).show()
                binding.bottomNavigation.selectedItemId = R.id.nav_settings
                return@setOnClickListener
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
            if (activeCashierBankCode.isEmpty()) {
                Toast.makeText(this, "Por favor configura tu banco en Ajustes primero", Toast.LENGTH_SHORT).show()
                binding.bottomNavigation.selectedItemId = R.id.nav_settings
                return@setOnClickListener
            }

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
        if (activeCashierBankName.isNotEmpty()) {
            binding.tvCashierWhatsAppSubtitle.text = "Toca para digitar monto y enviar datos de $activeCashierBankName con QR"
        } else {
            binding.tvCashierWhatsAppSubtitle.text = "Toca para configurar tu banco y datos de cobro"
        }
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
        val codesToShow = if (configuredCodes.isEmpty()) {
            if (profile.bankCode.isNotEmpty()) listOf(profile.bankCode) else emptyList()
        } else configuredCodes

        if (!codesToShow.contains(activeCashierBankCode)) {
            activeCashierBankCode = codesToShow.firstOrNull() ?: profile.bankCode
            activeCashierBankName = if (activeCashierBankCode.isNotEmpty()) {
                BankCatalog.findByCode(activeCashierBankCode)?.name ?: activeCashierBankCode
            } else ""
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
        if (activeCashierBankCode.isEmpty()) {
            binding.tvCashierBankQrIndicator.text = "⚠️ Sin banco (Configurar en Ajustes)"
            binding.tvCashierBankQrIndicator.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            return
        }
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

        binding.btnRescanNotifications.setOnClickListener {
            if (!PocketNotificationListenerService.isServiceRunning()) {
                promptActivateNotificationListener()
                return@setOnClickListener
            }
            val count = PocketNotificationListenerService.rescanActiveNotifications(playSound = true)
            if (count > 0) {
                Toast.makeText(this, "✅ Se sincronizaron $count cobro(s) de la barra de notificaciones", Toast.LENGTH_LONG).show()
                loadTodayLedger()
            } else {
                Toast.makeText(this, "ℹ️ No hay nuevos cobros pendientes en la barra", Toast.LENGTH_SHORT).show()
            }
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

        // Optimización de rendimiento: Si la pestaña de Historial no está visible, no re-inflar toda la lista
        if (binding.viewLedger.visibility != View.VISIBLE) {
            return
        }

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
                val isCrypto = tx.bankCode.equals("BINANCE", ignoreCase = true) ||
                               tx.category.contains("Binance", ignoreCase = true) ||
                               tx.category.contains("Cripto", ignoreCase = true)

                if (isIncome) {
                    itemBinding.ivTxIcon.setImageResource(R.drawable.ic_cashier)
                    itemBinding.ivTxIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent_green))
                    itemBinding.ivTxIcon.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_green_bg)
                    val catDisplay = TransactionCategories.getCategoryDisplayName(tx.category)
                    itemBinding.tvTxTitle.text = when {
                        isCrypto -> "Binance Pay (Cripto)"
                        tx.isManual -> "Ingreso: $catDisplay"
                        else -> "Pago Móvil ${tx.bankName}"
                    }
                    itemBinding.tvTxAmount.text = if (isCrypto && tx.amountUsd > 0.0) {
                        String.format(Locale.US, "+ $%.2f USDT", tx.amountUsd)
                    } else {
                        String.format(Locale.US, "+ Bs. %.2f", tx.amount)
                    }
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
                val senderText = if (tx.senderName.isNotEmpty()) " • Remitente: ${tx.senderName}" else ""
                val noteText = if (tx.note.isNotEmpty()) " • ${tx.note}" else ""
                val manualTag = if (tx.isManual) " (Manual)" else ""
                itemBinding.tvTxSubtitle.text = "${timeFormat.format(Date(tx.timestamp))}$manualTag$refText$senderText$noteText"

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

        val isPro = PlanCapabilityManager.canExportPdf(this)
        if (!isPro) {
            dialogBinding.btnShareOptionPdf.visibility = View.GONE
            dialogBinding.btnShareOptionCsv.visibility = View.GONE
            dialogBinding.btnShareOptionsProLocked.visibility = View.VISIBLE
            dialogBinding.btnShareOptionsProLocked.setOnClickListener {
                showProUpgradeDialog("Exportación de Reportes Formales en PDF y Excel")
            }
        } else {
            dialogBinding.btnShareOptionPdf.visibility = View.VISIBLE
            dialogBinding.btnShareOptionCsv.visibility = View.VISIBLE
            dialogBinding.btnShareOptionsProLocked.visibility = View.GONE

            dialogBinding.btnShareOptionPdf.setOnClickListener {
                dialog.dismiss()
                val pdfFile = ReportHelper.generatePdfReport(this, profile, currentFilteredTransactions, filterPeriodLabel)
                if (pdfFile != null) {
                    ReportHelper.sharePdf(this, pdfFile)
                } else {
                    Toast.makeText(this, "No se pudo generar el archivo PDF", Toast.LENGTH_SHORT).show()
                }
            }

            dialogBinding.btnShareOptionCsv.setOnClickListener {
                dialog.dismiss()
                val csvFile = ReportHelper.generateCsvReport(this, profile, currentFilteredTransactions, filterPeriodLabel)
                if (csvFile != null) {
                    ReportHelper.shareCsv(this, csvFile)
                } else {
                    Toast.makeText(this, "No se pudo generar el archivo Excel", Toast.LENGTH_SHORT).show()
                }
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
     * Sincroniza de forma unificada el banco seleccionado en toda la pestaña de Ajustes.
     */
    private fun syncSelectedBank(bankCode: String, bankName: String) {
        val entry = BankCatalog.findByCode(bankCode) ?: BankCatalog.findByNameOrCode(bankName)
        selectedBankConfigCode = entry?.code ?: bankCode
        selectedBankConfigName = entry?.name ?: bankName
        renderConfiguredBanksList()
    }

    // --- TAB AJUSTES: GESTOR UNIFICADO DE BANCOS Y CÓDIGOS QR ---
    private fun setupSettingsBankManager() {
        binding.btnAddAnotherBank.setOnClickListener {
            val profile = PreferencesHelper.getUserProfile(this)
            val isMultibank = PlanCapabilityManager.canUseMultibank(this)
            val configuredCodes = if (isMultibank) {
                PreferencesHelper.getConfiguredBankCodes(this)
            } else {
                if (profile.bankCode.isNotEmpty()) listOf(profile.bankCode) else emptyList()
            }

            showSelectBankDialog { selectedBank ->
                    if (configuredCodes.contains(selectedBank.code)) {
                        Toast.makeText(this, "${selectedBank.name} ya está en tu lista de bancos", Toast.LENGTH_SHORT).show()
                        return@showSelectBankDialog
                    }

                    if (!isMultibank) {
                        if (profile.bankCode.isEmpty()) {
                            // Primer banco en plan básico: pedir huella o PIN al cargar QR
                            requireSecurityAuth("Cargar Código QR de ${selectedBank.name}") {
                                PreferencesHelper.setPrimaryBank(this, selectedBank.code, selectedBank.name)
                                activeCashierBankCode = selectedBank.code
                                activeCashierBankName = selectedBank.name
                                syncSelectedBank(selectedBank.code, selectedBank.name)
                                renderCashierBankCards()
                                setupCashierBankSelector()
                                updateCashierHeaderSummary()
                                launchCropForBank(selectedBank.code)
                            }
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle("Reemplazar Banco")
                                .setMessage("Actualmente tienes configurado ${profile.bankName}.\n\nEn el Plan Básico puedes tener 1 banco activo a la vez.\n\n¿Deseas reemplazarlo por ${selectedBank.name} y cargar su código QR, o activar Zeasy PRO?")
                                .setPositiveButton("Reemplazar y Cargar QR") { _, _ ->
                                    requireSecurityAuth("Cargar Código QR de ${selectedBank.name}") {
                                        PreferencesHelper.replaceBasicPlanBank(this, selectedBank.code, selectedBank.name)
                                        activeCashierBankCode = selectedBank.code
                                        activeCashierBankName = selectedBank.name
                                        syncSelectedBank(selectedBank.code, selectedBank.name)
                                        renderCashierBankCards()
                                        setupCashierBankSelector()
                                        updateCashierHeaderSummary()
                                        launchCropForBank(selectedBank.code)
                                    }
                                }
                                .setNegativeButton("Cancelar", null)
                                .setNeutralButton("Ver Zeasy PRO") { _, _ ->
                                    showProUpgradeDialog("Multibanco Simultáneo (Varios bancos a la vez)")
                                }
                                .show()
                        }
                    } else {
                        PreferencesHelper.addConfiguredBank(this, selectedBank.code, selectedBank.name)
                        syncSelectedBank(selectedBank.code, selectedBank.name)
                        setupCashierBankSelector()
                        renderCashierBankCards()
                        updateCashierHeaderSummary()

                        AlertDialog.Builder(this)
                            .setTitle("✅ Banco Agregado")
                            .setMessage("Has agregado ${selectedBank.name} a tu lista.\n\n¿Deseas cargar el código QR de este banco ahora?")
                            .setPositiveButton("Sí, Cargar QR") { _, _ ->
                                requireSecurityAuth("Cargar Código QR de ${selectedBank.name}") {
                                    launchCropForBank(selectedBank.code)
                                }
                            }
                            .setNegativeButton("Más tarde", null)
                            .show()
                    }
                }
            }

        binding.switchDetectSms.setOnCheckedChangeListener { _, isChecked ->
            PreferencesHelper.setSmsDetectionEnabled(this, isChecked)
            if (isChecked) {
                Toast.makeText(this, "🔔 Escuchando SMS únicamente de tus bancos activos configurados", Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchCelebrationScreen.setOnCheckedChangeListener { _, isChecked ->
            PreferencesHelper.setCelebrationScreenEnabled(this, isChecked)
        }

        renderConfiguredBanksList()
    }

    private fun renderConfiguredBanksList() {
        binding.llConfiguredBanksContainer.removeAllViews()
        val profile = PreferencesHelper.getUserProfile(this)
        val isMultibank = PlanCapabilityManager.canUseMultibank(this)

        val bankCodes = if (isMultibank) {
            val configured = PreferencesHelper.getConfiguredBankCodes(this)
            if (configured.isNotEmpty()) configured else (if (profile.bankCode.isNotEmpty()) listOf(profile.bankCode) else emptyList())
        } else {
            if (profile.bankCode.isNotEmpty()) listOf(profile.bankCode) else emptyList()
        }

        // Dinámico: si no hay bancos configurados muestra "+ Agregar Banco y código QR", de lo contrario "+ Agregar otro banco..."
        if (bankCodes.isEmpty()) {
            binding.btnAddAnotherBank.text = "➕ Agregar Banco y código QR"
        } else {
            binding.btnAddAnotherBank.text = "➕ Agregar otro banco y código QR"
        }

        bankCodes.forEach { code ->
            val itemBinding = ItemConfiguredBankSettingBinding.inflate(layoutInflater, binding.llConfiguredBanksContainer, false)
            val bank = BankCatalog.findByCode(code)
            val bankName = bank?.name ?: code
            val brandColor = bank?.getBrandColor() ?: Color.parseColor("#00B2FE")
            val shortName = bank?.getShortName() ?: code.takeLast(3)

            itemBinding.tvBankItemBadge.text = shortName
            itemBinding.tvBankItemBadge.backgroundTintList = ColorStateList.valueOf(brandColor)
            itemBinding.tvBankItemName.text = bankName
            itemBinding.tvBankItemCode.text = "Código Sudeban: $code"

            val isPrimary = (code == profile.bankCode)
            if (isPrimary) {
                itemBinding.tvBankItemPrimaryBadge.visibility = View.VISIBLE
                itemBinding.btnBankItemMakePrimary.visibility = View.GONE
            } else {
                itemBinding.tvBankItemPrimaryBadge.visibility = View.GONE
                itemBinding.btnBankItemMakePrimary.visibility = View.VISIBLE
                itemBinding.btnBankItemMakePrimary.setOnClickListener {
                    PreferencesHelper.setPrimaryBank(this, code, bankName)
                    activeCashierBankCode = code
                    activeCashierBankName = bankName
                    renderConfiguredBanksList()
                    setupCashierBankSelector()
                    renderCashierBankCards()
                    updateCashierHeaderSummary()
                    Toast.makeText(this, "⭐ $bankName establecido como banco principal", Toast.LENGTH_SHORT).show()
                }
            }

            val qrUri = PreferencesHelper.getBankQrUri(this, code)
            if (!qrUri.isNullOrEmpty()) {
                itemBinding.tvBankItemQrStatus.text = "✅ Código QR Listo para Cobros"
                itemBinding.tvBankItemQrStatus.setTextColor(ContextCompat.getColor(this, R.color.income_green))
                itemBinding.btnBankItemUploadQr.text = "✂️ Cambiar QR"
                itemBinding.btnBankItemViewQr.visibility = View.VISIBLE
                itemBinding.btnBankItemDeleteQr.visibility = View.VISIBLE
            } else {
                itemBinding.tvBankItemQrStatus.text = "⚠️ Sin código QR cargado aún"
                itemBinding.tvBankItemQrStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                itemBinding.btnBankItemUploadQr.text = "✂️ Cargar Código QR"
                itemBinding.btnBankItemViewQr.visibility = View.GONE
                itemBinding.btnBankItemDeleteQr.visibility = View.GONE
            }

            itemBinding.btnBankItemUploadQr.setOnClickListener {
                handleUploadQrClick(code, bankName)
            }

            itemBinding.btnBankItemViewQr.setOnClickListener {
                val intent = Intent(this, FullScreenQrActivity::class.java).apply {
                    putExtra(FullScreenQrActivity.EXTRA_AMOUNT_VES, 0.0)
                    putExtra(FullScreenQrActivity.EXTRA_AMOUNT_USD, 0.0)
                    putExtra(FullScreenQrActivity.EXTRA_BANK_CODE, code)
                    putExtra(FullScreenQrActivity.EXTRA_BANK_NAME, bankName)
                }
                startActivity(intent)
            }

            itemBinding.btnBankItemDeleteQr.setOnClickListener {
                requireSecurityAuth("Eliminar Código QR de $bankName") {
                    AlertDialog.Builder(this)
                        .setTitle("Eliminar Código QR")
                        .setMessage("¿Deseas quitar el código QR de $bankName?")
                        .setPositiveButton("Eliminar") { _, _ ->
                            PreferencesHelper.clearBankQr(this, code)
                            renderConfiguredBanksList()
                            setupCashierBankSelector()
                            renderCashierBankCards()
                            Toast.makeText(this, "Código QR eliminado", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }

            // Permitir quitar banco
            itemBinding.btnBankItemDeleteBank.visibility = View.VISIBLE
            itemBinding.btnBankItemDeleteBank.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Quitar Banco")
                    .setMessage("¿Deseas quitar $bankName de tu lista de cobro?")
                    .setPositiveButton("Quitar") { _, _ ->
                        PreferencesHelper.removeConfiguredBank(this, code)
                        if (activeCashierBankCode == code) {
                            val nextProfile = PreferencesHelper.getUserProfile(this)
                            activeCashierBankCode = nextProfile.bankCode
                            activeCashierBankName = nextProfile.bankName
                        }
                        renderConfiguredBanksList()
                        setupCashierBankSelector()
                        renderCashierBankCards()
                        updateCashierHeaderSummary()
                        Toast.makeText(this, "Banco $bankName removido", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }

            binding.llConfiguredBanksContainer.addView(itemBinding.root)
        }
    }

    private fun updateSelectedBankConfigCard() {
        renderConfiguredBanksList()
    }

    /**
     * Configuración de la sección de Billeteras Digitales y Cripto (Binance Pay).
     * En Plan Básico: Muestra botón "+ Agregar Binance Pay (PRO)" que evita cargar QR y solicita PRO.
     * En Plan PRO: Muestra botón "+ Agregar Binance Pay" o la tarjeta configurada si ya se agregó.
     */
    private fun setupSettingsWallets() {
        val isPro = PlanCapabilityManager.canUseWallets(this)

        if (!isPro) {
            // Usuario en Plan Básico: botón que solicita PRO
            binding.btnAddBinancePay.visibility = View.VISIBLE
            binding.btnAddBinancePay.text = "➕ Agregar Binance Pay (PRO)"
            binding.layoutWalletsProContent.visibility = View.GONE
            binding.tvSettingsWalletsProBadge.text = "🔒 PRO"

            val proUpgradeClick = View.OnClickListener {
                showProUpgradeDialog("Billetera Binance Pay en Cripto (USDT)")
            }
            binding.btnAddBinancePay.setOnClickListener(proUpgradeClick)
            binding.cardSettingsWalletsSection.setOnClickListener(proUpgradeClick)
        } else {
            // Usuario con PRO o prueba activa
            binding.tvSettingsWalletsProBadge.text = "⭐ PRO"
            binding.cardSettingsWalletsSection.setOnClickListener(null)

            binding.btnAddBinancePay.setOnClickListener {
                requireSecurityAuth("Cargar QR de Binance Pay") {
                    binding.btnAddBinancePay.visibility = View.GONE
                    binding.layoutWalletsProContent.visibility = View.VISIBLE
                    launchCropForBank("BINANCE")
                }
            }

            binding.switchBinanceEnabled.setOnCheckedChangeListener { _, isChecked ->
                PreferencesHelper.setBinanceEnabled(this, isChecked)
                renderCashierBankCards()
                val statusText = if (isChecked) "activado en caja" else "desactivado en caja"
                Toast.makeText(this, "Binance Pay $statusText", Toast.LENGTH_SHORT).show()
            }

            binding.btnUploadBinanceQr.setOnClickListener {
                requireSecurityAuth("Cargar QR de Binance Pay") {
                    launchCropForBank("BINANCE")
                }
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
                requireSecurityAuth("Eliminar QR de Binance Pay") {
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

            binding.btnRemoveBinanceWallet.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Quitar Binance Pay")
                    .setMessage("¿Deseas quitar Binance Pay de tus métodos de cobro?")
                    .setPositiveButton("Quitar") { _, _ ->
                        PreferencesHelper.removeBinancePay(this)
                        updateBinanceSettingsCard()
                        renderCashierBankCards()
                        Toast.makeText(this, "Binance Pay removido", Toast.LENGTH_SHORT).show()
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
            binding.btnAddBinancePay.visibility = View.VISIBLE
            binding.btnAddBinancePay.text = "➕ Agregar Binance Pay (PRO)"
            binding.layoutWalletsProContent.visibility = View.GONE
            binding.tvSettingsWalletsProBadge.text = "🔒 PRO"
            return
        }

        if (PlanCapabilityManager.PLAY_STORE_REVIEW_MODE) {
            binding.tvSettingsWalletsProBadge.visibility = View.GONE
        } else {
            binding.tvSettingsWalletsProBadge.visibility = View.VISIBLE
            binding.tvSettingsWalletsProBadge.text = "⭐ PRO"
        }
        val hasConfigured = PreferencesHelper.hasConfiguredBinance(this)

        if (hasConfigured) {
            binding.btnAddBinancePay.visibility = View.GONE
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
                binding.btnUploadBinanceQr.text = "✂️ Cambiar QR"
                binding.btnViewBinanceQr.isEnabled = true
                binding.btnDeleteBinanceQr.visibility = View.VISIBLE
            } else {
                binding.tvBinanceQrStatus.text = "⚠️ Sin código QR cargado"
                binding.tvBinanceQrStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                binding.btnUploadBinanceQr.text = "✂️ Subir QR"
                binding.btnViewBinanceQr.isEnabled = false
                binding.btnDeleteBinanceQr.visibility = View.GONE
            }
        } else {
            binding.btnAddBinancePay.visibility = View.VISIBLE
            binding.btnAddBinancePay.text = "➕ Agregar Binance Pay"
            binding.layoutWalletsProContent.visibility = View.GONE
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

        // Limpiar valores por defecto / dummy al tocar las casillas en el registro
        setupClearDefaultOnTouch(binding.etSettingsMerchantName, DUMMY_DEFAULT_VALUES)
        setupClearDefaultOnTouch(binding.etSettingsPhone, DUMMY_DEFAULT_VALUES)
        setupClearDefaultOnTouch(binding.etSettingsId, DUMMY_DEFAULT_VALUES)
        setupClearDefaultOnTouch(binding.actvSettingsBusinessCategory, DUMMY_DEFAULT_VALUES)

        // Guardado reactivo de borrador para evitar pérdida de datos si el usuario sale a dar permisos
        val draftWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) { saveCurrentSettingsDraft() }
        }
        binding.etSettingsMerchantName.addTextChangedListener(draftWatcher)
        binding.actvSettingsBusinessCategory.addTextChangedListener(draftWatcher)
        binding.etSettingsPhone.addTextChangedListener(draftWatcher)
        binding.etSettingsId.addTextChangedListener(draftWatcher)
        binding.etSettingsEmail.addTextChangedListener(draftWatcher)
        binding.etSettingsBcvRate.addTextChangedListener(draftWatcher)

        // Botón Guía Web Oficial con Fotos y Ejemplos
        binding.btnOpenWebGuide.setOnClickListener {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("${PlanCapabilityManager.OFFICIAL_WEBSITE}/guia"))
                startActivity(browserIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "No se pudo abrir el navegador", Toast.LENGTH_SHORT).show()
            }
        }

        // Botón Asistencia Rápida por WhatsApp
        binding.btnQuickSupport.setOnClickListener {
            val text = Uri.encode("Hola, necesito ayuda para configurar mi Zeasy")
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=${PlanCapabilityManager.SUPPORT_PHONE_CLEAN}&text=$text")
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e: Exception) {
                Toast.makeText(this, PlanCapabilityManager.SUPPORT_PHONE_CLEAN, Toast.LENGTH_SHORT).show()
            }
        }

        // Gestor Multibanco y Códigos QR
        setupSettingsBankManager()
        setupSettingsWallets()

        // Permisos de Notificaciones Bancarias y Optimización de Batería
        // Permisos de Notificaciones Bancarias y Optimización de Batería con diálogo de advertencia
        binding.btnSettingsFixNotification.setOnClickListener {
            val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            val isListenerEnabled = enabledListeners != null && enabledListeners.contains(packageName)
            if (isListenerEnabled) {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ ¿Desactivar Detección de Pagos?")
                    .setMessage("ADVERTENCIA: Si desactivas este permiso, Zeasy NO podrá detectar ni cantar tus pagos automáticamente en segundo plano.\n\nLa aplicación no funcionará correctamente para alertarte cuando tus clientes te paguen.\n\n¿Deseas ir a los ajustes del sistema para desactivarlo?")
                    .setPositiveButton("👉 Ir a Ajustes") { _, _ ->
                        try {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        } catch (e: Exception) {
                            Toast.makeText(this, "Abre Ajustes > Notificaciones > Acceso a notificaciones", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            } else {
                promptActivateNotificationListener()
            }
        }
        binding.btnSettingsFixBattery.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                val isIgnoringBattery = powerManager?.isIgnoringBatteryOptimizations(packageName) == true
                if (isIgnoringBattery) {
                    AlertDialog.Builder(this)
                        .setTitle("⚠️ ¿Modificar Segundo Plano?")
                        .setMessage("ADVERTENCIA: Si reactivas el ahorro de batería o restringes a Zeasy en segundo plano, Android suspenderá la aplicación y no cantará tus pagos a tiempo cuando la pantalla esté bloqueada o la app esté cerrada.\n\n¿Deseas abrir la configuración de batería para modificar los permisos?")
                        .setPositiveButton("👉 Ir a Configuración") { _, _ ->
                            try {
                                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            } catch (e: Exception) {
                                try {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.parse("package:$packageName")
                                    }
                                    startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(this, "Abre Ajustes > Batería", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                } else {
                    requestBatteryOptimizationManual()
                }
            }
        }

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

        // Selección de modo Soundbox: Permitir probar la voz incluso en plan básico para saber cómo suena
        binding.toggleGroupSoundbox.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && checkedId == R.id.btnSoundVoice && !PlanCapabilityManager.canUseVoiceSoundbox(this)) {
                Toast.makeText(this, "🗣️ Modo Voz en prueba. Para activarlo en todos tus cobros, se requiere Plan PRO.", Toast.LENGTH_SHORT).show()
            }
        }

        // Probar Soundbox (dejar sonar la voz para que el usuario conozca la función)
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

        // Guardar Ajustes (con soporte para botón superior e inferior, y validación flexible)
        val handleSaveClick = {
            saveCurrentSettingsFromUi(requireBiometricIfConfigured = true, isExplicitClick = true)
        }
        binding.btnSaveSettings.setOnClickListener { handleSaveClick() }
        binding.btnQuickSaveSettingsTop.setOnClickListener { handleSaveClick() }

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

        // Hipervínculo Web Oficial
        binding.tvAboutWebsite.setOnClickListener {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(PlanCapabilityManager.OFFICIAL_WEBSITE))
                startActivity(browserIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "No se pudo abrir el navegador", Toast.LENGTH_SHORT).show()
            }
        }

        // Botón para solicitar Link de Pago PRO o activar prueba de 7 días
        binding.btnTrialRequestPaymentLink.setOnClickListener {
            val plan = PlanCapabilityManager.getPlanType(this)
            val usedTrial = PlanCapabilityManager.hasUsedTrial(this)
            if (plan == PlanType.FREE && !usedTrial) {
                val activated = PlanCapabilityManager.activateTrial(this, 7)
                if (activated) {
                    lifecycleScope.launch {
                        try {
                            ZeasySyncManager.activateTrialOnServer(this@MainActivity)
                        } catch (_: Exception) {}
                    }
                    loadSettings()
                    loadTodayLedger()
                    updateLicenseBadge()
                    updateBinanceSettingsCard()
                    renderCashierBankCards()
                    showTrialRegisteredPrompt()
                }
            } else {
                handleRequestPaymentLinkOption()
            }
        }

        // Botones Acerca de Ciem tech solutions C.A
        binding.btnAboutWhatsapp.setOnClickListener {
            val text = Uri.encode("Hola Ciem tech solutions C.A, requiero asistencia con Zeasy")
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=${PlanCapabilityManager.SUPPORT_PHONE_CLEAN}&text=$text")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }

        binding.btnAboutEmail.setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:${PlanCapabilityManager.SUPPORT_EMAIL}")
                putExtra(Intent.EXTRA_SUBJECT, "Contacto / Soporte Zeasy - Ciem tech solutions C.A")
            }
            try {
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, PlanCapabilityManager.SUPPORT_EMAIL, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveCurrentSettingsDraft() {
        if (binding.viewSettings.visibility == View.VISIBLE) {
            val phone = binding.etSettingsPhone.text?.toString()?.trim() ?: ""
            val idCard = binding.etSettingsId.text?.toString()?.trim() ?: ""
            val name = binding.etSettingsMerchantName.text?.toString()?.trim() ?: ""
            val email = binding.etSettingsEmail.text?.toString()?.trim() ?: ""
            val category = binding.actvSettingsBusinessCategory.text?.toString()?.trim() ?: ""
            val bcvRate = binding.etSettingsBcvRate.text?.toString()?.trim() ?: ""

            if (phone.isNotEmpty() || idCard.isNotEmpty() || name.isNotEmpty()) {
                PreferencesHelper.saveSettingsDraft(this, name, category, phone, idCard, email, bcvRate)
            }
        }
    }

    private fun loadSettings(forceReload: Boolean = false) {
        val themeMode = PreferencesHelper.getThemeMode(this)
        if (themeMode == "DARK") {
            binding.toggleGroupTheme.check(R.id.btnThemeDark)
        } else {
            binding.toggleGroupTheme.check(R.id.btnThemeLight)
        }

        val profile = PreferencesHelper.getUserProfile(this)
        val draft = if (!forceReload && PreferencesHelper.hasSettingsDraft(this)) {
            PreferencesHelper.getSettingsDraft(this)
        } else emptyMap()

        val initialName = draft["draft_merchant_name"]?.takeIf { it.isNotEmpty() } ?: profile.merchantName
        val initialCategory = draft["draft_business_category"]?.takeIf { it.isNotEmpty() } ?: profile.businessCategory
        val initialPhone = draft["draft_phone"]?.takeIf { it.isNotEmpty() } ?: profile.phoneNumber
        val initialId = draft["draft_id_card"]?.takeIf { it.isNotEmpty() } ?: profile.idCard
        val initialEmail = draft["draft_email"]?.takeIf { it.isNotEmpty() } ?: profile.email
        val initialBcv = draft["draft_bcv_rate"]?.takeIf { it.isNotEmpty() } ?: String.format(Locale.US, "%.2f", profile.bcvRate)

        // Conservar datos anotados por el usuario para no borrarlos al entrar a dar permisos
        if (forceReload || binding.etSettingsMerchantName.text.isNullOrBlank()) {
            binding.etSettingsMerchantName.setText(initialName)
        }
        if (forceReload || binding.actvSettingsBusinessCategory.text.isNullOrBlank()) {
            binding.actvSettingsBusinessCategory.setText(initialCategory, false)
        }

        syncSelectedBank(profile.bankCode, profile.bankName)

        if (forceReload || binding.etSettingsPhone.text.isNullOrBlank()) {
            binding.etSettingsPhone.setText(initialPhone)
        }
        if (forceReload || binding.etSettingsId.text.isNullOrBlank()) {
            binding.etSettingsId.setText(initialId)
        }
        if (forceReload || binding.etSettingsEmail.text.isNullOrBlank()) {
            binding.etSettingsEmail.setText(initialEmail)
        }
        if (forceReload || binding.etSettingsBcvRate.text.isNullOrBlank()) {
            binding.etSettingsBcvRate.setText(initialBcv)
        }

        if (forceReload) {
            when (profile.soundboxMode) {
                "CHIME" -> binding.toggleGroupSoundbox.check(R.id.btnSoundChime)
                "MUTED" -> binding.toggleGroupSoundbox.check(R.id.btnSoundMuted)
                else -> binding.toggleGroupSoundbox.check(R.id.btnSoundVoice)
            }
            binding.switchDetectSms.isChecked = PreferencesHelper.isSmsDetectionEnabled(this)
            binding.switchCelebrationScreen.isChecked = PreferencesHelper.isCelebrationScreenEnabled(this)
            binding.switchBiometric.isChecked = profile.biometricSecurityEnabled
        }

        updateNotificationPermissionUI()
        setupSettingsBankManager()
        setupCashierBankSelector()
        setupSettingsWallets()
        updatePlanUiInSettings()
    }

    private fun updatePlanUiInSettings() {
        binding.tvSettingsLegalLink.setOnClickListener {
            val serverUrl = ZeasySyncManager.getServerUrl(this)
            val legalUrl = if (serverUrl.isNotEmpty()) "$serverUrl/legal" else "${PlanCapabilityManager.OFFICIAL_WEBSITE}/legal"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(legalUrl)))
            } catch (e: Exception) {
                Toast.makeText(this, "No se pudo abrir el navegador", Toast.LENGTH_SHORT).show()
            }
        }

        // Estado del Plan Zeasy PRO y seguimiento de prueba / renovación
        if (PlanCapabilityManager.PLAY_STORE_REVIEW_MODE) {
            binding.tvSettingsPlanBadge.text = "Zeasy Edición Completa ⭐"
            binding.tvSettingsPlanDescription.text = "Acceso ilimitado a todas las herramientas: Multibanco, Soundbox por voz, Binance Pay y Reportes."
            binding.cardTrialPaymentAlert.visibility = View.GONE
            binding.btnManagePlanPro.visibility = View.GONE
            binding.btnSoundVoice.text = "🗣️ Voz"
            binding.tvSettingsWalletsProBadge.visibility = View.GONE
            updateLicenseBadge()
            return
        }

        val planType = PlanCapabilityManager.getPlanType(this)
        val daysRemaining = PlanCapabilityManager.getDaysRemaining(this)
        val hasUsedTrial = PlanCapabilityManager.hasUsedTrial(this)
        val hasHadPaidPro = PlanCapabilityManager.hasHadPaidPro(this)

        binding.tvSettingsPlanBadge.text = when (planType) {
            PlanType.FREE -> if (hasHadPaidPro) "Plan Básico (PRO Vencido)" else "Plan Básico Gratuito"
            PlanType.PRO_TRIAL -> "Prueba PRO ($daysRemaining días)"
            PlanType.PRO_ACTIVE -> "Zeasy PRO Activo ⭐"
        }
        binding.tvSettingsPlanDescription.text = when (planType) {
            PlanType.FREE -> if (hasHadPaidPro) "Tu mes de Zeasy PRO culminó. Continúas en modo básico gratuito con 1 banco activo." else "Cobro básico ilimitado gratuito con 1 banco activo."
            PlanType.PRO_TRIAL -> "Disfrutas de todas las funciones PRO por cortesía de prueba ($daysRemaining días restantes)."
            PlanType.PRO_ACTIVE -> "Suscripción activa con acceso ilimitado a todas las funciones ($daysRemaining días restantes)."
        }

        when (planType) {
            PlanType.PRO_ACTIVE -> {
                if (daysRemaining <= 3) {
                    // Aviso preventivo para renovar antes de que expire el mes
                    binding.cardTrialPaymentAlert.visibility = View.VISIBLE
                    binding.tvTrialPaymentAlertMessage.text = "⚠️ Tu suscripción Zeasy PRO vence en $daysRemaining día(s). Puedes renovar tu mes con anticipación para continuar sin interrupciones."
                    binding.btnTrialRequestPaymentLink.text = "💳 Renovar Suscripción Zeasy PRO"
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
                    binding.tvTrialPaymentAlertMessage.text = "⚠️ Tu prueba Zeasy PRO vence en $daysRemaining días. Solicita tu enlace de pago para no perder la sincronización ni el multibanco."
                    binding.btnTrialRequestPaymentLink.text = "💳 Solicitar Link de Pago PRO"
                    binding.btnManagePlanPro.visibility = View.VISIBLE
                    binding.btnManagePlanPro.text = "✨ Activar Zeasy PRO"
                } else {
                    binding.cardTrialPaymentAlert.visibility = View.GONE
                    binding.btnManagePlanPro.visibility = View.VISIBLE
                    binding.btnManagePlanPro.text = "✨ Ver Beneficios o Activar Zeasy PRO"
                }
            }
            PlanType.FREE -> {
                if (hasHadPaidPro) {
                    // Cliente cuya suscripción mensual acaba de expirar
                    binding.cardTrialPaymentAlert.visibility = View.VISIBLE
                    binding.tvTrialPaymentAlertMessage.text = "⏰ Tu suscripción mensual Zeasy PRO ha finalizado. Tu app sigue operando en modo Básico. Solicita tu enlace para reactivar PRO."
                    binding.btnTrialRequestPaymentLink.text = "💳 Renovar Suscripción Zeasy PRO"
                    binding.btnManagePlanPro.visibility = View.VISIBLE
                    binding.btnManagePlanPro.text = "💳 Renovar Suscripción Zeasy PRO"
                } else if (!hasUsedTrial) {
                    // Usuario nuevo que aún no ha iniciado los 7 días de prueba
                    binding.cardTrialPaymentAlert.visibility = View.VISIBLE
                    binding.tvTrialPaymentAlertMessage.text = "🎁 ¡Pruébalo gratis por 7 días! Disfruta de cobro multibanco, Binance Pay, alertas de voz en Soundbox y reportes en PDF/Excel sin costo."
                    binding.btnTrialRequestPaymentLink.text = "✨ Activar 7 Días de Prueba Gratis"
                    binding.btnManagePlanPro.visibility = View.VISIBLE
                    binding.btnManagePlanPro.text = "✨ Ver Beneficios de Zeasy PRO"
                } else {
                    // Usuario cuya prueba gratis de 7 días ya finalizó
                    binding.cardTrialPaymentAlert.visibility = View.VISIBLE
                    binding.tvTrialPaymentAlertMessage.text = "ℹ️ Tu prueba Zeasy PRO ha finalizado. Solicita tu enlace de pago para reactivar todas las funciones avanzadas."
                    binding.btnTrialRequestPaymentLink.text = "💳 Activar Plan Zeasy PRO"
                    binding.btnManagePlanPro.visibility = View.VISIBLE
                    binding.btnManagePlanPro.text = "✨ Activar Zeasy PRO"
                }
            }
        }

        updateLicenseBadge()
    }

    // --- LICENCIAS Y PLANES ---
    private fun updateLicenseBadge() {
        val deviceId = PlanCapabilityManager.getDeviceId(this)

        if (PlanCapabilityManager.PLAY_STORE_REVIEW_MODE) {
            binding.tvSubscriptionFooter.text = "Zeasy v1.3.3 • Ciem tech solutions C.A • ID: $deviceId"
            binding.tvHeaderSubtitle.text = "⭐ Zeasy Edición Completa"
            binding.tvHeaderSubtitle.setOnClickListener(null)
            return
        }

        val planType = PlanCapabilityManager.getPlanType(this)
        val daysRemaining = PlanCapabilityManager.getDaysRemaining(this)

        binding.tvSubscriptionFooter.text = "Zeasy v1.3.0 • Ciem tech solutions C.A • ID: $deviceId ($planType)"

        val subtitleText = when (planType) {
            PlanType.FREE -> "⚡ Plan Básico • Toca para PRO"
            PlanType.PRO_TRIAL -> "⭐ Prueba PRO ($daysRemaining d restantes)"
            PlanType.PRO_ACTIVE -> "⭐ Zeasy PRO Activo"
        }
        binding.tvHeaderSubtitle.text = subtitleText
        binding.tvHeaderSubtitle.setOnClickListener {
            if (!PlanCapabilityManager.isPro(this)) {
                showProUpgradeDialog("todas las funciones avanzadas de Zeasy PRO")
            }
        }
    }

    private fun showProUpgradeDialog(featureName: String) {
        if (PlanCapabilityManager.PLAY_STORE_REVIEW_MODE) {
            return
        }

        val planType = PlanCapabilityManager.getPlanType(this)
        val daysRemaining = PlanCapabilityManager.getDaysRemaining(this)
        if (planType == PlanType.PRO_ACTIVE && daysRemaining > 3) {
            Toast.makeText(this, "⭐ Tu plan Zeasy PRO está activo ($daysRemaining días restantes). Podrás renovar cuando falten 3 días.", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogBinding = DialogProUpgradeBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.tvProReasonSubtitle.text = "Desbloquea $featureName con Zeasy PRO ⚡"

        // Precios dinámicos sincronizados remotamente desde el panel admin
        val p1 = PreferencesHelper.getPrice1m(this)
        val p3 = PreferencesHelper.getPrice3m(this)
        val p12 = PreferencesHelper.getPrice12m(this)

        dialogBinding.tvPrice1m.text = String.format(java.util.Locale.US, "$%.2f", p1)
        dialogBinding.tvPrice3m.text = String.format(java.util.Locale.US, "$%.2f", p3)
        dialogBinding.tvPrice12m.text = String.format(java.util.Locale.US, "$%.2f", p12)

        var selectedMonths = 12

        val density = resources.displayMetrics.density
        val strokeActive = (2 * density).toInt()
        val strokeInactive = (1 * density).toInt()
        val colorActiveGreen = androidx.core.content.ContextCompat.getColor(this, R.color.accent_green)
        val colorActiveBlue = androidx.core.content.ContextCompat.getColor(this, R.color.accent_blue)
        val colorBorderSubtle = androidx.core.content.ContextCompat.getColor(this, R.color.border_subtle)
        val colorCardDefault = androidx.core.content.ContextCompat.getColor(this, R.color.card_background)

        fun updatePackageSelection(months: Int) {
            selectedMonths = months
            // 1m
            dialogBinding.cardPackage1m.strokeWidth = if (months == 1) strokeActive else strokeInactive
            dialogBinding.cardPackage1m.strokeColor = if (months == 1) colorActiveBlue else colorBorderSubtle
            dialogBinding.cardPackage1m.setCardBackgroundColor(if (months == 1) android.graphics.Color.parseColor("#EBF0F7") else colorCardDefault)

            // 3m
            dialogBinding.cardPackage3m.strokeWidth = if (months == 3) strokeActive else strokeInactive
            dialogBinding.cardPackage3m.strokeColor = if (months == 3) colorActiveBlue else colorBorderSubtle
            dialogBinding.cardPackage3m.setCardBackgroundColor(if (months == 3) android.graphics.Color.parseColor("#EBF0F7") else colorCardDefault)

            // 12m
            dialogBinding.cardPackage12m.strokeWidth = if (months == 12) strokeActive else strokeInactive
            dialogBinding.cardPackage12m.strokeColor = if (months == 12) colorActiveGreen else colorBorderSubtle
            dialogBinding.cardPackage12m.setCardBackgroundColor(if (months == 12) android.graphics.Color.parseColor("#F0FDF4") else colorCardDefault)
        }

        dialogBinding.cardPackage1m.setOnClickListener { updatePackageSelection(1) }
        dialogBinding.cardPackage3m.setOnClickListener { updatePackageSelection(3) }
        dialogBinding.cardPackage12m.setOnClickListener { updatePackageSelection(12) }
        updatePackageSelection(12)

        val hasUsedTrial = PlanCapabilityManager.hasUsedTrial(this)
        if (hasUsedTrial) {
            dialogBinding.btnProTrial.visibility = View.GONE
        } else {
            dialogBinding.btnProTrial.setOnClickListener {
                dialog.dismiss()
                val activated = PlanCapabilityManager.activateTrial(this)
                if (activated) {
                    lifecycleScope.launch {
                        val synced = ZeasySyncManager.activateTrialOnServer(this@MainActivity)
                        if (synced) {
                            runOnUiThread {
                                updateLicenseBadge()
                                updateBinanceSettingsCard()
                                renderCashierBankCards()
                            }
                        }
                    }
                    loadSettings()
                    loadTodayLedger()
                    updateLicenseBadge()
                    updateBinanceSettingsCard()
                    renderCashierBankCards()
                    showTrialRegisteredPrompt()
                }
            }
        }

        dialogBinding.btnProWhatsapp.setOnClickListener {
            dialog.dismiss()
            handleRequestPaymentLinkOption(selectedMonths)
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
            .setMessage("Tienes acceso a todas las funciones PRO (Multibanco, QR simultáneo, Copia de Seguridad y Exportación).\n\nPara brindarte soporte prioritario y enviarte tu enlace de pago cuando finalice tu prueba, registra tu comercio por WhatsApp con Ciem tech solutions C.A.")
            .setPositiveButton("💬 Registrar por WhatsApp") { dialog, _ ->
                dialog.dismiss()
                val text = Uri.encode("Hola Ciem tech solutions C.A, acabo de activar mi prueba gratis de 7 días de Zeasy PRO. Negocio: '$businessName' (ID Dispositivo: $deviceId). Quedo atento a su soporte y al link de pago al terminar.")
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
            .setTitle("⏰ Tu período Zeasy PRO ha finalizado")
            .setMessage("Tu aplicación continúa funcionando con normalidad en el Plan Básico Gratuito (Pago Móvil, tasa BCV y cuadre de caja).\n\nPara reactivar la detección multibanco simultánea, dictado por voz y migración de datos, renueva tu suscripción.")
            .setPositiveButton("💳 Renovar Suscripción") { _, _ ->
                handleRequestPaymentLinkOption()
            }
            .setNegativeButton("Continuar en Básico", null)
            .show()
    }

    private fun handleRequestPaymentLinkOption(months: Int = 1) {
        val planType = PlanCapabilityManager.getPlanType(this)
        val daysRemaining = PlanCapabilityManager.getDaysRemaining(this)
        if (planType == PlanType.PRO_ACTIVE && daysRemaining > 3) {
            Toast.makeText(this, "⭐ Tu plan Zeasy PRO está activo ($daysRemaining días restantes). Podrás renovar cuando falten 3 días.", Toast.LENGTH_SHORT).show()
            return
        }

        // Abre directamente el contacto de WhatsApp con CIEM Tech para solicitar el enlace oficial de CobrixPay
        openSupportWhatsAppForPro(months)
    }

    private fun openSupportWhatsAppForPro(months: Int = 1) {
        val profile = PreferencesHelper.getUserProfile(this)
        val deviceId = PlanCapabilityManager.getDeviceId(this)
        val businessName = profile.merchantName.ifEmpty { "Mi Negocio" }
        val rif = if (profile.idCard.isNotEmpty()) " (${profile.idCard})" else ""

        val (monthsText, priceVal) = when (months) {
            12 -> "1 Año (Anual con 2 meses gratis)" to PreferencesHelper.getPrice12m(this)
            3 -> "3 Meses (Trimestral con ahorro)" to PreferencesHelper.getPrice3m(this)
            else -> "1 Mes" to PreferencesHelper.getPrice1m(this)
        }
        val priceStr = String.format(java.util.Locale.US, "$%.2f", priceVal)

        val text = Uri.encode("Hola Ciem tech solutions C.A, deseo activar o renovar el Plan Zeasy PRO por $monthsText ($priceStr USD) para mi negocio '$businessName'$rif (ID Dispositivo: $deviceId). Por favor envíenme el link de pago oficial de CobrixPay.")
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
            .setTitle("🔑 Activar Zeasy PRO con Código")
            .setMessage("Ingresa el código de activación asignado a tu dispositivo por el equipo de soporte de CIEM:")
            .setView(input)
            .setPositiveButton("Activar") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isEmpty()) return@setPositiveButton

                Toast.makeText(this, "Validando código de activación...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val result = ZeasySyncManager.redeemActivationCode(this@MainActivity, code)
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
            val updated = ZeasySyncManager.syncWithBackend(this@MainActivity)
            val isProNow = PlanCapabilityManager.isPro(this@MainActivity)
            val daysRemaining = PlanCapabilityManager.getDaysRemaining(this@MainActivity)

            runOnUiThread {
                if (wasPro != isProNow || binding.viewSettings.visibility == View.VISIBLE) {
                    updatePlanUiInSettings()
                    updateBinanceSettingsCard()
                }
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

    /**
     * Sincroniza automáticamente la tasa oficial del BCV si han transcurrido más de 4 horas
     * o al iniciar una nueva jornada diaria, manteniendo los cálculos al día sin intervención.
     */
    private fun autoSyncBcvRateDaily() {
        val lastSync = PreferencesHelper.getLastBcvSyncTime(this)
        val fourHoursMs = 4 * 60 * 60 * 1000L
        if (System.currentTimeMillis() - lastSync >= fourHoursMs) {
            lifecycleScope.launch {
                val result = BcvRateHelper.fetchOfficialRate()
                if (result.isSuccess && result.rate > 0.0) {
                    val oldRate = PreferencesHelper.getUserProfile(this@MainActivity).bcvRate
                    BcvRateHelper.applyRateToProfile(this@MainActivity, result.rate)
                    PreferencesHelper.setLastBcvSyncTime(this@MainActivity, System.currentTimeMillis())

                    updateDisplayAmount()
                    val formatted = String.format(Locale.US, "%.2f", result.rate)
                    binding.etSettingsBcvRate.setText(formatted)
                    if (Math.abs(oldRate - result.rate) > 0.01) {
                        Toast.makeText(this@MainActivity, "🔄 Tasa BCV del día sincronizada: Bs. $formatted", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showProActivatedCelebrationDialog(daysRemaining: Int) {
        try {
            soundHelper?.playCashRegisterSound()
        } catch (_: Exception) {}

        AlertDialog.Builder(this)
            .setTitle("🎉 ¡Plan Zeasy PRO Activado! ⚡")
            .setMessage(
                "¡Tu suscripción ha sido confirmada con éxito!\n\n" +
                "📅 Vigencia: $daysRemaining día${if (daysRemaining > 1) "s" else ""} restante${if (daysRemaining > 1) "s" else ""}.\n\n" +
                "🚀 Ya puedes disfrutar de todas las funciones PRO:\n" +
                "• Detección y confirmación Multibanco simultánea\n" +
                "• Cobro y conversión de Divisas con Tasa BCV\n" +
                "• Soundbox con voz parlante y altavoz Bluetooth\n" +
                "• Carga rápida de ventas y gastos por voz\n" +
                "• Copia de seguridad y exportación de reportes\n\n" +
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
        // En lugar de forzar diálogos o banners intrusivos en la pantalla principal al iniciar,
        // actualizamos el estado de los permisos en la sección de Ajustes de forma limpia y silenciosa.
        updateNotificationPermissionUI()
    }

    private fun updateNotificationPermissionUI() {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val isListenerEnabled = enabledListeners != null && enabledListeners.contains(packageName)

        // Banner visible y funcional en la pantalla principal si falta el permiso
        if (!isListenerEnabled) {
            binding.layoutPermissionWarning.visibility = View.VISIBLE
            binding.btnFixPermission.setOnClickListener {
                promptActivateNotificationListener()
            }
        } else {
            binding.layoutPermissionWarning.visibility = View.GONE
        }

        // Actualizar indicador en Ajustes con opción a desactivar/modificar
        if (isListenerEnabled) {
            binding.tvSettingsNotificationStatus.text = "Escucha de pagos: Activa ✅"
            binding.tvSettingsNotificationStatus.setTextColor(ContextCompat.getColor(this, R.color.income_green))
            binding.btnSettingsFixNotification.text = "Desactivar"
            binding.btnSettingsFixNotification.isEnabled = true
            binding.btnSettingsFixNotification.alpha = 1.0f
            binding.btnSettingsFixNotification.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.warning_orange))
            wasNotificationListenerEnabled = true
        } else {
            binding.tvSettingsNotificationStatus.text = "Escucha de pagos: Inactiva ⚠️"
            binding.tvSettingsNotificationStatus.setTextColor(ContextCompat.getColor(this, R.color.warning_orange))
            binding.btnSettingsFixNotification.text = "Activar"
            binding.btnSettingsFixNotification.isEnabled = true
            binding.btnSettingsFixNotification.alpha = 1.0f
            binding.btnSettingsFixNotification.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_cyan))
            wasNotificationListenerEnabled = false
        }

        // Estado de optimización de batería en Ajustes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isIgnoringBattery = powerManager?.isIgnoringBatteryOptimizations(packageName) == true
            if (isIgnoringBattery) {
                binding.tvSettingsBatteryStatus.text = "Segundo plano: Sin límites ✅"
                binding.tvSettingsBatteryStatus.setTextColor(ContextCompat.getColor(this, R.color.income_green))
                binding.btnSettingsFixBattery.text = "Modificar"
                binding.btnSettingsFixBattery.isEnabled = true
                binding.btnSettingsFixBattery.alpha = 1.0f
                binding.btnSettingsFixBattery.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.warning_orange))
            } else {
                binding.tvSettingsBatteryStatus.text = "Segundo plano: Con restricciones ⚠️"
                binding.tvSettingsBatteryStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                binding.btnSettingsFixBattery.text = "Optimizar"
                binding.btnSettingsFixBattery.isEnabled = true
                binding.btnSettingsFixBattery.alpha = 1.0f
                binding.btnSettingsFixBattery.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_cyan))
            }
        }
    }

    private fun promptActivateNotificationListener() {
        AlertDialog.Builder(this)
            .setTitle("🔔 Activar Detección Automática de Pagos")
            .setMessage("Para que Zeasy escuche los pagos de tu banco y te avise en voz alta:\n\n1. En la pantalla que se abrirá, activa el interruptor de Zeasy.\n2. Presiona 'Permitir'.\n3. Regresa a Zeasy y ¡listo para cobrar!\n\n¿Deseas activarlo ahora?")
            .setPositiveButton("👉 Abrir Ajustes") { _, _ ->
                try {
                    val detailIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS").apply {
                            putExtra(
                                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                ComponentName(this@MainActivity, PocketNotificationListenerService::class.java).flattenToString()
                            )
                        }
                    } else {
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    }
                    startActivity(detailIntent)
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    } catch (_: Exception) {
                        Toast.makeText(this, "Abre Ajustes > Notificaciones > Acceso a notificaciones", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Más tarde", null)
            .show()
    }

    private fun requestBatteryOptimizationManual() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                        Toast.makeText(this, "Abre Ajustes > Batería > Sin restricciones para Zeasy", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Toast.makeText(this, "✅ La optimización de batería ya está configurada correctamente", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showWelcomeOnboardingDialog() {
        AlertDialog.Builder(this)
            .setTitle("👋 ¡Bienvenido a Zeasy Cobros!")
            .setMessage("La herramienta que canta tus pagos en voz alta y te da tu código QR al instante.\n\nPara dejar tu caja 100% lista para cobrar:\n\n1️⃣ Configura tu Teléfono y Cédula de Pago Móvil.\n2️⃣ Activa los permisos de escucha bancaria y segundo plano.\n\n¿Deseas configurarlo ahora?")
            .setPositiveButton("⚡ Configurar Ahora (Recomendado)") { dialog, _ ->
                dialog.dismiss()
                binding.bottomNavigation.selectedItemId = R.id.nav_settings
                binding.etSettingsPhone.requestFocus()

                // Invitar a activar los permisos tras ingresar a Ajustes
                binding.etSettingsPhone.postDelayed({
                    val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
                    val isListenerEnabled = enabledListeners != null && enabledListeners.contains(packageName)
                    if (!isListenerEnabled) {
                        promptActivateNotificationListener()
                    }
                }, 800)
            }
            .setNegativeButton("Explorar primero", null)
            .setCancelable(false)
            .show()
    }

    private fun setupClearDefaultOnTouch(editText: EditText, defaultVals: Set<String>) {
        editText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val txt = editText.text.toString().trim()
                if (txt in defaultVals) {
                    editText.setText("")
                }
            }
        }
        editText.setOnClickListener {
            val txt = editText.text.toString().trim()
            if (txt in defaultVals) {
                editText.setText("")
            }
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

    private fun showPaymentCelebrationDialog(
        amount: Double,
        ref: String,
        bank: String,
        phone: String,
        amountUsd: Double = 0.0,
        isCrypto: Boolean = false
    ) {
        celebrationDialog?.dismiss()
        celebrationDismissHandler.removeCallbacksAndMessages(null)

        val dialogBinding = DialogPaymentCelebrationBinding.inflate(layoutInflater)
        val profile = PreferencesHelper.getUserProfile(this)

        val isCryptoPayment = isCrypto || bank.contains("Binance", ignoreCase = true) || bank.contains("Cripto", ignoreCase = true)
        if (isCryptoPayment && amountUsd > 0.0) {
            dialogBinding.tvCelebrationAmountVes.text = String.format(Locale.US, "$%.2f USDT", amountUsd)
            dialogBinding.tvCelebrationAmountUsd.text = String.format(Locale.US, "≈ Bs. %.2f (Tasa %.2f)", amount, profile.bcvRate)
        } else {
            val calcUsd = if (profile.bcvRate > 0) amount / profile.bcvRate else 0.0
            dialogBinding.tvCelebrationAmountVes.text = String.format(Locale.US, "Bs. %.2f", amount)
            dialogBinding.tvCelebrationAmountUsd.text = String.format(Locale.US, "≈ $%.2f USD (Tasa %.2f)", calcUsd, profile.bcvRate)
        }
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
