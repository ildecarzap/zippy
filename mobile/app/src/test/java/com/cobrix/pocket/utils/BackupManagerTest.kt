package com.cobrix.pocket.utils

import com.cobrix.pocket.data.model.CountryProfileCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManagerTest {

    @Test
    fun testCountryValidation_sameCountry_isCompatible() {
        val result = BackupManager.validateCountryCompatibility(
            backupCountryCode = "VE",
            currentCountry = CountryProfileCatalog.VENEZUELA
        )
        assertTrue(result.isCompatible)
        assertEquals("VE", result.inferredCountry)
    }

    @Test
    fun testCountryValidation_mismatchedCountry_isRejected() {
        val result = BackupManager.validateCountryCompatibility(
            backupCountryCode = "VE",
            currentCountry = CountryProfileCatalog.COLOMBIA,
            backupDisplayName = "🇻🇪 Venezuela",
            backupCurrencyCode = "VES"
        )
        assertFalse(result.isCompatible)
        assertEquals("VE", result.inferredCountry)
        assertTrue(result.errorMessage?.contains("Venezuela") == true)
        assertTrue(result.errorMessage?.contains("Colombia") == true)
    }

    @Test
    fun testCountryValidation_colombiaToArgentina_isRejected() {
        val result = BackupManager.validateCountryCompatibility(
            backupCountryCode = "CO",
            currentCountry = CountryProfileCatalog.ARGENTINA,
            backupDisplayName = "🇨🇴 Colombia",
            backupCurrencyCode = "COP"
        )
        assertFalse(result.isCompatible)
        assertEquals("CO", result.inferredCountry)
        assertTrue(result.errorMessage?.contains("Colombia") == true)
        assertTrue(result.errorMessage?.contains("Argentina") == true)
    }

    @Test
    fun testCountryValidation_legacyBackupWithoutCountry_treatedAsVenezuela() {
        // En versiones anteriores, no existía campo de país (null o vacío)
        // Si la terminal actual es Colombia, debe rechazar el respaldo legado de Venezuela
        val resultInColombia = BackupManager.validateCountryCompatibility(
            backupCountryCode = null,
            currentCountry = CountryProfileCatalog.COLOMBIA
        )
        assertFalse(resultInColombia.isCompatible)
        assertEquals("VE", resultInColombia.inferredCountry)
        assertTrue(resultInColombia.errorMessage?.contains("Venezuela") == true)

        // Si la terminal actual es Venezuela, debe permitirlo con total retrocompatibilidad
        val resultInVenezuela = BackupManager.validateCountryCompatibility(
            backupCountryCode = "",
            currentCountry = CountryProfileCatalog.VENEZUELA
        )
        assertTrue(resultInVenezuela.isCompatible)
        assertEquals("VE", resultInVenezuela.inferredCountry)
    }
}
