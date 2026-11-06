package com.cobrix.pocket.service

import com.cobrix.pocket.data.model.BankCode
import com.cobrix.pocket.data.model.CountryCode
import com.cobrix.pocket.data.model.CountryProfileCatalog
import com.cobrix.pocket.service.parser.BanescoParser
import com.cobrix.pocket.service.parser.BdvParser
import com.cobrix.pocket.service.parser.MercadoPagoParser
import com.cobrix.pocket.service.parser.MultibankParserRouter
import com.cobrix.pocket.service.parser.NequiParser
import com.cobrix.pocket.service.parser.ParserUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias de seguridad anti-suplantación (Anti-Spoofing / False Payment Injection).
 * Verifica que ninguna notificación de mensajería (WhatsApp, Telegram, etc.) o SMS de número personal
 * pueda ser interpretada o aceptada como un pago legítimo.
 */
class AntiSpoofingSecurityTest {

    private val router = MultibankParserRouter()
    private val bdvParser = BdvParser()
    private val banescoParser = BanescoParser()
    private val mercantilParser = com.cobrix.pocket.service.parser.MercantilParser()
    private val provincialParser = com.cobrix.pocket.service.parser.ProvincialParser()
    private val bncParser = com.cobrix.pocket.service.parser.BncParser()
    private val bancamigaParser = com.cobrix.pocket.service.parser.BancamigaParser()
    private val ubiiParser = com.cobrix.pocket.service.parser.UbiiParser()
    private val binanceParser = com.cobrix.pocket.service.parser.BinancePayParser()
    private val mpParser = MercadoPagoParser()
    private val cuentaDniParser = com.cobrix.pocket.service.parser.CuentaDniParser()
    private val bnaMasParser = com.cobrix.pocket.service.parser.BnaMasParser()
    private val nequiParser = NequiParser()
    private val daviPlataParser = com.cobrix.pocket.service.parser.DaviPlataParser()
    private val bancolombiaParser = com.cobrix.pocket.service.parser.BancolombiaParser()

    @Test
    fun testWhatsAppMessagingNotificationIsBlockedByBlacklist() {
        val packages = listOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.facebook.orca",
            "com.instagram.android",
            "org.thoughtcrime.securesms",
            "com.discord"
        )

