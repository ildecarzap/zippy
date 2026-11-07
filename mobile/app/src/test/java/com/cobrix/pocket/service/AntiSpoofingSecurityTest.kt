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
            "com.discord",
            "com.google.android.gm",
            "com.microsoft.office.outlook",
            "com.yahoo.mobile.client.android.mail"
        )

        for (pkg in packages) {
            assertTrue("El paquete $pkg debe ser reconocido como app prohibida", ParserUtils.isProhibitedMessagingPackage(pkg))
        }
    }

    @Test
    fun testEmailAndContactNameSmsSpoofingAreBlocked() {
        val fakeBdvText = "BDV: PagoClave recibido por Bs. 950,00 de 04141234567. Ref: 7788"

        // 1. Correos falsos en Gmail / Outlook fingiendo ser el banco
        assertNull(router.routeAndParse("com.google.android.gm", "Banco de Venezuela", fakeBdvText, System.currentTimeMillis()))
        assertNull(router.routeAndParse("com.microsoft.office.outlook", "BDV", fakeBdvText, System.currentTimeMillis()))

        // 2. Contacto agendado con el nombre del banco enviando un SMS falso
        val smsPkg = "com.google.android.apps.messaging"
        val spoofedContactNames = listOf("BDV", "Banco de Venezuela", "Banesco", "Mercantil", "BNC", "Ubii")
        for (contactName in spoofedContactNames) {
            assertFalse("Un contacto alfabético ($contactName) no debe pasar como shortcode oficial",
                ParserUtils.isLegitimateBankSmsSender(contactName))
            assertNull("SMS de contacto agendado como '$contactName' debe ser bloqueado",
                router.routeAndParse(smsPkg, contactName, fakeBdvText, System.currentTimeMillis()))
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
        assertTrue(veWhitelist.contains("com.mercantilbanco.empresas"))
        assertTrue(veWhitelist.contains("bnc.bncnet.mobile2"))
        assertTrue(veWhitelist.contains("com.bnc.ve"))
        assertTrue(veWhitelist.contains("com.bancamiga.movil"))
        assertTrue(veWhitelist.contains("com.ubiipagos.app"))
        assertTrue(veWhitelist.contains("ubii.ubiipagos.com.ubiiapp"))

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

    @Test
    fun testBdvSms2661And2662AcceptedAsBdv() {
        val smsPkg = "com.google.android.apps.messaging"
        val text = "BDV: PagoClave recibido por Bs. 150,00 de 04141234567. Ref: 9876"

        val res2661 = router.routeAndParse(smsPkg, "2661", text, System.currentTimeMillis())
        assertNotNull(res2661)
        assertEquals(BankCode.BDV, res2661!!.bancoCodigo)
        assertEquals(150.0, res2661.monto, 0.001)

        val res2662 = router.routeAndParse(smsPkg, "2662", text, System.currentTimeMillis())
        assertNotNull(res2662)
        assertEquals(BankCode.BDV, res2662!!.bancoCodigo)
        assertEquals(150.0, res2662.monto, 0.001)
    }

    @Test
    fun testMercadoPagoSingularTransferAccepted() {
        val mpPkg = "com.mercadopago.wallet"
        val title = "Martín te transfirió $ 1.500"
        val text = "De Martín Gomez a tu cuenta de Mercado Pago"

        assertTrue(mpParser.canHandle(mpPkg, title, text))
        val result = mpParser.parse(mpPkg, title, text, System.currentTimeMillis())
        assertNotNull(result)
        assertEquals(1500.0, result!!.monto, 0.001)
        assertEquals(BankCode.AR_MERCADOPAGO, result.bancoCodigo)
    }

    @Test
    fun testUbiiAppOfficialPackageAccepted() {
        val ubiiPkg = "ubii.ubiipagos.com.ubiiapp"
        val title = "Pago Móvil Recibido"
        val text = "Has recibido Bs. 80,00 de Juan Pérez por Pago Móvil Ubii"

        assertTrue(ubiiParser.canHandle(ubiiPkg, title, text))
        val result = ubiiParser.parse(ubiiPkg, title, text, System.currentTimeMillis())
        assertNotNull(result)
        assertEquals(80.0, result!!.monto, 0.001)
        assertEquals(BankCode.UBII, result.bancoCodigo)
    }

    @Test
    fun testMercantilEmpresasPackageAccepted() {
        val mercantilPkg = "com.mercantilbanco.empresas"
        val title = "Operación Exitosa"
        val text = "Tpago recibido por Bs. 250,00 de 04241234567. Ref: 1122"

        assertTrue(mercantilParser.canHandle(mercantilPkg, title, text))
        val result = mercantilParser.parse(mercantilPkg, title, text, System.currentTimeMillis())
        assertNotNull(result)
        assertEquals(250.0, result!!.monto, 0.001)
        assertEquals(BankCode.MERCANTIL, result.bancoCodigo)
    }
}

