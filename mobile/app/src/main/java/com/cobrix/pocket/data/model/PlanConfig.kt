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
    val maxBanks: Int,                 // 1 para FREE, ilimitado (>1) para PRO
    val canExportPdf: Boolean,         // Exportación de PDF formal con membrete y firmas
    val canUseVoiceInput: Boolean,     // Carga de ingresos y gastos por voz (dictado)
    val canUseVoiceSoundbox: Boolean,  // Soundbox parlante con síntesis de voz anunciando nombres
    val canUseCloudBackup: Boolean,    // Respaldo en la nube y migración entre teléfonos
    val historyDaysRetention: Int      // Días de retención de reportes históricos
) {
    val isProUnlocked: Boolean
        get() = planType != PlanType.FREE
}
