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

    @Test
    fun testBncMorningAndNightSameSbnKeyAreNotBlockedByDeduplicator() {
        com.cobrix.pocket.service.utils.PaymentDeduplicator.clear()
        val reusedSbnKey = "0|bnc.bncnet.mobile2|0|null|10234"

        // 1. Cobro en la mañana (09:59)
        val morningTitle = "PAGO MOVIL RECIBIDO"
        val morningText = "BNC Pago Movil Recibido Bs.1000,00 Telf.0424***3112 Dia:06/11/26-09:59 Ref..."
        val morningPostTime = 1730896740000L
        val morningSig = "$reusedSbnKey|$morningPostTime|${morningTitle.hashCode()}|${morningText.hashCode()}"

        assertFalse(com.cobrix.pocket.service.utils.PaymentDeduplicator.isNotificationKeyAlreadyProcessed(morningSig))
        val morningPayment = parser.parse("bnc.bncnet.mobile2", morningTitle, morningText, morningPostTime)
        assertNotNull(morningPayment)
        assertFalse(com.cobrix.pocket.service.utils.PaymentDeduplicator.isPaymentDuplicate(morningPayment!!))
        com.cobrix.pocket.service.utils.PaymentDeduplicator.markNotificationKeyProcessed(morningSig)

        // 2. Cobro en la noche (20:45) reutilizando el mismo sbn.key
        val nightTitle = "PAGO MOVIL RECIBIDO"
        val nightText = "BNC Pago Movil Recibido Bs.1000,00 Telf.0424***3112 Dia:06/11/26-20:45 Ref..."
        val nightPostTime = 1730935500000L
        val nightSig = "$reusedSbnKey|$nightPostTime|${nightTitle.hashCode()}|${nightText.hashCode()}"

        assertFalse("El pago nocturno con mismo sbn.key pero distinto timestamp/texto NO debe bloquearse",
            com.cobrix.pocket.service.utils.PaymentDeduplicator.isNotificationKeyAlreadyProcessed(nightSig))
        val nightPayment = parser.parse("bnc.bncnet.mobile2", nightTitle, nightText, nightPostTime)
        assertNotNull(nightPayment)
        assertFalse("El pago nocturno debe tener firma de pago distinta al de la mañana",
            com.cobrix.pocket.service.utils.PaymentDeduplicator.isPaymentDuplicate(nightPayment!!))
    }
}

