package com.cobrix.pocket.service.parser

import com.cobrix.pocket.data.model.BankCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BdvParserTest {

    private val parser = BdvParser()

    @Test
    fun testBdvPushWithReference() {
        val packageName = "com.bancodevenezuela.app"
        val title = "PagomóvilBDV recibido"
        val text = "Recibiste un pago móvil de Bs. 250,50 de 04141234567. Ref: 8941"

        assertTrue(parser.canHandle(packageName, title, text))

        val payment = parser.parse(packageName, title, text, 1729710000000L)
        assertNotNull(payment)
        assertEquals(250.50, payment!!.monto, 0.001)
        assertEquals("8941", payment.referenciaUltimos4)
        assertEquals(BankCode.BDV, payment.bancoCodigo)
    }

    @Test
    fun testBdvPushWithoutExplicitReferenceFallback() {
        val packageName = "com.bancodevenezuela.app"
        val title = "Pago móvil recibido"
        val text = "Has recibido un Pago Móvil por Bs. 120,00 de JUAN PEREZ (04245551234)"

        assertTrue(parser.canHandle(packageName, title, text))

        val postTime = 1729715678000L
        val payment = parser.parse(packageName, title, text, postTime)
        assertNotNull(payment)
        assertEquals(120.0, payment!!.monto, 0.001)
        val expectedFallbackLast4 = (postTime % 10000).toString().padStart(4, '0').takeLast(4)
        assertEquals(expectedFallbackLast4, payment.referenciaUltimos4)
        assertEquals(BankCode.BDV, payment.bancoCodigo)
    }

    @Test
    fun testBdvSmsFrom26371() {
        val packageName = "com.google.android.apps.messaging"
        val title = "26371"
        val text = "BDV: PagoClave recibido por Bs. 85,00 de 04169876543 el 05/11/2026. Ref: 4512."

        assertTrue(parser.canHandle(packageName, title, text))

        val payment = parser.parse(packageName, title, text, 1729710000000L)
        assertNotNull(payment)
        assertEquals(85.0, payment!!.monto, 0.001)
        assertEquals("4512", payment.referenciaUltimos4)
        assertEquals("SMS", payment.canal)
    }

    @Test
    fun testBdvWithThousandsSeparatorDot() {
        val packageName = "com.bancodevenezuela.bdvdigital"
        val title = "BDV Digital"
        val text = "Pago móvil exitoso. Recibiste Bs. 1.500,00 en tu cuenta. Referencia: 10928374"

        assertTrue(parser.canHandle(packageName, title, text))

        val payment = parser.parse(packageName, title, text, 1729710000000L)
        assertNotNull(payment)
        assertEquals(1500.0, payment!!.monto, 0.001)
        assertEquals("8374", payment.referenciaUltimos4)
    }
}

