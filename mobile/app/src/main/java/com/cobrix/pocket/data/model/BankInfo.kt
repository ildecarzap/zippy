package com.cobrix.pocket.data.model

/**
 * Catálogo multipaís de bancos y billeteras (Venezuela, Colombia, Argentina)
 * e identificadores para filtrado y enrutamiento inteligente de notificaciones.
 */
enum class BankCode(
    val sudebanCode: String,
    val displayName: String,
    val defaultKeywords: List<String>,
    val knownPackageNames: List<String>,
    val country: CountryCode = CountryCode.VE
) {
    // --- VENEZUELA (VE) ---
    MERCANTIL(
        sudebanCode = "0105",
        displayName = "Banco Mercantil",
        defaultKeywords = listOf("tpago", "mercantil"),
        knownPackageNames = listOf(
            "com.mercantilbanco.mercantilmovil",
            "com.mercantilbanco.empresas",
            "com.mercantilbanco.android.movil",
            "com.mercantil.movil",
            "com.mercantilbanco.movil",
            "com.mercantilbanco",
            "com.mercantil.personas",
            "com.mercantilbanco.personas"
        ),
        country = CountryCode.VE
    ),
    BANESCO(
        sudebanCode = "0134",
        displayName = "Banesco",
        defaultKeywords = listOf("banesco", "pago móvil", "pago movil"),
        knownPackageNames = listOf(
            "com.banesco.samfbancamovilunificada",
            "com.banesco.banescomovil",
            "com.banesco.movil",
            "com.banesco",
            "com.banescomovil"
        ),
        country = CountryCode.VE
    ),
    BDV(
        sudebanCode = "0102",
        displayName = "Banco de Venezuela",
        defaultKeywords = listOf("bdv", "pagoclave", "pago clave", "venezuela", "pagomovil", "pago movil", "pagomóvil", "pago móvil"),
        knownPackageNames = listOf(
            "com.bancodevenezuela.bdvdigital",
            "com.bancodevenezuela.app",
            "com.bancodevenezuela.bdvenlinea",
            "com.bancodevenezuela.personas",
            "com.bancodevenezuela"
        ),
        country = CountryCode.VE
    ),
    PROVINCIAL(
        sudebanCode = "0108",
        displayName = "BBVA Provincial",
        defaultKeywords = listOf("provincial", "bbva"),
        knownPackageNames = emptyList(), // Desactivado por capturas ambiguas de pagos salientes
        country = CountryCode.VE
    ),
    BNC(
        sudebanCode = "0191",
        displayName = "Banco Nacional de Crédito",
        defaultKeywords = listOf("bnc", "pago movil recibido", "pago móvil recibido", "banco nacional de credito", "banco nacional de crédito"),
        knownPackageNames = listOf(
            "bnc.bncnet.mobile2",
            "bnc.bncnet.mobile",
            "com.bnc.ve",
            "com.bnc.movil",
            "com.bnc.app",
            "com.bnc.personas",
            "com.bncenlinea"
        ),
        country = CountryCode.VE
    ),
    BANCAMIGA(
        sudebanCode = "0172",
        displayName = "Bancamiga",
        defaultKeywords = listOf("bancamiga"),
        knownPackageNames = listOf(
            "com.bancamiga",
            "com.bancamiga.movil",
            "com.bancamiga.bancamigamovil",
            "com.bancamiga.personas"
        ),
        country = CountryCode.VE
    ),
    UBII(
        sudebanCode = "0178",
        displayName = "Ubii App",
        defaultKeywords = listOf("ubii", "ubiipagos", "pago móvil", "pago movil", "0178"),
        knownPackageNames = listOf(
            "ubii.ubiipagos.com.ubiiapp",
            "com.ubiipagos.ubii",
            "com.ubiiapp.ubii",
            "com.ubiipagos.app",
            "com.ubii.app",
            "com.ubii.ubiipagos",
            "com.ubii",
            "ubii.ubiipagos.com",
            "com.ubapp",
            "com.ub.app",
            "com.ubiipagos.ubapp"
        ),
        country = CountryCode.VE
    ),

    // --- COLOMBIA (CO) ---
    CO_NEQUI(
        sudebanCode = "CO_NEQUI",
        displayName = "Nequi",
        defaultKeywords = listOf("nequi", "recibiste", "te envió", "te envio", "envio"),
        knownPackageNames = listOf("com.nequi.MobileApp", "com.nequi.app", "com.nequi"),
        country = CountryCode.CO
    ),
    CO_DAVIPLATA(
        sudebanCode = "CO_DAVI",
        displayName = "DaviPlata",
        defaultKeywords = listOf("daviplata", "recibio", "recibiste", "pasar plata"),
        knownPackageNames = listOf("com.davivienda.daviplataapp", "com.davivienda.daviplata", "com.daviplata"),
        country = CountryCode.CO
    ),
    CO_BANCOLOMBIA(
        sudebanCode = "CO_BCOL",
        displayName = "Bancolombia",
        defaultKeywords = listOf("bancolombia", "recibiste una transferencia", "transferencia", "qr bancolombia"),
        knownPackageNames = listOf("KT.Bancolombia", "com.todo1.mobile", "com.bancolombia.alamanov2", "com.bancolombia.personas", "co.com.bancolombia"),
        country = CountryCode.CO
    ),

    // --- ARGENTINA (AR) ---
    AR_MERCADOPAGO(
        sudebanCode = "AR_MP",
        displayName = "Mercado Pago",
        defaultKeywords = listOf("mercado pago", "mercadopago", "te transfirieron", "te transfirio", "te transfirió", "recibiste", "cobro aprobado", "cobraste", "transferencia"),
        knownPackageNames = listOf(
            "com.mercadopago.wallet",
            "com.mercadolibre",
            "com.mercadopago",
            "com.mercadolibre.android"
        ),
        country = CountryCode.AR
    ),
    AR_CUENTADNI(
        sudebanCode = "AR_CDNI",
        displayName = "Cuenta DNI",
        defaultKeywords = listOf("cuenta dni", "recibiste una transferencia", "banco provincia", "acredit"),
        knownPackageNames = listOf("ar.com.bapro.cuentadni", "com.bapro.cuentadni"),
        country = CountryCode.AR
    ),
    AR_BNA_MAS(
        sudebanCode = "AR_BNA",
        displayName = "BNA+ (Banco Nación)",
        defaultKeywords = listOf("bna+", "banco nacion", "recibiste", "transferencia recibida", "modo"),
        knownPackageNames = listOf("com.bna.bnamas", "ar.com.bna.bnamas"),
        country = CountryCode.AR
    ),
    AR_UALA(
        sudebanCode = "AR_UALA",
        displayName = "Ualá",
        defaultKeywords = listOf("ualá", "uala", "recibiste una transferencia", "te enviaron", "te transfirieron"),
        knownPackageNames = listOf("com.uala", "ar.com.uala", "com.itau.uala"),
        country = CountryCode.AR
    ),

    // --- TRANSVERSALES (SMS & BINANCE PAY) ---
    SMS(
        sudebanCode = "0000",
        displayName = "Mensajes SMS",
        defaultKeywords = listOf("recibiste", "pago recibido", "tpago", "ref", "bs", "transferencia"),
        knownPackageNames = listOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms"
        )
    ),
    BINANCE(
        sudebanCode = "BINANCE",
        displayName = "Binance Pay",
        defaultKeywords = listOf("binance", "binance pay", "usdt", "you have received", "has recibido", "order"),
        knownPackageNames = listOf("com.binance.dev")
    );

    /**
     * Evalúa si el nombre de paquete de Android pertenece a la aplicación oficial de este banco o billetera,
     * combinando la lista blanca explícita con tokens institucionales de paquete para tolerar sufijos de versión.
     */
    fun matchesPackage(packageName: String): Boolean {
        val pkg = packageName.lowercase(java.util.Locale.ROOT)
        if (knownPackageNames.any { it.equals(pkg, ignoreCase = true) }) return true
        return when (this) {
            MERCANTIL -> pkg.contains("mercantil")
            BANESCO -> pkg.contains("banesco")
            BDV -> pkg.contains("bdv") || pkg.contains("bancodevenezuela")
            PROVINCIAL -> false
            BNC -> pkg.contains("bnc")
            BANCAMIGA -> pkg.contains("bancamiga")
            UBII -> pkg.contains("ubii") || pkg.contains("ubapp") || pkg.startsWith("com.ub.")
            CO_NEQUI -> pkg.contains("nequi")
            CO_DAVIPLATA -> pkg.contains("daviplata") || pkg.contains("davivienda")
            CO_BANCOLOMBIA -> pkg.contains("bancolombia") || pkg.contains("todo1")
            AR_MERCADOPAGO -> pkg.contains("mercadopago") || pkg.contains("mercadolibre")
            AR_CUENTADNI -> pkg.contains("cuentadni") || pkg.contains("bapro")
            AR_BNA_MAS -> pkg.contains("bnamas") || pkg.contains("bna")
            AR_UALA -> pkg.contains("uala")
            SMS -> false
            BINANCE -> pkg.contains("binance")
        }
    }

    companion object {
        fun fromCode(code: String?): BankCode? {
            return values().firstOrNull { it.name.equals(code, ignoreCase = true) }
        }

        fun fromSudeban(code: String?): BankCode? {
            return values().firstOrNull { it.sudebanCode.equals(code, ignoreCase = true) }
        }
    }
}

