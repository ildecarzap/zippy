package com.cobrix.pocket.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cobrix.pocket.data.model.Debtor
import com.cobrix.pocket.databinding.ItemDebtorBinding
import com.cobrix.pocket.utils.PreferencesHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebtorsAdapter(
    private var items: List<Debtor> = emptyList(),
    private var bcvRate: Double = 45.0,
    private val onChargeClick: (Debtor) -> Unit,
    private val onPaymentClick: (Debtor) -> Unit,
    private val onWhatsAppClick: (Debtor) -> Unit,
    private val onItemClick: (Debtor) -> Unit
) : RecyclerView.Adapter<DebtorsAdapter.DebtorViewHolder>() {

    fun updateData(newItems: List<Debtor>, newRate: Double) {
        this.items = newItems
        this.bcvRate = if (newRate > 0) newRate else 1.0
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DebtorViewHolder {
        val binding = ItemDebtorBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DebtorViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DebtorViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class DebtorViewHolder(private val binding: ItemDebtorBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(debtor: Debtor) {
            val ctx = binding.root.context
            val countryProfile = PreferencesHelper.getCountryProfile(ctx)
            val isBiCurrency = countryProfile.supportsExchangeRate
            val localSym = countryProfile.localCurrencySymbol

            binding.tvDebtorName.text = debtor.name

            val phoneText = if (!debtor.phone.isNullOrBlank()) debtor.phone else "Sin WhatsApp"
            val idText = if (!debtor.identification.isNullOrBlank()) " • ${debtor.identification}" else ""
            binding.tvDebtorMeta.text = "$phoneText$idText"

            val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.US)
            val updatedDate = dateFormat.format(Date(debtor.updatedAt))
            binding.tvDebtorUpdatedTime.text = "Actualizado: $updatedDate"

            when {
                debtor.isDebtor -> {
                    val debtUsd = debtor.debtAmountUsd
                    val debtVes = debtor.getEquivalentVes(bcvRate)
                    val concilVes = debtor.getConciliationVes(bcvRate)
                    binding.tvDebtorStatusBadge.text = if (isBiCurrency) {
                        String.format(Locale.US, "🔴 Debe $%.2f", debtUsd)
                    } else {
                        String.format(Locale.US, "🔴 Debe %s %.2f", localSym, debtVes)
                    }
                    binding.tvDebtorStatusBadge.setTextColor(Color.parseColor("#B91C1C"))
                    binding.tvDebtorStatusBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FEE2E2"))
                    binding.tvDebtorBalanceVes.text = if (isBiCurrency) {
                        String.format(
                            Locale.US,
                            "Bs. %.2f (BCV) • Conciliar: Bs. %.2f",
                            debtVes,
                            concilVes
                        )
                    } else {
                        String.format(
                            Locale.US,
                            "Monto a conciliar: %s %.2f (%s)",
                            localSym,
                            concilVes,
                            countryProfile.localCurrencyCode
                        )
                    }
                    binding.tvDebtorBalanceVes.setTextColor(Color.parseColor("#B91C1C"))
                    binding.btnItemWhatsApp.visibility = View.VISIBLE
                    binding.btnItemWhatsApp.text = "💬 Cobrar"
                    binding.btnItemWhatsApp.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00875A"))
                }
                debtor.hasCredit -> {
                    val creditUsd = debtor.creditAmountUsd
                    val creditVes = debtor.getEquivalentVes(bcvRate)
                    binding.tvDebtorStatusBadge.text = if (isBiCurrency) {
                        String.format(Locale.US, "🟢 A favor $%.2f", creditUsd)
                    } else {
                        String.format(Locale.US, "🟢 A favor %s %.2f", localSym, creditVes)
                    }
                    binding.tvDebtorStatusBadge.setTextColor(Color.parseColor("#15803D"))
                    binding.tvDebtorStatusBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#DCFCE7"))
                    binding.tvDebtorBalanceVes.text = if (isBiCurrency) {
                        String.format(Locale.US, "Disponible: Bs. %.2f (BCV)", creditVes)
                    } else {
                        String.format(Locale.US, "Disponible: %s %.2f (%s)", localSym, creditVes, countryProfile.localCurrencyCode)
                    }
                    binding.tvDebtorBalanceVes.setTextColor(Color.parseColor("#15803D"))
                    binding.btnItemWhatsApp.visibility = View.VISIBLE
                    binding.btnItemWhatsApp.text = "💬 Avisar"
                    binding.btnItemWhatsApp.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00875A"))
                }
                else -> {
                    binding.tvDebtorStatusBadge.text = if (isBiCurrency) {
                        "⚪ Al Día ($0.00)"
                    } else {
                        "⚪ Al Día ($localSym 0.00)"
                    }
                    binding.tvDebtorStatusBadge.setTextColor(Color.parseColor("#334155"))
                    binding.tvDebtorStatusBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F1F5F9"))
                    binding.tvDebtorBalanceVes.text = if (isBiCurrency) {
                        "Cuenta al día (Bs. 0,00)"
                    } else {
                        "Cuenta al día ($localSym 0.00)"
                    }
                    binding.tvDebtorBalanceVes.setTextColor(Color.parseColor("#475569"))
                    binding.btnItemWhatsApp.visibility = View.GONE
                }
            }

            binding.btnItemAddCharge.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onChargeClick(debtor)
            }
            binding.btnItemAddPayment.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onPaymentClick(debtor)
            }
            binding.btnItemWhatsApp.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onWhatsAppClick(debtor)
            }
            binding.root.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onItemClick(debtor)
            }
        }
    }
}
