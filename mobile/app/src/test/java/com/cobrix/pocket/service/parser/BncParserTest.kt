package com.cobrix.pocket.service.parser

import com.cobrix.pocket.data.model.BankCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BncParserTest {

    private val parser = BncParser()

    @Test
    fun testBncPushPaymentReceived_withFullReference() {
        val packageName = "bnc.bncnet.mobile2"
        val title = "PAGO MOVIL RECIBIDO"
        val text = "BNC Pago Movil Recibido Bs.1000,00 Telf.0424***3112 Dia:06/11/26-09:59 Ref: 39291823"

        assertTrue(parser.canHandle(packageName, title, text))

        val payment = parser.parse(packageName, title, text, 1729710000000L)
        assertNotNull(payment)
        assertEquals(1000.0, payment!!.monto, 0.001)
        assertEquals("VES", payment.moneda)
        assertEquals(BankCode.BNC, payment.bancoCodigo)
        assertEquals("39291823", payment.referencia)
        assertEquals("1823", payment.referenciaUltimos4)
        assertEquals("0424***3112", payment.telefonoOrigen)
        assertFalse(payment.isOutgoing)
    }

    @Test
    fun testBncPushPaymentReceived_truncatedEllipsisFromStatusBar() {
        val packageName = "bnc.bncnet.mobile2"
        val title = "PAGO MOVIL RECIBIDO"
        val text = "BNC Pago Movil Recibido Bs.1000,00 Telf.0424***3112 Dia:06/11/26-09:59 Ref..."

        assertTrue(parser.canHandle(packageName, title, text))

        val payment = parser.parse(packageName, title, text, 1729710000000L)
        assertNotNull(payment)
        assertEquals(1000.0, payment!!.monto, 0.001)
        assertEquals(BankCode.BNC, payment.bancoCodigo)
        assertEquals("0424***3112", payment.telefonoOrigen)
        assertFalse(payment.referencia.isBlank())
        assertFalse(payment.isOutgoing)
    }

    @Test
    fun testBncOfficialPackagesAreWhitelisted() {
        val packages = BankCode.BNC.knownPackageNames
        assertTrue(packages.contains("bnc.bncnet.mobile2"))
        assertTrue(packages.contains("com.bnc.ve"))
    }
}

