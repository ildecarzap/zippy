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

    // Ventana de deduplicación: 3 minutos (180.000 ms)
    private const val DEDUPLICATION_WINDOW_MS = 180_000L

    // Caché de pagos recientes: Clave -> Timestamp en ms
    private val recentPayments = ConcurrentHashMap<String, Long>()

    // Caché de notificaciones del sistema Android: sbnKey -> Timestamp en ms
    private val recentNotificationKeys = ConcurrentHashMap<String, Long>()

    /**
     * Verifica si una notificación ya fue procesada a nivel de clave del sistema StatusBarNotification.
     */
    fun isNotificationKeyDuplicate(sbnKey: String): Boolean {
        cleanupOldEntries()
        val now = System.currentTimeMillis()
        val lastSeen = recentNotificationKeys[sbnKey]
        if (lastSeen != null && (now - lastSeen) < 15_000L) {
            // Misma notificación actualizada por el sistema o la app en menos de 15 segundos
            return true
        }
        recentNotificationKeys[sbnKey] = now
        return false
    }

    /**
     * Verifica si una clave de notificación ya fue procesada y registrada exitosamente como cobro.
     */
    fun isNotificationKeyAlreadyProcessed(sbnKey: String): Boolean {
        cleanupOldEntries()
        val now = System.currentTimeMillis()
        val lastSeen = recentNotificationKeys[sbnKey]
        return lastSeen != null && (now - lastSeen) < 15_000L
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

        val lastSeen = recentPayments[key]
        if (lastSeen != null && (now - lastSeen) < DEDUPLICATION_WINDOW_MS) {
            Log.w(TAG, "Deduplicación activa: Se detectó pago repetido en ventana de ${DEDUPLICATION_WINDOW_MS / 1000}s -> $key")
            return true
        }

        recentPayments[key] = now
        return false
    }

    /**
     * Limpia entradas expiradas para prevenir fugas de memoria en ejecuciones continuas 24/7.
     */
    private fun cleanupOldEntries() {
        val now = System.currentTimeMillis()
        recentPayments.entries.removeIf { (now - it.value) > DEDUPLICATION_WINDOW_MS }
        recentNotificationKeys.entries.removeIf { (now - it.value) > 30_000L }
    }

    fun clear() {
        recentPayments.clear()
        recentNotificationKeys.clear()
    }
}
