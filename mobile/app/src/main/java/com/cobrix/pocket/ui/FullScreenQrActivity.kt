package com.cobrix.pocket.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.cobrix.pocket.R
import com.cobrix.pocket.databinding.ActivityFullScreenQrBinding
import com.cobrix.pocket.databinding.DialogPaymentCelebrationBinding
import com.cobrix.pocket.service.PocketNotificationListenerService
import com.cobrix.pocket.utils.PreferencesHelper
import java.util.Locale

class FullScreenQrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFullScreenQrBinding
    private var celebrationDialog: AlertDialog? = null
    private val celebrationDismissHandler = Handler(Looper.getMainLooper())

    companion object {
        const val EXTRA_AMOUNT_VES = "extra_amount_ves"
        const val EXTRA_AMOUNT_USD = "extra_amount_usd"
        const val EXTRA_BANK_CODE = "extra_bank_code"
        const val EXTRA_BANK_NAME = "extra_bank_name"
    }

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
                    PreferencesHelper.getAndClearPendingCelebration(this@FullScreenQrActivity)
                    if (PreferencesHelper.isCelebrationScreenEnabled(this@FullScreenQrActivity) && (amount > 0.0 || amountUsd > 0.0)) {
                        showPaymentCelebrationAndFinish(amount, ref, bank, phone, amountUsd, isCrypto)
                    } else {
                        finish()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Forzar brillo de pantalla al 100% mientras se muestra el QR para facilitar el escaneo
        val layoutParams = window.attributes
        layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        window.attributes = layoutParams

        binding = ActivityFullScreenQrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val amountVes = intent.getDoubleExtra(EXTRA_AMOUNT_VES, 0.0)
        val amountUsd = intent.getDoubleExtra(EXTRA_AMOUNT_USD, 0.0)

        val profile = PreferencesHelper.getUserProfile(this)
        val targetBankCode = intent.getStringExtra(EXTRA_BANK_CODE) ?: profile.bankCode
        val targetBankName = intent.getStringExtra(EXTRA_BANK_NAME) ?: profile.bankName
        val targetQrUri = PreferencesHelper.getBankQrUri(this, targetBankCode) ?: profile.qrImageUri

        val isBinance = targetBankCode.equals("BINANCE", ignoreCase = true)
        val isZinli = targetBankCode.equals("ZINLI", ignoreCase = true)

        if (amountVes > 0.0 || amountUsd > 0.0) {
            binding.tvFullScreenVes.visibility = View.VISIBLE
            binding.tvFullScreenUsd.visibility = View.VISIBLE
            if (isBinance) {
                binding.tvFullScreenVes.text = String.format(Locale.US, "$%.2f USDT", amountUsd)
                binding.tvFullScreenUsd.text = if (amountVes > 0) String.format(Locale.US, "(Equiv. Bs. %.2f)", amountVes) else ""
            } else {
                binding.tvFullScreenVes.text = String.format(Locale.US, "Bs. %.2f", amountVes)
                binding.tvFullScreenUsd.text = String.format(Locale.US, "($%.2f USD)", amountUsd)
            }
        } else {
            binding.tvFullScreenVes.visibility = View.GONE
            binding.tvFullScreenUsd.visibility = View.GONE
        }

        val merchant = if (profile.merchantName.isNotBlank()) profile.merchantName else "Mi Negocio"
        binding.tvFullScreenMerchant.text = "👤 $merchant • ${profile.businessCategory}"

        if (isBinance) {
            val payId = PreferencesHelper.getBinancePayId(this)
            binding.tvFullScreenBank.text = "🟡 Método: Binance Pay (USDT)"
            binding.tvFullScreenPhone.text = if (payId.isNotBlank()) "🆔 Pay ID / Correo: $payId" else "📱 Escanear con App Binance"
            binding.tvFullScreenId.visibility = View.GONE
        } else if (isZinli) {
            val zinliEmail = PreferencesHelper.getZinliEmail(this)
            binding.tvFullScreenBank.text = "🟣 Método: Zinli (USD)"
            binding.tvFullScreenPhone.text = if (zinliEmail.isNotBlank()) "✉️ Correo: $zinliEmail" else "📱 Escanear con Zinli"
            binding.tvFullScreenId.visibility = View.GONE
        } else {
            binding.tvFullScreenBank.text = "🏦 Banco: $targetBankName ($targetBankCode)"
            binding.tvFullScreenPhone.text = "📱 Teléfono: ${profile.phoneNumber}"
            binding.tvFullScreenId.visibility = View.VISIBLE
            binding.tvFullScreenId.text = "🪪 Cédula/RIF: ${profile.idCard}"
        }

        if (!targetQrUri.isNullOrEmpty()) {
            try {
                binding.ivBigQr.setImageURI(Uri.parse(targetQrUri))
            } catch (_: Exception) {
                binding.ivBigQr.setImageResource(R.drawable.ic_zippy_icon)
            }
        } else {
            binding.ivBigQr.setImageResource(R.drawable.ic_zippy_icon)
        }

        binding.btnDoneFullScreen.setOnClickListener {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (PreferencesHelper.isCelebrationScreenEnabled(this)) {
            val pending = PreferencesHelper.getAndClearPendingCelebration(this)
            if (pending != null && (pending.amount > 0.0 || pending.amountUsd > 0.0)) {
                showPaymentCelebrationAndFinish(pending.amount, pending.ref, pending.bank, pending.phone, pending.amountUsd, pending.isCrypto)
                return
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
        super.onDestroy()
    }

    private fun showPaymentCelebrationAndFinish(
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
            finish()
        }

        celebrationDialog = dialog
        dialog.show()

        // Disparar confetis
        dialogBinding.confettiView.startConfetti()

        // Auto-cerrar tras 3.8 segundos y finalizar pantalla para volver a caja
        celebrationDismissHandler.postDelayed({
            if (!isFinishing && !isDestroyed && dialog.isShowing) {
                dialog.dismiss()
            }
        }, 3800)
    }
}
