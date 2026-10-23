package com.cobrix.pocket.utils

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.cobrix.pocket.data.model.Transaction
import com.cobrix.pocket.data.model.TransactionCategories
import com.cobrix.pocket.data.model.TransactionType
import com.cobrix.pocket.data.model.UserProfile
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportHelper {

    private val DATE_FORMAT = SimpleDateFormat("dd/MM/yyyy", Locale.US)
    private val TIME_FORMAT = SimpleDateFormat("hh:mm a", Locale.US)
    private val FULL_DATETIME_FORMAT = SimpleDateFormat("dd/MM/yyyy • hh:mm a", Locale.US)

    data class ReportFinancials(
        val totalIncomeVes: Double,
        val totalExpenseVes: Double,
        val netProfitVes: Double,
        val totalIncomeUsd: Double,
        val totalExpenseUsd: Double,
        val netProfitUsd: Double,
        val incomeCount: Int,
        val expenseCount: Int,
        val incomeByCategory: Map<String, Pair<Double, Int>>,
        val expenseByCategory: Map<String, Pair<Double, Int>>
    )

    fun calculateFinancials(transactions: List<Transaction>, bcvRate: Double): ReportFinancials {
        var totalIncomeVes = 0.0
        var totalExpenseVes = 0.0
        var incomeCount = 0
        var expenseCount = 0

        val incomeMap = mutableMapOf<String, Pair<Double, Int>>()
        val expenseMap = mutableMapOf<String, Pair<Double, Int>>()

        for (tx in transactions) {
            if (tx.type == TransactionType.INCOME) {
                totalIncomeVes += tx.amount
                incomeCount++
                val cur = incomeMap[tx.category] ?: Pair(0.0, 0)
                incomeMap[tx.category] = Pair(cur.first + tx.amount, cur.second + 1)
            } else {
                totalExpenseVes += tx.amount
                expenseCount++
                val cur = expenseMap[tx.category] ?: Pair(0.0, 0)
                expenseMap[tx.category] = Pair(cur.first + tx.amount, cur.second + 1)
            }
        }

        val netVes = totalIncomeVes - totalExpenseVes
        val totalIncomeUsd = if (bcvRate > 0) totalIncomeVes / bcvRate else 0.0
        val totalExpenseUsd = if (bcvRate > 0) totalExpenseVes / bcvRate else 0.0
        val netUsd = if (bcvRate > 0) netVes / bcvRate else 0.0

        return ReportFinancials(
            totalIncomeVes = totalIncomeVes,
            totalExpenseVes = totalExpenseVes,
            netProfitVes = netVes,
            totalIncomeUsd = totalIncomeUsd,
            totalExpenseUsd = totalExpenseUsd,
            netProfitUsd = netUsd,
            incomeCount = incomeCount,
            expenseCount = expenseCount,
            incomeByCategory = incomeMap,
            expenseByCategory = expenseMap
        )
    }

    /**
     * Genera un reporte detallado en texto enriquecido con emojis apto para WhatsApp o Correo.
     */
    fun generateDetailedTextReport(
        profile: UserProfile,
        transactions: List<Transaction>,
        periodLabel: String = "Hoy"
    ): String {
        val now = Date()
        val financials = calculateFinancials(transactions, profile.bcvRate)
        val sb = StringBuilder()

        sb.append("📊 *REPORTE DE CIERRE - ZIPPY*\n")
        sb.append("─────────────────────────────────\n")
        sb.append("🏢 *Negocio:* ${profile.merchantName.ifEmpty { "Mi Negocio" }}\n")
        sb.append("🏷️ *Rubro:* ${profile.businessCategory}\n")
        sb.append("📅 *Periodo:* $periodLabel\n")
        sb.append("🕒 *Emitido el:* ${FULL_DATETIME_FORMAT.format(now)}\n")
        sb.append("💵 *Tasa BCV Referencial:* Bs. ${String.format(Locale.US, "%.2f", profile.bcvRate)} / USD\n")
        sb.append("─────────────────────────────────\n\n")

        sb.append("📈 *DESGLOSE DE INGRESOS POR TIPO:*\n")
        if (financials.incomeByCategory.isEmpty()) {
            sb.append("• Sin ingresos registrados en este periodo.\n")
        } else {
            financials.incomeByCategory.forEach { (category, data) ->
                val (amountVes, count) = data
                val amountUsd = if (profile.bcvRate > 0) amountVes / profile.bcvRate else 0.0
                val displayName = TransactionCategories.getCategoryDisplayName(category)
                sb.append("• $displayName ($count): Bs. ${String.format(Locale.US, "%.2f", amountVes)} ($${String.format(Locale.US, "%.2f", amountUsd)})\n")
            }
        }
        sb.append("🟢 *TOTAL INGRESOS:* Bs. ${String.format(Locale.US, "%.2f", financials.totalIncomeVes)} ($${String.format(Locale.US, "%.2f", financials.totalIncomeUsd)})\n\n")

        sb.append("📉 *DESGLOSE DE GASTOS OPERATIVOS:*\n")
        if (financials.expenseByCategory.isEmpty()) {
            sb.append("• Cero gastos operativos registrados en este periodo.\n")
        } else {
            financials.expenseByCategory.forEach { (category, data) ->
                val (amountVes, count) = data
                val amountUsd = if (profile.bcvRate > 0) amountVes / profile.bcvRate else 0.0
                val displayName = TransactionCategories.getCategoryDisplayName(category)
                sb.append("• $displayName ($count): Bs. ${String.format(Locale.US, "%.2f", amountVes)} ($${String.format(Locale.US, "%.2f", amountUsd)})\n")
            }
        }
        sb.append("🔴 *TOTAL GASTOS:* Bs. ${String.format(Locale.US, "%.2f", financials.totalExpenseVes)} ($${String.format(Locale.US, "%.2f", financials.totalExpenseUsd)})\n\n")

        sb.append("─────────────────────────────────\n")
        sb.append("🏁 *BALANCE FINAL - GANANCIA NETA REAL:*\n")
        sb.append("👉 *Bs. ${String.format(Locale.US, "%.2f", financials.netProfitVes)}* ($${String.format(Locale.US, "%.2f", financials.netProfitUsd)} USD)\n")
        sb.append("─────────────────────────────────\n\n")

        sb.append("📝 *DETALLE DE MOVIMIENTOS CON TASA HISTÓRICA:* (${transactions.size})\n")
        if (transactions.isEmpty()) {
            sb.append("• No hubo movimientos registrados en el periodo seleccionado.\n")
        } else {
            val dateTimeFormat = SimpleDateFormat("dd/MM hh:mm a", Locale.US)
            transactions.take(30).forEach { tx ->
                val dtStr = dateTimeFormat.format(Date(tx.timestamp))
                val sign = if (tx.type == TransactionType.INCOME) "+" else "-"
                val catName = TransactionCategories.getCategoryDisplayName(tx.category)
                val txRate = if (tx.amountUsd > 0) tx.amount / tx.amountUsd else profile.bcvRate
                val rateStr = "tasa: Bs. ${String.format(Locale.US, "%.2f", txRate)}"
                val refStr = if (tx.reference.isNotEmpty()) "🔢 *Ref: ${tx.reference}*" else "🔢 *Ref: S/R*"
                val bankTag = if (tx.bankName.isNotEmpty()) " • ${tx.bankName}" else ""
                val noteTag = if (tx.note.isNotEmpty()) " (${tx.note})" else ""
                sb.append("• [$dtStr] $sign Bs. ${String.format(Locale.US, "%.2f", tx.amount)} ($${String.format(Locale.US, "%.2f", tx.amountUsd)} @ $rateStr)\n")
                sb.append("   ↳ $refStr$bankTag | $catName$noteTag\n")
            }
            if (transactions.size > 30) {
                sb.append("... y ${transactions.size - 30} movimientos adicionales (ver PDF adjunto).\n")
            }
        }

        sb.append("\n─────────────────────────────────\n")
        sb.append("Desarrollado por Ciem tech solutions C.A\n")
        sb.append("RIF: J-50849495-4 • Soporte: +58 4242603112\n")
        sb.append("ildemaro.carrasco@outlook.com\n")

        return sb.toString()
    }

    /**
     * Comparte el reporte formateado por correo electrónico.
     */
    fun shareViaEmail(
        context: Context,
        profile: UserProfile,
        reportContent: String,
        periodLabel: String = "Hoy"
    ) {
        val subject = "Cierre de Jornada ($periodLabel) - ${profile.merchantName.ifEmpty { "Mi Negocio" }}"

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, reportContent)
        }

        try {
            context.startActivity(Intent.createChooser(intent, "Compartir Cierre por Correo"))
        } catch (e: Exception) {
            Toast.makeText(context, "No se encontró cliente de correo", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Genera un documento PDF formal con membrete corporativo, tablas de desglose y firmas.
     */
    fun generatePdfReport(
        context: Context,
        profile: UserProfile,
        transactions: List<Transaction>,
        periodLabel: String = "Hoy"
    ): File? {
        val financials = calculateFinancials(transactions, profile.bcvRate)

        val pageWidth = 595 // A4 standard width in points (72 dpi)
        val pageHeight = 842 // A4 standard height
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = document.startPage(pageInfo)
        val canvas: Canvas = page.canvas

        // Pinturas de texto y colores
        val paintTitle = Paint().apply {
            color = Color.parseColor("#0A2540")
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val paintSubtitle = Paint().apply {
            color = Color.parseColor("#0096C7")
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val paintLabel = Paint().apply {
            color = Color.parseColor("#627D98")
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
        }

        val paintValue = Paint().apply {
            color = Color.parseColor("#0A192F")
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val paintSectionHeader = Paint().apply {
            color = Color.parseColor("#0A2540")
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val paintTableText = Paint().apply {
            color = Color.parseColor("#0A192F")
            textSize = 9f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
        }

        val paintIncome = Paint().apply {
            color = Color.parseColor("#00875A")
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val paintExpense = Paint().apply {
            color = Color.parseColor("#DE350B")
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val paintLine = Paint().apply {
            color = Color.parseColor("#CFE0F5")
            strokeWidth = 1f
        }

        val paintBoxBg = Paint().apply {
            color = Color.parseColor("#F4F8FC")
            style = Paint.Style.FILL
        }

        var y = 40f
        val margin = 36f
        val contentWidth = pageWidth - (margin * 2)

        // 1. Membrete Superior
        canvas.drawText("ZIPPY • CIERRE DE JORNADA", margin, y, paintTitle)
        y += 18f
        canvas.drawText("CIEM TECH SOLUTIONS C.A • RIF: J-50849495-4", margin, y, paintSubtitle)
        y += 16f
        canvas.drawLine(margin, y, margin + contentWidth, y, paintLine)
        y += 16f

        // 2. Caja de Información General (Negocio, Rubro, Periodo, Tasa)
        canvas.drawRoundRect(margin, y, margin + contentWidth, y + 64f, 8f, 8f, paintBoxBg)
        val col1 = margin + 12f
        val col2 = margin + (contentWidth / 2f) + 12f

        canvas.drawText("Nombre del Negocio:", col1, y + 18f, paintLabel)
        canvas.drawText(profile.merchantName.ifEmpty { "Mi Negocio" }, col1, y + 32f, paintValue)

        canvas.drawText("Rubro:", col1, y + 46f, paintLabel)
        canvas.drawText(profile.businessCategory, col1, y + 58f, paintValue)

        canvas.drawText("Periodo Consultado:", col2, y + 18f, paintLabel)
        canvas.drawText(periodLabel, col2, y + 32f, paintValue)

        canvas.drawText("Tasa BCV Referencial:", col2, y + 46f, paintLabel)
        canvas.drawText("Bs. ${String.format(Locale.US, "%.2f", profile.bcvRate)} / USD", col2, y + 58f, paintValue)

        y += 80f

        // 3. Tarjetas KPI de Resumen (Ingresos, Gastos, Ganancia Neta)
        val kpiWidth = (contentWidth - 16f) / 3f
        val kpiHeight = 52f

        // KPI Ingresos
        canvas.drawRoundRect(margin, y, margin + kpiWidth, y + kpiHeight, 6f, 6f, paintBoxBg)
        canvas.drawText("TOTAL INGRESOS (${financials.incomeCount})", margin + 8f, y + 16f, paintLabel)
        canvas.drawText("Bs. ${String.format(Locale.US, "%.2f", financials.totalIncomeVes)}", margin + 8f, y + 32f, paintIncome)
        canvas.drawText("≈ $${String.format(Locale.US, "%.2f", financials.totalIncomeUsd)} USD", margin + 8f, y + 45f, paintLabel)

        // KPI Gastos
        val kpi2X = margin + kpiWidth + 8f
        canvas.drawRoundRect(kpi2X, y, kpi2X + kpiWidth, y + kpiHeight, 6f, 6f, paintBoxBg)
        canvas.drawText("TOTAL GASTOS (${financials.expenseCount})", kpi2X + 8f, y + 16f, paintLabel)
        canvas.drawText("Bs. ${String.format(Locale.US, "%.2f", financials.totalExpenseVes)}", kpi2X + 8f, y + 32f, paintExpense)
        canvas.drawText("≈ $${String.format(Locale.US, "%.2f", financials.totalExpenseUsd)} USD", kpi2X + 8f, y + 45f, paintLabel)

        // KPI Ganancia Neta
        val kpi3X = kpi2X + kpiWidth + 8f
        val paintNetBg = Paint().apply {
            color = Color.parseColor("#E0F2FE")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(kpi3X, y, kpi3X + kpiWidth, y + kpiHeight, 6f, 6f, paintNetBg)
        canvas.drawText("GANANCIA NETA REAL", kpi3X + 8f, y + 16f, paintSubtitle)
        canvas.drawText("Bs. ${String.format(Locale.US, "%.2f", financials.netProfitVes)}", kpi3X + 8f, y + 32f, paintTitle)
        canvas.drawText("≈ $${String.format(Locale.US, "%.2f", financials.netProfitUsd)} USD", kpi3X + 8f, y + 45f, paintSubtitle)

        y += 70f

        // 4. Tabla de Desglose de Ingresos por Tipo
        canvas.drawText("DESGLOSE DE INGRESOS POR TIPO", margin, y, paintSectionHeader)
        y += 14f
        canvas.drawLine(margin, y, margin + contentWidth, y, paintLine)
        y += 14f

        canvas.drawText("Tipo de Ingreso / Origen", margin, y, paintLabel)
        canvas.drawText("Operaciones", margin + 260f, y, paintLabel)
        canvas.drawText("Subtotal Bs.", margin + 350f, y, paintLabel)
        canvas.drawText("Subtotal USD", margin + 440f, y, paintLabel)
        y += 6f
        canvas.drawLine(margin, y, margin + contentWidth, y, paintLine)
        y += 14f

        if (financials.incomeByCategory.isEmpty()) {
            canvas.drawText("No se registraron ingresos en esta jornada", margin, y, paintTableText)
            y += 16f
        } else {
            financials.incomeByCategory.forEach { (category, data) ->
                val (amtVes, count) = data
                val amtUsd = if (profile.bcvRate > 0) amtVes / profile.bcvRate else 0.0
                val displayName = TransactionCategories.getCategoryDisplayName(category)
                canvas.drawText(displayName, margin, y, paintTableText)
                canvas.drawText("$count cobros", margin + 260f, y, paintTableText)
                canvas.drawText("Bs. ${String.format(Locale.US, "%.2f", amtVes)}", margin + 350f, y, paintIncome)
                canvas.drawText("$${String.format(Locale.US, "%.2f", amtUsd)}", margin + 440f, y, paintTableText)
                y += 16f
            }
        }

        y += 10f

        // 5. Tabla de Desglose de Gastos
        canvas.drawText("DESGLOSE DE GASTOS OPERATIVOS", margin, y, paintSectionHeader)
        y += 14f
        canvas.drawLine(margin, y, margin + contentWidth, y, paintLine)
        y += 14f

        canvas.drawText("Categoría de Gasto", margin, y, paintLabel)
        canvas.drawText("Registros", margin + 260f, y, paintLabel)
        canvas.drawText("Subtotal Bs.", margin + 350f, y, paintLabel)
        canvas.drawText("Subtotal USD", margin + 440f, y, paintLabel)
        y += 6f
        canvas.drawLine(margin, y, margin + contentWidth, y, paintLine)
        y += 14f

        if (financials.expenseByCategory.isEmpty()) {
            canvas.drawText("No se registraron gastos operativos en esta jornada", margin, y, paintTableText)
            y += 16f
        } else {
            financials.expenseByCategory.forEach { (category, data) ->
                val (amtVes, count) = data
                val amtUsd = if (profile.bcvRate > 0) amtVes / profile.bcvRate else 0.0
                val displayName = TransactionCategories.getCategoryDisplayName(category)
                canvas.drawText(displayName, margin, y, paintTableText)
                canvas.drawText("$count gastos", margin + 260f, y, paintTableText)
                canvas.drawText("Bs. ${String.format(Locale.US, "%.2f", amtVes)}", margin + 350f, y, paintExpense)
                canvas.drawText("$${String.format(Locale.US, "%.2f", amtUsd)}", margin + 440f, y, paintTableText)
                y += 16f
            }
        }

        y += 10f

        // 6. Lista Cronológica de Movimientos (hasta donde quepa en la hoja A4)
        canvas.drawText("ÚLTIMOS MOVIMIENTOS REGISTRADOS", margin, y, paintSectionHeader)
        y += 14f
        canvas.drawLine(margin, y, margin + contentWidth, y, paintLine)
        y += 14f

        canvas.drawText("Fecha/Hora", margin, y, paintLabel)
        canvas.drawText("Concepto / Tipo", margin + 65f, y, paintLabel)
        canvas.drawText("Ref / Nota", margin + 195f, y, paintLabel)
        canvas.drawText("Tasa Dólar", margin + 335f, y, paintLabel)
        canvas.drawText("Monto Bs. ($ USD)", margin + 415f, y, paintLabel)
        y += 6f
        canvas.drawLine(margin, y, margin + contentWidth, y, paintLine)
        y += 14f

        val dateTimeFormat = SimpleDateFormat("dd/MM HH:mm", Locale.US)
        val maxListY = pageHeight - 110f
        for (tx in transactions) {
            if (y > maxListY) {
                canvas.drawText("... más movimientos en el historial digital ...", margin, y, paintLabel)
                break
            }
            val dtStr = dateTimeFormat.format(Date(tx.timestamp))
            val isIncome = tx.type == TransactionType.INCOME
            val sign = if (isIncome) "+" else "-"
            val catName = TransactionCategories.getCategoryDisplayName(tx.category)
            val refText = if (tx.reference.isNotEmpty()) "Ref: ${tx.reference}" else tx.note.ifEmpty { "S/R" }
            val txRate = if (tx.amountUsd > 0) tx.amount / tx.amountUsd else profile.bcvRate
            val rateStr = "Bs. ${String.format(Locale.US, "%.2f", txRate)}"

            canvas.drawText(dtStr, margin, y, paintTableText)
            canvas.drawText(catName.take(20), margin + 65f, y, paintTableText)
            canvas.drawText(refText.take(22), margin + 195f, y, paintTableText)
            canvas.drawText(rateStr, margin + 335f, y, paintTableText)
            val amountStr = "$sign${String.format(Locale.US, "%.2f", tx.amount)} ($${String.format(Locale.US, "%.2f", tx.amountUsd)})"
            canvas.drawText(amountStr, margin + 415f, y, if (isIncome) paintIncome else paintExpense)

            y += 14f
        }

        // 7. Firmas de Conformidad al pie de página
        val footerY = pageHeight - 55f
        canvas.drawLine(margin, footerY, margin + 200f, footerY, paintLine)
        canvas.drawLine(margin + contentWidth - 200f, footerY, margin + contentWidth, footerY, paintLine)

        canvas.drawText("Firma del Responsable / Cajero", margin + 20f, footerY + 14f, paintLabel)
        canvas.drawText("Firma Administrador / Propietario", margin + contentWidth - 190f, footerY + 14f, paintLabel)

        val bottomText = "Generado por Zippy • Ciem tech solutions C.A • RIF J-50849495-4 • Soporte: +58 4242603112"
        canvas.drawText(bottomText, margin + 20f, pageHeight - 16f, paintLabel)

        document.finishPage(page)

        // Guardar archivo en cache con formato solicitado (ej: reporte_jornada_21-10-26_hora)
        val reportsDir = File(context.cacheDir, "reports")
        if (!reportsDir.exists()) {
            reportsDir.mkdirs()
        }

        val fileTimestamp = SimpleDateFormat("dd-MM-yy_HHmm", Locale.US).format(Date())
        val pdfFile = File(reportsDir, "reporte_jornada_${fileTimestamp}.pdf")
        try {
            FileOutputStream(pdfFile).use { out ->
                document.writeTo(out)
            }
            document.close()
            return pdfFile
        } catch (e: Exception) {
            document.close()
            e.printStackTrace()
            return null
        }
    }

    /**
     * Comparte el archivo PDF generado mediante Intent.ACTION_SEND con selector nativo.
     */
    fun sharePdf(context: Context, pdfFile: File) {
        val uri: Uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error al preparar archivo PDF", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Cierre de Jornada Zippy")
            putExtra(Intent.EXTRA_TEXT, "Adjunto reporte formal de cierre de jornada generado con Zippy.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            context.startActivity(Intent.createChooser(intent, "Compartir Reporte PDF"))
        } catch (e: Exception) {
            Toast.makeText(context, "No se pudo compartir el archivo PDF", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Genera un archivo en formato Excel (.csv) con punto y coma ';' y BOM UTF-8,
     * permitiendo abrirlo directamente en Microsoft Excel o Google Sheets con caracteres en español.
     */
    fun generateCsvReport(
        context: Context,
        profile: UserProfile,
        transactions: List<Transaction>,
        periodTitle: String
    ): File? {
        val reportsDir = File(context.cacheDir, "reports")
        if (!reportsDir.exists()) reportsDir.mkdirs()

        val fileTimestamp = SimpleDateFormat("dd-MM-yy_HHmm", Locale.US).format(Date())
        val csvFile = File(reportsDir, "reporte_jornada_${fileTimestamp}.csv")

        return try {
            val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.US)
            val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

            val sb = StringBuilder()
            // Encabezado comercial
            sb.append("Comercio;").append("\"${profile.merchantName}\"").append("\n")
            sb.append("RIF / Cedula;").append("\"${profile.idCard}\"").append("\n")
            sb.append("Periodo;").append("\"$periodTitle\"").append("\n")
            sb.append("Tasa BCV Referencial;").append(String.format(Locale.US, "%.2f", profile.bcvRate)).append("\n\n")

            // Columnas de la tabla
            sb.append("Fecha;Hora;Tipo;Categoria;Banco / Metodo;Monto Bs;Monto USD;Referencia;Remitente / Pagador;Nota\n")

            for (tx in transactions) {
                val dateStr = dateFormat.format(Date(tx.timestamp))
                val timeStr = timeFormat.format(Date(tx.timestamp))
                val typeStr = if (tx.type == TransactionType.INCOME) "Cobro" else "Gasto"
                val catStr = TransactionCategories.getCategoryDisplayName(tx.category)
                val bankStr = tx.bankName.ifEmpty { "Caja" }
                val amountBs = String.format(Locale.US, "%.2f", tx.amount)
                val amountUsd = String.format(Locale.US, "%.2f", tx.amountUsd)
                val refStr = tx.reference
                val senderStr = tx.senderName
                val noteStr = tx.note.replace(";", ",")

                sb.append("$dateStr;$timeStr;$typeStr;\"$catStr\";\"$bankStr\";$amountBs;$amountUsd;\"$refStr\";\"$senderStr\";\"$noteStr\"\n")
            }

            // Resumen de auditoría
            val (totalIncome, totalExpense) = transactions.partition { it.type == TransactionType.INCOME }
            val sumIncomeBs = totalIncome.sumOf { it.amount }
            val sumExpenseBs = totalExpense.sumOf { it.amount }
            val netBs = sumIncomeBs - sumExpenseBs

            sb.append("\n")
            sb.append("TOTAL COBRADO (BS);;;;;" + String.format(Locale.US, "%.2f", sumIncomeBs) + "\n")
            sb.append("TOTAL GASTOS (BS);;;;;" + String.format(Locale.US, "%.2f", sumExpenseBs) + "\n")
            sb.append("BALANCE NETO (BS);;;;;" + String.format(Locale.US, "%.2f", netBs) + "\n")

            FileOutputStream(csvFile).use { out ->
                // Escribir BOM UTF-8 (EF BB BF) para que Excel reconozca tildes y caracteres especiales
                out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                out.write(sb.toString().toByteArray(Charsets.UTF_8))
            }
            csvFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Comparte el archivo CSV generado mediante Intent.ACTION_SEND con selector nativo.
     */
    fun shareCsv(context: Context, csvFile: File) {
        val uri: Uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", csvFile)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error al preparar archivo Excel", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Cierre de Jornada Zippy (${csvFile.name})")
            putExtra(Intent.EXTRA_TEXT, "Adjunto reporte detallado en Excel (.csv) de la jornada generado con Zippy.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            context.startActivity(Intent.createChooser(intent, "Compartir Reporte Excel (.csv)"))
        } catch (e: Exception) {
            Toast.makeText(context, "No se pudo compartir el archivo Excel", Toast.LENGTH_SHORT).show()
        }
    }
}