data class BankCatalogEntry(
    val code: String,
    val name: String,
    val hasAutoDetection: Boolean,
    val country: CountryCode = CountryCode.VE
) {
    val shortTag: String get() = getShortName()
    override fun toString(): String = "$code - $name"
    fun getShortName(): String = BankCatalog.getShortName(code)
    fun getBrandColor(): Int = BankCatalog.getBrandColor(code)
}

object BankCatalog {

    val VE_BANKS = listOf(
        BankCatalogEntry("0102", "Banco de Venezuela (BDV)", true, CountryCode.VE),
        BankCatalogEntry("0134", "Banesco Banco Universal", true, CountryCode.VE),
        BankCatalogEntry("0105", "Banco Mercantil", true, CountryCode.VE),
        BankCatalogEntry("0191", "Banco Nacional de Crédito (BNC)", true, CountryCode.VE),
        BankCatalogEntry("0172", "Bancamiga", true, CountryCode.VE),
        BankCatalogEntry("0178", "Ubii App", true, CountryCode.VE)
    )

    val CO_BANKS = listOf(
        BankCatalogEntry("CO_NEQUI", "Nequi Colombia", true, CountryCode.CO),
        BankCatalogEntry("CO_DAVI", "DaviPlata", true, CountryCode.CO),
        BankCatalogEntry("CO_BCOL", "Bancolombia / A la Mano", true, CountryCode.CO)
    )

