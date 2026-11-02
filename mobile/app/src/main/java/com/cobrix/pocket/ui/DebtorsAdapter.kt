package com.cobrix.pocket.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cobrix.pocket.data.model.Debtor
import com.cobrix.pocket.databinding.ItemDebtorBinding
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
                    binding.tvDebtorStatusBadge.text = String.format(Locale.US, "🔴 Debe $%.2f", debtUsd)
                    binding.tvDebtorStatusBadge.setTextColor(Color.parseColor("#C62828"))
                    binding.tvDebtorStatusBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFEBEE"))
                    binding.tvDebtorBalanceVes.text = String.format(Locale.US, "Equivalente: Bs. %.2f (BCV)", debtVes)
                    binding.tvDebtorBalanceVes.setTextColor(Color.parseColor("#D32F2F"))
                    binding.btnItemWhatsApp.visibility = View.VISIBLE
                    binding.btnItemWhatsApp.text = "💬 Cobrar"
                    binding.btnItemWhatsApp.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00875A"))
                }
                debtor.hasCredit -> {
                    val creditUsd = debtor.creditAmountUsd
                    val creditVes = debtor.getEquivalentVes(bcvRate)
                    binding.tvDebtorStatusBadge.text = String.format(Locale.US, "🟢 A favor $%.2f", creditUsd)
                    binding.tvDebtorStatusBadge.setTextColor(Color.parseColor("#2E7D32"))
                    binding.tvDebtorStatusBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8F5E9"))
                    binding.tvDebtorBalanceVes.text = String.format(Locale.US, "Disponible: Bs. %.2f (BCV)", creditVes)
                    binding.tvDebtorBalanceVes.setTextColor(Color.parseColor("#388E3C"))
                    binding.btnItemWhatsApp.visibility = View.VISIBLE
                    binding.btnItemWhatsApp.text = "💬 Avisar"
                    binding.btnItemWhatsApp.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00875A"))
                }
                else -> {
                    binding.tvDebtorStatusBadge.text = "⚪ Al Día ($0.00)"
                    binding.tvDebtorStatusBadge.setTextColor(Color.parseColor("#5E6C84"))
                    binding.tvDebtorStatusBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F4F5F7"))
                    binding.tvDebtorBalanceVes.text = "Cuenta al día (Bs. 0,00)"
                    binding.tvDebtorBalanceVes.setTextColor(Color.parseColor("#718096"))
                    binding.btnItemWhatsApp.visibility = View.GONE
                }
            }

            binding.btnItemAddCharge.setOnClickListener { onChargeClick(debtor) }
            binding.btnItemAddPayment.setOnClickListener { onPaymentClick(debtor) }
            binding.btnItemWhatsApp.setOnClickListener { onWhatsAppClick(debtor) }
            binding.root.setOnClickListener { onItemClick(debtor) }
        }
    }
}
