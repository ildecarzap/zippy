package com.cobrix.pocket.data.model

/**
 * Define los tipos de planes disponibles en Zippy.
 */
enum class PlanType {
    FREE,        // Plan básico gratuito vitalicio (sin vencimiento en cobro básico)
    PRO_TRIAL,   // Período de prueba de 7 días con todas las funciones PRO desbloqueadas
    PRO_ACTIVE   // Plan PRO suscrito ($2.99/mes o anual)
}

/**
 * Matriz de capacidades por plan.
 */
data class PlanFeatures(
    val planType: PlanType,
    val maxBanks: Int,                         // 1 para FREE, ilimitado (>1) para PRO
    val canExportPdf: Boolean,                 // Exportación de PDF formal de Cierre de Jornada con membrete y firmas
    val canUseVoiceInput: Boolean,             // Carga de ingresos y gastos por voz (dictado)
    val canUseVoiceSoundbox: Boolean,          // Soundbox parlante con síntesis de voz anunciando montos y clientes
    val canUseCloudBackup: Boolean,            // Respaldo en la nube y migración entre teléfonos
    val historyDaysRetention: Int,             // Días de retención de reportes históricos
    val canUseWallets: Boolean = false,        // Billeteras digitales y cobros en cripto (Binance Pay / Zinli)
    val canExportDebtorPdf: Boolean = false,   // Estado de Cuenta Detallado en PDF para clientes de Libreta
    val canUseAutoConciliation: Boolean = false // Conciliación automática de fiados por céntimos al llegar Pago Móvil
) {
    val isProUnlocked: Boolean
        get() = planType != PlanType.FREE
}