    val AR_BANKS = listOf(
        BankCatalogEntry("AR_MP", "Mercado Pago", true, CountryCode.AR),
        BankCatalogEntry("AR_CDNI", "Cuenta DNI (Banco Provincia)", true, CountryCode.AR),
        BankCatalogEntry("AR_BNA", "BNA+ (Banco Nación / MODO)", true, CountryCode.AR),
        BankCatalogEntry("AR_UALA", "Ualá", true, CountryCode.AR)
    )

    val ALL_BANKS = VE_BANKS + CO_BANKS + AR_BANKS

    fun getBanksForCountry(country: CountryCode): List<BankCatalogEntry> {
        return when (country) {
            CountryCode.VE -> VE_BANKS
            CountryCode.CO -> CO_BANKS
            CountryCode.AR -> AR_BANKS
        }
    }

    fun getDefaultBankForCountry(country: CountryCode): BankCatalogEntry {
        return when (country) {
            CountryCode.VE -> VE_BANKS.first { it.code == "0102" } // BDV
            CountryCode.CO -> CO_BANKS.first { it.code == "CO_NEQUI" } // Nequi
            CountryCode.AR -> AR_BANKS.first { it.code == "AR_MP" } // Mercado Pago
        }
    }

    fun findByCode(code: String): BankCatalogEntry? {
        return ALL_BANKS.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }

