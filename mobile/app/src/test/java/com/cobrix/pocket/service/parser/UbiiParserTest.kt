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
    fun testUbiiPaymentFromUserScreenshot_UbApp() {
        val packagesToTest = listOf(
            "ubii.ubiipagos.com.ubiiapp",
            "com.ubiipagos.ubii",
            "com.ubapp"
        )
        val title = "Transacción recibida"
        val text = "Has recibido Bs. 1180.00 por pago móvil del número 0424-260.31.12 con referencia 231038151407"

        for (pkg in packagesToTest) {
            assertTrue("UbiiParser debe aceptar paquete $pkg", parser.canHandle(pkg, title, text))
            val payment = parser.parse(pkg, title, text, 1730900000000L)
            assertNotNull("El cobro de Ub App debe parsearse correctamente para $pkg", payment)
            assertEquals(1180.0, payment!!.monto, 0.001)
            assertEquals("231038151407", payment.referencia)
            assertEquals("1407", payment.referenciaUltimos4)
            assertEquals("04242603112", payment.telefonoOrigen)
            assertEquals(BankCode.UBII, payment.bancoCodigo)
        }
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
