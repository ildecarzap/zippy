package com.cobrix.pocket.ui

import android.app.Activity
import java.io.File
import java.io.FileOutputStream
import android.app.DatePickerDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
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
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.cobrix.pocket.BuildConfig
import com.cobrix.pocket.R
import com.cobrix.pocket.data.db.PocketDatabaseHelper
import com.cobrix.pocket.data.model.BankCatalog
import com.cobrix.pocket.data.model.CountryCode
import com.cobrix.pocket.data.model.CountryProfileCatalog
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
import com.cobrix.pocket.utils.LicenseRepository
import com.cobrix.pocket.utils.PreferencesHelper
import com.cobrix.pocket.utils.QrValidator
import com.cobrix.pocket.utils.ReportHelper
import com.cobrix.pocket.utils.SoundHelper
import com.cobrix.pocket.utils.WhatsAppHelper
import com.google.android.material.bottomsheet.BottomSheetDialog
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
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import com.cobrix.pocket.data.model.Debtor
import com.cobrix.pocket.data.model.LedgerEntry
import com.cobrix.pocket.data.model.LedgerEntryType
import com.cobrix.pocket.databinding.DialogAddDebtorBinding
import com.cobrix.pocket.databinding.DialogEditDebtorBinding
import com.cobrix.pocket.databinding.DialogSecurityPinBinding
import com.cobrix.pocket.databinding.DialogChangeSecurityPinBinding
import com.cobrix.pocket.databinding.DialogAddChargeBinding
import com.cobrix.pocket.databinding.DialogAddPaymentBinding
import com.cobrix.pocket.databinding.DialogDebtorDetailBinding
import com.cobrix.pocket.databinding.DialogQuickFiarSelectBinding
import com.cobrix.pocket.databinding.DialogSetupWizardBinding
import com.cobrix.pocket.databinding.ItemDebtorBinding
import com.cobrix.pocket.databinding.ItemLedgerEntryBinding
import com.cobrix.pocket.ui.DebtorsAdapter
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var dbHelper: PocketDatabaseHelper
    private var soundHelper: SoundHelper? = null

    private var currentInput = "0"
    private var isVesMode = true // Por defecto: Bolívares (VES)

    // Asistente de Configuración Paso a Paso (Alta Accesibilidad + Guardado Progresivo)
    private var setupWizardDialog: Dialog? = null
    private var setupWizardBinding: DialogSetupWizardBinding? = null
    private var currentWizardStep: Int = 1
    private var isWizardMandatory: Boolean = false

    // Control de diálogos de licencia y celebración para evitar ventanas duplicadas
    private var isSyncingLicense: Boolean = false
    private var proCelebrationDialog: Dialog? = null
    private var trialRegisteredDialog: Dialog? = null

    // Estado para Libreta de Clientes y Fiados
    private lateinit var debtorsAdapter: DebtorsAdapter
    private var allDebtorsList: List<Debtor> = emptyList()
    private var activeDebtorFilter: String = "ALL"

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
                loadDebtors()
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
    private var isBindingSettingsData: Boolean = false

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
            if (setupWizardDialog?.isShowing == true) {
                refreshSetupWizardStepUi()
            }
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
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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

    // Callback y Launcher para importar deudor desde la libreta de contactos del teléfono
    private var onContactPickedCallback: ((name: String, phone: String) -> Unit)? = null

    private val pickContactLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val contactUri = result.data?.data
            if (contactUri != null) {
                try {
                    val projection = arrayOf(
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                    )
                    contentResolver.query(contactUri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                            val numIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                            val contactName = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim() ?: "" else ""
                            val rawNumber = if (numIdx >= 0) cursor.getString(numIdx)?.trim() ?: "" else ""

                            // Normalizar número venezolano (ej. +58 414-1234567 -> 04141234567)
                            var cleanPhone = rawNumber.replace(Regex("[^0-9+]"), "")
                            if (cleanPhone.startsWith("+58")) {
                                cleanPhone = "0" + cleanPhone.removePrefix("+58")
                            } else if (cleanPhone.startsWith("58") && cleanPhone.length == 12) {
                                cleanPhone = "0" + cleanPhone.substring(2)
                            } else {
                                cleanPhone = cleanPhone.replace("+", "")
                            }

                            onContactPickedCallback?.invoke(contactName, cleanPhone)
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "No se pudo leer el contacto seleccionado", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun launchPhoneContactPicker(onPicked: (name: String, phone: String) -> Unit) {
        onContactPickedCallback = onPicked
        try {
            val intent = Intent(Intent.ACTION_PICK, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            pickContactLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se encontró aplicación de Contactos en el teléfono", Toast.LENGTH_SHORT).show()
        }
    }

    // Receptor en tiempo real para cuando el servicio captura un pago o salida bancaria
    private val paymentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PocketNotificationListenerService.ACTION_PAYMENT_RECEIVED) {
                val isOutgoing = intent.getBooleanExtra(PocketNotificationListenerService.EXTRA_IS_OUTGOING, false)
                if (isOutgoing) {
                    runOnUiThread {
                        loadTodayLedger()
                    }
                    return
                }

                val amount = intent.getDoubleExtra(PocketNotificationListenerService.EXTRA_AMOUNT, 0.0)
                val amountUsd = intent.getDoubleExtra(PocketNotificationListenerService.EXTRA_AMOUNT_USD, 0.0)
                val isCrypto = intent.getBooleanExtra(PocketNotificationListenerService.EXTRA_IS_CRYPTO, false)
                val ref = intent.getStringExtra(PocketNotificationListenerService.EXTRA_REF) ?: ""
                val bank = intent.getStringExtra(PocketNotificationListenerService.EXTRA_BANK) ?: ""
                val phone = intent.getStringExtra(PocketNotificationListenerService.EXTRA_PHONE) ?: ""
                val reconciledDebtor = intent.getStringExtra(PocketNotificationListenerService.EXTRA_RECONCILED_DEBTOR) ?: ""

                runOnUiThread {
                    loadTodayLedger()
                    loadDebtors()
                    PreferencesHelper.getAndClearPendingCelebration(this@MainActivity)
                    if (reconciledDebtor.isNotEmpty()) {
                        Toast.makeText(
                            this@MainActivity,
                            "✅ ¡Pago Móvil conciliado automáticamente con el fiado de $reconciledDebtor!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    if (PreferencesHelper.isCelebrationScreenEnabled(this@MainActivity) && (amount > 0.0 || amountUsd > 0.0)) {
                        showPaymentCelebrationDialog(amount, ref, bank, phone, amountUsd, isCrypto)
                    } else if (reconciledDebtor.isEmpty()) {
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

        // Habilitar Edge-to-Edge nativo para Android 15+ (targetSdk 35/36) con APIs modernas no obsoletas
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)
        PreferencesHelper.sanitizeBasicPlanBank(this)
        val initialProfile = PreferencesHelper.getUserProfile(this)
        activeCashierBankCode = initialProfile.bankCode
        activeCashierBankName = initialProfile.bankName
        selectedBankConfigCode = initialProfile.bankCode
        selectedBankConfigName = initialProfile.bankName

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Gestión de bajorrelieves (insets: status bar / notch y barra de navegación del sistema)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            val defaultPaddingH = (20 * density).toInt()
            val defaultPaddingBottom = (16 * density).toInt()

            binding.layoutTopHeaderBar.setPadding(
                defaultPaddingH,
                systemBars.top + (8 * density).toInt(),
                defaultPaddingH,
                defaultPaddingBottom
            )

            binding.bottomNavigation.setPadding(0, 0, 0, systemBars.bottom)

            insets
        }

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
        setupDebtorsModule()
        applyCountryProfileToUi()
        updateLicenseBadge()
        checkNotificationPermission()
        checkBiometricAuthOnStart()

        // Interceptar botón atrás si hay cambios pendientes en ajustes
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.viewSettings.visibility == View.VISIBLE) {
                    checkUnsavedSettingsBeforeNavigating {
                        binding.bottomNavigation.selectedItemId = R.id.nav_cashier
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // La sincronización de licencia se realiza en onResume() de forma segura y no concurrente
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
        PocketNotificationListenerService.ensureServiceBound(this)

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

        if (setupWizardDialog?.isShowing == true) {
            refreshSetupWizardStepUi()
        } else if (!PreferencesHelper.isOnboardingCompleted(this)) {
            showSetupWizardDialog(
                isMandatory = true,
                startStep = PreferencesHelper.getOnboardingStep(this)
            )
        }
        updateShiftUI()
        loadTodayLedger()
        loadDebtors()
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
                        showShareReportDialog()
                        Toast.makeText(this, "Jornada pausada. Mostrando balance del turno.", Toast.LENGTH_SHORT).show()
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
        PocketNotificationListenerService.updateForegroundShiftState(this)
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
     * datos bancarios sensibles, cargar/cambiar códigos QR, o modificar/eliminar clientes de la libreta.
     * Si el dispositivo o emulador no cuenta con biometría o credencial de sistema activa,
     * solicita el PIN de seguridad de Zeasy (por defecto: 1234).
     */
    private fun requireSecurityAuth(
        actionTitle: String,
        description: String = "Coloca tu huella digital o ingresa el PIN para confirmar esta acción",
        onSuccess: () -> Unit
    ) {
        if (BiometricHelper.isBiometricAvailable(this)) {
            BiometricHelper.showBiometricPrompt(
                activity = this,
                title = actionTitle,
                subtitle = "Confirmación de Seguridad",
                description = description,
                onSuccess = onSuccess,
                onError = { _ ->
                    // Si se cancela o falla la biometría, permitir validar con el PIN de la app
                    showSecurityPinDialog(actionTitle, description, onSuccess)
                }
            )
        } else {
            showSecurityPinDialog(actionTitle, description, onSuccess)
        }
    }

    /**
     * Diálogo de confirmación de PIN de seguridad de la app.
     * Protege acciones sensibles en emuladores o dispositivos sin bloqueo de pantalla configurado.
     */
    private fun showSecurityPinDialog(
        actionTitle: String,
        description: String,
        onSuccess: () -> Unit
    ) {
        val dialog = Dialog(this)
        val dBinding = DialogSecurityPinBinding.inflate(layoutInflater)
        dialog.setContentView(dBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        dBinding.tvPinDialogTitle.text = actionTitle
        dBinding.tvPinDialogDescription.text = description

        val isCustom = PreferencesHelper.hasCustomSecurityPin(this)
        if (isCustom) {
            dBinding.tvPinHelperNotice.text = "🔒 Ingresa tu PIN de seguridad configurado"
        } else {
            dBinding.tvPinHelperNotice.text = "💡 PIN predeterminado: 1234 • Puedes cambiarlo en Ajustes"
        }

        dBinding.btnCancelPin.setOnClickListener {
            dialog.dismiss()
            Toast.makeText(this, "🔒 Acción cancelada: Se requiere autorización", Toast.LENGTH_SHORT).show()
        }

        dBinding.btnConfirmPin.setOnClickListener {
            val enteredPin = dBinding.etSecurityPinInput.text.toString().trim()
            val savedPin = PreferencesHelper.getSecurityPin(this)

            if (enteredPin.isEmpty()) {
                dBinding.etSecurityPinInput.error = "Ingresa tu PIN"
                return@setOnClickListener
            }

            if (enteredPin == savedPin) {
                dialog.dismiss()
                onSuccess()
            } else {
                dBinding.etSecurityPinInput.error = "PIN incorrecto"
                Toast.makeText(this, "❌ PIN de seguridad incorrecto", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    /**
     * Permite al dueño del negocio configurar o cambiar su PIN de seguridad.
     */
    private fun showChangeSecurityPinDialog() {
        val dialog = Dialog(this)
        val dBinding = DialogChangeSecurityPinBinding.inflate(layoutInflater)
        dialog.setContentView(dBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val isCustom = PreferencesHelper.hasCustomSecurityPin(this)
        if (!isCustom) {
            dBinding.layoutCurrentPin.hint = "PIN Actual (Predeterminado: 1234)"
        }

        dBinding.btnCancelChangePin.setOnClickListener { dialog.dismiss() }

        dBinding.btnSaveNewPin.setOnClickListener {
            val currentPinInput = dBinding.etCurrentPin.text.toString().trim()
            val newPinInput = dBinding.etNewPin.text.toString().trim()
            val confirmPinInput = dBinding.etConfirmNewPin.text.toString().trim()
            val savedPin = PreferencesHelper.getSecurityPin(this)

            if (currentPinInput.isEmpty()) {
                dBinding.etCurrentPin.error = "Ingresa el PIN actual"
                return@setOnClickListener
            }
            if (currentPinInput != savedPin) {
                dBinding.etCurrentPin.error = "El PIN actual no coincide"
                return@setOnClickListener
            }
            if (newPinInput.length < 4) {
                dBinding.etNewPin.error = "El nuevo PIN debe tener al menos 4 dígitos"
                return@setOnClickListener
            }
            if (newPinInput != confirmPinInput) {
                dBinding.etConfirmNewPin.error = "Los nuevos PIN no coinciden"
                return@setOnClickListener
            }

            PreferencesHelper.setSecurityPin(this, newPinInput)
            Toast.makeText(this, "✅ Nuevo PIN de seguridad guardado con éxito", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    // --- SELECTOR DE MONEDA (BS / $) CON TOGGLEGROUP EXCLUSIVO ---
    private fun setupCurrencyToggle() {
        binding.toggleGroupCurrency.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val countryProfile = PreferencesHelper.getCountryProfile(this)
                isVesMode = (checkedId == R.id.btnCurrencyVes)
                currentInput = "0"
                if (isVesMode) {
                    binding.tvDisplayLabel.text = "MONTO EN ${countryProfile.localCurrencyVoiceName.uppercase(Locale.ROOT)}"
                    binding.tvCurrencySymbol.text = countryProfile.localCurrencySymbol
                } else {
                    binding.tvDisplayLabel.text = "MONTO EN DÓLARES"
                    binding.tvCurrencySymbol.text = "$"
                }
                updateDisplayAmount()
            }
        }
    }

    /**
     * Aplica dinámicamente las etiquetas, monedas, hints de documento/teléfono
     * y visibilidad de tasa cambiaria según el País de Operación (VE, CO, AR).
     */
    private fun applyCountryProfileToUi() {
        val countryProfile = PreferencesHelper.getCountryProfile(this)

        // Mostrar País de Operación fijo en Ajustes
        binding.tvSettingsActiveCountry.text = if (countryProfile.supportsExchangeRate) {
            "${countryProfile.flagEmoji} ${countryProfile.displayName} (Bolívares Bs. / USD)"
        } else {
            "${countryProfile.flagEmoji} ${countryProfile.displayName} (${countryProfile.localCurrencyDisplay})"
        }

        // Actualizar selector oculto por compatibilidad
        val targetToggleId = when (countryProfile.code) {
            CountryCode.VE -> R.id.btnCountryVe
            CountryCode.CO -> R.id.btnCountryCo
            CountryCode.AR -> R.id.btnCountryAr
        }
        if (binding.toggleGroupCountry.checkedButtonId != targetToggleId) {
            binding.toggleGroupCountry.check(targetToggleId)
        }

        // Visibilidad de fila BCV en Ajustes (solo Venezuela requiere tasa bimoneda BCV)
        binding.layoutSettingsBcvRow.visibility = if (countryProfile.supportsExchangeRate) {
            View.VISIBLE
        } else {
            View.GONE
        }

        // Visibilidad de sección SMS Bancario (Solo relevante para Venezuela: BDV, Banesco, etc.)
        binding.layoutSettingsSmsSection.visibility = if (countryProfile.code == CountryCode.VE) {
            View.VISIBLE
        } else {
            View.GONE
        }

        // Títulos y hints de teléfono e identificación fiscal adaptados al país
        when (countryProfile.code) {
            CountryCode.VE -> {
                binding.tvSettingsBankSectionTitle.text = "🏦 Pago Móvil y Bancos"
                binding.tvSettingsBankSectionSubtitle.text = "Número receptor de Pago Móvil, Cédula o RIF y códigos QR"
                binding.tilSettingsPhone.hint = "Teléfono Pago Móvil (ej. ${countryProfile.phonePlaceholder})"
                binding.etSettingsPhone.inputType = InputType.TYPE_CLASS_PHONE
                binding.tilSettingsId.hint = "${countryProfile.nationalIdLabel} (ej. ${countryProfile.nationalIdPlaceholder})"
            }
            CountryCode.CO -> {
                binding.tvSettingsBankSectionTitle.text = "🏦 Cobro por Transferencia y Bancos"
                binding.tvSettingsBankSectionSubtitle.text = "Celular receptor Nequi/DaviPlata, Cédula/NIT y códigos QR"
                binding.tilSettingsPhone.hint = "Celular Nequi / DaviPlata o Cuenta (ej. ${countryProfile.phonePlaceholder})"
                binding.etSettingsPhone.inputType = InputType.TYPE_CLASS_PHONE
                binding.tilSettingsId.hint = "${countryProfile.nationalIdLabel} (ej. ${countryProfile.nationalIdPlaceholder})"
            }
            CountryCode.AR -> {
                binding.tvSettingsBankSectionTitle.text = "🏦 Cobro por Alias / CVU y Bancos"
                binding.tvSettingsBankSectionSubtitle.text = "Tu Alias o CVU de Mercado Pago / Banco (0% de comisión)"
                binding.tilSettingsPhone.hint = "Tu Alias o CVU de cobro (ej. ${countryProfile.phonePlaceholder})"
                binding.etSettingsPhone.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                binding.tilSettingsId.hint = "${countryProfile.nationalIdLabel} (ej. ${countryProfile.nationalIdPlaceholder})"
            }
        }

        // Configurar el selector de moneda en Caja
        binding.btnCurrencyVes.text = "${countryProfile.localCurrencySymbol} ${countryProfile.localCurrencyCode}"
        if (countryProfile.supportsExchangeRate) {
            binding.toggleGroupCurrency.visibility = View.VISIBLE
        } else {
            binding.toggleGroupCurrency.visibility = View.GONE
            isVesMode = true
        }

        if (isVesMode) {
            binding.tvDisplayLabel.text = "MONTO EN ${countryProfile.localCurrencyVoiceName.uppercase(Locale.ROOT)}"
            binding.tvCurrencySymbol.text = countryProfile.localCurrencySymbol
        } else {
            binding.tvDisplayLabel.text = "MONTO EN DÓLARES"
            binding.tvCurrencySymbol.text = "$"
        }

        updateDisplayAmount()
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

        // Validación estricta adaptada al país: NO permitir guardar valores por defecto o vacíos
        val countryProfile = PreferencesHelper.getCountryProfile(this)
        val isValidPhone = if (countryProfile.code == CountryCode.AR) {
            phone.isNotBlank() && phone.length >= 3 && phone !in DUMMY_DEFAULT_VALUES
        } else {
            val cleanDigits = phone.replace(Regex("[^0-9]"), "")
            cleanDigits.length >= 7 && phone !in DUMMY_DEFAULT_VALUES
        }

        if (!isValidPhone) {
            if (isExplicitClick) {
                val errorMsg = when (countryProfile.code) {
                    CountryCode.AR -> "⚠️ Por favor ingresa tu Alias o CVU de cobro (${countryProfile.phonePlaceholder})"
                    CountryCode.CO -> "⚠️ Por favor ingresa tu Celular o Cuenta de cobro (${countryProfile.phonePlaceholder})"
                    CountryCode.VE -> "⚠️ Por favor ingresa tu número de teléfono de Pago Móvil (${countryProfile.phonePlaceholder})"
                }
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                binding.etSettingsPhone.requestFocus()
            }
            return false
        }

        if (idCard.isEmpty() || idCard in DUMMY_DEFAULT_VALUES) {
            if (isExplicitClick) {
                Toast.makeText(this, "⚠️ Por favor ingresa tu ${countryProfile.nationalIdLabel} (${countryProfile.nationalIdPlaceholder})", Toast.LENGTH_LONG).show()
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
            loadDebtors()
            loadSettings(forceReload = true)

            lifecycleScope.launch {
                val updatedPro = ZeasySyncManager.syncWithBackend(this@MainActivity)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "✅ Configuración guardada correctamente", Toast.LENGTH_SHORT).show()
                    updateLicenseBadge()
                    updateBinanceSettingsCard()
                    renderCashierBankCards()
                    updateSaveButtonState()
                    onSaved?.invoke()
                }
            }
        }

        // --- SEGURIDAD FINANCIERA ANTIFRAUDE ---
        // Si se cambia el teléfono, cédula/RIF, banco, Binance Pay ID o la seguridad biométrica:
        val isSensitiveFinancialChange = (phone != current.phoneNumber) ||
            (idCard != current.idCard) ||
            (finalBankCode != current.bankCode) ||
            (PlanCapabilityManager.canUseWallets(this) && newBinancePayId != currentBinancePayId) ||
            (binding.switchBiometric.isChecked != current.biometricSecurityEnabled)

        val requiresAuth = requireBiometricIfConfigured && (current.biometricSecurityEnabled || binding.switchBiometric.isChecked || isSensitiveFinancialChange)

        if (requiresAuth && BiometricHelper.isBiometricAvailable(this)) {
            BiometricHelper.showBiometricPrompt(
                activity = this,
                title = "🔒 Proteger Datos de Cobro",
                subtitle = "Confirmación de Identidad Obligatoria",
                description = "Coloca tu huella digital o ingresa el PIN para autorizar cambios en tus cuentas de cobro",
                onSuccess = { saveAction() },
                onError = { _ ->
                    Toast.makeText(this, "🔒 Modificación cancelada: Se requiere huella o PIN del dueño", Toast.LENGTH_LONG).show()
                }
            )
        } else if (isSensitiveFinancialChange) {
            // Si el dispositivo no tiene huella o PIN configurado en Android, pedir confirmación explícita
            AlertDialog.Builder(this)
                .setTitle("🛡️ Confirmar Cambio de Datos de Pago")
                .setMessage("Estás modificando los datos donde se recibirán los pagos del negocio:\n\n" +
                    "• Teléfono Pago Móvil: $phone\n" +
                    "• Cédula / RIF: $idCard\n" +
                    (if (finalBankName.isNotEmpty()) "• Banco: $finalBankName\n" else "") +
                    (if (newBinancePayId.isNotEmpty()) "• Binance Pay ID: $newBinancePayId\n" else "") +
                    "\n¿Confirmas que estos datos te pertenecen a ti como titular?")
                .setPositiveButton("Sí, Confirmar") { _, _ -> saveAction() }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            saveAction()
        }
        return true
    }

    // --- DETECCIÓN REACTIVA DE CAMBIOS PENDIENTES EN AJUSTES ---
    private fun hasPendingSettingsChanges(): Boolean {
        if (isBindingSettingsData) return false
        if (binding.viewSettings.visibility != View.VISIBLE) return false

        val current = PreferencesHelper.getUserProfile(this)
        val merchantName = binding.etSettingsMerchantName.text?.toString()?.trim() ?: ""
        val phone = binding.etSettingsPhone.text?.toString()?.trim() ?: ""
        val idCard = binding.etSettingsId.text?.toString()?.trim() ?: ""
        val email = binding.etSettingsEmail.text?.toString()?.trim() ?: ""
        val category = binding.actvSettingsBusinessCategory.text?.toString()?.trim()?.ifEmpty { "General" } ?: "General"
        
        val bcvRateInput = binding.etSettingsBcvRate.text?.toString()?.replace(',', '.')?.trim() ?: ""
        val parsedBcvRate = bcvRateInput.toDoubleOrNull() ?: current.bcvRate
        val bcvChanged = Math.abs(parsedBcvRate - current.bcvRate) > 0.005

        val selectedSoundMode = when (binding.toggleGroupSoundbox.checkedButtonId) {
            R.id.btnSoundChime -> "CHIME"
            R.id.btnSoundMuted -> "MUTED"
            R.id.btnSoundVoice -> "VOICE"
            else -> current.soundboxMode
        }

        val currentBinancePayId = PreferencesHelper.getBinancePayId(this)
        val newBinancePayId = binding.etBinancePayId.text?.toString()?.trim() ?: ""

        val isBiometricChanged = binding.switchBiometric.isChecked != current.biometricSecurityEnabled

        return (merchantName != current.merchantName) ||
            (category != current.businessCategory) ||
            (phone != current.phoneNumber) ||
            (idCard != current.idCard) ||
            (email != current.email) ||
            bcvChanged ||
            (selectedSoundMode != current.soundboxMode) ||
            isBiometricChanged ||
            (PlanCapabilityManager.canUseWallets(this) && newBinancePayId != currentBinancePayId)
    }

    /**
     * Actualiza el botón único superior de Guardar:
     * - Si hay cambios: se hace visible en color cyan ("💾 Guardar") y se mantiene fijo arriba
     * - Si no hay cambios: desaparece (GONE) para una vista limpia sin alertas ni ruidos visuales
     */
    private fun updateSaveButtonState() {
        if (isBindingSettingsData) {
            binding.btnQuickSaveSettingsTop.visibility = View.GONE
            return
        }

        val hasChanges = hasPendingSettingsChanges()
        if (hasChanges) {
            binding.btnQuickSaveSettingsTop.visibility = View.VISIBLE
            binding.btnQuickSaveSettingsTop.isEnabled = true
            binding.btnQuickSaveSettingsTop.alpha = 1.0f
            binding.btnQuickSaveSettingsTop.text = "💾 Guardar"
            binding.btnQuickSaveSettingsTop.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_cyan))
            binding.btnQuickSaveSettingsTop.setTextColor(ContextCompat.getColor(this, R.color.white))
            binding.tvSettingsHeaderStatus.text = "⚠️ Hay cambios sin guardar"
            binding.tvSettingsHeaderStatus.setTextColor(ContextCompat.getColor(this, R.color.warning_orange))
        } else {
            binding.btnQuickSaveSettingsTop.visibility = View.GONE
            binding.tvSettingsHeaderStatus.text = "Personaliza tus datos de cobro, bancos y alertas"
            binding.tvSettingsHeaderStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    /**
     * Valida si el usuario modificó datos críticos y alerta antes de salir
     * para proteger los QR y cuentas de cobro de manipulaciones no deseadas.
     */
    private fun checkUnsavedSettingsBeforeNavigating(onProceed: () -> Unit) {
        if (binding.viewSettings.visibility == View.VISIBLE && hasPendingSettingsChanges()) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ Cambios sin Guardar")
                .setMessage("Modificaste datos de cobro o seguridad en Ajustes.\n\n¿Deseas guardar los cambios antes de salir?")
                .setPositiveButton("💾 Guardar") { _, _ ->
                    val saved = saveCurrentSettingsFromUi(requireBiometricIfConfigured = true, isExplicitClick = true) {
                        onProceed()
                    }
                    if (!saved) {
                        // Si faltaba algún dato obligatorio, permanece en Ajustes
                    }
                }
                .setNegativeButton("Descartar") { _, _ ->
                    PreferencesHelper.clearSettingsDraft(this)
                    loadSettings(forceReload = true)
                    updateSaveButtonState()
                    onProceed()
                }
                .setNeutralButton("Seguir Editando", null)
                .show()
        } else {
            onProceed()
        }
    }

    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_cashier -> {
                    checkUnsavedSettingsBeforeNavigating {
                        binding.viewCashier.visibility = View.VISIBLE
                        binding.viewLedger.visibility = View.GONE
                        binding.viewDebtors.visibility = View.GONE
                        binding.viewSettings.visibility = View.GONE
                        setupCashierBankSelector()
                        renderCashierBankCards()
                        updateCashierHeaderSummary()
                        binding.bottomNavigation.menu.findItem(R.id.nav_cashier).isChecked = true
                    }
                    binding.viewSettings.visibility != View.VISIBLE || !hasPendingSettingsChanges()
                }
                R.id.nav_ledger -> {
                    checkUnsavedSettingsBeforeNavigating {
                        binding.viewCashier.visibility = View.GONE
                        binding.viewLedger.visibility = View.VISIBLE
                        binding.viewDebtors.visibility = View.GONE
                        binding.viewSettings.visibility = View.GONE
                        loadTodayLedger()
                        binding.bottomNavigation.menu.findItem(R.id.nav_ledger).isChecked = true
                    }
                    binding.viewSettings.visibility != View.VISIBLE || !hasPendingSettingsChanges()
                }
                R.id.nav_debtors -> {
                    checkUnsavedSettingsBeforeNavigating {
                        binding.viewCashier.visibility = View.GONE
                        binding.viewLedger.visibility = View.GONE
                        binding.viewDebtors.visibility = View.VISIBLE
                        binding.viewSettings.visibility = View.GONE
                        loadDebtors()
                        binding.bottomNavigation.menu.findItem(R.id.nav_debtors).isChecked = true
                    }
                    binding.viewSettings.visibility != View.VISIBLE || !hasPendingSettingsChanges()
                }
                R.id.nav_settings -> {
                    binding.viewCashier.visibility = View.GONE
                    binding.viewLedger.visibility = View.GONE
                    binding.viewDebtors.visibility = View.GONE
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
        val countryProfile = PreferencesHelper.getCountryProfile(this)

        binding.tvDisplayAmount.text = currentInput

        if (!countryProfile.supportsExchangeRate) {
            binding.tvConvertedAmount.text = "${countryProfile.flagEmoji} ${countryProfile.displayName} • ${countryProfile.localCurrencyCode}"
            return
        }

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
            val countryProfile = PreferencesHelper.getCountryProfile(this)
            val amountVes: Double
            val amountUsd: Double

            if (!countryProfile.supportsExchangeRate) {
                amountVes = amount
                amountUsd = amount
            } else if (amount > 0.0) {
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
            val countryProfile = PreferencesHelper.getCountryProfile(this)
            val amountVes: Double
            val amountUsd: Double

            if (!countryProfile.supportsExchangeRate) {
                amountVes = amount
                amountUsd = amount
            } else if (isVesMode) {
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
                bankCode = activeCashierBankCode,
                countryProfile = countryProfile
            )
            val qrUri = PreferencesHelper.getBankQrUri(this, activeCashierBankCode)?.let { Uri.parse(it) }
            WhatsAppHelper.sendViaWhatsApp(this, "", message, qrUri)
        }

        binding.btnChargeWhatsApp.setOnLongClickListener {
            val amount = currentInput.toDoubleOrNull() ?: 0.0
            if (amount > 0.0) {
                val profile = PreferencesHelper.getUserProfile(this)
                val countryProfile = PreferencesHelper.getCountryProfile(this)
                val amountVes = if (!countryProfile.supportsExchangeRate) amount else if (isVesMode) amount else amount * profile.bcvRate
                val amountUsd = if (!countryProfile.supportsExchangeRate) amount else if (isVesMode) (if (profile.bcvRate > 0) amount / profile.bcvRate else 0.0) else amount
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
     * Muestra las tarjetas de bancos configurados (con QR o para cobro directo por Alias / CVU o número).
     */
    private fun renderCashierBankCards() {
        binding.layoutCashierBankCardsContainer.removeAllViews()
        val isMultibank = PlanCapabilityManager.canUseMultibank(this)
        val profile = PreferencesHelper.getUserProfile(this)
        val currentCountry = PreferencesHelper.getCountryCode(this)

        val bankCodesToShow = if (isMultibank) {
            val configured = PreferencesHelper.getConfiguredBankCodes(this)
            if (configured.isNotEmpty()) {
                configured
            } else if (profile.bankCode.isNotEmpty()) {
                listOf(profile.bankCode)
            } else emptyList()
        } else {
            if (profile.bankCode.isNotEmpty()) {
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
            val hasQr = PreferencesHelper.getBankQrUri(this, code) != null

            cardBinding.tvCashierBankBadge.text = shortName
            cardBinding.tvCashierBankBadge.backgroundTintList = ColorStateList.valueOf(brandColor)
            cardBinding.tvCashierBankName.text = bankName

            if (hasQr) {
                if (currentCountry == CountryCode.AR && profile.phoneNumber.isNotBlank()) {
                    cardBinding.tvCashierBankSubtitle.text = "Alias / CVU: ${profile.phoneNumber} • Toca para QR 100%"
                } else {
                    cardBinding.tvCashierBankSubtitle.text = "Toca para mostrar QR a brillo 100%"
                }
                cardBinding.btnCardShowQr.text = "📸 Mostrar Código QR en Pantalla"
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
                cardBinding.btnCardDeleteQr.text = "🗑️ Quitar"
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
            } else {
                // Tarjeta sin QR (Cobro directo por Alias / CVU o datos de transferencia con 0% comisión)
                if (currentCountry == CountryCode.AR) {
                    val aliasOrCvu = profile.phoneNumber.ifBlank { profile.idCard }
                    if (aliasOrCvu.isNotBlank()) {
                        cardBinding.tvCashierBankSubtitle.text = "Cobro por Alias / CVU (0% comisión): $aliasOrCvu"
                        cardBinding.btnCardShowQr.text = "📋 Copiar Alias / CVU para Cobro"
                        cardBinding.btnCardShowQr.backgroundTintList = ColorStateList.valueOf(brandColor)
                        val copyAction = View.OnClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Alias / CVU", aliasOrCvu))
                            Toast.makeText(this, "📋 Alias / CVU copiado: $aliasOrCvu", Toast.LENGTH_SHORT).show()
                        }
                        cardBinding.cardCashierBank.setOnClickListener(copyAction)
                        cardBinding.btnCardShowQr.setOnClickListener(copyAction)
                    } else {
                        cardBinding.tvCashierBankSubtitle.text = "Configura tu Alias o CVU de Mercado Pago / Banco"
                        cardBinding.btnCardShowQr.text = "⚙️ Configurar Datos de Cobro"
                        cardBinding.btnCardShowQr.backgroundTintList = ColorStateList.valueOf(brandColor)
                        cardBinding.btnCardShowQr.setOnClickListener {
                            binding.bottomNavigation.selectedItemId = R.id.nav_settings
                        }
                    }
                } else {
                    val phoneOrDoc = profile.phoneNumber.ifBlank { profile.idCard }
                    if (phoneOrDoc.isNotBlank()) {
                        val label = if (currentCountry == CountryCode.VE) "Pago Móvil" else "Transferencia"
                        cardBinding.tvCashierBankSubtitle.text = "$label: $phoneOrDoc"
                        cardBinding.btnCardShowQr.text = "📋 Copiar Datos de Cobro"
                        cardBinding.btnCardShowQr.backgroundTintList = ColorStateList.valueOf(brandColor)
                        val copyAction = View.OnClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Datos de Cobro", phoneOrDoc))
                            Toast.makeText(this, "📋 Datos copiados: $phoneOrDoc", Toast.LENGTH_SHORT).show()
                        }
                        cardBinding.cardCashierBank.setOnClickListener(copyAction)
                        cardBinding.btnCardShowQr.setOnClickListener(copyAction)
                    } else {
                        cardBinding.tvCashierBankSubtitle.text = "Configura tus datos de cobro o sube un QR"
                        cardBinding.btnCardShowQr.text = "📸 Subir Código QR"
                        cardBinding.btnCardShowQr.backgroundTintList = ColorStateList.valueOf(brandColor)
                        cardBinding.btnCardShowQr.setOnClickListener {
                            launchCropForBank(code)
                        }
                    }
                }

                cardBinding.btnCardDeleteQr.text = "📸 Subir QR"
                cardBinding.btnCardDeleteQr.setTextColor(ContextCompat.getColor(this, R.color.accent_blue))
                cardBinding.btnCardDeleteQr.setOnClickListener {
                    launchCropForBank(code)
                }
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
            val hasQr = PreferencesHelper.getBankQrUri(this, activeCashierBankCode) != null
            val qrSuffix = if (hasQr) " con QR" else ""
            binding.tvCashierWhatsAppSubtitle.text = "Toca para digitar monto y enviar datos de $activeCashierBankName$qrSuffix"
        } else {
            binding.tvCashierWhatsAppSubtitle.text = "Toca para configurar tu banco y datos de cobro"
        }
    }

    /**
     * Muestra el modal de cobro por WhatsApp con teclado numérico integrado.
     */
    private fun showWhatsAppChargeDialog() {
        val configuredCodes = PreferencesHelper.getConfiguredBankCodes(this)
        val profile = PreferencesHelper.getUserProfile(this)
        if (activeCashierBankCode.isBlank() && profile.bankCode.isBlank() && configuredCodes.isEmpty()) {
            Toast.makeText(this, "⚙️ Por favor configura primero tu banco o datos de cobro en Ajustes", Toast.LENGTH_LONG).show()
            binding.bottomNavigation.selectedItemId = R.id.nav_settings
            return
        }

        val dialog = Dialog(this)
        val dialogBinding = DialogWhatsappChargeBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val countryProfile = PreferencesHelper.getCountryProfile(this)
        var wsBankCode = activeCashierBankCode
        var wsBankName = activeCashierBankName
        var isWsVesMode = true
        var wsInput = if (currentInput != "0") currentInput else "0"

        dialogBinding.tilWsClientPhone.hint = "Teléfono del cliente (opcional, ${countryProfile.phonePlaceholder})"
        dialogBinding.btnWsCurrencyVes.text = "${countryProfile.localCurrencySymbol} ${countryProfile.localCurrencyCode}"
        if (!countryProfile.supportsExchangeRate) {
            dialogBinding.toggleGroupWsCurrency.visibility = View.GONE
            isWsVesMode = true
        }

        fun updateWsDisplay() {
            dialogBinding.tvWsDisplayAmount.text = wsInput
            val isBinanceSelected = (wsBankCode == "BINANCE")

            if (isBinanceSelected) {
                dialogBinding.tvWsCurrencySymbol.text = "$"
                dialogBinding.tvWsConvertedAmount.text = "Cobro en Cripto (USDT • Binance Pay)"
            } else if (!countryProfile.supportsExchangeRate) {
                dialogBinding.tvWsCurrencySymbol.text = countryProfile.localCurrencySymbol
                dialogBinding.tvWsConvertedAmount.text = "${countryProfile.flagEmoji} ${countryProfile.displayName} (${countryProfile.localCurrencyCode})"
            } else {
                dialogBinding.tvWsCurrencySymbol.text = if (isWsVesMode) "Bs." else "$"
                val amount = wsInput.toDoubleOrNull() ?: 0.0
                if (isWsVesMode) {
                    val usd = if (profile.bcvRate > 0) amount / profile.bcvRate else 0.0
                    dialogBinding.tvWsConvertedAmount.text = String.format(Locale.US, "≈ $%.2f USD (Tasa BCV: %.2f)", usd, profile.bcvRate)
                } else {
                    val ves = amount * profile.bcvRate
                    dialogBinding.tvWsConvertedAmount.text = String.format(Locale.US, "≈ Bs. %.2f VES (Tasa BCV: %.2f)", ves, profile.bcvRate)
                }
            }

            if (isBinanceSelected) {
                val payId = PreferencesHelper.getBinancePayId(this)
                val displayId = if (payId.isNotBlank()) "Pay ID: $payId" else "Billetera Binance"
                dialogBinding.tvWsSelectedBankSubtitle.text = "🟡 $displayId (Cobro en USDT)"
            } else if (!countryProfile.supportsExchangeRate) {
                dialogBinding.tvWsSelectedBankSubtitle.text = "Datos de: $wsBankName (${countryProfile.flagEmoji} ${countryProfile.localCurrencyCode})"
            } else {
                dialogBinding.tvWsSelectedBankSubtitle.text = "Datos de: $wsBankName ($wsBankCode)"
            }
        }

        updateWsDisplay()

        val isMultibank = PlanCapabilityManager.canUseMultibank(this)
        val canWallets = PlanCapabilityManager.canUseWallets(this)
        val hasBinance = canWallets && PreferencesHelper.isBinanceEnabled(this) && PreferencesHelper.getBinanceQrUri(this) != null
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
            if (!countryProfile.supportsExchangeRate) {
                amountVes = amount
                amountUsd = amount
            } else if (isWsVesMode) {
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
                bankCode = wsBankCode,
                countryProfile = countryProfile
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
        val countryProfile = PreferencesHelper.getCountryProfile(this)
        val countryBanks = BankCatalog.getBanksForCountry(countryProfile.code)

        dialogBinding.tvSelectBankSubtitle.text = if (isMultibank) {
            "Elige un banco o billetera de ${countryProfile.flagEmoji} ${countryProfile.displayName} para activar:"
        } else {
            "Elige tu banco de ${countryProfile.flagEmoji} ${countryProfile.displayName} (Plan Básico: 1 banco activo):"
        }

        dialogBinding.btnSelectBankClose.setOnClickListener {
            dialog.dismiss()
        }

        countryBanks.forEach { bank ->
            val itemBinding = ItemBankSelectBinding.inflate(layoutInflater, dialogBinding.layoutBankListContainer, false)
            val brandColor = bank.getBrandColor()
            val shortName = bank.getShortName()

            itemBinding.tvBankItemBadge.text = shortName
            itemBinding.tvBankItemBadge.backgroundTintList = ColorStateList.valueOf(brandColor)
            itemBinding.tvBankItemName.text = bank.name
            itemBinding.tvBankItemCode.text = if (countryProfile.code == CountryCode.VE) {
                "Código Sudeban: ${bank.code}"
            } else {
                "${countryProfile.flagEmoji} ${countryProfile.displayName} • ${bank.shortTag}"
            }

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
        val countryProfile = PreferencesHelper.getCountryProfile(this)
        val input = EditText(this).apply {
            hint = "Monto en ${countryProfile.localCurrencyDisplay} (ej. 150.00)"
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
                val amountUsd = if (countryProfile.supportsExchangeRate && profile.bcvRate > 0) amount / profile.bcvRate else amount

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
        val countryProfile = PreferencesHelper.getCountryProfile(this)

        val input = EditText(this).apply {
            hint = "${countryProfile.phonePlaceholder} (o dejar vacío)"
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
                    bankCode = activeCashierBankCode,
                    countryProfile = countryProfile
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
            // 1. Siempre refrescar la tabla de Jornada y Libreta de inmediato
            loadTodayLedger()
            loadDebtors()

            // 2. Verificar si el permiso está otorgado en Ajustes de Android (no si la instancia en RAM se durmió)
            if (!PocketNotificationListenerService.isPermissionGranted(this)) {
                promptActivateNotificationListener()
                return@setOnClickListener
            }

            // 3. Si el permiso ya está concedido pero Android suspendió el servicio, reconectarlo sin pedir permisos
            if (!PocketNotificationListenerService.isServiceRunning()) {
                PocketNotificationListenerService.ensureServiceBound(this)
                binding.btnRescanNotifications.postDelayed({
                    val count = PocketNotificationListenerService.rescanActiveNotifications(playSound = true)
                    loadTodayLedger()
                    loadDebtors()
                    if (count > 0) {
                        Toast.makeText(this, "✅ Se sincronizaron $count cobro(s) de la barra de notificaciones", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "🔄 Jornada actualizada y escucha activa", Toast.LENGTH_SHORT).show()
                    }
                }, 650)
                return@setOnClickListener
            }

            val count = PocketNotificationListenerService.rescanActiveNotifications(playSound = true)
            if (count > 0) {
                Toast.makeText(this, "✅ Se sincronizaron $count cobro(s) de la barra de notificaciones", Toast.LENGTH_LONG).show()
                loadTodayLedger()
                loadDebtors()
            } else {
                Toast.makeText(this, "🔄 Jornada actualizada • Sin cobros nuevos en la barra", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // =========================================================================
    // MÓDULO LIBRETA DE CLIENTES / CUENTAS POR COBRAR Y SALDOS A FAVOR
    // =========================================================================

    private fun setupDebtorsModule() {
        val countryProfile = PreferencesHelper.getCountryProfile(this)
        val initialRate = if (countryProfile.supportsExchangeRate) PreferencesHelper.getUserProfile(this).bcvRate else 1.0
        debtorsAdapter = DebtorsAdapter(
            items = emptyList(),
            bcvRate = initialRate,
            onChargeClick = { debtor -> showAddChargeDialog(debtor) },
            onPaymentClick = { debtor -> showAddPaymentDialog(debtor) },
            onWhatsAppClick = { debtor -> sendWhatsAppReminderToDebtor(debtor) },
            onItemClick = { debtor -> showDebtorDetailDialog(debtor) }
        )

        binding.rvDebtors.layoutManager = LinearLayoutManager(this)
        binding.rvDebtors.adapter = debtorsAdapter

        binding.btnAddNewDebtor.setOnClickListener {
            showAddDebtorDialog()
        }

        binding.btnQuickFiar.setOnClickListener {
            onQuickFiarClicked()
        }

        binding.etDebtorSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterAndDisplayDebtors()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.chipGroupDebtorsFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chipFilterAll
            activeDebtorFilter = when (checkedId) {
                R.id.chipFilterDebt -> "DEBT"
                R.id.chipFilterCredit -> "CREDIT"
                R.id.chipFilterClear -> "CLEAR"
                else -> "ALL"
            }
            filterAndDisplayDebtors()
        }
    }

    private fun loadDebtors() {
        allDebtorsList = dbHelper.getAllDebtors()
        val summary = dbHelper.getDebtorsSummary()
        val profile = PreferencesHelper.getUserProfile(this)
        val countryProfile = PreferencesHelper.getCountryProfile(this)
        val rate = if (countryProfile.supportsExchangeRate && profile.bcvRate > 0) profile.bcvRate else 1.0

        binding.tvDebtorsTotalStreetUsd.text = if (countryProfile.supportsExchangeRate) {
            String.format(Locale.US, "$ %.2f", summary.totalDebtUsd)
        } else {
            String.format(Locale.US, "%s %.2f", countryProfile.localCurrencySymbol, summary.totalDebtUsd)
        }
        binding.tvDebtorsTotalStreetVes.text = if (countryProfile.supportsExchangeRate) {
            String.format(Locale.US, "≈ Bs. %.2f", summary.totalDebtUsd * rate)
        } else {
            "${countryProfile.flagEmoji} ${countryProfile.localCurrencyCode}"
        }

        binding.tvDebtorsTotalCreditUsd.text = if (countryProfile.supportsExchangeRate) {
            String.format(Locale.US, "$ %.2f", summary.totalCreditUsd)
        } else {
            String.format(Locale.US, "%s %.2f", countryProfile.localCurrencySymbol, summary.totalCreditUsd)
        }
        binding.tvDebtorsTotalCreditVes.text = if (countryProfile.supportsExchangeRate) {
            String.format(Locale.US, "≈ Bs. %.2f", summary.totalCreditUsd * rate)
        } else {
            "${countryProfile.flagEmoji} ${countryProfile.localCurrencyCode}"
        }

        filterAndDisplayDebtors()
    }

    private fun filterAndDisplayDebtors() {
        val query = binding.etDebtorSearch.text.toString().trim().lowercase(Locale.ROOT)
        val profile = PreferencesHelper.getUserProfile(this)

        val filtered = allDebtorsList.filter { debtor ->
            // Filtro por estado
            val matchesStatus = when (activeDebtorFilter) {
                "DEBT" -> debtor.isDebtor
                "CREDIT" -> debtor.hasCredit
                "CLEAR" -> debtor.isClear
                else -> true
            }
            if (!matchesStatus) return@filter false

            // Filtro por búsqueda
            if (query.isNotEmpty()) {
                val matchName = debtor.name.lowercase(Locale.ROOT).contains(query)
                val matchPhone = debtor.phone?.lowercase(Locale.ROOT)?.contains(query) == true
                val matchId = debtor.identification?.lowercase(Locale.ROOT)?.contains(query) == true
                if (!matchName && !matchPhone && !matchId) return@filter false
            }
            true
        }

        if (filtered.isEmpty()) {
            binding.rvDebtors.visibility = View.GONE
            binding.layoutEmptyDebtors.visibility = View.VISIBLE
        } else {
            binding.rvDebtors.visibility = View.VISIBLE
            binding.layoutEmptyDebtors.visibility = View.GONE
            val countryProfile = PreferencesHelper.getCountryProfile(this)
            val effectiveRate = if (countryProfile.supportsExchangeRate && profile.bcvRate > 0) profile.bcvRate else 1.0
            debtorsAdapter.updateData(filtered, effectiveRate)
        }
    }

    private fun showAddDebtorDialog() {
        val dialog = BottomSheetDialog(this)
        val dBinding = DialogAddDebtorBinding.inflate(layoutInflater)
        dialog.setContentView(dBinding.root)

        val countryProfile = PreferencesHelper.getCountryProfile(this)
        dBinding.tilNewDebtorPhone.hint = "Teléfono WhatsApp (ej. ${countryProfile.mobilePhonePlaceholder})"
        dBinding.tilNewDebtorId.hint = "${countryProfile.nationalIdLabel} (Opcional, ej. ${countryProfile.nationalIdPlaceholder})"

        dBinding.btnPickContactNewDebtor.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            launchPhoneContactPicker { pickedName, pickedPhone ->
                if (pickedName.isNotEmpty()) {
                    dBinding.etNewDebtorName.setText(pickedName)
                }
                if (pickedPhone.isNotEmpty()) {
                    dBinding.etNewDebtorPhone.setText(pickedPhone)
                }
                Toast.makeText(this, "📇 Datos cargados desde tus contactos", Toast.LENGTH_SHORT).show()
            }
        }

        dBinding.btnSaveNewDebtor.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val name = dBinding.etNewDebtorName.text.toString().trim()
            val phone = dBinding.etNewDebtorPhone.text.toString().trim().ifBlank { null }
            val idDoc = dBinding.etNewDebtorId.text.toString().trim().ifBlank { null }

            if (name.isEmpty()) {
                dBinding.etNewDebtorName.error = "Ingresa el nombre del cliente"
                return@setOnClickListener
            }

            val newDebtor = Debtor(
                name = name,
                phone = phone,
                identification = idDoc
            )
            val newId = dbHelper.insertDebtor(newDebtor)
            if (newId > 0) {
                Toast.makeText(this, "✅ Cliente registrado en la libreta", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                loadDebtors()
            } else {
                Toast.makeText(this, "Error guardando cliente", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun showAddChargeDialog(debtor: Debtor) {
        val dialog = BottomSheetDialog(this)
        val dBinding = DialogAddChargeBinding.inflate(layoutInflater)
        dialog.setContentView(dBinding.root)

        val profile = PreferencesHelper.getUserProfile(this)
        val countryProfile = PreferencesHelper.getCountryProfile(this)
        val rate = if (profile.bcvRate > 0) profile.bcvRate else 1.0
        var isChargeVesMode = false // Por defecto $ USD, seleccionable a Bs. Bolívares

        dBinding.tvChargeClientName.text = "Cliente: ${debtor.name}"
        dBinding.btnChargeCurrencyVes.text = "${countryProfile.localCurrencySymbol} ${countryProfile.localCurrencyCode}"

        if (!countryProfile.supportsExchangeRate) {
            dBinding.toggleChargeCurrency.visibility = View.GONE
            dBinding.tilChargeAmount.hint = "Monto a Fiar en ${countryProfile.localCurrencyCode} (${countryProfile.localCurrencySymbol}) *"
            dBinding.tvChargeEquivBs.visibility = View.GONE
            dBinding.tvChargeBcvNotice.visibility = View.GONE
        } else {
            dBinding.tvChargeEquivBs.visibility = View.VISIBLE
            dBinding.tvChargeBcvNotice.visibility = View.VISIBLE
        }

        if (debtor.hasCredit) {
            dBinding.layoutCreditNoticeBanner.visibility = View.VISIBLE
            dBinding.tvCreditNoticeText.text = if (countryProfile.supportsExchangeRate) {
                String.format(
                    Locale.US,
                    "🟢 Este cliente tiene $%.2f de saldo a favor. Al fiar, se descontará de este saldo.",
                    debtor.creditAmountUsd
                )
            } else {
                String.format(
                    Locale.US,
                    "🟢 Este cliente tiene %s %.2f de saldo a favor. Al fiar, se descontará de este saldo.",
                    countryProfile.localCurrencySymbol,
                    debtor.creditAmountUsd
                )
            }
        } else {
            dBinding.layoutCreditNoticeBanner.visibility = View.GONE
        }

        fun updateChargeConversion() {
            if (!countryProfile.supportsExchangeRate) return
            val rawInput = dBinding.etChargeAmountUsd.text?.toString()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
            if (isChargeVesMode) {
                val equivUsd = rawInput / rate
                dBinding.tvChargeEquivBs.text = String.format(
                    Locale.US,
                    "≈ $%.2f USD (Tasa BCV: %.2f)",
                    equivUsd,
                    rate
                )
            } else {
                val equivBs = rawInput * rate
                dBinding.tvChargeEquivBs.text = String.format(
                    Locale.US,
                    "≈ Bs. %.2f (Tasa BCV: %.2f)",
                    equivBs,
                    rate
                )
            }
        }

        dBinding.toggleChargeCurrency.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isChargeVesMode = (checkedId == R.id.btnChargeCurrencyVes)
                dBinding.tilChargeAmount.hint = if (isChargeVesMode) {
                    "Monto a Fiar en ${countryProfile.localCurrencyVoiceName.replaceFirstChar { it.uppercase() }} (${countryProfile.localCurrencySymbol}) *"
                } else {
                    "Monto a Fiar en Dólares ($ USD) *"
                }
                updateChargeConversion()
            }
        }

        dBinding.etChargeAmountUsd.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateChargeConversion()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        dBinding.btnConfirmCharge.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val rawInput = dBinding.etChargeAmountUsd.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            val concept = dBinding.etChargeConcept.text.toString().trim()

            if (rawInput <= 0.0) {
                dBinding.etChargeAmountUsd.error = "Ingresa un monto válido"
                return@setOnClickListener
            }

            val amountUsd: Double
            val amountVes: Double
            if (!countryProfile.supportsExchangeRate) {
                amountUsd = rawInput
                amountVes = rawInput
            } else if (isChargeVesMode) {
                amountVes = rawInput
                amountUsd = rawInput / rate
            } else {
                amountUsd = rawInput
                amountVes = rawInput * rate
            }

            dbHelper.addCharge(
                debtorId = debtor.id,
                amountUsd = amountUsd,
                amountVes = amountVes,
                bcvRate = if (countryProfile.supportsExchangeRate) rate else 1.0,
                notes = concept.ifBlank { "Compra fiada" }
            )

            val toastAmount = if (countryProfile.supportsExchangeRate) {
                String.format(Locale.US, "$%.2f", amountUsd)
            } else {
                String.format(Locale.US, "%s %.2f", countryProfile.localCurrencySymbol, amountUsd)
            }
            Toast.makeText(
                this,
                "✅ Fiado registrado a ${debtor.name} ($toastAmount)",
                Toast.LENGTH_SHORT
            ).show()
            dialog.dismiss()
            loadDebtors()
        }

        dialog.show()
    }

    private fun showAddPaymentDialog(debtor: Debtor) {
        val dialog = BottomSheetDialog(this)
        val dBinding = DialogAddPaymentBinding.inflate(layoutInflater)
        dialog.setContentView(dBinding.root)

        val profile = PreferencesHelper.getUserProfile(this)
        val countryProfile = PreferencesHelper.getCountryProfile(this)
        val rate = if (countryProfile.supportsExchangeRate && profile.bcvRate > 0) profile.bcvRate else 1.0
        var isPaymentVesMode = false

        dBinding.tvPaymentClientName.text = "Cliente: ${debtor.name}"
        dBinding.btnPaymentCurrencyVes.text = "${countryProfile.localCurrencySymbol} ${countryProfile.localCurrencyCode}"

        if (!countryProfile.supportsExchangeRate) {
            dBinding.togglePaymentCurrency.visibility = View.GONE
            dBinding.tilPaymentAmount.hint = "Monto a Abonar en ${countryProfile.localCurrencyCode} (${countryProfile.localCurrencySymbol}) *"
            dBinding.tvPaymentEquivBs.visibility = View.GONE
        } else {
            dBinding.tvPaymentEquivBs.visibility = View.VISIBLE
        }

        val refHint = when (countryProfile.code) {
            CountryCode.VE -> "Nota / Referencia (Opcional, ej. Pago Móvil o Efectivo)"
            CountryCode.CO -> "Nota / Referencia (Opcional, ej. Transferencia, Nequi o Efectivo)"
            CountryCode.AR -> "Nota / Referencia (Opcional, ej. Transferencia, Alias o Efectivo)"
        }
        dBinding.tilPaymentNote.hint = refHint

        if (debtor.isDebtor) {
            dBinding.tvPaymentCurrentDebt.text = if (countryProfile.supportsExchangeRate) {
                String.format(
                    Locale.US,
                    "Deuda actual: $%.2f USD (≈ Bs. %.2f BCV)",
                    debtor.debtAmountUsd,
                    debtor.getEquivalentVes(rate)
                )
            } else {
                String.format(
                    Locale.US,
                    "Deuda actual: %s %.2f (%s)",
                    countryProfile.localCurrencySymbol,
                    debtor.debtAmountUsd,
                    countryProfile.localCurrencyCode
                )
            }
            dBinding.tvPaymentCurrentDebt.setTextColor(Color.parseColor("#B91C1C"))
        } else if (debtor.hasCredit) {
            dBinding.tvPaymentCurrentDebt.text = if (countryProfile.supportsExchangeRate) {
                String.format(
                    Locale.US,
                    "Saldo a favor actual: $%.2f USD (≈ Bs. %.2f)",
                    debtor.creditAmountUsd,
                    debtor.getEquivalentVes(rate)
                )
            } else {
                String.format(
                    Locale.US,
                    "Saldo a favor actual: %s %.2f (%s)",
                    countryProfile.localCurrencySymbol,
                    debtor.creditAmountUsd,
                    countryProfile.localCurrencyCode
                )
            }
            dBinding.tvPaymentCurrentDebt.setTextColor(Color.parseColor("#15803D"))
        } else {
            dBinding.tvPaymentCurrentDebt.text = if (countryProfile.supportsExchangeRate) {
                "Cuenta al día ($0.00)"
            } else {
                "Cuenta al día (${countryProfile.localCurrencySymbol} 0.00)"
            }
            dBinding.tvPaymentCurrentDebt.setTextColor(Color.parseColor("#334155"))
        }

        fun updatePaymentConversion() {
            val rawInput = dBinding.etPaymentAmountUsd.text?.toString()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
            val inputUsd: Double
            if (isPaymentVesMode && countryProfile.supportsExchangeRate) {
                inputUsd = rawInput / rate
                dBinding.tvPaymentEquivBs.text = String.format(Locale.US, "≈ $%.2f USD (Tasa BCV: %.2f)", inputUsd, rate)
            } else {
                inputUsd = rawInput
                if (countryProfile.supportsExchangeRate) {
                    val equivBs = rawInput * rate
                    dBinding.tvPaymentEquivBs.text = String.format(Locale.US, "≈ Bs. %.2f (Tasa BCV: %.2f)", equivBs, rate)
                }
            }

            if (debtor.isDebtor && inputUsd > debtor.debtAmountUsd + 0.009) {
                val excess = inputUsd - debtor.debtAmountUsd
                dBinding.tvPaymentExcessHint.text = if (countryProfile.supportsExchangeRate) {
                    String.format(
                        Locale.US,
                        "💡 El excedente de $%.2f quedará como Saldo a Favor y el total entrará a Jornada.",
                        excess
                    )
                } else {
                    String.format(
                        Locale.US,
                        "💡 El excedente de %s %.2f quedará como Saldo a Favor y el total entrará a Jornada.",
                        countryProfile.localCurrencySymbol,
                        excess
                    )
                }
            } else {
                dBinding.tvPaymentExcessHint.text = "🟢 Este abono se registrará como entrada de dinero en tu Jornada de hoy para el reporte final."
            }
        }

        dBinding.togglePaymentCurrency.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isPaymentVesMode = (checkedId == R.id.btnPaymentCurrencyVes)
                dBinding.tilPaymentAmount.hint = if (isPaymentVesMode) {
                    "Monto a Abonar en ${countryProfile.localCurrencyVoiceName.replaceFirstChar { it.uppercase() }} (${countryProfile.localCurrencySymbol}) *"
                } else {
                    "Monto a Abonar en Dólares ($ USD) *"
                }
                updatePaymentConversion()
            }
        }

        dBinding.etPaymentAmountUsd.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updatePaymentConversion()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        dBinding.btnConfirmPayment.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val rawInput = dBinding.etPaymentAmountUsd.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            val note = dBinding.etPaymentNote.text.toString().trim()

            if (rawInput <= 0.0) {
                dBinding.etPaymentAmountUsd.error = "Ingresa un monto válido"
                return@setOnClickListener
            }

            val amountUsd: Double
            val amountVes: Double
            if (!countryProfile.supportsExchangeRate) {
                amountUsd = rawInput
                amountVes = rawInput
            } else if (isPaymentVesMode) {
                amountVes = rawInput
                // Si el pago en Bs cubre exactamente la deuda base o el monto de conciliación, saldar la deuda completa sin residuo de redondeo
                val baseDebtVes = debtor.getEquivalentVes(rate)
                val concilDebtVes = debtor.getConciliationVes(rate)
                amountUsd = if (debtor.isDebtor && (Math.abs(rawInput - baseDebtVes) < 0.05 || Math.abs(rawInput - concilDebtVes) < 0.05)) {
                    debtor.debtAmountUsd
                } else {
                    rawInput / rate
                }
            } else {
                amountUsd = rawInput
                amountVes = rawInput * rate
            }

            val cleanNote = note.ifBlank { "Abono a cuenta" }

            // 1. Registrar en Jornada como Entrada de Dinero (INCOME) para que el reporte del día lo considere
            val tx = Transaction(
                amount = amountVes,
                amountUsd = amountUsd,
                reference = "",
                senderName = debtor.name,
                type = TransactionType.INCOME,
                category = TransactionCategories.INCOME_ABONO_FIADO,
                note = "Abono Fiado (${debtor.name}): $cleanNote",
                timestamp = System.currentTimeMillis(),
                isManual = true
            )
            val insertedTxId = dbHelper.insertTransaction(tx)

            // 2. Registrar el abono en la Libreta vinculado a la transacción de Jornada
            dbHelper.addPayment(
                debtorId = debtor.id,
                amountUsd = amountUsd,
                amountVes = amountVes,
                bcvRate = if (countryProfile.supportsExchangeRate) rate else 1.0,
                matchedTxId = if (insertedTxId > 0L) insertedTxId else null,
                notes = cleanNote
            )

            Toast.makeText(this, "✅ Abono de ${debtor.name} registrado y sumado a Jornada", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            loadDebtors()
            loadTodayLedger()
        }

        dialog.show()
    }

    private fun sendWhatsAppReminderToDebtor(debtor: Debtor) {
        val profile = PreferencesHelper.getUserProfile(this)
        val countryProfile = PreferencesHelper.getCountryProfile(this)
        val phone = debtor.phone

        if (phone.isNullOrBlank()) {
            Toast.makeText(this, "Este cliente no tiene número de teléfono registrado", Toast.LENGTH_LONG).show()
            return
        }

        val rate = if (countryProfile.supportsExchangeRate && profile.bcvRate > 0) profile.bcvRate else 1.0
        val targetBankCode = activeCashierBankCode.ifBlank { profile.bankCode }
        val targetBankName = activeCashierBankName.ifBlank { profile.bankName }
        val qrUriString = PreferencesHelper.getBankQrUri(this, targetBankCode)
        val qrUri = qrUriString?.let { uriStr ->
            val uri = Uri.parse(uriStr)
            val path = uri.path ?: uriStr
            if (java.io.File(path).exists()) uri else null
        }
        val autoConcilAllowed = PlanCapabilityManager.canUseAutoConciliation(this)

        if (debtor.isDebtor) {
            val totalUsd = debtor.debtAmountUsd
            val totalVes = debtor.getEquivalentVes(rate)
            val totalConcilVes = if (autoConcilAllowed) debtor.getConciliationVes(rate) else totalVes

            val firstOption = if (countryProfile.supportsExchangeRate) {
                String.format(
                    Locale.US,
                    "💵 Cobrar Deuda Total ($%.2f USD → Bs. %.2f)",
                    totalUsd,
                    totalConcilVes
                )
            } else {
                String.format(
                    Locale.US,
                    "💵 Cobrar Deuda Total (%s %.2f)",
                    countryProfile.localCurrencySymbol,
                    totalConcilVes
                )
            }

            val options = arrayOf(
                firstOption,
                "🤝 Acordar Abono Parcial (Monto específico)"
            )

            AlertDialog.Builder(this)
                .setTitle("💬 Cobrar a ${debtor.name}")
                .setItems(options) { _, which ->
                    if (which == 0) {
                        if (autoConcilAllowed) {
                            PreferencesHelper.savePendingDebtorConciliation(this, debtor.id, totalConcilVes, totalUsd)
                        }
                        val msg = WhatsAppHelper.generateDebtReminderMessage(
                            profile = profile,
                            debtorName = debtor.name,
                            balanceUsd = debtor.debtAmountUsd,
                            bcvRate = rate,
                            bankName = targetBankName,
                            bankCode = targetBankCode,
                            conciliationCents = debtor.conciliationCents,
                            requestedAmountUsd = null,
                            includeConciliationCents = autoConcilAllowed,
                            countryProfile = countryProfile,
                            hasQr = (qrUri != null)
                        )
                        WhatsAppHelper.sendViaWhatsApp(this, phone, msg, qrUri)
                    } else {
                        showPartialWhatsAppChargeDialog(debtor, rate, targetBankName, targetBankCode, qrUri, phone)
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        } else if (debtor.hasCredit) {
            val msg = WhatsAppHelper.generateCreditNoticeMessage(
                profile = profile,
                debtorName = debtor.name,
                creditUsd = debtor.creditAmountUsd,
                bcvRate = rate,
                countryProfile = countryProfile
            )
            WhatsAppHelper.sendViaWhatsApp(this, phone, msg, null)
        } else {
            Toast.makeText(this, "El cliente está al día (${countryProfile.localCurrencySymbol} 0.00)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPartialWhatsAppChargeDialog(
        debtor: Debtor,
        rate: Double,
        targetBankName: String,
        targetBankCode: String,
        qrUri: Uri?,
        phone: String
    ) {
        val profile = PreferencesHelper.getUserProfile(this)
        val countryProfile = PreferencesHelper.getCountryProfile(this)
        val autoConcilAllowed = PlanCapabilityManager.canUseAutoConciliation(this)

        val input = EditText(this).apply {
            hint = if (countryProfile.supportsExchangeRate) {
                String.format(Locale.US, "Monto en $ USD (Deuda: $%.2f)", debtor.debtAmountUsd)
            } else {
                String.format(Locale.US, "Monto en %s (Deuda: %s %.2f)", countryProfile.localCurrencyCode, countryProfile.localCurrencySymbol, debtor.debtAmountUsd)
            }
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(48, 36, 48, 36)
        }

        val concilNotice = if (autoConcilAllowed && countryProfile.supportsExchangeRate) {
            "\n\n✨ Se sumarán +${countryProfile.localCurrencySymbol} ${String.format(Locale.US, "%.2f", debtor.conciliationCents)} de centavos de conciliación para descontar automáticamente al llegar el pago."
        } else ""

        val currencyLabel = if (countryProfile.supportsExchangeRate) "dólares ($ USD)" else "${countryProfile.localCurrencyDisplay}"

        AlertDialog.Builder(this)
            .setTitle("🤝 Acordar Abono Parcial")
            .setMessage("Ingresa el monto en $currencyLabel que ${debtor.name} acordó abonar hoy:$concilNotice")
            .setView(input)
            .setPositiveButton("Enviar por WhatsApp") { _, _ ->
                val raw = input.text.toString().replace(",", ".").trim()
                val partialUsd = raw.toDoubleOrNull() ?: 0.0
                if (partialUsd <= 0.0) {
                    Toast.makeText(this, "Ingresa un monto válido mayor a 0", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val baseVes = if (countryProfile.supportsExchangeRate) Math.round(partialUsd * rate * 100.0) / 100.0 else partialUsd
                val exactConcilVes = if (autoConcilAllowed && countryProfile.supportsExchangeRate) {
                    Math.round((baseVes + debtor.conciliationCents) * 100.0) / 100.0
                } else baseVes

                if (autoConcilAllowed && countryProfile.supportsExchangeRate) {
                    PreferencesHelper.savePendingDebtorConciliation(this, debtor.id, exactConcilVes, partialUsd)
                }

                val msg = WhatsAppHelper.generateDebtReminderMessage(
                    profile = profile,
                    debtorName = debtor.name,
                    balanceUsd = debtor.debtAmountUsd,
                    bcvRate = rate,
                    bankName = targetBankName,
                    bankCode = targetBankCode,
                    conciliationCents = debtor.conciliationCents,
                    requestedAmountUsd = partialUsd,
                    includeConciliationCents = autoConcilAllowed,
                    countryProfile = countryProfile,
                    hasQr = (qrUri != null)
                )
                WhatsAppHelper.sendViaWhatsApp(this, phone, msg, qrUri)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showDebtorDetailDialog(debtor: Debtor) {
        val dialog = Dialog(this)
        val dBinding = DialogDebtorDetailBinding.inflate(layoutInflater)
        dialog.setContentView(dBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val profile = PreferencesHelper.getUserProfile(this)
        val countryProfile = PreferencesHelper.getCountryProfile(this)
        val rate = if (countryProfile.supportsExchangeRate && profile.bcvRate > 0) profile.bcvRate else 1.0

        dBinding.tvDetailDebtorName.text = debtor.name
        val phone = debtor.phone ?: "Sin WhatsApp"
        val idDoc = if (!debtor.identification.isNullOrBlank()) " • ${debtor.identification}" else ""
        val concilText = if (debtor.isDebtor) {
            if (countryProfile.supportsExchangeRate) {
                String.format(
                    Locale.US,
                    "\nBs. %.2f (BCV) • Monto exacto a conciliar: Bs. %.2f",
                    debtor.getEquivalentVes(rate),
                    debtor.getConciliationVes(rate)
                )
            } else {
                String.format(
                    Locale.US,
                    "\nTotal adeudado: %s %.2f (%s)",
                    countryProfile.localCurrencySymbol,
                    debtor.debtAmountUsd,
                    countryProfile.localCurrencyCode
                )
            }
        } else ""
        dBinding.tvDetailDebtorMeta.text = "$phone$idDoc$concilText"

        when {
            debtor.isDebtor -> {
                dBinding.tvDetailBalanceBadge.text = if (countryProfile.supportsExchangeRate) {
                    String.format(Locale.US, "🔴 Debe $%.2f", debtor.debtAmountUsd)
                } else {
                    String.format(Locale.US, "🔴 Debe %s %.2f", countryProfile.localCurrencySymbol, debtor.debtAmountUsd)
                }
                dBinding.tvDetailBalanceBadge.setTextColor(Color.parseColor("#C62828"))
                dBinding.tvDetailBalanceBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFEBEE"))
                dBinding.btnDetailShareWhatsApp.text = if (countryProfile.supportsExchangeRate) {
                    val hasQr = !PreferencesHelper.getBankQrUri(this, profile.bankCode).isNullOrEmpty()
                    if (hasQr) "💬 Cobrar con QR" else "💬 Cobrar por WhatsApp"
                } else {
                    "💬 Cobrar por WhatsApp"
                }
            }
            debtor.hasCredit -> {
                dBinding.tvDetailBalanceBadge.text = if (countryProfile.supportsExchangeRate) {
                    String.format(Locale.US, "🟢 A favor $%.2f", debtor.creditAmountUsd)
                } else {
                    String.format(Locale.US, "🟢 A favor %s %.2f", countryProfile.localCurrencySymbol, debtor.creditAmountUsd)
                }
                dBinding.tvDetailBalanceBadge.setTextColor(Color.parseColor("#2E7D32"))
                dBinding.tvDetailBalanceBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8F5E9"))
                dBinding.btnDetailShareWhatsApp.text = "💬 Avisar por WhatsApp"
            }
            else -> {
                dBinding.tvDetailBalanceBadge.text = "⚪ Al Día (${countryProfile.localCurrencySymbol} 0.00)"
                dBinding.tvDetailBalanceBadge.setTextColor(Color.parseColor("#5E6C84"))
                dBinding.tvDetailBalanceBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F4F5F7"))
                dBinding.btnDetailShareWhatsApp.visibility = View.GONE
            }
        }

        // Cargar movimientos cronológicos
        val entries = dbHelper.getLedgerEntries(debtor.id)
        dBinding.layoutLedgerEntriesContainer.removeAllViews()

        if (entries.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Sin movimientos registrados aún"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                textSize = 13f
                setPadding(0, 20, 0, 20)
            }
            dBinding.layoutLedgerEntriesContainer.addView(emptyTv)
        } else {
            val dateFormat = SimpleDateFormat("dd/MM/yy HH:mm", Locale.US)
            entries.forEach { entry ->
                val rowBinding = ItemLedgerEntryBinding.inflate(layoutInflater, dBinding.layoutLedgerEntriesContainer, false)
                val isCharge = entry.type == LedgerEntryType.CHARGE

                rowBinding.tvEntryTypeIcon.text = if (isCharge) "📦" else "💵"
                rowBinding.tvEntryConcept.text = entry.notes ?: if (isCharge) "Compra fiada" else "Abono / Pago"
                rowBinding.tvEntryDate.text = dateFormat.format(Date(entry.timestamp))

                if (isCharge) {
                    rowBinding.tvEntryAmountUsd.text = if (countryProfile.supportsExchangeRate) {
                        String.format(Locale.US, "-$%.2f", entry.amountUsd)
                    } else {
                        String.format(Locale.US, "-%s %.2f", countryProfile.localCurrencySymbol, entry.amountUsd)
                    }
                    rowBinding.tvEntryAmountUsd.setTextColor(Color.parseColor("#DE350B"))
                    rowBinding.tvEntryAmountBs.text = if (countryProfile.supportsExchangeRate) {
                        String.format(Locale.US, "Bs. %.2f", entry.amountVes)
                    } else {
                        countryProfile.localCurrencyCode
                    }
                } else {
                    rowBinding.tvEntryAmountUsd.text = if (countryProfile.supportsExchangeRate) {
                        String.format(Locale.US, "+$%.2f", entry.amountUsd)
                    } else {
                        String.format(Locale.US, "+%s %.2f", countryProfile.localCurrencySymbol, entry.amountUsd)
                    }
                    rowBinding.tvEntryAmountUsd.setTextColor(Color.parseColor("#00875A"))
                    rowBinding.tvEntryAmountBs.text = if (countryProfile.supportsExchangeRate) {
                        String.format(Locale.US, "Bs. %.2f", entry.amountVes)
                    } else {
                        countryProfile.localCurrencyCode
                    }
                }
                dBinding.layoutLedgerEntriesContainer.addView(rowBinding.root)
            }
        }

        // Estado del botón de Estado de Cuenta PDF según plan
        if (PlanCapabilityManager.canExportDebtorPdf(this)) {
            dBinding.btnDetailSharePdf.text = "📄 Enviar Estado de Cuenta Detallado (PDF)"
        } else {
            dBinding.btnDetailSharePdf.text = "📄 Estado de Cuenta Detallado en PDF (PRO ⚡)"
        }

        dBinding.btnDetailSharePdf.setOnClickListener {
            if (!PlanCapabilityManager.canExportDebtorPdf(this)) {
                dialog.dismiss()
                showProUpgradeDialog("el envío de Estados de Cuenta detallados en PDF")
                return@setOnClickListener
            }
            val pdfFile = ReportHelper.generateDebtorStatementPdf(this, profile, debtor, entries)
            if (pdfFile != null) {
                ReportHelper.shareDebtorStatementPdf(this, pdfFile, debtor.name, debtor.phone)
            } else {
                Toast.makeText(this, "No se pudo generar el PDF del estado de cuenta", Toast.LENGTH_SHORT).show()
            }
        }

        dBinding.btnDetailEdit.setOnClickListener {
            showEditDebtorDialog(debtor, dialog)
        }

        dBinding.btnDetailDelete.setOnClickListener {
            confirmAndDeleteDebtor(debtor, dialog)
        }

        dBinding.btnDetailClose.setOnClickListener { dialog.dismiss() }
        dBinding.btnDetailShareWhatsApp.setOnClickListener {
            dialog.dismiss()
            sendWhatsAppReminderToDebtor(debtor)
        }

        dialog.show()
    }

    /**
     * Muestra el modal para editar datos del cliente (Nombre, Teléfono, Cédula/RIF).
     * Exige autorización de seguridad (Huella digital o PIN) para guardar cualquier cambio.
     */
    private fun showEditDebtorDialog(debtor: Debtor, parentDetailDialog: Dialog) {
        val dialog = Dialog(this)
        val dBinding = DialogEditDebtorBinding.inflate(layoutInflater)
        dialog.setContentView(dBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val countryProfile = PreferencesHelper.getCountryProfile(this)
        dBinding.tilEditDebtorPhone.hint = "Teléfono WhatsApp (ej. ${countryProfile.mobilePhonePlaceholder})"
        dBinding.tilEditDebtorId.hint = "${countryProfile.nationalIdLabel} (Opcional, ej. ${countryProfile.nationalIdPlaceholder})"

        dBinding.etEditDebtorName.setText(debtor.name)
        dBinding.etEditDebtorPhone.setText(debtor.phone ?: "")
        dBinding.etEditDebtorId.setText(debtor.identification ?: "")

        dBinding.btnPickContactEditDebtor.setOnClickListener {
            launchPhoneContactPicker { pickedName, pickedPhone ->
                if (pickedName.isNotEmpty()) {
                    dBinding.etEditDebtorName.setText(pickedName)
                }
                if (pickedPhone.isNotEmpty()) {
                    dBinding.etEditDebtorPhone.setText(pickedPhone)
                }
                Toast.makeText(this, "📇 Datos cargados desde tus contactos", Toast.LENGTH_SHORT).show()
            }
        }

        dBinding.btnCancelEditDebtor.setOnClickListener { dialog.dismiss() }

        dBinding.btnSaveEditDebtor.setOnClickListener {
            val newName = dBinding.etEditDebtorName.text.toString().trim()
            val newPhone = dBinding.etEditDebtorPhone.text.toString().trim().ifBlank { null }
            val newIdDoc = dBinding.etEditDebtorId.text.toString().trim().ifBlank { null }

            if (newName.isEmpty()) {
                dBinding.etEditDebtorName.error = "Ingresa el nombre del cliente"
                return@setOnClickListener
            }

            val hasChanges = (newName != debtor.name) ||
                    (newPhone != debtor.phone) ||
                    (newIdDoc != debtor.identification)

            if (!hasChanges) {
                Toast.makeText(this, "No se realizaron cambios", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                return@setOnClickListener
            }

            requireSecurityAuth(
                actionTitle = "🔒 Modificar Cliente",
                description = "Coloca tu huella digital o ingresa el PIN para guardar los cambios de $newName"
            ) {
                val updatedDebtor = debtor.copy(
                    name = newName,
                    phone = newPhone,
                    identification = newIdDoc
                )
                val success = dbHelper.updateDebtor(updatedDebtor)
                if (success) {
                    Toast.makeText(this, "✅ Datos del cliente actualizados con éxito", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    parentDetailDialog.dismiss()
                    loadDebtors()
                    // Mostrar detalle con la información actualizada
                    showDebtorDetailDialog(updatedDebtor)
                } else {
                    Toast.makeText(this, "Error al actualizar los datos del cliente", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
    }

    /**
     * Muestra alerta de advertencia y exige confirmación de seguridad (Huella o PIN)
     * antes de eliminar un cliente de la libreta, previniendo borrados accidentales o no autorizados.
     */
    private fun confirmAndDeleteDebtor(debtor: Debtor, parentDetailDialog: Dialog) {
        val profile = PreferencesHelper.getUserProfile(this)
        val countryProfile = PreferencesHelper.getCountryProfile(this)
        val rate = if (countryProfile.supportsExchangeRate && profile.bcvRate > 0) profile.bcvRate else 1.0

        val alertMessage = when {
            debtor.isDebtor -> {
                val debtText = if (countryProfile.supportsExchangeRate) {
                    val debtVes = debtor.getEquivalentVes(rate)
                    "$${String.format(Locale.US, "%.2f", debtor.debtAmountUsd)} (≈ Bs. ${String.format(Locale.US, "%.2f", debtVes)})"
                } else {
                    "${countryProfile.localCurrencySymbol} ${String.format(Locale.US, "%.2f", debtor.debtAmountUsd)}"
                }
                "⚠️ ATENCIÓN: Este cliente tiene una deuda pendiente de:\n\n" +
                        "• $debtText\n\n" +
                        "Si lo eliminas de la libreta, se borrará su saldo deudor y todo su historial de fiados y pagos.\n\n" +
                        "¿Estás seguro de que deseas eliminar a \"${debtor.name}\"?"
            }
            debtor.hasCredit -> {
                val creditText = if (countryProfile.supportsExchangeRate) {
                    val creditVes = debtor.getEquivalentVes(rate)
                    "$${String.format(Locale.US, "%.2f", debtor.creditAmountUsd)} (≈ Bs. ${String.format(Locale.US, "%.2f", creditVes)})"
                } else {
                    "${countryProfile.localCurrencySymbol} ${String.format(Locale.US, "%.2f", debtor.creditAmountUsd)}"
                }
                "⚠️ ATENCIÓN: Este cliente tiene un saldo a favor de:\n\n" +
                        "• $creditText\n\n" +
                        "Si lo eliminas, se borrará este crédito disponible y sus registros.\n\n" +
                        "¿Estás seguro de que deseas eliminar a \"${debtor.name}\"?"
            }
            else -> {
                "¿Estás seguro de que deseas eliminar a \"${debtor.name}\" de tu libreta?\n\n" +
                        "Se borrará su registro y el historial de movimientos de manera definitiva."
            }
        }

        AlertDialog.Builder(this)
            .setTitle("🗑️ ¿Eliminar Cliente?")
            .setMessage(alertMessage)
            .setPositiveButton("Sí, Eliminar") { _, _ ->
                requireSecurityAuth(
                    actionTitle = "🔒 Eliminar Cliente",
                    description = "Coloca tu huella digital o ingresa el PIN para autorizar la eliminación de ${debtor.name}"
                ) {
                    val success = dbHelper.deleteDebtor(debtor.id)
                    if (success) {
                        Toast.makeText(this, "🗑️ ${debtor.name} eliminado de la libreta", Toast.LENGTH_SHORT).show()
                        parentDetailDialog.dismiss()
                        loadDebtors()
                    } else {
                        Toast.makeText(this, "Error al eliminar el cliente de la libreta", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun onQuickFiarClicked() {
        val actualAmount = currentInput.toDoubleOrNull() ?: 0.0

        if (actualAmount <= 0.0) {
            Toast.makeText(this, "Ingresa un monto en la caja primero", Toast.LENGTH_SHORT).show()
            return
        }

        val profile = PreferencesHelper.getUserProfile(this)
        val countryProfile = PreferencesHelper.getCountryProfile(this)
        val rate = if (countryProfile.supportsExchangeRate && profile.bcvRate > 0) profile.bcvRate else 1.0

        // Calcular equivalencias según modo VES / USD actual
        val (amountUsd, amountVes) = if (!countryProfile.supportsExchangeRate) {
            Pair(actualAmount, actualAmount)
        } else if (isVesMode) {
            Pair(actualAmount / rate, actualAmount)
        } else {
            Pair(actualAmount, actualAmount * rate)
        }

        val dialog = Dialog(this)
        val dBinding = DialogQuickFiarSelectBinding.inflate(layoutInflater)
        dialog.setContentView(dBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        dBinding.tvQuickFiarAmountSummary.text = if (countryProfile.supportsExchangeRate) {
            String.format(
                Locale.US,
                "Monto a fiar: $%.2f USD (Bs. %.2f)",
                amountUsd,
                amountVes
            )
        } else {
            String.format(
                Locale.US,
                "Monto a fiar: %s %.2f (%s)",
                countryProfile.localCurrencySymbol,
                amountVes,
                countryProfile.localCurrencyCode
            )
        }

        // Configurar autocompletado de clientes existentes
        val existingDebtors = dbHelper.getAllDebtors()
        val names = existingDebtors.map { it.name }
        val nameAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, names)
        dBinding.actvQuickFiarClientName.setAdapter(nameAdapter)

        dBinding.btnConfirmQuickFiar.setOnClickListener {
            val enteredName = dBinding.actvQuickFiarClientName.text.toString().trim()
            val concept = dBinding.etQuickFiarConcept.text.toString().trim()

            if (enteredName.isEmpty()) {
                dBinding.actvQuickFiarClientName.error = "Ingresa el nombre del cliente"
                return@setOnClickListener
            }

            // Buscar si ya existe cliente con este nombre
            var targetDebtor = existingDebtors.find { it.name.equals(enteredName, ignoreCase = true) }
            if (targetDebtor == null) {
                val newId = dbHelper.insertDebtor(Debtor(name = enteredName))
                targetDebtor = dbHelper.getDebtorById(newId)
            }

            if (targetDebtor != null) {
                dbHelper.addCharge(
                    debtorId = targetDebtor.id,
                    amountUsd = amountUsd,
                    amountVes = amountVes,
                    bcvRate = if (countryProfile.supportsExchangeRate) rate else 1.0,
                    notes = concept.ifBlank { "Fiado desde caja" }
                )
                Toast.makeText(this, "✅ Fiado anotado a ${targetDebtor.name}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                currentInput = "0"
                updateDisplayAmount()
                loadDebtors()
            } else {
                Toast.makeText(this, "Error registrando cliente", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
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
        val countryProfile = PreferencesHelper.getCountryProfile(this)
        val localSym = countryProfile.localCurrencySymbol

        val netUsd = if (profile.bcvRate > 0) net / profile.bcvRate else 0.0

        binding.tvTotalIncomeVes.text = String.format(Locale.US, "%s %.2f", localSym, income)
        binding.tvIncomeCount.text = "🟢 Cobrado ($count)"
        binding.tvTotalExpenseVes.text = String.format(Locale.US, "%s %.2f", localSym, expense)
        binding.tvNetProfitVes.text = String.format(Locale.US, "%s %.2f", localSym, net)
        binding.tvNetProfitUsd.text = if (countryProfile.supportsExchangeRate) {
            String.format(Locale.US, "≈ $%.2f USD", netUsd)
        } else {
            "${countryProfile.flagEmoji} ${countryProfile.displayName} (${countryProfile.localCurrencyCode})"
        }
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
                        tx.category == TransactionCategories.INCOME_ABONO_FIADO ->
                            if (tx.bankName.isNotEmpty()) "📒 Abono Fiado (${tx.bankName})" else "📒 Abono de Fiado (Libreta)"
                        tx.isManual -> "Ingreso: $catDisplay"
                        countryProfile.supportsExchangeRate -> "Pago Móvil ${tx.bankName}"
                        else -> "Cobro ${tx.bankName}"
                    }
                    itemBinding.tvTxAmount.text = if (isCrypto && tx.amountUsd > 0.0) {
                        String.format(Locale.US, "+ $%.2f USDT", tx.amountUsd)
                    } else {
                        String.format(Locale.US, "+ %s %.2f", localSym, tx.amount)
                    }
                    itemBinding.tvTxAmount.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                } else {
                    itemBinding.ivTxIcon.setImageResource(R.drawable.ic_ledger)
                    itemBinding.ivTxIcon.setColorFilter(ContextCompat.getColor(this, R.color.expense_red))
                    itemBinding.ivTxIcon.backgroundTintList = ContextCompat.getColorStateList(this, R.color.expense_red_bg)
                    val catDisplay = TransactionCategories.getCategoryDisplayName(tx.category)
                    itemBinding.tvTxTitle.text = if (!tx.isManual && tx.bankName.isNotEmpty()) {
                        "Salida ${tx.bankName}"
                    } else {
                        "Gasto: $catDisplay"
                    }
                    itemBinding.tvTxAmount.text = String.format(Locale.US, "- %s %.2f", localSym, tx.amount)
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
        val countryProfile = PreferencesHelper.getCountryProfile(this)
        var isIncomeVes = true

        if (!countryProfile.supportsExchangeRate) {
            dialogBinding.toggleIncomeCurrency.visibility = View.GONE
            dialogBinding.chipEfectivoUsd.visibility = View.GONE
            dialogBinding.chipEfectivoBs.text = "💵 Efectivo ${countryProfile.localCurrencyCode}"
            dialogBinding.chipPagoMovilManual.text = "📱 ${countryProfile.instantPaymentSystemName}"
            dialogBinding.tilIncomeAmount.hint = "Monto Recibido (${countryProfile.localCurrencySymbol})"
            dialogBinding.tvIncomeConverted.text = "${countryProfile.flagEmoji} ${countryProfile.displayName} (${countryProfile.localCurrencyCode})"
        }

        val updateConversion = {
            if (countryProfile.supportsExchangeRate) {
                val raw = dialogBinding.etIncomeAmount.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                if (isIncomeVes) {
                    val usd = if (profile.bcvRate > 0) raw / profile.bcvRate else 0.0
                    dialogBinding.tvIncomeConverted.text = String.format(Locale.US, "≈ $%.2f USD (Tasa %.2f)", usd, profile.bcvRate)
                } else {
                    val ves = raw * profile.bcvRate
                    dialogBinding.tvIncomeConverted.text = String.format(Locale.US, "≈ Bs. %.2f (Tasa %.2f)", ves, profile.bcvRate)
                }
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
            if (!countryProfile.supportsExchangeRate) {
                amountVes = rawAmount
                amountUsd = rawAmount
            } else if (isIncomeVes) {
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

        val countryProfile = PreferencesHelper.getCountryProfile(this)
        dialogBinding.tilExpenseAmount.hint = "Monto en ${countryProfile.localCurrencyDisplay}"

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
            val countryProfile = PreferencesHelper.getCountryProfile(this)
            val amountUsd = if (countryProfile.supportsExchangeRate && profile.bcvRate > 0) amount / profile.bcvRate else amount

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
        dialogBinding.tvEditTxTitle.text = if (isIncome) "Detalle de Ingreso" else "Detalle de Gasto"
        dialogBinding.tvEditTxSubtitle.text = if (tx.isManual) "Registro manual agregado por ti" else "Detectado automáticamente de ${tx.bankName}"
        dialogBinding.etEditTxAmount.setText(String.format(Locale.US, "%.2f", tx.amount))
        dialogBinding.etEditTxCategory.setText(TransactionCategories.getCategoryDisplayName(tx.category))
        dialogBinding.etEditTxReference.setText(tx.reference)
        dialogBinding.etEditTxNote.setText(tx.note)

        // Si es un Ingreso (Pago Móvil / Cobro), mostrar sección para conciliar con un Fiado de la Libreta
        if (isIncome) {
            dialogBinding.layoutTxFiadoSection.visibility = View.VISIBLE
            val linkedEntry = dbHelper.getLedgerEntryByTransactionId(tx.id)
            if (linkedEntry != null) {
                val linkedDebtor = dbHelper.getDebtorById(linkedEntry.debtorId)
                val debtorName = linkedDebtor?.name ?: "Cliente"
                dialogBinding.tvTxFiadoStatus.text = String.format(
                    Locale.US,
                    "✅ Aplicado como pago de fiado a: %s ($%.2f USD descontados)",
                    debtorName,
                    linkedEntry.amountUsd
                )
                dialogBinding.btnAssignTxToFiado.visibility = View.GONE
            } else {
                dialogBinding.tvTxFiadoStatus.text = "📒 ¿Existe duda o este pago corresponde a un Fiado de la Libreta?"
                dialogBinding.btnAssignTxToFiado.visibility = View.VISIBLE
                dialogBinding.btnAssignTxToFiado.setOnClickListener {
                    showAssignTransactionToDebtorDialog(tx, dialog)
                }
            }
        } else {
            dialogBinding.layoutTxFiadoSection.visibility = View.GONE
        }

        dialogBinding.btnSaveEditTx.setOnClickListener {
            val amount = dialogBinding.etEditTxAmount.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            if (amount <= 0.0) {
                Toast.makeText(this, "Ingresa un monto válido", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val profile = PreferencesHelper.getUserProfile(this)
            val countryProfile = PreferencesHelper.getCountryProfile(this)
            val amountUsd = if (countryProfile.supportsExchangeRate && profile.bcvRate > 0) amount / profile.bcvRate else amount
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
            val countryProfile = PreferencesHelper.getCountryProfile(this)
            val deleteSym = if (countryProfile.supportsExchangeRate) "Bs." else countryProfile.localCurrencySymbol
            AlertDialog.Builder(this)
                .setTitle("¿Eliminar Movimiento?")
                .setMessage("Esta acción eliminará el registro de $deleteSym ${String.format(Locale.US, "%.2f", tx.amount)} del balance de hoy.")
                .setPositiveButton("Eliminar") { _, _ ->
                    dbHelper.deleteTransaction(tx.id)
                    dialog.dismiss()
                    loadTodayLedger()
                    loadDebtors()
                    Toast.makeText(this, "Movimiento eliminado", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        dialog.show()
    }

    /**
     * Permite considerar cualquier ingreso o Pago Móvil de Jornada como el pago/abono de un fiado,
     * descontándolo de la deuda del cliente seleccionado en la Libreta.
     */
    private fun showAssignTransactionToDebtorDialog(tx: Transaction, parentTxDialog: Dialog) {
        val allDebtors = dbHelper.getAllDebtors()
        if (allDebtors.isEmpty()) {
            Toast.makeText(this, "No tienes clientes registrados en la Libreta aún", Toast.LENGTH_LONG).show()
            return
        }

        val profile = PreferencesHelper.getUserProfile(this)
        val countryProfile = PreferencesHelper.getCountryProfile(this)
        val rate = if (countryProfile.supportsExchangeRate && profile.bcvRate > 0) profile.bcvRate else 1.0

        // Ordenar priorizando deudores con deuda activa
        val sortedDebtors = allDebtors.sortedWith(
            compareByDescending<Debtor> { it.isDebtor }
                .thenByDescending { it.debtAmountUsd }
                .thenBy { it.name }
        )

        val items = sortedDebtors.map { d ->
            if (!countryProfile.supportsExchangeRate) {
                when {
                    d.isDebtor -> String.format(Locale.US, "🔴 %s — Debe %s %.2f", d.name, countryProfile.localCurrencySymbol, d.debtAmountUsd)
                    d.hasCredit -> String.format(Locale.US, "🟢 %s — A favor %s %.2f", d.name, countryProfile.localCurrencySymbol, d.creditAmountUsd)
                    else -> "⚪ ${d.name} — Al día (${countryProfile.localCurrencySymbol} 0.00)"
                }
            } else {
                when {
                    d.isDebtor -> String.format(
                        Locale.US,
                        "🔴 %s — Debe $%.2f (Bs. %.2f)",
                        d.name,
                        d.debtAmountUsd,
                        d.getEquivalentVes(rate)
                    )
                    d.hasCredit -> String.format(
                        Locale.US,
                        "🟢 %s — A favor $%.2f",
                        d.name,
                        d.creditAmountUsd
                    )
                    else -> "⚪ ${d.name} — Al día ($0.00)"
                }
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("📒 Considerar Pago de un Fiado")
            .setItems(items) { _, which ->
                val selectedDebtor = sortedDebtors[which]
                val baseDebtVes = selectedDebtor.getEquivalentVes(rate)
                val concilDebtVes = selectedDebtor.getConciliationVes(rate)

                // Si el monto coincide con la deuda base o el monto de conciliación, saldar deuda exacta
                val discountUsd = if (selectedDebtor.isDebtor &&
                    (Math.abs(tx.amount - baseDebtVes) <= 1.0 || Math.abs(tx.amount - concilDebtVes) <= 1.0)
                ) {
                    selectedDebtor.debtAmountUsd
                } else if (tx.amountUsd > 0.0) {
                    tx.amountUsd
                } else {
                    if (countryProfile.supportsExchangeRate) tx.amount / rate else tx.amount
                }

                val refLabel = if (tx.reference.isNotBlank()) "Ref: ${tx.reference}" else "S/R"
                val bankLabel = tx.bankName.ifBlank { "Jornada" }

                dbHelper.addPayment(
                    debtorId = selectedDebtor.id,
                    amountUsd = discountUsd,
                    amountVes = tx.amount,
                    bcvRate = if (countryProfile.supportsExchangeRate) rate else 1.0,
                    matchedTxId = tx.id,
                    notes = "Aplicado desde Jornada ($bankLabel • $refLabel)"
                )

                val newSender = if (tx.senderName.isNotBlank() && !tx.senderName.contains(selectedDebtor.name)) {
                    "${selectedDebtor.name} (${tx.senderName})"
                } else {
                    selectedDebtor.name
                }
                val newNote = if (tx.note.isNotBlank()) {
                    "${tx.note} • ✅ Fiado: ${selectedDebtor.name}"
                } else {
                    "✅ Pago de Fiado: ${selectedDebtor.name}"
                }

                val updatedTx = tx.copy(
                    category = TransactionCategories.INCOME_ABONO_FIADO,
                    senderName = newSender,
                    note = newNote
                )
                dbHelper.updateTransaction(updatedTx)

                parentTxDialog.dismiss()
                loadTodayLedger()
                loadDebtors()

                Toast.makeText(
                    this,
                    String.format(
                        Locale.US,
                        "✅ Pago de Bs. %.2f ($%.2f) descontado de la deuda de %s",
                        tx.amount,
                        discountUsd,
                        selectedDebtor.name
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
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

            val countryProfile = PreferencesHelper.getCountryProfile(this)
            val effectiveRate = if (countryProfile.supportsExchangeRate && profile.bcvRate > 0) profile.bcvRate else 1.0
            val financials = ReportHelper.calculateFinancials(currentFilteredTransactions, effectiveRate)
            dialogBinding.tvReportSummaryBadge.text = if (countryProfile.supportsExchangeRate) {
                "${currentFilteredTransactions.size} movimientos • Bs. ${String.format(Locale.US, "%.2f", financials.totalIncomeVes)} ($${String.format(Locale.US, "%.2f", financials.totalIncomeUsd)}) • Tasa BCV: Bs. ${String.format(Locale.US, "%.2f", profile.bcvRate)}"
            } else {
                "${currentFilteredTransactions.size} movimientos • ${countryProfile.localCurrencySymbol} ${String.format(Locale.US, "%.2f", financials.totalIncomeVes)} (${countryProfile.localCurrencyCode})"
            }
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
            val debtorsSummary = dbHelper.getDebtorsSummary()
            val countryProfile = PreferencesHelper.getCountryProfile(this)
            val fullTextReport = ReportHelper.generateDetailedTextReport(profile, currentFilteredTransactions, filterPeriodLabel, debtorsSummary, countryProfile)
            WhatsAppHelper.sendViaWhatsApp(this, "", fullTextReport)
        }

        dialogBinding.btnShareOptionEmail.setOnClickListener {
            dialog.dismiss()
            val debtorsSummary = dbHelper.getDebtorsSummary()
            val countryProfile = PreferencesHelper.getCountryProfile(this)
            val fullTextReport = ReportHelper.generateDetailedTextReport(profile, currentFilteredTransactions, filterPeriodLabel, debtorsSummary, countryProfile)
            ReportHelper.shareViaEmail(this, profile, fullTextReport, filterPeriodLabel)
        }

        val isPro = PlanCapabilityManager.canExportPdf(this)
        if (!isPro) {
            dialogBinding.btnShareOptionPdf.visibility = View.GONE
            dialogBinding.btnShareOptionCsv.visibility = View.GONE
            dialogBinding.btnShareOptionsProLocked.visibility = View.VISIBLE
            dialogBinding.btnShareOptionsProLocked.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("💼 Función para Comercios")
                    .setMessage(
                        "La exportación de balances en PDF y CSV requiere una clave de activación comercial para negocios.\n\n" +
                        "Puedes ingresar tu clave si ya la tienes o consultar con asistencia para activarla en tu dispositivo."
                    )
                    .setPositiveButton("🔑 Ingresar Clave") { _, _ ->
                        dialog.dismiss()
                        showRedeemCodeDialog()
                    }
                    .setNeutralButton("💬 Consultar con Asistencia") { _, _ ->
                        dialog.dismiss()
                        openSupportWhatsAppForPro("Exportación de balances en PDF y CSV")
                    }
                    .setNegativeButton("Cerrar", null)
                    .show()
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
                val allDebtors = dbHelper.getAllDebtors()
                val csvFile = ReportHelper.generateCsvReport(this, profile, currentFilteredTransactions, filterPeriodLabel, allDebtors)
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
            .replace("doscientos", "200")
            .replace("trescientos", "300")
            .replace("cuatrocientos", "400")
            .replace("quinientos", "500")
            .replace("seiscientos", "600")
            .replace("setecientos", "700")
            .replace("ochocientos", "800")
            .replace("novecientos", "900")

        val conRegex = Regex("""(\d+)\s+con\s+(\d+)""")
        textForNumber = conRegex.replace(textForNumber) { matchResult ->
            "${matchResult.groupValues[1]}.${matchResult.groupValues[2]}"
        }

        // Reconocimiento fonético para "mil" y "k" (ej: "50 mil" -> 50000, "1.5 mil" -> 1500)
        val milRegex = Regex("""(?i)(\d+(?:[.,]\d+)?)\s*(?:mil|k)\b""")
        textForNumber = milRegex.replace(textForNumber) { mr ->
            val num = mr.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 1.0
            String.format(Locale.US, "%.0f", num * 1000.0)
        }
        textForNumber = textForNumber.replace(Regex("""(?i)\bmil\b"""), "1000")

        val numberRegex = Regex("""(\d+(?:[.,]\d+)?)""")
        val numberMatch = numberRegex.find(textForNumber)
        val parsedRawAmount = numberMatch?.value?.replace(',', '.')?.toDoubleOrNull() ?: 0.0

        val profile = PreferencesHelper.getUserProfile(this)
        val bcvRate = if (profile.bcvRate > 0) profile.bcvRate else 45.0

        val countryProfile = PreferencesHelper.getCountryProfile(this)
        val amountVes: Double
        val amountUsd: Double
        if (!countryProfile.supportsExchangeRate) {
            amountVes = parsedRawAmount
            amountUsd = parsedRawAmount
        } else if (isUsd) {
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

        val countryProfile = PreferencesHelper.getCountryProfile(this)
        if (parsedRawAmount > 0.0) {
            if (!countryProfile.supportsExchangeRate) {
                val formattedAmt = if (countryProfile.code == CountryCode.CO) {
                    String.format(Locale.US, "%s %,.0f", countryProfile.localCurrencySymbol, amountVes)
                } else {
                    String.format(Locale.US, "%s %,.2f", countryProfile.localCurrencySymbol, amountVes)
                }
                dialogBinding.tvVoiceParsedAmount.text = "$formattedAmt (${countryProfile.localCurrencyCode})"
            } else if (isUsd) {
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
            val spokenAmt = if (countryProfile.supportsExchangeRate && isUsd) amountUsd else amountVes
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

        val countryProfile = PreferencesHelper.getCountryProfile(this)

        bankCodes.forEach { code ->
            val itemBinding = ItemConfiguredBankSettingBinding.inflate(layoutInflater, binding.llConfiguredBanksContainer, false)
            val bank = BankCatalog.findByCode(code)
            val bankName = bank?.name ?: code
            val brandColor = bank?.getBrandColor() ?: Color.parseColor("#00B2FE")
            val shortName = bank?.getShortName() ?: code.takeLast(3)

            itemBinding.tvBankItemBadge.text = shortName
            itemBinding.tvBankItemBadge.backgroundTintList = ColorStateList.valueOf(brandColor)
            itemBinding.tvBankItemName.text = bankName
            itemBinding.tvBankItemCode.text = if (countryProfile.code == CountryCode.VE) {
                "Código Sudeban: $code"
            } else {
                "${countryProfile.flagEmoji} ${countryProfile.displayName} • ${bank?.shortTag ?: code}"
            }

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

        if (PlanCapabilityManager.isReviewMode(this)) {
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
        binding.btnRequestCountryChange.setOnClickListener {
            val profile = PreferencesHelper.getUserProfile(this)
            val currentCountry = PreferencesHelper.getCountryProfile(this)
            val deviceId = PlanCapabilityManager.getDeviceId(this)
            val businessName = profile.merchantName.ifEmpty { "Mi Negocio" }

            AlertDialog.Builder(this)
                .setTitle("🌎 Solicitar Cambio de País")
                .setMessage(
                    "El país de operación (${currentCountry.flagEmoji} ${currentCountry.displayName}) queda fijo al instalar la aplicación para proteger la contabilidad y moneda de tu caja.\n\n" +
                    "Si necesitas cambiar tu terminal a otro país (Venezuela, Colombia o Argentina), nuestro equipo de Atención al Cliente lo actualizará de inmediato en el sistema.\n\n" +
                    "🔑 ID de tu Terminal: $deviceId"
                )
                .setPositiveButton("💬 Contactar Atención al Cliente") { _, _ ->
                    val text = Uri.encode(
                        "Hola equipo de Atención al Cliente Zeasy, solicito el cambio de país de operación para mi terminal.\n\n" +
                        "🏪 Comercio: $businessName\n" +
                        "🔑 ID de Terminal: $deviceId\n" +
                        "🌎 País actual: ${currentCountry.flagEmoji} ${currentCountry.displayName} (${currentCountry.localCurrencyCode})\n" +
                        "👉 Deseo cambiar a: "
                    )
                    val uri = Uri.parse("https://api.whatsapp.com/send?phone=${PlanCapabilityManager.SUPPORT_PHONE_CLEAN}&text=$text")
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: Exception) {
                        Toast.makeText(this, "WhatsApp Soporte: ${PlanCapabilityManager.SUPPORT_PHONE_DISPLAY}", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

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

        // Guardado reactivo de borrador y actualización instantánea del botón de guardar
        val draftWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isBindingSettingsData) {
                    saveCurrentSettingsDraft()
                    updateSaveButtonState()
                }
            }
        }
        binding.etSettingsMerchantName.addTextChangedListener(draftWatcher)
        binding.actvSettingsBusinessCategory.addTextChangedListener(draftWatcher)
        binding.etSettingsPhone.addTextChangedListener(draftWatcher)
        binding.etSettingsId.addTextChangedListener(draftWatcher)
        binding.etSettingsEmail.addTextChangedListener(draftWatcher)
        binding.etSettingsBcvRate.addTextChangedListener(draftWatcher)
        binding.etBinancePayId.addTextChangedListener(draftWatcher)

        // Botón Asistente Paso a Paso (Guía Fácil para todas las edades)
        binding.btnOpenSetupWizard.setOnClickListener {
            showSetupWizardDialog(isMandatory = false, startStep = 1)
        }

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
                    loadDebtors()
                    Toast.makeText(this@MainActivity, "✅ Tasa oficial BCV actualizada: Bs. $formatted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "⚠️ No se pudo obtener tasa BCV: ${result.errorMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Selección de modo Soundbox
        binding.toggleGroupSoundbox.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && checkedId == R.id.btnSoundVoice && !PlanCapabilityManager.canUseVoiceSoundbox(this)) {
                Toast.makeText(this, "🗣️ Modo Voz en prueba. Para activarlo en todos tus cobros, se requiere Plan PRO.", Toast.LENGTH_SHORT).show()
            }
            if (!isBindingSettingsData) {
                updateSaveButtonState()
            }
        }

        // Listener de switch biométrico para actualizar estado de botón
        binding.switchBiometric.setOnCheckedChangeListener { _, _ ->
            if (!isBindingSettingsData) {
                updateSaveButtonState()
            }
        }

        binding.btnChangeSecurityPin.setOnClickListener {
            showChangeSecurityPinDialog()
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

        // Botón único superior de Guardar Ajustes
        val handleSaveClick = {
            saveCurrentSettingsFromUi(requireBiometricIfConfigured = true, isExplicitClick = true)
        }
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

        // Botón Generar Código de Acceso para Zeasy Dashboard PC
        binding.btnGenerateDashboardPin.setOnClickListener {
            val isPro = PlanCapabilityManager.isPro(this)

            if (!isPro) {
                showProUpgradeDialog("Zeasy Dashboard PC y Auditoría Contable")
                return@setOnClickListener
            }

            binding.btnGenerateDashboardPin.isEnabled = false
            binding.btnGenerateDashboardPin.text = "⏳ Generando PIN..."

            lifecycleScope.launch {
                val result = ZeasySyncManager.generateDashboardPin(this@MainActivity)
                binding.btnGenerateDashboardPin.isEnabled = true
                binding.btnGenerateDashboardPin.text = "🔑 Generar Código de Acceso PC"

                if (result.isSuccess) {
                    val (pin, _) = result.getOrThrow()
                    val profile = PreferencesHelper.getUserProfile(this@MainActivity)
                    val phone = profile.phoneNumber.ifEmpty { "tu teléfono registrado" }
                    val formattedPin = if (pin.length == 6) "${pin.substring(0, 3)} - ${pin.substring(3)}" else pin

                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("💻 Tu Código de Acceso PC")
                        .setMessage("Ingresa a dashboard.zeasy.app desde tu computadora e introduce tu teléfono y este código:\n\n" +
                            "👉  $formattedPin  👈\n\n" +
                            "• Teléfono: $phone\n" +
                            "• Válido por: 10 minutos\n\n" +
                            "Una vez vinculado, tu computadora quedará autorizada automáticamente por 30 días.")
                        .setPositiveButton("Copiar Código") { _, _ ->
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Zeasy PC PIN", pin)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(this@MainActivity, "📋 Código $pin copiado al portapapeles", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cerrar", null)
                        .show()
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Error al conectar con el servidor"
                    Toast.makeText(this@MainActivity, "⚠️ $error", Toast.LENGTH_LONG).show()
                }
            }
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
        isBindingSettingsData = true
        try {
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

            if (forceReload || binding.etSettingsMerchantName.text.isNullOrBlank() || draft.isNotEmpty()) {
                binding.etSettingsMerchantName.setText(initialName)
            }
            if (forceReload || binding.actvSettingsBusinessCategory.text.isNullOrBlank() || draft.isNotEmpty()) {
                binding.actvSettingsBusinessCategory.setText(initialCategory, false)
            }

            syncSelectedBank(profile.bankCode, profile.bankName)

            if (forceReload || binding.etSettingsPhone.text.isNullOrBlank() || draft.isNotEmpty()) {
                binding.etSettingsPhone.setText(initialPhone)
            }
            if (forceReload || binding.etSettingsId.text.isNullOrBlank() || draft.isNotEmpty()) {
                binding.etSettingsId.setText(initialId)
            }
            if (forceReload || binding.etSettingsEmail.text.isNullOrBlank() || draft.isNotEmpty()) {
                binding.etSettingsEmail.setText(initialEmail)
            }
            if (forceReload || binding.etSettingsBcvRate.text.isNullOrBlank() || draft.isNotEmpty()) {
                binding.etSettingsBcvRate.setText(initialBcv)
            }

            when (profile.soundboxMode) {
                "CHIME" -> binding.toggleGroupSoundbox.check(R.id.btnSoundChime)
                "MUTED" -> binding.toggleGroupSoundbox.check(R.id.btnSoundMuted)
                else -> binding.toggleGroupSoundbox.check(R.id.btnSoundVoice)
            }
            binding.switchDetectSms.isChecked = PreferencesHelper.isSmsDetectionEnabled(this)
            binding.switchCelebrationScreen.isChecked = PreferencesHelper.isCelebrationScreenEnabled(this)
            binding.switchBiometric.isChecked = profile.biometricSecurityEnabled

            updateNotificationPermissionUI()
            applyCountryProfileToUi()
            setupSettingsBankManager()
            setupCashierBankSelector()
            setupSettingsWallets()
            updatePlanUiInSettings()
        } finally {
            isBindingSettingsData = false
            updateSaveButtonState()
        }
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

        // Estado del Plan Zeasy PRO y seguimiento de código de activación
        if (PlanCapabilityManager.isReviewMode(this)) {
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
            PlanType.FREE -> if (hasHadPaidPro) "Plan Básico (Credencial Vencida)" else "Plan Básico Gratuito"
            PlanType.PRO_TRIAL -> "Prueba PRO ($daysRemaining días)"
            PlanType.PRO_ACTIVE -> "Zeasy PRO Activo ⭐"
        }
        binding.tvSettingsPlanDescription.text = when (planType) {
            PlanType.FREE -> if (hasHadPaidPro) "Tu credencial Zeasy PRO culminó. Continúas en modo básico gratuito con 1 banco y Libreta activa." else "Cobro básico y Libreta de Clientes ilimitada con 1 banco activo."
            PlanType.PRO_TRIAL -> "Disfrutas de todas las funciones PRO por cortesía de prueba ($daysRemaining días restantes)."
            PlanType.PRO_ACTIVE -> "Credencial PRO activa con acceso ilimitado a todas las funciones ($daysRemaining días restantes)."
        }

        when (planType) {
            PlanType.PRO_ACTIVE -> {
                if (daysRemaining <= 3) {
                    binding.cardTrialPaymentAlert.visibility = View.VISIBLE
                    binding.tvTrialPaymentAlertMessage.text = "⚠️ Tu credencial Zeasy PRO vence en $daysRemaining día(s). Solicita tu nuevo código de activación a soporte para continuar sin interrupciones."
                    binding.btnTrialRequestPaymentLink.text = "🔑 Ingresar o Solicitar Código PRO"
                    binding.btnManagePlanPro.visibility = View.VISIBLE
                    binding.btnManagePlanPro.text = "🔑 Extender Credencial PRO"
                } else {
                    binding.cardTrialPaymentAlert.visibility = View.GONE
                    binding.btnManagePlanPro.visibility = View.GONE
                }
            }
            PlanType.PRO_TRIAL -> {
                if (daysRemaining <= 2) {
                    binding.cardTrialPaymentAlert.visibility = View.VISIBLE
                    binding.tvTrialPaymentAlertMessage.text = "⚠️ Tu prueba Zeasy PRO vence en $daysRemaining días. Solicita tu código de activación a soporte para mantener todas las herramientas."
                    binding.btnTrialRequestPaymentLink.text = "🔑 Solicitar Código de Activación PRO"
                    binding.btnManagePlanPro.visibility = View.VISIBLE
                    binding.btnManagePlanPro.text = "🔑 Activar Código Zeasy PRO"
                } else {
                    binding.cardTrialPaymentAlert.visibility = View.GONE
                    binding.btnManagePlanPro.visibility = View.VISIBLE
                    binding.btnManagePlanPro.text = "🔑 Ver Beneficios o Ingresar Código PRO"
                }
            }
            PlanType.FREE -> {
                if (hasHadPaidPro) {
                    binding.cardTrialPaymentAlert.visibility = View.VISIBLE
                    binding.tvTrialPaymentAlertMessage.text = "⏰ Tu período Zeasy PRO ha finalizado. Tu app sigue operando en modo Básico. Ingresa o solicita tu código de activación para reactivar PRO."
                    binding.btnTrialRequestPaymentLink.text = "🔑 Ingresar o Solicitar Código PRO"
                    binding.btnManagePlanPro.visibility = View.VISIBLE
                    binding.btnManagePlanPro.text = "🔑 Reactivar con Código PRO"
                } else if (!hasUsedTrial) {
                    binding.cardTrialPaymentAlert.visibility = View.VISIBLE
                    binding.tvTrialPaymentAlertMessage.text = "🎁 ¡Pruébalo gratis por 7 días! Disfruta de cobro multibanco, Binance Pay, alertas de voz, conciliación automática y estados de cuenta PDF."
                    binding.btnTrialRequestPaymentLink.text = "✨ Activar 7 Días de Prueba Gratis"
                    binding.btnManagePlanPro.visibility = View.VISIBLE
                    binding.btnManagePlanPro.text = "🔑 Ver Beneficios o Ingresar Código PRO"
                } else {
                    binding.cardTrialPaymentAlert.visibility = View.VISIBLE
                    binding.tvTrialPaymentAlertMessage.text = "ℹ️ Tu prueba Zeasy PRO ha finalizado. Solicita tu código de activación al equipo de soporte CIEM para habilitar todas las funciones avanzadas."
                    binding.btnTrialRequestPaymentLink.text = "🔑 Solicitar o Ingresar Código PRO"
                    binding.btnManagePlanPro.visibility = View.VISIBLE
                    binding.btnManagePlanPro.text = "🔑 Activar con Código PRO"
                }
            }
        }

        updateLicenseBadge()
    }

    // --- LICENCIAS Y PLANES ---
    private fun updateLicenseBadge() {
        val deviceId = PlanCapabilityManager.getDeviceId(this)

        if (PlanCapabilityManager.isReviewMode(this)) {
            binding.tvSubscriptionFooter.text = "Zeasy v${BuildConfig.VERSION_NAME} • Ciem tech solutions C.A • ID: $deviceId"
            binding.tvHeaderSubtitle.text = "⭐ Zeasy Edición Completa"
            binding.tvHeaderSubtitle.setOnClickListener(null)
            return
        }

        val planType = PlanCapabilityManager.getPlanType(this)
        val daysRemaining = PlanCapabilityManager.getDaysRemaining(this)

        binding.tvSubscriptionFooter.text = "Zeasy v${BuildConfig.VERSION_NAME} • Ciem tech solutions C.A • ID: $deviceId ($planType)"

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
        if (PlanCapabilityManager.isReviewMode(this)) {
            return
        }

        val planType = PlanCapabilityManager.getPlanType(this)
        val daysRemaining = PlanCapabilityManager.getDaysRemaining(this)
        if (planType == PlanType.PRO_ACTIVE && daysRemaining > 3) {
            Toast.makeText(this, "⭐ Tu credencial Zeasy PRO está activa ($daysRemaining días restantes).", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogBinding = DialogProUpgradeBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.tvProReasonSubtitle.text = "Habilita $featureName con tu licencia Zeasy PRO ⚡"

        // Oculto temporalmente mientras se verifica el micro-depósito y se aprueba zeasy_pro_monthly en Play Console
        val enablePlayBillingUi = false
        val supportsPlayBilling = enablePlayBillingUi && LicenseRepository.shouldShowPlayBillingOption(this)
        if (supportsPlayBilling) {
            dialogBinding.cardProGooglePlay.visibility = View.VISIBLE
            val cProf = PreferencesHelper.getCountryProfile(this)
            dialogBinding.tvProPlayBillingSubtitle.text =
                "Suscripción mensual oficial con Google Play Store en ${cProf.localCurrencyDisplay}. Cancela cuando quieras."

            dialogBinding.btnProGooglePlay.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                AlertDialog.Builder(this)
                    .setTitle("💳 Suscripción Google Play Store")
                    .setMessage(
                        "Estás en la versión de Prueba Cerrada de Zeasy para ${cProf.displayName}.\n\n" +
                        "La pasarela de facturación automática de Google Play está en proceso de activación final.\n\n" +
                        "Durante esta fase de prueba puedes activar tus 7 días de prueba gratis o solicitar un código de activación a nuestro equipo de Atención al Cliente."
                    )
                    .setPositiveButton("💬 Contactar Soporte") { _, _ ->
                        dialog.dismiss()
                        openSupportWhatsAppForPro(featureName)
                    }
                    .setNegativeButton("Entendido", null)
                    .show()
            }
        } else {
            dialogBinding.cardProGooglePlay.visibility = View.GONE
        }

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
                    loadDebtors()
                    updateLicenseBadge()
                    updateBinanceSettingsCard()
                    renderCashierBankCards()
                    showTrialRegisteredPrompt()
                }
            }
        }

        // Validar código directamente desde el campo integrado en el modal usando LicenseRepository
        dialogBinding.btnProRedeemCode.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val code = dialogBinding.etProActivationCode.text?.toString()?.trim() ?: ""
            if (code.isEmpty()) {
                dialogBinding.etProActivationCode.error = "Ingresa tu código de licencia o contacta a soporte"
                dialogBinding.etProActivationCode.requestFocus()
                return@setOnClickListener
            }

            dialogBinding.btnProRedeemCode.isEnabled = false
            dialogBinding.btnProRedeemCode.text = "⏳ Validando código..."
            lifecycleScope.launch {
                val result = LicenseRepository.redeemExternalLicenseCode(this@MainActivity, code)
                runOnUiThread {
                    dialogBinding.btnProRedeemCode.isEnabled = true
                    dialogBinding.btnProRedeemCode.text = "🔑 Canjear Código de Licencia"
                    if (result.success) {
                        dialog.dismiss()
                        loadSettings()
                        loadTodayLedger()
                        loadDebtors()
                        updateLicenseBadge()
                        updateBinanceSettingsCard()
                        renderCashierBankCards()
                        showProActivatedCelebrationDialog(result.remainingDays)
                    } else {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Código no válido")
                            .setMessage(result.message)
                            .setPositiveButton("Aceptar", null)
                            .setNeutralButton("💬 Soporte Comercial") { _, _ ->
                                dialog.dismiss()
                                openSupportWhatsAppForPro(featureName)
                            }
                            .show()
                    }
                }
            }
        }

        dialogBinding.btnProWhatsapp.setOnClickListener {
            dialog.dismiss()
            openSupportWhatsAppForPro(featureName)
        }

        dialogBinding.btnProEmail.setOnClickListener {
            dialog.dismiss()
            openSupportEmailForPro(featureName)
        }

        dialogBinding.tvProDismiss.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showTrialRegisteredPrompt() {
        if (isFinishing || isDestroyed) return
        if (trialRegisteredDialog?.isShowing == true) return

        val profile = PreferencesHelper.getUserProfile(this)
        val deviceId = PlanCapabilityManager.getDeviceId(this)
        val businessName = profile.merchantName.ifEmpty { "Mi Negocio" }

        val dialog = AlertDialog.Builder(this)
            .setTitle("🎉 ¡Prueba de 7 Días Activada!")
            .setMessage("Tienes acceso completo a todas las funciones PRO (Multibanco, Soundbox por Voz, Conciliación Automática de Libreta y Reportes PDF).\n\n¿Deseas registrar tu terminal con el equipo de soporte de Ciem tech solutions C.A por WhatsApp para recibir asistencia prioritaria?")
            .setPositiveButton("💬 Registrar por WhatsApp") { d, _ ->
                d.dismiss()
                trialRegisteredDialog = null
                val text = Uri.encode("Hola Ciem tech solutions C.A, acabo de activar mis 7 días de prueba de Zeasy PRO. Comercio: '$businessName' (ID de Terminal: $deviceId). Quedo atento a su soporte y asistencia para mi código de activación.")
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=${PlanCapabilityManager.SUPPORT_PHONE_CLEAN}&text=$text")
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
            .setNegativeButton("Ahora no") { d, _ ->
                d.dismiss()
                trialRegisteredDialog = null
            }
            .create()

        dialog.setOnDismissListener {
            trialRegisteredDialog = null
        }
        trialRegisteredDialog = dialog
        dialog.show()
    }

    private fun showSubscriptionExpiredPrompt() {
        AlertDialog.Builder(this)
            .setTitle("⏰ Tu período Zeasy PRO ha finalizado")
            .setMessage("Tu aplicación continúa funcionando con normalidad en el Plan Básico Gratuito (Pago Móvil, tasa BCV, cuadre de caja y Libreta de Clientes).\n\nPara reactivar la detección multibanco, voz, conciliación automática y estados de cuenta PDF, ingresa o solicita tu código de activación.")
            .setPositiveButton("🔑 Ingresar / Solicitar Código") { _, _ ->
                showProUpgradeDialog("todas las funciones de Zeasy PRO")
            }
            .setNegativeButton("Continuar en Básico", null)
            .show()
    }

    private fun handleRequestPaymentLinkOption() {
        showProUpgradeDialog("todas las funciones de Zeasy PRO")
    }

    private fun openSupportWhatsAppForPro(featureName: String = "Zeasy PRO") {
        val profile = PreferencesHelper.getUserProfile(this)
        val deviceId = PlanCapabilityManager.getDeviceId(this)
        val businessName = profile.merchantName.ifEmpty { "Mi Negocio" }
        val rif = if (profile.idCard.isNotEmpty()) " (${profile.idCard})" else ""

        val text = Uri.encode(
            "Hola Ciem tech solutions C.A, solicito un código de activación Zeasy PRO para habilitar $featureName en mi negocio '$businessName'$rif.\n\n" +
            "🔑 ID de mi Terminal: $deviceId\n" +
            "📱 Teléfono: ${profile.phoneNumber.ifEmpty { "No indicado" }}"
        )
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=${PlanCapabilityManager.SUPPORT_PHONE_CLEAN}&text=$text")
        try {
            startActivity(Intent.ACTION_VIEW.let { Intent(it, uri) })
        } catch (_: Exception) {
            Toast.makeText(this, "WhatsApp Soporte: ${PlanCapabilityManager.SUPPORT_PHONE_DISPLAY}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openSupportEmailForPro(featureName: String = "Zeasy PRO") {
        val profile = PreferencesHelper.getUserProfile(this)
        val deviceId = PlanCapabilityManager.getDeviceId(this)
        val businessName = profile.merchantName.ifEmpty { "Mi Negocio" }
        val rif = if (profile.idCard.isNotEmpty()) profile.idCard else "No indicado"

        val subject = "Solicitud de Código de Activación Zeasy PRO - $businessName ($deviceId)"
        val body = """
            Hola equipo de Ciem tech solutions C.A,
            
            Solicito un código de activación para habilitar las funciones de Zeasy PRO ($featureName) en mi dispositivo:
            
            • ID de Terminal: $deviceId
            • Nombre del Comercio: $businessName
            • Cédula / RIF: $rif
            • Teléfono de Contacto: ${profile.phoneNumber.ifEmpty { "No indicado" }}
            
            Quedo atento a sus indicaciones. ¡Muchas gracias!
        """.trimIndent()

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:${PlanCapabilityManager.SUPPORT_EMAIL}")
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            startActivity(Intent.createChooser(intent, "Solicitar Código por Correo"))
        } catch (_: Exception) {
            Toast.makeText(this, "Correo de soporte: ${PlanCapabilityManager.SUPPORT_EMAIL}", Toast.LENGTH_LONG).show()
        }
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
        if (isSyncingLicense) return
        isSyncingLicense = true
        lifecycleScope.launch {
            try {
                val wasPro = PlanCapabilityManager.isPro(this@MainActivity)
                val prevCountry = PreferencesHelper.getCountryCode(this@MainActivity)
                val updated = ZeasySyncManager.syncWithBackend(this@MainActivity)
                val isProNow = PlanCapabilityManager.isPro(this@MainActivity)
                val newCountry = PreferencesHelper.getCountryCode(this@MainActivity)
                val daysRemaining = PlanCapabilityManager.getDaysRemaining(this@MainActivity)

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (newCountry != prevCountry) {
                        val newProfile = CountryProfileCatalog.getProfile(newCountry)
                        val updatedUser = PreferencesHelper.getUserProfile(this@MainActivity)
                        activeCashierBankCode = updatedUser.bankCode
                        activeCashierBankName = updatedUser.bankName
                        selectedBankConfigCode = updatedUser.bankCode
                        selectedBankConfigName = updatedUser.bankName
                        soundHelper?.shutdown()
                        soundHelper = SoundHelper(this@MainActivity)
                        applyCountryProfileToUi()
                        renderConfiguredBanksList()
                        setupCashierBankSelector()
                        renderCashierBankCards()
                        updateCashierHeaderSummary()
                        loadTodayLedger()
                        loadDebtors()
                        Toast.makeText(
                            this@MainActivity,
                            "✅ Atención al Cliente actualizó tu país a ${newProfile.flagEmoji} ${newProfile.displayName} (${newProfile.localCurrencyCode})",
                            Toast.LENGTH_LONG
                        ).show()
                    }

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
            } finally {
                isSyncingLicense = false
            }
        }
    }

    /**
     * Sincroniza automáticamente la tasa oficial del BCV si han transcurrido más de 4 horas
     * o al iniciar una nueva jornada diaria, manteniendo los cálculos al día sin intervención.
     */
    private fun autoSyncBcvRateDaily() {
        val countryProfile = PreferencesHelper.getCountryProfile(this)
        if (!countryProfile.supportsExchangeRate) return

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
                    loadDebtors()
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
        if (isFinishing || isDestroyed) return
        if (proCelebrationDialog?.isShowing == true) return

        try {
            soundHelper?.playCashRegisterSound()
        } catch (_: Exception) {}

        val dialog = AlertDialog.Builder(this)
            .setTitle("🎉 ¡Credencial Zeasy PRO Activada! ⚡")
            .setMessage(
                "¡Tu código de activación ha sido validado con éxito!\n\n" +
                "📅 Vigencia: $daysRemaining día${if (daysRemaining > 1) "s" else ""} restante${if (daysRemaining > 1) "s" else ""}.\n\n" +
                "🚀 Ya puedes disfrutar de todas las funciones PRO:\n" +
                "• Detección y confirmación Multibanco simultánea\n" +
                "• Conciliación automática de Fiados al llegar Pago Móvil\n" +
                "• Estados de Cuenta detallados en PDF para clientes\n" +
                "• Soundbox con voz parlante y carga rápida por voz\n" +
                "• Copia de seguridad, Binance Pay y reportes PDF/Excel\n\n" +
                "¡Gracias por confiar en Ciem tech solutions C.A!"
            )
            .setPositiveButton("¡Empezar a Usar PRO!") { d, _ ->
                d.dismiss()
                proCelebrationDialog = null
                loadSettings()
                loadTodayLedger()
                loadDebtors()
                setupCashierBankSelector()
                updateLicenseBadge()
            }
            .setCancelable(false)
            .create()

        dialog.setOnDismissListener {
            proCelebrationDialog = null
        }

        proCelebrationDialog = dialog
        dialog.show()
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
            binding.btnSettingsFixNotification.text = "Activo ✅"
            binding.btnSettingsFixNotification.setTextColor(Color.WHITE)
            binding.btnSettingsFixNotification.isEnabled = true
            binding.btnSettingsFixNotification.alpha = 1.0f
            binding.btnSettingsFixNotification.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.income_green))
            wasNotificationListenerEnabled = true
        } else {
            binding.tvSettingsNotificationStatus.text = "Escucha de pagos: Inactiva ⚠️"
            binding.tvSettingsNotificationStatus.setTextColor(ContextCompat.getColor(this, R.color.warning_orange))
            binding.btnSettingsFixNotification.text = "👉 Activar"
            binding.btnSettingsFixNotification.setTextColor(Color.WHITE)
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
                binding.btnSettingsFixBattery.text = "Activo ✅"
                binding.btnSettingsFixBattery.setTextColor(Color.WHITE)
                binding.btnSettingsFixBattery.isEnabled = true
                binding.btnSettingsFixBattery.alpha = 1.0f
                binding.btnSettingsFixBattery.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.income_green))
            } else {
                binding.tvSettingsBatteryStatus.text = "Segundo plano: Con restricciones ⚠️"
                binding.tvSettingsBatteryStatus.setTextColor(ContextCompat.getColor(this, R.color.warning_orange))
                binding.btnSettingsFixBattery.text = "👉 Permitir"
                binding.btnSettingsFixBattery.setTextColor(Color.WHITE)
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
                AlertDialog.Builder(this)
                    .setTitle("🔋 Mantener Zeasy Activo en Segundo Plano")
                    .setMessage(
                        "Para que Zeasy cante tus pagos instantáneamente con la pantalla bloqueada o la app cerrada:\n\n" +
                        "1. Presiona '👉 Abrir Ajustes'.\n" +
                        "2. Busca la sección 'Batería' o 'Uso de batería de la app'.\n" +
                        "3. Selecciona 'Sin restricciones' (o desactiva el ahorro de energía para Zeasy).\n\n" +
                        "💡 En teléfonos Xiaomi / MIUI, Tecno o Samsung, este ajuste evita que Android suspenda la detección automática de cobros."
                    )
                    .setPositiveButton("👉 Abrir Ajustes") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(this, "Abre Ajustes > Aplicaciones > Zeasy > Batería > Sin restricciones", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Entendido", null)
                    .show()
            } else {
                Toast.makeText(this, "✅ La optimización de batería ya está configurada correctamente", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Asistente de Configuración Paso a Paso de Alta Accesibilidad (pensado para todas las edades y adultos mayores).
     * Al instalar la aplicación, guía obligatoriamente por los 5 pasos según el país elegido y guarda cada dato
     * de forma progresiva e instantánea para evitar errores de configuración o pérdida de progreso.
     */
    private fun showSetupWizardDialog(isMandatory: Boolean = true, startStep: Int = 1) {
        setupWizardDialog?.dismiss()
        isWizardMandatory = isMandatory
        currentWizardStep = startStep.coerceIn(1, 5)

        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
        val wBinding = DialogSetupWizardBinding.inflate(layoutInflater)
        dialog.setContentView(wBinding.root)
        dialog.setCancelable(!isMandatory)
        setupWizardDialog = dialog
        setupWizardBinding = wBinding

        // Respetar la barra de navegación del sistema (3 botones o gestos) dinámicamente
        ViewCompat.setOnApplyWindowInsetsListener(wBinding.layoutWizardBottomBar) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            val defaultPaddingH = (20 * density).toInt()
            val defaultPaddingTop = (12 * density).toInt()
            val defaultPaddingBottom = (16 * density).toInt()
            v.setPadding(
                defaultPaddingH,
                defaultPaddingTop,
                defaultPaddingH,
                defaultPaddingBottom + navBars.bottom
            )
            insets
        }

        var selectedCountry = PreferencesHelper.getCountryCode(this)
        val countryAlreadyLocked = PreferencesHelper.hasSelectedCountryOnFirstLaunch(this) && !isMandatory

        fun updateCountrySelectionButtons(country: CountryCode) {
            selectedCountry = country
            val activeStroke = ColorStateList.valueOf(Color.parseColor("#0096C7"))
            val inactiveStroke = ColorStateList.valueOf(Color.parseColor("#CBD5E1"))
            val activeBg = ColorStateList.valueOf(Color.parseColor("#E0F7FE"))
            val inactiveBg = ColorStateList.valueOf(Color.WHITE)

            listOf(
                Pair(wBinding.btnWizardCountryVe, CountryCode.VE),
                Pair(wBinding.btnWizardCountryCo, CountryCode.CO),
                Pair(wBinding.btnWizardCountryAr, CountryCode.AR)
            ).forEach { (btn, code) ->
                val isSelected = (code == country)
                btn.setTextColor(Color.parseColor("#0A192F"))
                btn.strokeColor = if (isSelected) activeStroke else inactiveStroke
                btn.strokeWidth = if (isSelected) (3 * resources.displayMetrics.density).toInt() else (1.5f * resources.displayMetrics.density).toInt()
                btn.backgroundTintList = if (isSelected) activeBg else inactiveBg
            }
        }

        updateCountrySelectionButtons(selectedCountry)

        if (countryAlreadyLocked) {
            val cProf = CountryProfileCatalog.getProfile(selectedCountry)
            wBinding.btnWizardCountryVe.isEnabled = (selectedCountry == CountryCode.VE)
            wBinding.btnWizardCountryCo.isEnabled = (selectedCountry == CountryCode.CO)
            wBinding.btnWizardCountryAr.isEnabled = (selectedCountry == CountryCode.AR)
            wBinding.tvWizardCountryLockNotice.text =
                "🔒 Tu país actual es ${cProf.flagEmoji} ${cProf.displayName} (${cProf.localCurrencyCode}). Si necesitas cambiar de país, puedes solicitarlo a Atención al Cliente desde Ajustes."
        } else {
            wBinding.btnWizardCountryVe.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                updateCountrySelectionButtons(CountryCode.VE)
            }
            wBinding.btnWizardCountryCo.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                updateCountrySelectionButtons(CountryCode.CO)
            }
            wBinding.btnWizardCountryAr.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                updateCountrySelectionButtons(CountryCode.AR)
            }
        }

        // Guardado progresivo instantáneo mientras el usuario escribe en el Paso 2
        val progressiveWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (currentWizardStep == 2) {
                    val name = wBinding.etWizardMerchantName.text?.toString()?.trim()
                    val phone = wBinding.etWizardPhone.text?.toString()?.trim()
                    val idDoc = wBinding.etWizardIdCard.text?.toString()?.trim()
                    val bcv = wBinding.etWizardBcvRate.text?.toString()?.replace(',', '.')?.toDoubleOrNull()
                    PreferencesHelper.saveProgressiveOnboardingData(
                        context = this@MainActivity,
                        merchantName = name,
                        phone = phone,
                        idCard = idDoc,
                        bcvRate = bcv
                    )
                    wBinding.tvWizardAutoSaveBadge.text = "✅ Guardado al instante"
                }
            }
        }
        wBinding.etWizardMerchantName.addTextChangedListener(progressiveWatcher)
        wBinding.etWizardPhone.addTextChangedListener(progressiveWatcher)
        wBinding.etWizardIdCard.addTextChangedListener(progressiveWatcher)
        wBinding.etWizardBcvRate.addTextChangedListener(progressiveWatcher)

        wBinding.btnWizardFetchBcv.setOnClickListener {
            wBinding.btnWizardFetchBcv.isEnabled = false
            wBinding.btnWizardFetchBcv.text = "⏳ ..."
            lifecycleScope.launch {
                val res = BcvRateHelper.fetchOfficialRate()
                wBinding.btnWizardFetchBcv.isEnabled = true
                wBinding.btnWizardFetchBcv.text = "🔄 BCV Hoy"
                if (res.isSuccess && res.rate > 0.0) {
                    val fmt = String.format(Locale.US, "%.2f", res.rate)
                    wBinding.etWizardBcvRate.setText(fmt)
                    BcvRateHelper.applyRateToProfile(this@MainActivity, res.rate)
                    Toast.makeText(this@MainActivity, "✅ Tasa BCV actualizada: Bs. $fmt", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Puedes escribir la tasa manualmente", Toast.LENGTH_SHORT).show()
                }
            }
        }

        wBinding.btnWizardUploadQr.setOnClickListener {
            val currentBankCode = PreferencesHelper.getUserProfile(this).bankCode
            if (currentBankCode.isNotEmpty()) {
                launchCropForBank(currentBankCode)
            } else {
                Toast.makeText(this, "Primero toca tu banco en la lista de arriba", Toast.LENGTH_SHORT).show()
            }
        }

        wBinding.btnWizardActivateNotif.setOnClickListener {
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

        wBinding.btnWizardActivateBattery.setOnClickListener {
            requestBatteryOptimizationManual()
        }

        wBinding.btnWizardTestSound.setOnClickListener {
            val isVoice = PlanCapabilityManager.canUseVoiceSoundbox(this)
            if (isVoice) {
                soundHelper?.announcePayment(150.0)
            } else {
                soundHelper?.playCashRegisterSound()
            }
            Toast.makeText(this, "🔊 Así sonará tu caja al recibir un pago", Toast.LENGTH_SHORT).show()
        }

        wBinding.btnWizardBack.setOnClickListener {
            if (currentWizardStep > 1) {
                currentWizardStep -= 1
                PreferencesHelper.setOnboardingStep(this, currentWizardStep)
                refreshSetupWizardStepUi()
            } else if (!isMandatory) {
                dialog.dismiss()
            }
        }

        wBinding.btnWizardNext.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            when (currentWizardStep) {
                1 -> {
                    PreferencesHelper.setCountryCode(this, selectedCountry)
                    lifecycleScope.launch {
                        ZeasySyncManager.updateInitialCountryOnServer(this@MainActivity, selectedCountry)
                    }
                    val updatedUser = PreferencesHelper.getUserProfile(this)
                    activeCashierBankCode = updatedUser.bankCode
                    activeCashierBankName = updatedUser.bankName
                    selectedBankConfigCode = updatedUser.bankCode
                    selectedBankConfigName = updatedUser.bankName
                    soundHelper?.shutdown()
                    soundHelper = SoundHelper(this)
                    applyCountryProfileToUi()
                    setupCashierBankSelector()
                    renderCashierBankCards()
                    updateCashierHeaderSummary()
                    loadTodayLedger()
                    loadDebtors()

                    currentWizardStep = 2
                    PreferencesHelper.setOnboardingStep(this, 2)
                    refreshSetupWizardStepUi()
                }
                2 -> {
                    val cProf = PreferencesHelper.getCountryProfile(this)
                    val name = wBinding.etWizardMerchantName.text?.toString()?.trim() ?: ""
                    val phone = wBinding.etWizardPhone.text?.toString()?.trim() ?: ""
                    val idDoc = wBinding.etWizardIdCard.text?.toString()?.trim() ?: ""
                    val bcv = wBinding.etWizardBcvRate.text?.toString()?.replace(',', '.')?.toDoubleOrNull()
                        ?: PreferencesHelper.getUserProfile(this).bcvRate

                    if (name.length < 2 || name in DUMMY_DEFAULT_VALUES) {
                        wBinding.etWizardMerchantName.error = "Escribe el nombre de tu negocio o taxi"
                        wBinding.etWizardMerchantName.requestFocus()
                        Toast.makeText(this, "✍️ Paso 1: Escribe el nombre de tu negocio o taxi", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    val isValidPhone = if (cProf.code == CountryCode.AR) {
                        phone.isNotBlank() && phone.length >= 3 && phone !in DUMMY_DEFAULT_VALUES
                    } else {
                        val cleanDigits = phone.replace(Regex("[^0-9]"), "")
                        cleanDigits.length >= 7 && phone !in DUMMY_DEFAULT_VALUES
                    }
                    if (!isValidPhone) {
                        val label = if (cProf.code == CountryCode.AR) "tu Alias o CVU de cobro" else "tu teléfono de cobro"
                        wBinding.etWizardPhone.error = "Ingresa $label (${cProf.phonePlaceholder})"
                        wBinding.etWizardPhone.requestFocus()
                        Toast.makeText(this, "📱 Paso 2: Ingresa $label (${cProf.phonePlaceholder})", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    if (idDoc.length < 4 || idDoc in DUMMY_DEFAULT_VALUES) {
                        wBinding.etWizardIdCard.error = "Ingresa tu ${cProf.nationalIdLabel} (${cProf.nationalIdPlaceholder})"
                        wBinding.etWizardIdCard.requestFocus()
                        Toast.makeText(this, "🪪 Paso 3: Ingresa tu ${cProf.nationalIdLabel}", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }

                    // Guardar perfil de forma definitiva y progresiva
                    val curProfile = PreferencesHelper.getUserProfile(this)
                    val updatedProfile = curProfile.copy(
                        merchantName = name,
                        phoneNumber = phone,
                        idCard = idDoc,
                        bcvRate = if (bcv > 0.0) bcv else curProfile.bcvRate
                    )
                    PreferencesHelper.saveUserProfile(this, updatedProfile)
                    PreferencesHelper.clearSettingsDraft(this)
                    loadSettings(forceReload = true)

                    currentWizardStep = 3
                    PreferencesHelper.setOnboardingStep(this, 3)
                    refreshSetupWizardStepUi()
                }
                3 -> {
                    currentWizardStep = 4
                    PreferencesHelper.setOnboardingStep(this, 4)
                    refreshSetupWizardStepUi()
                }
                4 -> {
                    val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
                    val isListenerEnabled = enabledListeners != null && enabledListeners.contains(packageName)
                    if (!isListenerEnabled) {
                        AlertDialog.Builder(this)
                            .setTitle("🔔 Falta Activar la Lectura de Pagos")
                            .setMessage("Para que Zeasy pueda escuchar cuando te llega un pago del banco y cantarlo en voz alta, necesitas prender el interruptor de Zeasy en la siguiente pantalla.\n\n¿Deseas activarlo ahora?")
                            .setPositiveButton("👉 Sí, Activar Ahora") { _, _ ->
                                wBinding.btnWizardActivateNotif.performClick()
                            }
                            .setNegativeButton("Pasar al último paso") { _, _ ->
                                currentWizardStep = 5
                                PreferencesHelper.setOnboardingStep(this, 5)
                                refreshSetupWizardStepUi()
                            }
                            .show()
                        return@setOnClickListener
                    }
                    currentWizardStep = 5
                    PreferencesHelper.setOnboardingStep(this, 5)
                    refreshSetupWizardStepUi()
                }
                5 -> {
                    PreferencesHelper.setOnboardingCompleted(this, true)
                    PreferencesHelper.setOnboardingStep(this, 5)
                    loadSettings(forceReload = true)
                    setupCashierBankSelector()
                    renderCashierBankCards()
                    updateCashierHeaderSummary()
                    loadTodayLedger()
                    loadDebtors()
                    lifecycleScope.launch {
                        ZeasySyncManager.syncWithBackend(this@MainActivity)
                    }
                    dialog.dismiss()
                    Toast.makeText(this, "🎉 ¡Todo listo! Tu caja Zeasy ya está configurada para cobrar.", Toast.LENGTH_LONG).show()
                }
            }
        }

        refreshSetupWizardStepUi()
        dialog.show()
    }

    private fun refreshSetupWizardStepUi() {
        val wBinding = setupWizardBinding ?: return
        val cProf = PreferencesHelper.getCountryProfile(this)
        val uProf = PreferencesHelper.getUserProfile(this)

        wBinding.tvWizardStepBadge.text = "PASO $currentWizardStep DE 5"
        wBinding.progressWizard.progress = currentWizardStep
        wBinding.scrollWizardContent.scrollTo(0, 0)

        wBinding.layoutWizardStep1.visibility = if (currentWizardStep == 1) View.VISIBLE else View.GONE
        wBinding.layoutWizardStep2.visibility = if (currentWizardStep == 2) View.VISIBLE else View.GONE
        wBinding.layoutWizardStep3.visibility = if (currentWizardStep == 3) View.VISIBLE else View.GONE
        wBinding.layoutWizardStep4.visibility = if (currentWizardStep == 4) View.VISIBLE else View.GONE
        wBinding.layoutWizardStep5.visibility = if (currentWizardStep == 5) View.VISIBLE else View.GONE

        wBinding.btnWizardBack.visibility = if (currentWizardStep > 1 || !isWizardMandatory) View.VISIBLE else View.GONE
        wBinding.btnWizardBack.text = if (currentWizardStep == 1 && !isWizardMandatory) "✖ Cerrar" else "⬅ Atrás"

        when (currentWizardStep) {
            1 -> {
                wBinding.tvWizardStepTitle.text = "🌎 1. Selecciona tu País"
                wBinding.tvWizardStepSubtitle.text = "Toca con tu dedo el país donde trabajas para ajustar tu moneda y tus bancos automáticamente."
                wBinding.btnWizardNext.text = "Confirmar País y Continuar ➔"
            }
            2 -> {
                wBinding.tvWizardStepTitle.text = "🏪 2. Tus Datos de Cobro (${cProf.flagEmoji} ${cProf.displayName})"
                wBinding.tvWizardStepSubtitle.text = "Escribe los datos donde tus clientes te transfieren. Se van guardando solos mientras escribes."

                wBinding.tvWizardPhoneLabel.text = when (cProf.code) {
                    CountryCode.VE -> "2. Teléfono afiliado a Pago Móvil:"
                    CountryCode.CO -> "2. Celular Nequi / DaviPlata o Cuenta:"
                    CountryCode.AR -> "2. Celular o Alias / CVU de cobro:"
                }
                wBinding.etWizardPhone.inputType = if (cProf.code == CountryCode.AR) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                } else {
                    InputType.TYPE_CLASS_PHONE
                }
                val cleanPhoneHint = cProf.phonePlaceholder.removePrefix("Ej: ").removePrefix("ej. ").trim()
                val cleanIdHint = cProf.nationalIdPlaceholder.removePrefix("Ej: ").removePrefix("ej. ").trim()
                wBinding.tilWizardPhone.hint = "Ej: $cleanPhoneHint"

                wBinding.tvWizardIdLabel.text = "3. Tu ${cProf.nationalIdLabel}:"
                wBinding.tilWizardIdCard.hint = "Ej: $cleanIdHint"

                if (wBinding.etWizardMerchantName.text.isNullOrBlank() && uProf.merchantName.isNotBlank()) {
                    wBinding.etWizardMerchantName.setText(uProf.merchantName)
                }
                if (wBinding.etWizardPhone.text.isNullOrBlank() && uProf.phoneNumber.isNotBlank()) {
                    wBinding.etWizardPhone.setText(uProf.phoneNumber)
                }
                if (wBinding.etWizardIdCard.text.isNullOrBlank() && uProf.idCard.isNotBlank()) {
                    wBinding.etWizardIdCard.setText(uProf.idCard)
                }

                if (cProf.supportsExchangeRate) {
                    wBinding.layoutWizardBcvSection.visibility = View.VISIBLE
                    if (wBinding.etWizardBcvRate.text.isNullOrBlank()) {
                        wBinding.etWizardBcvRate.setText(String.format(Locale.US, "%.2f", uProf.bcvRate))
                    }
                    // Intentar actualizar la tasa BCV automáticamente en segundo plano
                    lifecycleScope.launch {
                        val res = BcvRateHelper.fetchOfficialRate()
                        if (res.isSuccess && res.rate > 0.0) {
                            BcvRateHelper.applyRateToProfile(this@MainActivity, res.rate)
                            wBinding.etWizardBcvRate.setText(String.format(Locale.US, "%.2f", res.rate))
                        }
                    }
                } else {
                    wBinding.layoutWizardBcvSection.visibility = View.GONE
                }

                wBinding.btnWizardNext.text = "Guardar Datos y Continuar ➔"
            }
            3 -> {
                if (cProf.code == CountryCode.AR) {
                    wBinding.tvWizardStepTitle.text = "🏦 3. Tu Billetera o Banco Principal"
                    wBinding.tvWizardStepSubtitle.text = "En Argentina, los cobros por Transferencia a tu Alias o CVU tienen 0% de comisión. Toca tu billetera o banco:"
                } else {
                    wBinding.tvWizardStepTitle.text = "🏦 3. Elige tu Banco o Billetera"
                    wBinding.tvWizardStepSubtitle.text = "Toca el banco donde recibes tus pagos en ${cProf.displayName}."
                }

                val countryBanks = BankCatalog.getBanksForCountry(cProf.code).filter { it.code != "0000" }
                val activeCode = uProf.bankCode
                val activeName = if (activeCode.isNotBlank()) BankCatalog.findByCode(activeCode)?.name ?: uProf.bankName else ""

                if (activeCode.isNotBlank()) {
                    wBinding.tvWizardSelectedBank.text = "✅ $activeName"
                    wBinding.tvWizardSelectedBank.setTextColor(Color.parseColor("#00875A"))
                } else {
                    wBinding.tvWizardSelectedBank.text = "👆 Toca tu banco o billetera abajo (Opcional)"
                    wBinding.tvWizardSelectedBank.setTextColor(Color.parseColor("#64748B"))
                }

                wBinding.layoutWizardBanksContainer.removeAllViews()
                countryBanks.forEach { bankEntry ->
                    val isSelected = activeCode.isNotBlank() && bankEntry.code.equals(activeCode, ignoreCase = true)
                    val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (10 * resources.displayMetrics.density).toInt()
                        }
                        minHeight = (56 * resources.displayMetrics.density).toInt()
                        isAllCaps = false
                        textSize = 16f
                        if (isSelected) {
                            text = "✔  ${bankEntry.name} (Seleccionado)"
                            setTextColor(ColorStateList.valueOf(Color.WHITE))
                            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00875A"))
                            strokeColor = ColorStateList.valueOf(Color.parseColor("#006644"))
                            strokeWidth = (2 * resources.displayMetrics.density).toInt()
                        } else {
                            text = "🏦  ${bankEntry.name}"
                            setTextColor(ColorStateList.valueOf(Color.parseColor("#0A192F")))
                            backgroundTintList = ColorStateList.valueOf(Color.WHITE)
                            strokeColor = ColorStateList.valueOf(Color.parseColor("#CBD5E1"))
                            strokeWidth = (1.5f * resources.displayMetrics.density).toInt()
                        }
                        setOnClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            if (!PlanCapabilityManager.canUseMultibank(this@MainActivity)) {
                                PreferencesHelper.replaceBasicPlanBank(this@MainActivity, bankEntry.code, bankEntry.name)
                            } else {
                                PreferencesHelper.setPrimaryBank(this@MainActivity, bankEntry.code, bankEntry.name)
                            }
                            activeCashierBankCode = bankEntry.code
                            activeCashierBankName = bankEntry.name
                            selectedBankConfigCode = bankEntry.code
                            selectedBankConfigName = bankEntry.name
                            syncSelectedBank(bankEntry.code, bankEntry.name)
                            refreshSetupWizardStepUi()
                        }
                    }
                    wBinding.layoutWizardBanksContainer.addView(btn)
                }

                val hasQr = activeCode.isNotBlank() && !PreferencesHelper.getBankQrUri(this, activeCode).isNullOrEmpty()
                wBinding.btnWizardUploadQr.setTextColor(Color.parseColor("#0A192F"))
                wBinding.btnWizardUploadQr.strokeColor = ColorStateList.valueOf(Color.parseColor("#CBD5E1"))
                wBinding.btnWizardUploadQr.backgroundTintList = ColorStateList.valueOf(Color.WHITE)

                if (activeCode.isBlank()) {
                    wBinding.tvWizardQrStatus.text = "💡 Puedes elegir tu banco arriba o continuar y configurarlo más tarde."
                    wBinding.tvWizardQrStatus.setTextColor(Color.parseColor("#64748B"))
                    wBinding.btnWizardUploadQr.visibility = View.GONE
                } else if (cProf.code == CountryCode.AR) {
                    wBinding.btnWizardUploadQr.visibility = View.VISIBLE
                    if (hasQr) {
                        wBinding.tvWizardQrStatus.text = "✅ Ya subiste el código QR de $activeName (Opcional)"
                        wBinding.tvWizardQrStatus.setTextColor(Color.parseColor("#00875A"))
                        wBinding.btnWizardUploadQr.text = "📸 Cambiar Imagen de mi QR"
                    } else {
                        wBinding.tvWizardQrStatus.text = "💡 En Argentina se cobra gratis (0% comisión) por Alias o CVU de Mercado Pago.\nEl código QR es totalmente opcional."
                        wBinding.tvWizardQrStatus.setTextColor(Color.parseColor("#1E293B"))
                        wBinding.btnWizardUploadQr.text = "📸 Subir QR (Opcional)"
                    }
                } else {
                    wBinding.btnWizardUploadQr.visibility = View.VISIBLE
                    if (hasQr) {
                        wBinding.tvWizardQrStatus.text = "✅ ¡Excelente! Ya subiste el código QR de $activeName"
                        wBinding.tvWizardQrStatus.setTextColor(Color.parseColor("#00875A"))
                        wBinding.btnWizardUploadQr.text = "📸 Cambiar Imagen de mi QR"
                    } else {
                        wBinding.tvWizardQrStatus.text = "📸 Código QR de $activeName (Opcional: puedes subirlo ahora o después)"
                        wBinding.tvWizardQrStatus.setTextColor(Color.parseColor("#1E293B"))
                        wBinding.btnWizardUploadQr.text = "📸 Subir Imagen de mi QR"
                    }
                }

                wBinding.btnWizardNext.text = if (activeCode.isNotBlank()) "Guardar Banco y Continuar ➔" else "Continuar ➔"
            }
            4 -> {
                wBinding.tvWizardStepTitle.text = "🔔 4. Permiso para Cantar los Pagos"
                wBinding.tvWizardStepSubtitle.text = "Este permiso es indispensable para que tu teléfono lea el aviso del banco y te cante el monto en voz alta."

                val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
                val isListenerEnabled = enabledListeners != null && enabledListeners.contains(packageName)

                if (isListenerEnabled) {
                    wBinding.tvWizardNotifStatusBadge.text = "🟢 ¡Excelente! La lectura de pagos ya está ACTIVADA ✅"
                    wBinding.tvWizardNotifStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.income_green))
                    wBinding.btnWizardActivateNotif.text = "✅ Permiso Activado (Tocar si deseas revisarlo)"
                    wBinding.btnWizardNext.text = "Continuar al Último Paso ➔"
                } else {
                    wBinding.tvWizardNotifStatusBadge.text = "🔴 Falta activar: Toca el botón azul de abajo y prende el interruptor de Zeasy"
                    wBinding.tvWizardNotifStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.warning_orange))
                    wBinding.btnWizardActivateNotif.text = "👉 1. Tocar Aquí para Activar Permiso"
                    wBinding.btnWizardNext.text = "Ya lo activé, Siguiente Paso ➔"
                }
            }
            5 -> {
                wBinding.tvWizardStepTitle.text = "🔊 5. ¡Último Paso y Prueba de Sonido!"
                wBinding.tvWizardStepSubtitle.text = "Asegura que tu teléfono no se duerma y escucha cómo te avisará cuando te paguen."

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                    val isIgnoringBattery = pm?.isIgnoringBatteryOptimizations(packageName) == true
                    if (isIgnoringBattery) {
                        wBinding.tvWizardBatteryStatusBadge.text = "🟢 ¡Perfecto! Tu teléfono no suspenderá a Zeasy cuando se apague la pantalla ✅"
                        wBinding.tvWizardBatteryStatusBadge.setTextColor(Color.parseColor("#00875A"))
                        wBinding.btnWizardActivateBattery.text = "✅ Uso con Pantalla Apagada Activado"
                        wBinding.btnWizardActivateBattery.setTextColor(Color.WHITE)
                        wBinding.btnWizardActivateBattery.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00875A"))
                        wBinding.btnWizardActivateBattery.strokeColor = ColorStateList.valueOf(Color.parseColor("#006644"))
                    } else {
                        wBinding.tvWizardBatteryStatusBadge.text = "⚠️ Toca el botón de abajo y elige 'Permitir' para que funcione con pantalla apagada:"
                        wBinding.tvWizardBatteryStatusBadge.setTextColor(Color.parseColor("#FF8B00"))
                        wBinding.btnWizardActivateBattery.text = "🔋 Permitir Uso con Pantalla Apagada"
                        wBinding.btnWizardActivateBattery.setTextColor(Color.parseColor("#0A192F"))
                        wBinding.btnWizardActivateBattery.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
                        wBinding.btnWizardActivateBattery.strokeColor = ColorStateList.valueOf(Color.parseColor("#0096C7"))
                    }
                }

                wBinding.btnWizardNext.text = "✅ ¡Terminar y Empezar a Cobrar!"
            }
        }
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
        val countryProfile = PreferencesHelper.getCountryProfile(this)

        val isCryptoPayment = isCrypto || bank.contains("Binance", ignoreCase = true) || bank.contains("Cripto", ignoreCase = true)
        if (isCryptoPayment && amountUsd > 0.0) {
            dialogBinding.tvCelebrationAmountVes.text = String.format(Locale.US, "$%.2f USDT", amountUsd)
            dialogBinding.tvCelebrationAmountUsd.text = if (countryProfile.supportsExchangeRate) {
                String.format(Locale.US, "≈ Bs. %.2f (Tasa %.2f)", amount, profile.bcvRate)
            } else {
                String.format(Locale.US, "≈ %s %.2f %s", countryProfile.localCurrencySymbol, amount, countryProfile.localCurrencyCode)
            }
        } else if (countryProfile.supportsExchangeRate) {
            val calcUsd = if (profile.bcvRate > 0) amount / profile.bcvRate else 0.0
            dialogBinding.tvCelebrationAmountVes.text = String.format(Locale.US, "Bs. %.2f", amount)
            dialogBinding.tvCelebrationAmountUsd.text = String.format(Locale.US, "≈ $%.2f USD (Tasa %.2f)", calcUsd, profile.bcvRate)
        } else {
            dialogBinding.tvCelebrationAmountVes.text = String.format(Locale.US, "%s %.2f", countryProfile.localCurrencySymbol, amount)
            dialogBinding.tvCelebrationAmountUsd.text = "${countryProfile.flagEmoji} Pago verificado en ${countryProfile.localCurrencyCode}"
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
