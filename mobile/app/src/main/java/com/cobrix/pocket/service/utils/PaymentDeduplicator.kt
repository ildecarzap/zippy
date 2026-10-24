package com.cobrix.pocket.service.utils

import android.util.Log
import com.cobrix.pocket.data.model.ParsedPayment
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Motor de deduplicación en memoria para PayBridge.
 * 
 * Evita que eventos duplicados (por actualización de notificación en Android,
 * o llegada simultánea de notificación Push de la app bancaria + Email de Gmail)
 * se procesen dos veces, suenen doble en el Soundbox o se transmitan dos veces al servidor.
 */
object PaymentDeduplicator {

    private const val TAG = "PaymentDeduplicator"

    // Ventana de deduplicación: 24 horas (86.400.000 ms) para cubrir toda la jornada diaria
    private const val DEDUPLICATION_WINDOW_MS = 86_400_000L

    // Caché de pagos recientes: Clave -> Timestamp en ms
    private val recentPayments = ConcurrentHashMap<String, Long>()

    // Caché de notificaciones del sistema Android: sbnKey -> Timestamp en ms
    private val recentNotificationKeys = ConcurrentHashMap<String, Long>()

    /**
     * Verifica si una notificación ya fue procesada a nivel de clave del sistema StatusBarNotification.
     */
    fun isNotificationKeyDuplicate(sbnKey: String): Boolean {
        cleanupOldEntries()
        val lastSeen = recentNotificationKeys[sbnKey]
        if (lastSeen != null) {
            return true
        }
        recentNotificationKeys[sbnKey] = System.currentTimeMillis()
        return false
    }

    /**
     * Verifica si una clave de notificación ya fue procesada y registrada exitosamente como cobro.
     */
    fun isNotificationKeyAlreadyProcessed(sbnKey: String): Boolean {
        cleanupOldEntries()
        return recentNotificationKeys.containsKey(sbnKey)
    }

    /**
     * Registra que una clave de notificación fue confirmada y procesada como cobro válido.
     */
    fun markNotificationKeyProcessed(sbnKey: String) {
        recentNotificationKeys[sbnKey] = System.currentTimeMillis()
    }

    /**
     * Verifica si un pago parseado es duplicado basándose en:
     * Banco + Últimos 4 dígitos de Referencia + Monto con 2 decimales.
     */
    fun isPaymentDuplicate(payment: ParsedPayment): Boolean {
        cleanupOldEntries()
        val formattedAmount = String.format(Locale.US, "%.2f", payment.monto)
        val key = "${payment.bancoCodigo.name}_${payment.referenciaUltimos4}_$formattedAmount"
        val now = System.currentTimeMillis()

        // 1. Deduplicación estándar (Banco + Ref4 + Monto) para toda la jornada
        val lastSeen = recentPayments[key]
        if (lastSeen != null && (now - lastSeen) < DEDUPLICATION_WINDOW_MS) {
            Log.w(TAG, "Deduplicación activa: Se detectó pago repetido en ventana de 24h -> $key")
            return true
        }

        // 2. Deduplicación inteligente para Binance Pay (Push de Binance App vs Correo de Gmail)
        if (payment.bancoCodigo == com.cobrix.pocket.data.model.BankCode.BINANCE) {
            // A. Si coincide el remitente (ID / Nickname Binance) y el monto dentro de 10 minutos
            if (!payment.telefonoOrigen.isNullOrBlank()) {
                val senderKey = "BINANCE_SENDER_${payment.telefonoOrigen}_$formattedAmount"
                val lastSenderSeen = recentPayments[senderKey]
                if (lastSenderSeen != null && (now - lastSenderSeen) < 600_000L) {
                    Log.w(TAG, "Deduplicación Binance activa (Push + Email por remitente/monto): $senderKey")
                    return true
                }
                recentPayments[senderKey] = now
            }

            // B. Si llega un pago de Binance del mismo monto exacto dentro de 3 minutos
            val binanceAmountKey = "BINANCE_AMT_$formattedAmount"
            val lastAmtSeen = recentPayments[binanceAmountKey]
            if (lastAmtSeen != null && (now - lastAmtSeen) < 180_000L) {
                Log.w(TAG, "Deduplicación Binance activa (Push + Email simultáneo por monto): $binanceAmountKey")
                return true
            }
            recentPayments[binanceAmountKey] = now
        }

        recentPayments[key] = now
        return false
    }

    /**
     * Limpia entradas expiradas de más de 24 horas para prevenir fugas de memoria.
     */
    private fun cleanupOldEntries() {
        val now = System.currentTimeMillis()
        recentPayments.entries.removeIf { (now - it.value) > DEDUPLICATION_WINDOW_MS }
        recentNotificationKeys.entries.removeIf { (now - it.value) > DEDUPLICATION_WINDOW_MS }
    }

    fun clear() {
        recentPayments.clear()
        recentNotificationKeys.clear()
    }
}