        for (pkg in packages) {
            assertTrue("El paquete $pkg debe ser reconocido como app prohibida", ParserUtils.isProhibitedMessagingPackage(pkg))
        }
    }

    @Test
    fun testWhatsAppDebtMessageSpoofingIsRejectedByAllParsers() {
        val whatsappPkg = "com.whatsapp"
        val whatsappBusinessPkg = "com.whatsapp.w4b"
        val title = "Ernesto (WhatsApp)"
        val text = "👋 Hola Ernesto, un cordial saludo de Mi Comercio. Datos para Pago Móvil: Banco de Venezuela. Monto exacto a transferir: Bs. 1500.00 Ref: 8941"

        // 1. Venezuela: BDV, Banesco, Mercantil, Provincial, BNC, Bancamiga, Ubii
        assertFalse(bdvParser.canHandle(whatsappPkg, title, text))
        assertFalse(bdvParser.canHandle(whatsappBusinessPkg, title, text))
        assertFalse(banescoParser.canHandle(whatsappPkg, title, text))
        assertFalse(mercantilParser.canHandle(whatsappPkg, title, text))
        assertFalse(provincialParser.canHandle(whatsappPkg, title, text))
        assertFalse(bncParser.canHandle(whatsappPkg, title, text))
        assertFalse(bancamigaParser.canHandle(whatsappPkg, title, text))
        assertFalse(ubiiParser.canHandle(whatsappPkg, title, text))

        // 2. Argentina: Mercado Pago, Cuenta DNI, BNA+
        assertFalse(mpParser.canHandle(whatsappPkg, title, text))
        assertFalse(cuentaDniParser.canHandle(whatsappPkg, title, text))
        assertFalse(bnaMasParser.canHandle(whatsappPkg, title, text))

        // 3. Colombia: Nequi, DaviPlata, Bancolombia
        assertFalse(nequiParser.canHandle(whatsappPkg, title, text))
        assertFalse(daviPlataParser.canHandle(whatsappPkg, title, text))
        assertFalse(bancolombiaParser.canHandle(whatsappPkg, title, text))

        // 4. Cripto: Binance Pay
        assertFalse(binanceParser.canHandle(whatsappPkg, title, text))

        // 5. Verificar que el Router central bloquee y retorne null inmediatamente
        val result = router.routeAndParse(whatsappPkg, title, text, System.currentTimeMillis())
        assertNull("Zeasy NUNCA debe procesar notificaciones provenientes de WhatsApp", result)

        val resultBusiness = router.routeAndParse(whatsappBusinessPkg, title, text, System.currentTimeMillis())
        assertNull("Zeasy NUNCA debe procesar notificaciones provenientes de WhatsApp Business", resultBusiness)
    }

    @Test
    fun testTelegramMessageSpoofingIsRejected() {
        val telegramPkg = "org.telegram.messenger"
        val title = "Cliente Telegram"
        val text = "Te envié el pago móvil de Bs. 850,00 por Banesco. Referencia 4512"

        assertFalse(banescoParser.canHandle(telegramPkg, title, text))
        val result = router.routeAndParse(telegramPkg, title, text, System.currentTimeMillis())
        assertNull("Zeasy NUNCA debe procesar notificaciones provenientes de Telegram", result)
    }

    @Test
    fun testPersonalMobileNumberSmsIsRejected() {
        val smsPkg = "com.google.android.apps.messaging"
        val personalPhoneTitles = listOf(
            "04141234567",
            "04245551234",
            "04127894561",
            "+584141234567",
            "+584249876543",
            "3001234567",      // Colombia
            "+573001234567",
            "1123456789",      // Argentina
            "+549112345678"
        )
        val text = "BDV: PagoClave recibido por Bs. 500,00 de 04140000000. Ref: 1234"

        for (sender in personalPhoneTitles) {
            assertFalse(
                "El remitente $sender debe ser rechazado por ser teléfono personal",
                ParserUtils.isLegitimateBankSmsSender(sender)
            )

            val result = router.routeAndParse(smsPkg, sender, text, System.currentTimeMillis())
            assertNull("SMS simulado desde $sender debe ser descartado sin procesar", result)
        }
    }

    @Test
    fun testOfficialBankSmsShortcodeIsAccepted() {
        val smsPkg = "com.google.android.apps.messaging"
        val officialShortcode = "26371" // BDV oficial
        val text = "BDV: PagoClave recibido por Bs. 500,00 de 04140000000. Ref: 1234"

        assertTrue(ParserUtils.isLegitimateBankSmsSender(officialShortcode))
        val result = router.routeAndParse(smsPkg, officialShortcode, text, System.currentTimeMillis())
        assertNotNull("SMS oficial de shortcode 26371 debe ser aceptado", result)
        assertEquals(500.0, result!!.monto, 0.001)
        assertEquals(BankCode.BDV, result.bancoCodigo)
    }

    @Test
    fun testOfficialBankPushIsAccepted() {
        val bdvPkg = "com.bancodevenezuela.app"
        val title = "Pago móvil recibido"
        val text = "Recibiste un pago móvil de Bs. 300,00 de 04141234567. Ref: 5544"

        assertTrue(bdvParser.canHandle(bdvPkg, title, text))
        val result = router.routeAndParse(bdvPkg, title, text, System.currentTimeMillis())
        assertNotNull("Notificación push de app oficial BDV debe ser aceptada", result)
        assertEquals(300.0, result!!.monto, 0.001)
        assertEquals(BankCode.BDV, result.bancoCodigo)
    }

    @Test
    fun testCountryProfileWhitelistIntegrity() {
        val veWhitelist = CountryProfileCatalog.getAllowedPackagesForCountry(CountryCode.VE, includeSms = true, includeBinance = true)
        val coWhitelist = CountryProfileCatalog.getAllowedPackagesForCountry(CountryCode.CO, includeSms = false, includeBinance = true)
        val arWhitelist = CountryProfileCatalog.getAllowedPackagesForCountry(CountryCode.AR, includeSms = false, includeBinance = true)

        // Ninguna whitelist debe contener aplicaciones de mensajería
        val forbidden = listOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.facebook.orca")
        for (f in forbidden) {
            assertFalse("VE whitelist no debe contener $f", veWhitelist.contains(f))
            assertFalse("CO whitelist no debe contener $f", coWhitelist.contains(f))
            assertFalse("AR whitelist no debe contener $f", arWhitelist.contains(f))
        }

        // Venezuela debe contener apps bancarias oficiales verificadas
        assertTrue(veWhitelist.contains("com.bancodevenezuela.bdvdigital"))
        assertTrue(veWhitelist.contains("com.banesco.banescomovil"))
        assertTrue(veWhitelist.contains("com.mercantilbanco.android.movil"))
        assertTrue(veWhitelist.contains("bnc.bncnet.mobile2"))
        assertTrue(veWhitelist.contains("com.bnc.ve"))
        assertTrue(veWhitelist.contains("com.bancamiga.movil"))
        assertTrue(veWhitelist.contains("com.ubiipagos.app"))

        // Colombia debe contener Nequi, DaviPlata, Bancolombia
        assertTrue(coWhitelist.contains("com.nequi.mobileapp"))
        assertTrue(coWhitelist.contains("com.davivienda.daviplataapp"))
        assertTrue(coWhitelist.contains("kt.bancolombia"))

        // Argentina debe contener Mercado Pago, Cuenta DNI, BNA+, Ualá
        assertTrue(arWhitelist.contains("com.mercadopago.wallet"))
        assertTrue(arWhitelist.contains("ar.com.bapro.cuentadni"))
        assertTrue(arWhitelist.contains("com.bna.bnamas"))
        assertTrue(arWhitelist.contains("com.uala"))
    }
}

