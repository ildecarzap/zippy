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
}

object BankCatalog {
    val ALL_BANKS = listOf(
        BankCatalogEntry("0134", "Banesco Banco Universal", true),
        BankCatalogEntry("0102", "Banco de Venezuela (BDV)", true),
        BankCatalogEntry("0105", "Banco Mercantil", true),
        BankCatalogEntry("0108", "BBVA Provincial", true),
        BankCatalogEntry("0191", "Banco Nacional de Crédito (BNC)", true),
        BankCatalogEntry("0172", "Bancamiga", true)
    )

    fun findByCode(code: String): BankCatalogEntry? {
        return ALL_BANKS.firstOrNull { it.code == code }
    }

    fun findByNameOrCode(query: String): BankCatalogEntry? {
        val q = query.trim().lowercase()
        return ALL_BANKS.firstOrNull { it.code == q || it.name.lowercase().contains(q) }
    }
}
