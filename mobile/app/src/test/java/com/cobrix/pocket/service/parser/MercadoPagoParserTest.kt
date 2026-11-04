package com.cobrix.pocket.service.parser

import com.cobrix.pocket.data.model.BankCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MercadoPagoParserTest {

    private val parser = MercadoPagoParser()

    @Test
    fun testMercadoPagoFromUserScreenshot() {
        val packageName = "com.mercadopago.wallet"
        val title = "Recibiste $ 100"
        val text = "De Ildemaro Antonio Carrasco Zapata desde su cuenta de Mercado Pago."

        assertTrue(parser.canHandle(packageName, title, text))

        val payment = parser.parse(packageName, title, text, 1729710000000L)
        assertNotNull(payment)
        assertEquals(100.0, payment!!.monto, 0.001)
        assertEquals("ARS", payment.moneda)
        assertEquals("Mercado Pago", payment.bancoEmisor)
        assertEquals(BankCode.AR_MERCADOPAGO, payment.bancoCodigo)
        assertEquals("Ildemaro Antonio Carrasco Zapata", payment.telefonoOrigen)
    }

    @Test
    fun testMercadoPagoWithNonBreakingSpaceAndMercadoLibrePackage() {
        val packageName = "com.mercadolibre"
        val title = "Recibiste $\u00A01.500,50"
        val text = "De Juan Perez desde su cuenta de Mercado Pago."

        assertTrue(parser.canHandle(packageName, title, text))

        val payment = parser.parse(packageName, title, text, 1729710000000L)
        assertNotNull(payment)
        assertEquals(1500.50, payment!!.monto, 0.001)
        assertEquals("Juan Perez", payment.telefonoOrigen)
    }

    @Test
    fun testMercadoPagoTransferencia() {
        val packageName = "com.mercadopago.wallet"
        val title = "Te transfirieron $ 25.000"
        val text = "De Maria Gonzalez desde su cuenta bancaria. Ref: 8943"

        assertTrue(parser.canHandle(packageName, title, text))

        val payment = parser.parse(packageName, title, text, 1729710000000L)
        assertNotNull(payment)
        assertEquals(25000.0, payment!!.monto, 0.001)
        assertEquals("8943", payment.referenciaUltimos4)
    }
}

