package com.cobrix.pocket.service.parser

import com.cobrix.pocket.data.model.BankCode
import com.cobrix.pocket.data.model.ParsedPayment

/**
 * Contrato base para cada parser bancario individual.
 */
interface BankParser {
    val bankCode: BankCode

    /**
     * Determina si la notificación recibida pertenece o puede ser procesada por este banco.
     */
    fun canHandle(packageName: String, title: String?, text: String): Boolean

    /**
     * Parsea los datos críticos (monto, referencia, teléfono, etc.) del texto de la notificación.
     */
    fun parse(packageName: String, title: String?, text: String, postTimeMillis: Long): ParsedPayment?
}
