package com.cobrix.pocket.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.cobrix.pocket.data.model.Debtor
import com.cobrix.pocket.data.model.DebtorsSummary
import com.cobrix.pocket.data.model.LedgerEntry
import com.cobrix.pocket.data.model.LedgerEntryType
import com.cobrix.pocket.data.model.Transaction
import com.cobrix.pocket.data.model.TransactionType
import java.util.Calendar

class PocketDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "cobrix_pocket.db"
        private const val DATABASE_VERSION = 3

        // Tabla Transacciones (Existente)
        private const val TABLE_TRANSACTIONS = "transactions"
        private const val COL_ID = "id"
        private const val COL_AMOUNT = "amount"
        private const val COL_AMOUNT_USD = "amount_usd"
        private const val COL_REFERENCE = "reference"
        private const val COL_BANK_CODE = "bank_code"
        private const val COL_BANK_NAME = "bank_name"
        private const val COL_SENDER_NAME = "sender_name"
        private const val COL_TYPE = "type"
        private const val COL_CATEGORY = "category"
        private const val COL_NOTE = "note"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_IS_MANUAL = "is_manual"

        // Tabla Deudores / Libreta de Clientes (Nueva v3)
        private const val TABLE_DEBTORS = "debtors"
        private const val COL_DEBTOR_ID = "id"
        private const val COL_DEBTOR_NAME = "name"
        private const val COL_DEBTOR_PHONE = "phone"
        private const val COL_DEBTOR_IDENTIFICATION = "identification"
        private const val COL_DEBTOR_BALANCE_USD = "balance_usd"
        private const val COL_DEBTOR_CREATED_AT = "created_at"
        private const val COL_DEBTOR_UPDATED_AT = "updated_at"

        // Tabla Movimientos de Libreta (Nueva v3)
        private const val TABLE_LEDGER_ENTRIES = "ledger_entries"
        private const val COL_ENTRY_ID = "id"
        private const val COL_ENTRY_DEBTOR_ID = "debtor_id"
        private const val COL_ENTRY_TYPE = "type"
        private const val COL_ENTRY_AMOUNT_USD = "amount_usd"
        private const val COL_ENTRY_AMOUNT_VES = "amount_ves"
        private const val COL_ENTRY_BCV_RATE = "bcv_rate"
        private const val COL_ENTRY_MATCHED_TX_ID = "matched_tx_id"
        private const val COL_ENTRY_NOTES = "notes"
        private const val COL_ENTRY_TIMESTAMP = "timestamp"

        @Volatile
        private var INSTANCE: PocketDatabaseHelper? = null

        fun getInstance(context: Context): PocketDatabaseHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PocketDatabaseHelper(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableQuery = """
            CREATE TABLE $TABLE_TRANSACTIONS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_AMOUNT REAL NOT NULL,
                $COL_AMOUNT_USD REAL DEFAULT 0.0,
                $COL_REFERENCE TEXT,
                $COL_BANK_CODE TEXT,
                $COL_BANK_NAME TEXT,
                $COL_SENDER_NAME TEXT,
                $COL_TYPE TEXT NOT NULL,
                $COL_CATEGORY TEXT NOT NULL,
                $COL_NOTE TEXT,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_IS_MANUAL INTEGER DEFAULT 0
            )
        """.trimIndent()
        db.execSQL(createTableQuery)
        db.execSQL("CREATE INDEX idx_transactions_timestamp ON $TABLE_TRANSACTIONS($COL_TIMESTAMP)")

        // Tablas v3: Libreta de Fiados y Saldos a Favor
        createDebtorsTables(db)
    }

    private fun createDebtorsTables(db: SQLiteDatabase) {
        val createDebtorsQuery = """
            CREATE TABLE IF NOT EXISTS $TABLE_DEBTORS (
                $COL_DEBTOR_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_DEBTOR_NAME TEXT NOT NULL,
                $COL_DEBTOR_PHONE TEXT,
                $COL_DEBTOR_IDENTIFICATION TEXT,
                $COL_DEBTOR_BALANCE_USD REAL DEFAULT 0.0,
                $COL_DEBTOR_CREATED_AT INTEGER NOT NULL,
                $COL_DEBTOR_UPDATED_AT INTEGER NOT NULL
            )
        """.trimIndent()
        db.execSQL(createDebtorsQuery)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_debtors_phone ON $TABLE_DEBTORS($COL_DEBTOR_PHONE)")

        val createLedgerQuery = """
            CREATE TABLE IF NOT EXISTS $TABLE_LEDGER_ENTRIES (
                $COL_ENTRY_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_ENTRY_DEBTOR_ID INTEGER NOT NULL,
                $COL_ENTRY_TYPE TEXT NOT NULL,
                $COL_ENTRY_AMOUNT_USD REAL NOT NULL,
                $COL_ENTRY_AMOUNT_VES REAL NOT NULL,
                $COL_ENTRY_BCV_RATE REAL NOT NULL,
                $COL_ENTRY_MATCHED_TX_ID INTEGER,
                $COL_ENTRY_NOTES TEXT,
                $COL_ENTRY_TIMESTAMP INTEGER NOT NULL
            )
        """.trimIndent()
        db.execSQL(createLedgerQuery)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ledger_debtor ON $TABLE_LEDGER_ENTRIES($COL_ENTRY_DEBTOR_ID)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_TRANSACTIONS ADD COLUMN $COL_IS_MANUAL INTEGER DEFAULT 0")
            } catch (_: Exception) { }
        }
        if (oldVersion < 3) {
            try {
                createDebtorsTables(db)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun insertTransaction(tx: Transaction): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_AMOUNT, tx.amount)
            put(COL_AMOUNT_USD, tx.amountUsd)
            put(COL_REFERENCE, tx.reference)
            put(COL_BANK_CODE, tx.bankCode)
            put(COL_BANK_NAME, tx.bankName)
            put(COL_SENDER_NAME, tx.senderName)
            put(COL_TYPE, tx.type.name)
            put(COL_CATEGORY, tx.category)
            put(COL_NOTE, tx.note)
            put(COL_TIMESTAMP, tx.timestamp)
            put(COL_IS_MANUAL, if (tx.isManual) 1 else 0)
        }
        return db.insert(TABLE_TRANSACTIONS, null, values)
    }

    /**
     * Verifica si ya existe en SQLite un cobro registrado con la misma referencia y monto
     * dentro de una ventana de tiempo (ej. la jornada actual), o duplicado reciente de Binance Pay.
     */
    fun hasDuplicateIncome(
        reference: String,
        amountVes: Double,
        amountUsd: Double = 0.0,
        sinceTimestamp: Long = 0L,
        isCrypto: Boolean = false,
        sender: String? = null,
        txType: TransactionType = TransactionType.INCOME
    ): Boolean {
        val db = readableDatabase

        // Verificación especial para Binance Pay (Cripto): evitar duplicados entre Push y Gmail
        if (isCrypto && amountUsd > 0.0) {
            val tenMinutesAgo = System.currentTimeMillis() - 600_000L
            val cryptoQuery = """
                SELECT $COL_SENDER_NAME, $COL_REFERENCE FROM $TABLE_TRANSACTIONS 
                WHERE $COL_TYPE = ? AND ($COL_CATEGORY LIKE '%Binance%' OR $COL_BANK_CODE = 'BINANCE')
                AND ABS($COL_AMOUNT_USD - ?) < 0.005 
                AND $COL_TIMESTAMP >= ?
            """.trimIndent()
            val cryptoCursor = db.rawQuery(cryptoQuery, arrayOf(txType.name, amountUsd.toString(), tenMinutesAgo.toString()))
            var cryptoDuplicate = false
            cryptoCursor.use {
                while (it.moveToNext()) {
                    val prevSender = it.getString(0) ?: ""
                    val prevRef = it.getString(1) ?: ""
                    // Si coincide monto y además (mismo remitente, o misma ref, o llegó en menos de 5 minutos)
                    if (!sender.isNullOrBlank() && prevSender.isNotBlank() && (prevSender.contains(sender) || sender.contains(prevSender))) {
                        cryptoDuplicate = true
                        break
                    }
                    if (reference.isNotBlank() && prevRef == reference) {
                        cryptoDuplicate = true
                        break
                    }
                    // Si es exactamente el mismo monto USDT en menos de 10 minutos
                    cryptoDuplicate = true
                    break
                }
            }
            if (cryptoDuplicate) return true
        }

        if (reference.isBlank() || reference == "0000" || reference.equals("nada", ignoreCase = true)) return false

        val query = if (sinceTimestamp > 0) {
            """
            SELECT COUNT(*) FROM $TABLE_TRANSACTIONS 
            WHERE $COL_TYPE = ? AND $COL_REFERENCE = ? 
            AND (ABS($COL_AMOUNT - ?) < 0.05 OR ($COL_AMOUNT_USD > 0 AND ABS($COL_AMOUNT_USD - ?) < 0.01))
            AND $COL_TIMESTAMP >= ?
            """.trimIndent()
        } else {
            """
            SELECT COUNT(*) FROM $TABLE_TRANSACTIONS 
            WHERE $COL_TYPE = ? AND $COL_REFERENCE = ? 
            AND (ABS($COL_AMOUNT - ?) < 0.05 OR ($COL_AMOUNT_USD > 0 AND ABS($COL_AMOUNT_USD - ?) < 0.01))
            """.trimIndent()
        }
        val args = if (sinceTimestamp > 0) {
            arrayOf(txType.name, reference, amountVes.toString(), amountUsd.toString(), sinceTimestamp.toString())
        } else {
            arrayOf(txType.name, reference, amountVes.toString(), amountUsd.toString())
        }
        val cursor = db.rawQuery(query, args)
        var exists = false
        cursor.use {
            if (it.moveToFirst()) {
                exists = it.getInt(0) > 0
            }
        }
        return exists
    }

    fun updateTransaction(tx: Transaction): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_AMOUNT, tx.amount)
            put(COL_AMOUNT_USD, tx.amountUsd)
            put(COL_CATEGORY, tx.category)
            put(COL_NOTE, tx.note)
            put(COL_REFERENCE, tx.reference)
            put(COL_SENDER_NAME, tx.senderName)
        }
        return db.update(TABLE_TRANSACTIONS, values, "$COL_ID = ?", arrayOf(tx.id.toString()))
    }

    fun deleteTransaction(id: Long): Int {
        val db = writableDatabase
        reversePaymentByTransactionId(id)
        return db.delete(TABLE_TRANSACTIONS, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun getTransactionsBetween(startTime: Long, endTime: Long): List<Transaction> {
        val list = mutableListOf<Transaction>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_TRANSACTIONS,
            null,
            "$COL_TIMESTAMP >= ? AND $COL_TIMESTAMP <= ?",
            arrayOf(startTime.toString(), endTime.toString()),
            null,
            null,
            "$COL_TIMESTAMP DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToTransaction(it))
            }
        }
        return list
    }

    fun getTodaySummary(startOfDay: Long, endOfDay: Long): Triple<Double, Double, Int> {
        var totalIncome = 0.0
        var totalExpense = 0.0
        var incomeCount = 0

        val db = readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT $COL_TYPE, SUM($COL_AMOUNT), COUNT(*) 
            FROM $TABLE_TRANSACTIONS 
            WHERE $COL_TIMESTAMP >= ? AND $COL_TIMESTAMP <= ?
            GROUP BY $COL_TYPE
            """.trimIndent(),
            arrayOf(startOfDay.toString(), endOfDay.toString())
        )

        cursor.use {
            while (it.moveToNext()) {
                val type = it.getString(0)
                val sum = it.getDouble(1)
                val count = it.getInt(2)
                if (type == TransactionType.INCOME.name) {
                    totalIncome = sum
                    incomeCount = count
                } else if (type == TransactionType.EXPENSE.name) {
                    totalExpense = sum
                }
            }
        }

        return Triple(totalIncome, totalExpense, incomeCount)
    }

    /**
     * Retorna todas las transacciones ordenadas cronológicamente para respaldos completos.
     */
    fun getAllTransactions(): List<Transaction> {
        val list = mutableListOf<Transaction>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_TRANSACTIONS,
            null,
            null,
            null,
            null,
            null,
            "$COL_TIMESTAMP DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToTransaction(it))
            }
        }
        return list
    }

    /**
     * Inserta un lote de transacciones durante la restauración evitando duplicados por timestamp y referencia.
     */
    fun insertTransactionsBatch(transactions: List<Transaction>): Int {
        val db = writableDatabase
        var insertedCount = 0
        db.beginTransaction()
        try {
            for (tx in transactions) {
                // Verificar si ya existe para evitar duplicación
                val existsCursor = db.query(
                    TABLE_TRANSACTIONS,
                    arrayOf(COL_ID),
                    "$COL_TIMESTAMP = ? AND $COL_AMOUNT = ? AND $COL_TYPE = ?",
                    arrayOf(tx.timestamp.toString(), tx.amount.toString(), tx.type.name),
                    null,
                    null,
                    null
                )
                val exists = existsCursor.use { it.count > 0 }
                if (!exists) {
                    val values = ContentValues().apply {
                        put(COL_AMOUNT, tx.amount)
                        put(COL_AMOUNT_USD, tx.amountUsd)
                        put(COL_REFERENCE, tx.reference)
                        put(COL_BANK_CODE, tx.bankCode)
                        put(COL_BANK_NAME, tx.bankName)
                        put(COL_SENDER_NAME, tx.senderName)
                        put(COL_TYPE, tx.type.name)
                        put(COL_CATEGORY, tx.category)
                        put(COL_NOTE, tx.note)
                        put(COL_TIMESTAMP, tx.timestamp)
                        put(COL_IS_MANUAL, if (tx.isManual) 1 else 0)
                    }
                    db.insert(TABLE_TRANSACTIONS, null, values)
                    insertedCount++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return insertedCount
    }

    fun clearAllTransactions(): Int {
        val db = writableDatabase
        return db.delete(TABLE_TRANSACTIONS, null, null)
    }

    /**
     * Vacía completamente todas las tablas de la base de datos (Transacciones, Deudores y Movimientos de Libreta).
     */
    fun resetAllData(): Boolean {
        return try {
            val db = writableDatabase
            db.delete(TABLE_LEDGER_ENTRIES, null, null)
            db.delete(TABLE_DEBTORS, null, null)
            db.delete(TABLE_TRANSACTIONS, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun cursorToTransaction(cursor: Cursor): Transaction {
        val isManualIndex = cursor.getColumnIndex(COL_IS_MANUAL)
        val isManual = if (isManualIndex >= 0) {
            cursor.getInt(isManualIndex) == 1
        } else {
            false
        }

        return Transaction(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
            amount = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_AMOUNT)),
            amountUsd = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_AMOUNT_USD)),
            reference = cursor.getString(cursor.getColumnIndexOrThrow(COL_REFERENCE)) ?: "",
            bankCode = cursor.getString(cursor.getColumnIndexOrThrow(COL_BANK_CODE)) ?: "",
            bankName = cursor.getString(cursor.getColumnIndexOrThrow(COL_BANK_NAME)) ?: "",
            senderName = cursor.getString(cursor.getColumnIndexOrThrow(COL_SENDER_NAME)) ?: "",
            type = TransactionType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(COL_TYPE))),
            category = cursor.getString(cursor.getColumnIndexOrThrow(COL_CATEGORY)) ?: "GENERAL",
            note = cursor.getString(cursor.getColumnIndexOrThrow(COL_NOTE)) ?: "",
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)),
            isManual = isManual
        )
    }

    // =========================================================================
    // OPERACIONES DE LIBRETA DE FIADOS Y SALDOS A FAVOR (v3)
    // =========================================================================

    fun insertDebtor(debtor: Debtor): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_DEBTOR_NAME, debtor.name)
            put(COL_DEBTOR_PHONE, debtor.phone)
            put(COL_DEBTOR_IDENTIFICATION, debtor.identification)
            put(COL_DEBTOR_BALANCE_USD, debtor.balanceUsd)
            put(COL_DEBTOR_CREATED_AT, debtor.createdAt)
            put(COL_DEBTOR_UPDATED_AT, debtor.updatedAt)
        }
        return db.insert(TABLE_DEBTORS, null, values)
    }

    fun updateDebtor(debtor: Debtor): Boolean {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_DEBTOR_NAME, debtor.name)
            put(COL_DEBTOR_PHONE, debtor.phone)
            put(COL_DEBTOR_IDENTIFICATION, debtor.identification)
            put(COL_DEBTOR_BALANCE_USD, debtor.balanceUsd)
            put(COL_DEBTOR_UPDATED_AT, System.currentTimeMillis())
        }
        val rows = db.update(TABLE_DEBTORS, values, "$COL_DEBTOR_ID = ?", arrayOf(debtor.id.toString()))
        return rows > 0
    }

    fun deleteDebtor(id: Long): Boolean {
        val db = writableDatabase
        db.delete(TABLE_LEDGER_ENTRIES, "$COL_ENTRY_DEBTOR_ID = ?", arrayOf(id.toString()))
        val rows = db.delete(TABLE_DEBTORS, "$COL_DEBTOR_ID = ?", arrayOf(id.toString()))
        return rows > 0
    }

    fun getDebtorById(id: Long): Debtor? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DEBTORS,
            null,
            "$COL_DEBTOR_ID = ?",
            arrayOf(id.toString()),
            null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) cursorToDebtor(it) else null
        }
    }

    fun findDebtorByPhone(phone: String): Debtor? {
        if (phone.isBlank()) return null
        val cleanPhone = phone.replace(Regex("[^0-9]"), "")
        val db = readableDatabase
        // Buscar por coincidencia parcial de los últimos 7 u 8 dígitos
        val queryPhone = if (cleanPhone.length >= 7) cleanPhone.takeLast(7) else cleanPhone
        val cursor = db.rawQuery(
            "SELECT * FROM $TABLE_DEBTORS WHERE REPLACE(REPLACE(REPLACE($COL_DEBTOR_PHONE, ' ', ''), '-', ''), '+', '') LIKE ? LIMIT 1",
            arrayOf("%$queryPhone%")
        )
        return cursor.use {
            if (it.moveToFirst()) cursorToDebtor(it) else null
        }
    }

    fun getAllDebtors(): List<Debtor> {
        val list = mutableListOf<Debtor>()
        val db = readableDatabase
        // Ordenar primero por los que tienen saldo pendiente o a favor, luego por nombre
        val cursor = db.query(
            TABLE_DEBTORS,
            null, null, null, null, null,
            "CASE WHEN $COL_DEBTOR_BALANCE_USD != 0 THEN 0 ELSE 1 END, $COL_DEBTOR_UPDATED_AT DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToDebtor(it))
            }
        }
        return list
    }

    fun searchDebtors(query: String): List<Debtor> {
        val list = mutableListOf<Debtor>()
        val db = readableDatabase
        val q = "%${query.trim()}%"
        val cursor = db.query(
            TABLE_DEBTORS,
            null,
            "$COL_DEBTOR_NAME LIKE ? OR $COL_DEBTOR_PHONE LIKE ? OR $COL_DEBTOR_IDENTIFICATION LIKE ?",
            arrayOf(q, q, q),
            null, null,
            "$COL_DEBTOR_NAME ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToDebtor(it))
            }
        }
        return list
    }

    fun getDebtorsSummary(): DebtorsSummary {
        val db = readableDatabase
        var totalDebtUsd = 0.0
        var totalCreditUsd = 0.0
        var debtorsCount = 0
        var creditsCount = 0

        val cursor = db.rawQuery("SELECT $COL_DEBTOR_BALANCE_USD FROM $TABLE_DEBTORS", null)
        cursor.use {
            while (it.moveToNext()) {
                val balance = it.getDouble(0)
                if (balance < -0.009) {
                    totalDebtUsd += -balance
                    debtorsCount++
                } else if (balance > 0.009) {
                    totalCreditUsd += balance
                    creditsCount++
                }
            }
        }
        return DebtorsSummary(
            totalDebtUsd = totalDebtUsd,
            totalCreditUsd = totalCreditUsd,
            debtorsCount = debtorsCount,
            creditsCount = creditsCount
        )
    }

    /**
     * Registra una compra fiada / cargo a la cuenta del cliente.
     * Disminuye su balance (lo hace más negativo o consume su saldo a favor).
     */
    fun addCharge(
        debtorId: Long,
        amountUsd: Double,
        amountVes: Double,
        bcvRate: Double,
        notes: String?
    ): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val entryValues = ContentValues().apply {
                put(COL_ENTRY_DEBTOR_ID, debtorId)
                put(COL_ENTRY_TYPE, LedgerEntryType.CHARGE.name)
                put(COL_ENTRY_AMOUNT_USD, amountUsd)
                put(COL_ENTRY_AMOUNT_VES, amountVes)
                put(COL_ENTRY_BCV_RATE, bcvRate)
                put(COL_ENTRY_NOTES, notes)
                put(COL_ENTRY_TIMESTAMP, System.currentTimeMillis())
            }
            val entryId = db.insert(TABLE_LEDGER_ENTRIES, null, entryValues)

            // Actualizar balance deudor (restar amountUsd)
            db.execSQL(
                "UPDATE $TABLE_DEBTORS SET $COL_DEBTOR_BALANCE_USD = $COL_DEBTOR_BALANCE_USD - ?, $COL_DEBTOR_UPDATED_AT = ? WHERE $COL_DEBTOR_ID = ?",
                arrayOf(amountUsd, System.currentTimeMillis(), debtorId)
            )

            db.setTransactionSuccessful()
            return entryId
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Registra un abono / pago del cliente a su cuenta corriente.
     * Aumenta su balance (reduce la deuda o crea/aumenta saldo a favor).
     */
    fun addPayment(
        debtorId: Long,
        amountUsd: Double,
        amountVes: Double,
        bcvRate: Double,
        matchedTxId: Long? = null,
        notes: String? = null
    ): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val entryValues = ContentValues().apply {
                put(COL_ENTRY_DEBTOR_ID, debtorId)
                put(COL_ENTRY_TYPE, LedgerEntryType.PAYMENT.name)
                put(COL_ENTRY_AMOUNT_USD, amountUsd)
                put(COL_ENTRY_AMOUNT_VES, amountVes)
                put(COL_ENTRY_BCV_RATE, bcvRate)
                put(COL_ENTRY_MATCHED_TX_ID, matchedTxId)
                put(COL_ENTRY_NOTES, notes)
                put(COL_ENTRY_TIMESTAMP, System.currentTimeMillis())
            }
            val entryId = db.insert(TABLE_LEDGER_ENTRIES, null, entryValues)

            // Actualizar balance deudor (sumar amountUsd)
            db.execSQL(
                "UPDATE $TABLE_DEBTORS SET $COL_DEBTOR_BALANCE_USD = $COL_DEBTOR_BALANCE_USD + ?, $COL_DEBTOR_UPDATED_AT = ? WHERE $COL_DEBTOR_ID = ?",
                arrayOf(amountUsd, System.currentTimeMillis(), debtorId)
            )

            db.setTransactionSuccessful()
            return entryId
        } finally {
            db.endTransaction()
        }
    }

    fun getLedgerEntries(debtorId: Long): List<LedgerEntry> {
        val list = mutableListOf<LedgerEntry>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_LEDGER_ENTRIES,
            null,
            "$COL_ENTRY_DEBTOR_ID = ?",
            arrayOf(debtorId.toString()),
            null, null,
            "$COL_ENTRY_TIMESTAMP DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToLedgerEntry(it))
            }
        }
        return list
    }

    /**
     * Busca si una transacción de Jornada ya está vinculada a un abono/pago en la Libreta.
     */
    fun getLedgerEntryByTransactionId(txId: Long): LedgerEntry? {
        if (txId <= 0L) return null
        val db = readableDatabase
        val cursor = db.query(
            TABLE_LEDGER_ENTRIES,
            null,
            "$COL_ENTRY_MATCHED_TX_ID = ?",
            arrayOf(txId.toString()),
            null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) cursorToLedgerEntry(it) else null
        }
    }

    /**
     * Si se elimina un movimiento en Jornada que estaba vinculado a un abono de fiado,
     * revierte el abono en la libreta para mantener la contabilidad consistente.
     */
    fun reversePaymentByTransactionId(txId: Long): Boolean {
        val entry = getLedgerEntryByTransactionId(txId) ?: return false
        if (entry.type != LedgerEntryType.PAYMENT) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE $TABLE_DEBTORS SET $COL_DEBTOR_BALANCE_USD = $COL_DEBTOR_BALANCE_USD - ?, $COL_DEBTOR_UPDATED_AT = ? WHERE $COL_DEBTOR_ID = ?",
                arrayOf(entry.amountUsd, System.currentTimeMillis(), entry.debtorId)
            )
            db.delete(TABLE_LEDGER_ENTRIES, "$COL_ENTRY_ID = ?", arrayOf(entry.id.toString()))
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    data class ConciliationMatch(
        val debtor: Debtor,
        val discountUsd: Double,
        val isPartialAgreed: Boolean = false
    )

    /**
     * Busca automáticamente un deudor activo cuyo monto exacto de conciliación (Equivalente BCV + céntimos,
     * o un abono parcial acordado con céntimos) coincida con el Pago Móvil recibido.
     * Si no hay coincidencia única sin dudas, retorna null para que el comerciante pueda asignarlo manualmente desde Jornada.
     */
    fun findDebtorForAutoConciliation(
        context: Context,
        amountVes: Double,
        bcvRate: Double,
        senderPhone: String? = null
    ): ConciliationMatch? {
        if (amountVes <= 0.0 || bcvRate <= 0.0) return null
        val activeDebtors = getAllDebtors().filter { it.isDebtor }
        if (activeDebtors.isEmpty()) return null

        val cleanSenderPhone = senderPhone?.replace(Regex("[^0-9]"), "")?.takeLast(7) ?: ""

        // 0. Verificar si algún deudor tiene un acuerdo de cobro (total o abono parcial) enviado por WhatsApp con céntimos
        val agreedMatches = activeDebtors.mapNotNull { debtor ->
            val pending = com.cobrix.pocket.utils.PreferencesHelper.getPendingDebtorConciliation(context, debtor.id)
            if (pending != null && Math.abs(amountVes - pending.first) < 0.005) {
                ConciliationMatch(
                    debtor = debtor,
                    discountUsd = pending.second,
                    isPartialAgreed = pending.second < debtor.debtAmountUsd - 0.01
                )
            } else null
        }
        if (agreedMatches.size == 1) {
            return agreedMatches.first()
        } else if (agreedMatches.size > 1 && cleanSenderPhone.length >= 7) {
            val phoneMatch = agreedMatches.find { m ->
                m.debtor.phone?.replace(Regex("[^0-9]"), "")?.endsWith(cleanSenderPhone) == true
            }
            if (phoneMatch != null) return phoneMatch
        }

        // 1. Coincidencia exacta con el monto de conciliación total (Equivalente BCV + céntimos adicionales del deudor)
        val exactConciliationMatches = activeDebtors.filter { debtor ->
            Math.abs(amountVes - debtor.getConciliationVes(bcvRate)) < 0.005
        }
        if (exactConciliationMatches.size == 1) {
            val d = exactConciliationMatches.first()
            return ConciliationMatch(debtor = d, discountUsd = d.debtAmountUsd, isPartialAgreed = false)
        } else if (exactConciliationMatches.size > 1 && cleanSenderPhone.length >= 7) {
            val phoneMatch = exactConciliationMatches.find { d ->
                d.phone?.replace(Regex("[^0-9]"), "")?.endsWith(cleanSenderPhone) == true
            }
            if (phoneMatch != null) {
                return ConciliationMatch(debtor = phoneMatch, discountUsd = phoneMatch.debtAmountUsd, isPartialAgreed = false)
            }
        }

        // 2. Si viene teléfono del remitente y coincide con un único deudor cuyo saldo base en Bs coincide exactamente
        if (cleanSenderPhone.length >= 7) {
            val phoneAndBaseMatches = activeDebtors.filter { debtor ->
                val dPhone = debtor.phone?.replace(Regex("[^0-9]"), "") ?: ""
                dPhone.endsWith(cleanSenderPhone) && Math.abs(amountVes - debtor.getEquivalentVes(bcvRate)) < 0.02
            }
            if (phoneAndBaseMatches.size == 1) {
                val d = phoneAndBaseMatches.first()
                return ConciliationMatch(debtor = d, discountUsd = d.debtAmountUsd, isPartialAgreed = false)
            }
        }

        return null
    }

    fun getAllLedgerEntries(): List<LedgerEntry> {
        val list = mutableListOf<LedgerEntry>()
        val db = readableDatabase
        val cursor = db.query(TABLE_LEDGER_ENTRIES, null, null, null, null, null, "$COL_ENTRY_TIMESTAMP ASC")
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToLedgerEntry(it))
            }
        }
        return list
    }

    fun bulkInsertDebtors(debtors: List<Debtor>): Int {
        val db = writableDatabase
        db.beginTransaction()
        var count = 0
        try {
            for (d in debtors) {
                val values = ContentValues().apply {
                    if (d.id > 0) put(COL_DEBTOR_ID, d.id)
                    put(COL_DEBTOR_NAME, d.name)
                    put(COL_DEBTOR_PHONE, d.phone)
                    put(COL_DEBTOR_IDENTIFICATION, d.identification)
                    put(COL_DEBTOR_BALANCE_USD, d.balanceUsd)
                    put(COL_DEBTOR_CREATED_AT, d.createdAt)
                    put(COL_DEBTOR_UPDATED_AT, d.updatedAt)
                }
                db.insertWithOnConflict(TABLE_DEBTORS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    fun bulkInsertLedgerEntries(entries: List<LedgerEntry>): Int {
        val db = writableDatabase
        db.beginTransaction()
        var count = 0
        try {
            for (e in entries) {
                val values = ContentValues().apply {
                    if (e.id > 0) put(COL_ENTRY_ID, e.id)
                    put(COL_ENTRY_DEBTOR_ID, e.debtorId)
                    put(COL_ENTRY_TYPE, e.type.name)
                    put(COL_ENTRY_AMOUNT_USD, e.amountUsd)
                    put(COL_ENTRY_AMOUNT_VES, e.amountVes)
                    put(COL_ENTRY_BCV_RATE, e.bcvRate)
                    put(COL_ENTRY_MATCHED_TX_ID, e.matchedTransactionId)
                    put(COL_ENTRY_NOTES, e.notes)
                    put(COL_ENTRY_TIMESTAMP, e.timestamp)
                }
                db.insertWithOnConflict(TABLE_LEDGER_ENTRIES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    private fun cursorToDebtor(cursor: Cursor): Debtor {
        return Debtor(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DEBTOR_ID)),
            name = cursor.getString(cursor.getColumnIndexOrThrow(COL_DEBTOR_NAME)),
            phone = cursor.getString(cursor.getColumnIndexOrThrow(COL_DEBTOR_PHONE)),
            identification = cursor.getString(cursor.getColumnIndexOrThrow(COL_DEBTOR_IDENTIFICATION)),
            balanceUsd = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_DEBTOR_BALANCE_USD)),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DEBTOR_CREATED_AT)),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DEBTOR_UPDATED_AT))
        )
    }

    private fun cursorToLedgerEntry(cursor: Cursor): LedgerEntry {
        val typeStr = cursor.getString(cursor.getColumnIndexOrThrow(COL_ENTRY_TYPE))
        val type = try {
            LedgerEntryType.valueOf(typeStr)
        } catch (_: Exception) {
            LedgerEntryType.CHARGE
        }
        val matchedIdx = cursor.getColumnIndex(COL_ENTRY_MATCHED_TX_ID)
        val matchedId = if (matchedIdx >= 0 && !cursor.isNull(matchedIdx)) cursor.getLong(matchedIdx) else null

        return LedgerEntry(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ENTRY_ID)),
            debtorId = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ENTRY_DEBTOR_ID)),
            type = type,
            amountUsd = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_ENTRY_AMOUNT_USD)),
            amountVes = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_ENTRY_AMOUNT_VES)),
            bcvRate = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_ENTRY_BCV_RATE)),
            matchedTransactionId = matchedId,
            notes = cursor.getString(cursor.getColumnIndexOrThrow(COL_ENTRY_NOTES)),
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ENTRY_TIMESTAMP))
        )
    }
}