    fun findByNameOrCode(query: String): BankCatalogEntry? {
        val raw = query.trim()
        if (raw.isEmpty()) return null
        val codeCandidate = if (raw.contains(" - ")) raw.substringBefore(" - ").trim() else raw.take(8).trim()
        val byCode = findByCode(codeCandidate)
        if (byCode != null) return byCode

        val q = raw.lowercase()
        return ALL_BANKS.firstOrNull { entry ->
            entry.code.lowercase() == q ||
            entry.name.lowercase().contains(q) ||
            q.contains(entry.code.lowercase()) ||
            q.contains(entry.name.lowercase())
        }
    }

    fun getShortName(code: String): String {
        return when (code.uppercase()) {
            "0178" -> "Ubii App"
            "0134" -> "Banesco"
            "0102" -> "BDV"
            "0105" -> "Mercantil"
            "0108" -> "Provincial"
            "0191" -> "BNC"
            "0172" -> "Bancamiga"
            "0114" -> "Bancaribe"
            "0174" -> "Banplus"
            "0138" -> "Banco Plaza"
            "0175" -> "Bicentenario"
            "0163" -> "Banco del Tesoro"
            "0151" -> "Fondo Común"
            "0115" -> "Exterior"
            "0156" -> "100% Banco"
            "0171" -> "Activo"
            "0104" -> "Venezolano de Crédito"
            "CO_NEQUI" -> "Nequi"
            "CO_DAVI" -> "DaviPlata"
            "CO_BCOL" -> "Bancolombia"
            "CO_DAVIV" -> "Davivienda"
            "CO_BBVA" -> "BBVA CO"
            "CO_BOG" -> "B. Bogotá"
            "AR_MP" -> "Mercado Pago"
            "AR_CDNI" -> "Cuenta DNI"
            "AR_BNA" -> "BNA+"
            "AR_UALA" -> "Ualá"
            "AR_GAL" -> "Galicia"
            "AR_BRU" -> "Brubank"
            "AR_SANT" -> "Santander AR"
            else -> findByCode(code)?.name ?: code
        }
    }

    fun getBrandColor(code: String): Int {
        return when (code.uppercase()) {
            "0178" -> android.graphics.Color.parseColor("#00B2FE") // Ubii Cyan
            "0134" -> android.graphics.Color.parseColor("#00875A") // Banesco Green
            "0102" -> android.graphics.Color.parseColor("#D32F2F") // BDV Red
            "0105" -> android.graphics.Color.parseColor("#0052FF") // Mercantil Blue
            "0108" -> android.graphics.Color.parseColor("#003E7E") // Provincial Navy
            "0191" -> android.graphics.Color.parseColor("#1B5E20") // BNC Forest Green
            "0172" -> android.graphics.Color.parseColor("#00838F") // Bancamiga Teal
            "0114" -> android.graphics.Color.parseColor("#0277BD") // Bancaribe
            "0174" -> android.graphics.Color.parseColor("#C2185B") // Banplus
            "0138" -> android.graphics.Color.parseColor("#E65100") // Plaza
            "CO_NEQUI" -> android.graphics.Color.parseColor("#DA0081") // Nequi Magenta
            "CO_DAVI" -> android.graphics.Color.parseColor("#ED1C24") // DaviPlata Red
            "CO_BCOL" -> android.graphics.Color.parseColor("#00448D") // Bancolombia Blue
            "AR_MP" -> android.graphics.Color.parseColor("#009EE3") // Mercado Pago Cyan
            "AR_CDNI" -> android.graphics.Color.parseColor("#00875A") // Cuenta DNI Green
            "AR_BNA" -> android.graphics.Color.parseColor("#00529B") // BNA+ Blue
            "AR_UALA" -> android.graphics.Color.parseColor("#E61E54") // Ualá Red/Pink
            else -> android.graphics.Color.parseColor("#0A2540")
        }
    }
}
