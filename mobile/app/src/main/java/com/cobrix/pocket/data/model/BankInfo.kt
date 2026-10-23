package com.cobrix.pocket.data.model

/**
 * Catálogo de bancos venezolanos e identificadores para filtrado y enrutamiento inteligente.
 */
enum class BankCode(
    val sudebanCode: String,
    val displayName: String,
    val defaultKeywords: List<String>,
    val knownPackageNames: List<String>
) {
    MERCANTIL(
        sudebanCode = "0105",
        displayName = "Banco Mercantil",
        defaultKeywords = listOf("tpago", "mercantil"),
        knownPackageNames = listOf("com.mercantilbanco.android.movil")
    ),
    BANESCO(
        sudebanCode = "0134",
        displayName = "Banesco",
        defaultKeywords = listOf("banesco", "pago móvil", "pago movil"),
        knownPackageNames = listOf("com.banesco.banescomovil")
    ),
    BDV(
        sudebanCode = "0102",
        displayName = "Banco de Venezuela",
        defaultKeywords = listOf("bdv", "pagoclave", "pago clave", "venezuela"),
        knownPackageNames = listOf("com.bancodevenezuela.bdvdigital")
    ),
    PROVINCIAL(
        sudebanCode = "0108",
        displayName = "BBVA Provincial",
        defaultKeywords = listOf("provincial", "bbva"),
        knownPackageNames = listOf("com.bbva.provincial.android")
    ),
    BNC(
        sudebanCode = "0191",
        displayName = "Banco Nacional de Crédito",
        defaultKeywords = listOf("bnc", "banco nacional de credito"),
        knownPackageNames = listOf("com.bnc.movil")
    ),
    BANCAMIGA(
        sudebanCode = "0172",
        displayName = "Bancamiga",
        defaultKeywords = listOf("bancamiga"),
        knownPackageNames = listOf("com.bancamiga.movil")
    ),
    UBII(
        sudebanCode = "0178",
        displayName = "Ubii App",
        defaultKeywords = listOf("ubii", "ubiipagos", "pago móvil", "pago movil", "0178"),
        knownPackageNames = listOf("com.ubiipagos.app", "com.ubii.app", "com.ubii.ubiipagos", "com.ubii")
    ),
    SMS(
        sudebanCode = "0000",
        displayName = "Mensajes SMS",
        defaultKeywords = listOf("recibiste", "pago recibido", "tpago", "ref", "bs"),
        knownPackageNames = listOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms"
        )
    );

    companion object {
        fun fromCode(code: String?): BankCode? {
            return values().firstOrNull { it.name.equals(code, ignoreCase = true) }
        }

        fun fromSudeban(code: String?): BankCode? {
            return values().firstOrNull { it.sudebanCode == code }
        }
    }
}

data class BankCatalogEntry(
    val code: String,
    val name: String,
    val hasAutoDetection: Boolean
) {
    override fun toString(): String = "$code - $name"
    fun getShortName(): String = BankCatalog.getShortName(code)
    fun getBrandColor(): Int = BankCatalog.getBrandColor(code)
}

object BankCatalog {
    val ALL_BANKS = listOf(
        BankCatalogEntry("0178", "Ubii App", true),
        BankCatalogEntry("0134", "Banesco Banco Universal", true),
        BankCatalogEntry("0102", "Banco de Venezuela (BDV)", true),
        BankCatalogEntry("0105", "Banco Mercantil", true),
        BankCatalogEntry("0108", "BBVA Provincial", true),
        BankCatalogEntry("0191", "Banco Nacional de Crédito (BNC)", true),
        BankCatalogEntry("0172", "Bancamiga", true),
        BankCatalogEntry("0114", "Bancaribe", false),
        BankCatalogEntry("0174", "Banplus", false),
        BankCatalogEntry("0138", "Banco Plaza", false),
        BankCatalogEntry("0175", "Banco Bicentenario", false),
        BankCatalogEntry("0163", "Banco del Tesoro", false),
        BankCatalogEntry("0151", "BFC Banco Fondo Común", false),
        BankCatalogEntry("0115", "Banco Exterior", false),
        BankCatalogEntry("0156", "100% Banco", false),
        BankCatalogEntry("0171", "Banco Activo", false),
        BankCatalogEntry("0104", "Venezolano de Crédito", false)
    )

    fun findByCode(code: String): BankCatalogEntry? {
        return ALL_BANKS.firstOrNull { it.code == code }
    }

    fun findByNameOrCode(query: String): BankCatalogEntry? {
        val raw = query.trim()
        if (raw.isEmpty()) return null
        val codeCandidate = if (raw.contains(" - ")) raw.substringBefore(" - ").trim() else raw.take(4).trim()
        val byCode = findByCode(codeCandidate)
        if (byCode != null) return byCode

        val q = raw.lowercase()
        return ALL_BANKS.firstOrNull { entry ->
            entry.code == q ||
            entry.name.lowercase().contains(q) ||
            q.contains(entry.code) ||
            q.contains(entry.name.lowercase())
        }
    }

    fun getShortName(code: String): String {
        return when (code) {
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
            else -> findByCode(code)?.name ?: code
        }
    }

    fun getBrandColor(code: String): Int {
        return when (code) {
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
            else -> android.graphics.Color.parseColor("#0A2540")
        }
    }
}
