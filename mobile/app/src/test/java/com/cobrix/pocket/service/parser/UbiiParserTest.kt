package com.cobrix.pocket.service.parser

import com.cobrix.pocket.data.model.BankCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UbiiParserTest {

    private val parser = UbiiParser()

    @Test
    fun testUbiiPayment1800NotTruncated() {
        val packageName = "com.ubiipagos.app"
        val title = "Pago Móvil Recibido"
        val text = "Recibiste un pago móvil de Bs. 1800,00 de 04141234567. Ref: 0515"

        assertTrue(parser.canHandle(packageName, title, text))

        val payment = parser.parse(packageName, title, text, 1729710000000L)
        assertNotNull(payment)
        assertEquals(1800.0, payment!!.monto, 0.001)
        assertEquals("0515", payment.referenciaUltimos4)
        assertEquals(BankCode.UBII, payment.bancoCodigo)
    }

    @Test
    fun testUbiiPayment1800WithThousandDot() {
        val packageName = "com.ubiipagos.app"
        val title = "Ubii Pagos"
        val text = "Has recibido un pago móvil por Bs. 1.800,00. Ref: 3819"

        assertTrue(parser.canHandle(packageName, title, text))

        val payment = parser.parse(packageName, title, text, 1729710000000L)
        assertNotNull(payment)
        assertEquals(1800.0, payment!!.monto, 0.001)
        assertEquals("3819", payment.referenciaUltimos4)
    }

    @Test
    fun testUbiiPaymentFromProvincial() {
        val packageName = "com.ubiipagos.app"
        val title = "Pago Móvil"
        val text = "Recibiste Bs. 30,00 del Banco Provincial. Ref: 3819. Tel: 04141234567"

        assertTrue(parser.canHandle(packageName, title, text))

        val payment = parser.parse(packageName, title, text, 1729710000000L)
        assertNotNull(payment)
        assertEquals(30.0, payment!!.monto, 0.001)
        assertEquals("3819", payment.referenciaUltimos4)
        assertEquals("BBVA Provincial", payment.bancoEmisor)
        assertEquals(BankCode.UBII, payment.bancoCodigo)
    }

    @Test
    fun testAmountParserUtilsVariousFormats() {
        val amounts = listOf(
            "Bs. 1.800,00" to 1800.0,
            "Bs. 1800,00" to 1800.0,
            "Bs 1800.00" to 1800.0,
            "Bs. 1,800.00" to 1800.0,
            "Bs. 1.800" to 1800.0,
            "Bs 1800" to 1800.0,
            "Bs. 30,00" to 30.0,
            "Bs. 30.00" to 30.0,
            "Bs 30" to 30.0,
            "Bs. 900,00" to 900.0,
            "Bs 900" to 900.0,
            "Bs. 12.500,50" to 12500.5,
            "Bs 12500,50" to 12500.5,
            "Bs 12500" to 12500.0
        )

        for ((text, expected) in amounts) {
            val extracted = ParserUtils.extractAmount(text)
            assertNotNull("Fallo al extraer monto de '$text'", extracted)
            assertEquals("Monto incorrecto para '$text'", expected, extracted!!.first, 0.001)
        }
    }
}
