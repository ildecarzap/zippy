package com.cobrix.pocket.data.model

enum class LedgerEntryType {
    CHARGE,             // Fiado / Venta fiada (aumenta deuda o consume saldo a favor)
    PAYMENT,            // Abono o pago recibido del cliente (reduce deuda o genera saldo a favor)
    CREDIT_ADJUSTMENT   // Ajuste manual de cuenta
}

/**
 * Movimiento cronológico individual de la cuenta corriente de un cliente en la libreta.
 */
data class LedgerEntry(
    val id: Long = 0L,
    val debtorId: Long,
    val type: LedgerEntryType,
    val amountUsd: Double,
    val amountVes: Double,
    val bcvRate: Double,
    val matchedTransactionId: Long? = null,
    val notes: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
