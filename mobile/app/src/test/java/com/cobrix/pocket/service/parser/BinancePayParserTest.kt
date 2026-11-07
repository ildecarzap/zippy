package com.cobrix.pocket.service.parser

import com.cobrix.pocket.data.model.BankCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BinancePayParserTest {

    private val parser = BinancePayParser()

    @Test
    fun testGmailAndOutlookSpoofingAreBlockedForBinancePay() {
        val gmailPkg = "com.google.android.gm"
        val outlookPkg = "com.microsoft.office.outlook"
        val title = "Binance"
        val text = """
            [Binance] Pago recibido correctamente - 2026-10-23 19:12:15 (UTC)
            Pago recibido correctamente
            Recibiste una transferencia:
            Remitente:
            Mcdh-61869
            Monto:
            0.5 USDT
        """.trimIndent()

        assertFalse("Gmail debe estar bloqueado por seguridad anti-spoofing", parser.canHandle(gmailPkg, title, text))
        assertNull("Gmail no debe parsear pagos de Binance", parser.parse(gmailPkg, title, text, 1729710735000L))
        assertFalse("Outlook debe estar bloqueado por seguridad anti-spoofing", parser.canHandle(outlookPkg, title, text))
        assertNull("Outlook no debe parsear pagos de Binance", parser.parse(outlookPkg, title, text, 1729710735000L))
    }

    @Test
    fun testAmountStartingWithDotOnOfficialBinanceApp() {
        val packageName = "com.binance.dev"
        val title = "Binance Pay"
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

    @Test
    fun testFiatBolivaresIsDiscarded() {
        val packageName = "com.cobrix.pocket"
        val title = "✅ Pago Recibido: Bs. 900.00"
        val text = "Banco Mercantil • Ref terminada en 3817"

        val payment = parser.parse(packageName, title, text, 2000L)
        assertNull("Las notificaciones en bolívares nunca deben ser Binance Pay", payment)
    }

    @Test
    fun testZippySelfNotificationNotHandled() {
        val packageName = "com.cobrix.pocket"
        val title = "✅ Pago Recibido: Bs. 180.00"
        val text = "Ubii App • Ref: 0515"

        val canHandle = parser.canHandle(packageName, title, text)
        assertFalse("Binance no debe manejar notificaciones de Zippy", canHandle)
    }

    @Test
    fun testExactUserPushNotification() {
        val packageName = "com.binance.dev"
        val title = "Has recibido un pago"
        val text = "Recibiste un pago de 0.37 USDT de Mcdh-61869"

        assertTrue(parser.canHandle(packageName, title, text))

        val payment = parser.parse(packageName, title, text, 1729710000000L)
        assertNotNull(payment)
        assertEquals(0.37, payment!!.monto, 0.001)
        assertEquals("Mcdh-61869", payment.telefonoOrigen)
        assertEquals("1869", payment.referenciaUltimos4)
    }
}
