package com.cobrix.pocket.data.model

enum class TransactionType {
    INCOME,   // Pago recibido por carrera / venta
    EXPENSE   // Gasto operativo (Gasolina, almuerzo, repuesto, etc.)
}

object TransactionCategories {
    // Ingresos
    const val INCOME_AUTO_PAGO_MOVIL = "PAGO_MOVIL"
    const val INCOME_MANUAL_PAGO_MOVIL = "PAGO_MOVIL_MANUAL"
    const val INCOME_MANUAL_EFECTIVO_BS = "EFECTIVO_BS"
    const val INCOME_MANUAL_EFECTIVO_USD = "EFECTIVO_USD"
    const val INCOME_MANUAL_PUNTO = "PUNTO_VENTA"
    const val INCOME_MANUAL_TRANSFERENCIA = "TRANSFERENCIA"
    const val INCOME_MANUAL_OTRO = "OTRO_INGRESO"

    // Gastos
    const val EXPENSE_GASOLINA = "GASOLINA"
    const val EXPENSE_COMIDA = "COMIDA"
    const val EXPENSE_REPUESTO = "REPUESTO"
    const val EXPENSE_MANTENIMIENTO = "MANTENIMIENTO"
    const val EXPENSE_PEAJE = "PEAJE"
    const val EXPENSE_OTRO = "OTRO"

    fun getCategoryDisplayName(category: String): String {
        return when (category) {
            INCOME_AUTO_PAGO_MOVIL -> "Pago Móvil (Automático)"
            INCOME_MANUAL_PAGO_MOVIL -> "Pago Móvil (Manual)"
            INCOME_MANUAL_EFECTIVO_BS -> "Efectivo (Bs.)"
            INCOME_MANUAL_EFECTIVO_USD -> "Efectivo ($ USD)"
            INCOME_MANUAL_PUNTO -> "Punto de Venta / Tarjeta"
            INCOME_MANUAL_TRANSFERENCIA -> "Transferencia"
            INCOME_MANUAL_OTRO -> "Otro Ingreso"
            EXPENSE_GASOLINA -> "Gasolina"
            EXPENSE_COMIDA -> "Comida / Café"
            EXPENSE_REPUESTO -> "Repuesto"
            EXPENSE_MANTENIMIENTO -> "Mantenimiento / Taller"
            EXPENSE_PEAJE -> "Peaje"
            EXPENSE_OTRO -> "Otro Gasto"
            else -> category
        }
    }
}

data class Transaction(
    val id: Long = 0,
    val amount: Double,              // Monto en VES
    val amountUsd: Double = 0.0,     // Monto en USD estimado
    val reference: String = "",      // Últimos 4 dígitos o referencia completa
    val bankCode: String = "",       // Ej. "0134"
    val bankName: String = "",       // Ej. "Banesco"
    val senderName: String = "",     // Nombre del pagador si viene en la notificación
    val type: TransactionType,
    val category: String = "GENERAL", // PAGO_MOVIL, EFECTIVO_BS, EFECTIVO_USD, GASOLINA, etc.
    val note: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isManual: Boolean = false
)
