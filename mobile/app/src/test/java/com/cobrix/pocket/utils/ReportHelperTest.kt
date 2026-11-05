package com.cobrix.pocket.utils

import com.cobrix.pocket.data.model.CountryProfileCatalog
import com.cobrix.pocket.data.model.DebtorsSummary
import com.cobrix.pocket.data.model.Transaction
import com.cobrix.pocket.data.model.TransactionCategories
import com.cobrix.pocket.data.model.TransactionType
import com.cobrix.pocket.data.model.UserProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportHelperTest {

    @Test
    fun testVenezuelaBiCurrencyReport() {
        val profile = UserProfile(
            merchantName = "Inversiones Caracas",
            businessCategory = "Comercio",
            bcvRate = 50.0
        )
        val transactions = listOf(
            Transaction(
                amount = 2500.0,
                amountUsd = 50.0,
                bankName = "Banco de Venezuela",
                reference = "1234",
                type = TransactionType.INCOME,
                category = TransactionCategories.INCOME_AUTO_PAGO_MOVIL
            )
        )
        val debtors = DebtorsSummary(totalDebtUsd = 20.0, debtorsCount = 1)

        val report = ReportHelper.generateDetailedTextReport(
            profile = profile,
            transactions = transactions,
            debtorsSummary = debtors,
            countryProfile = CountryProfileCatalog.VENEZUELA
        )

        assertTrue(report.contains("Tasa BCV Referencial"))
        assertTrue(report.contains("Bs. 50.00"))
        assertTrue(report.contains("Bs. 2500.00 ($50.00)"))
        assertTrue(report.contains("Fiados por cobrar: $20.00 USD (≈ Bs. 1000.00)"))
    }

    @Test
    fun testColombiaMonoCurrencyReport() {
        val profile = UserProfile(
            merchantName = "Tienda Medellín",
            businessCategory = "Comercio",
            bcvRate = 50.0 // Shouldn't be used in CO
        )
        val transactions = listOf(
            Transaction(
                amount = 50000.0,
                amountUsd = 50000.0,
                bankName = "Bancolombia",
                reference = "5678",
                type = TransactionType.INCOME,
                category = TransactionCategories.INCOME_MANUAL_TRANSFERENCIA
            )
        )
        val debtors = DebtorsSummary(totalDebtUsd = 35000.0, debtorsCount = 1)

        val report = ReportHelper.generateDetailedTextReport(
            profile = profile,
            transactions = transactions,
            debtorsSummary = debtors,
            countryProfile = CountryProfileCatalog.COLOMBIA
        )

        assertFalse(report.contains("Tasa BCV"))
        assertFalse(report.contains("Bs."))
        assertTrue(report.contains(CountryProfileCatalog.COLOMBIA.localCurrencyDisplay))
        assertTrue(report.contains("COP $ 50000.00"))
        assertTrue(report.contains("Cuentas por cobrar: COP $ 35000.00 (COP)"))
    }

    @Test
    fun testArgentinaMonoCurrencyReport() {
        val profile = UserProfile(
            merchantName = "Kiosco Buenos Aires",
            businessCategory = "Comercio",
            bcvRate = 50.0 // Shouldn't be used in AR
        )
        val transactions = listOf(
            Transaction(
                amount = 7000.0,
                amountUsd = 7000.0,
                bankName = "Mercado Pago",
                reference = "9988",
                type = TransactionType.INCOME,
                category = TransactionCategories.INCOME_MANUAL_TRANSFERENCIA
            )
        )
        val debtors = DebtorsSummary(totalDebtUsd = 14000.0, debtorsCount = 2)

        val report = ReportHelper.generateDetailedTextReport(
            profile = profile,
            transactions = transactions,
            debtorsSummary = debtors,
            countryProfile = CountryProfileCatalog.ARGENTINA
        )

        assertFalse(report.contains("Tasa BCV"))
        assertFalse(report.contains("Bs."))
        assertTrue(report.contains(CountryProfileCatalog.ARGENTINA.localCurrencyDisplay))
        assertTrue(report.contains("ARS $ 7000.00"))
        assertTrue(report.contains("Cuentas por cobrar: ARS $ 14000.00 (ARS)"))
    }
}
