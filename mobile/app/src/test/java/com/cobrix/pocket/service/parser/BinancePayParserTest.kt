package com.cobrix.pocket.service.parser

import com.cobrix.pocket.data.model.BankCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BinancePayParserTest {

    private val parser = BinancePayParser()

    @Test
    fun testExactUserGmailNotification() {
        val packageName = "com.google.android.gm"
        val title = "Binance"
        val text = """
            [Binance] Pago recibido correctamente - 2026-10-23 19:12:15 (UTC)
            Pago recibido correctamente
            Recibiste una transferencia:
            Fecha y hora:
            2026-10-23 19:12:15(UTC)
            Remitente:
            Mcdh-61869
            Monto:
            0.5 USDT
        """.trimIndent()

        assertTrue(parser.canHandle(packageName, title, text))

        val payment = parser.parse(packageName, title, text, 1729710735000L)
        assertNotNull("El pago de Binance por Gmail no debe ser nulo", payment)
        assertEquals(0.5, payment!!.monto, 0.001)
        assertEquals("USD", payment.moneda)
        assertEquals(BankCode.BINANCE, payment.bancoCodigo)
        assertEquals("Binance Pay", payment.bancoEmisor)
        assertEquals("Mcdh-61869", payment.telefonoOrigen)
        assertEquals("1869", payment.referenciaUltimos4)
        assertEquals("EMAIL", payment.canal)
    }

    @Test
    fun testAmountStartingWithDot() {
        val packageName = "com.google.android.gm"
        val title = "Binance"
        val text = """
            [Binance] Pago recibido correctamente
            Remitente:
            Carlos
            Monto:
            .50 USDT
        """.trimIndent()

        val payment = parser.parse(packageName, title, text, 1000L)
        assertNotNull(payment)
        assertEquals(0.50, payment!!.monto, 0.001)
    }

    @Test
    fun testEnglishPushNotification() {
        val packageName = "com.binance.dev"
        val title = "Payment Received"
        val text = "You have received 15.00 USDT from Satoshi (Order ID: 98765432)"

        assertTrue(parser.canHandle(packageName, title, text))

        val payment = parser.parse(packageName, title, text, 2000L)
        assertNotNull(payment)
        assertEquals(15.0, payment!!.monto, 0.001)
        assertEquals("5432", payment.referenciaUltimos4)
        assertEquals("PUSH", payment.canal)
    }

    @Test
    fun testSpanishPushNotification() {
        val packageName = "com.binance.dev"
        val title = "Binance Pay"
        val text = "Has recibido 2.50 USDT"

        assertTrue(parser.canHandle(packageName, title, text))

        val payment = parser.parse(packageName, title, text, 2000L)
        assertNotNull(payment)
        assertEquals(2.50, payment!!.monto, 0.001)
    }

    @Test
    fun testOutgoingIsDiscarded() {
        val packageName = "com.binance.dev"
        val title = "Binance Pay"
        val text = "Has enviado 10.00 USDT"

        val payment = parser.parse(packageName, title, text, 2000L)
        assertNull("Los pagos salientes deben descartarse", payment)
    }
}
