package com.cobrix.pocket.data.model

/**
 * Representa a un cliente en la Libreta de Cuentas por Cobrar y Saldos a Favor de Zeasy.
 *
 * Convención de balance:
 * - balanceUsd < 0 -> El cliente DEBE dinero al comercio (Deuda / Fiado pendiente).
 * - balanceUsd > 0 -> El cliente TIENE SALDO A FAVOR (Vuelto prepagado / Pago en exceso).
 * - balanceUsd == 0 -> Cuenta al día.
 */
data class Debtor(
    val id: Long = 0L,
    val name: String,
    val phone: String? = null,
    val identification: String? = null,
    val balanceUsd: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /** True si el cliente le debe dinero al comercio (saldo deudor) */
    val isDebtor: Boolean
        get() = balanceUsd < -0.009

    /** True si el cliente tiene saldo a su favor para consumir (crédito) */
    val hasCredit: Boolean
        get() = balanceUsd > 0.009

    /** True si la cuenta está exactamente en cero */
    val isClear: Boolean
        get() = !isDebtor && !hasCredit

    /** Monto adeudado positivo en USD para presentación */
    val debtAmountUsd: Double
        get() = if (balanceUsd < 0) -balanceUsd else 0.0

    /** Monto a favor positivo en USD para presentación */
    val creditAmountUsd: Double
        get() = if (balanceUsd > 0) balanceUsd else 0.0

    /** Calcula el equivalente en Bolívares según la tasa BCV suministrada */
    fun getEquivalentVes(bcvRate: Double): Double {
        val rate = if (bcvRate > 0) bcvRate else 1.0
        return when {
            isDebtor -> debtAmountUsd * rate
            hasCredit -> creditAmountUsd * rate
            else -> 0.0
        }
    }
}

/**
 * Resumen consolidado del total de dinero en la calle y saldos a favor de clientes.
 */
data class DebtorsSummary(
    val totalDebtUsd: Double = 0.0,
    val totalCreditUsd: Double = 0.0,
    val debtorsCount: Int = 0,
    val creditsCount: Int = 0
)

