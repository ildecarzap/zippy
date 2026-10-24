package com.cobrix.pocket.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.cobrix.pocket.data.model.Transaction
import com.cobrix.pocket.data.model.TransactionType
import java.util.Calendar

class PocketDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "cobrix_pocket.db"
        private const val DATABASE_VERSION = 2

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_TRANSACTIONS ADD COLUMN $COL_IS_MANUAL INTEGER DEFAULT 0")
            } catch (_: Exception) { }
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
        sender: String? = null
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
            val cryptoCursor = db.rawQuery(cryptoQuery, arrayOf(TransactionType.INCOME.name, amountUsd.toString(), tenMinutesAgo.toString()))
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
            arrayOf(TransactionType.INCOME.name, reference, amountVes.toString(), amountUsd.toString(), sinceTimestamp.toString())
        } else {
            arrayOf(TransactionType.INCOME.name, reference, amountVes.toString(), amountUsd.toString())
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
        }
        return db.update(TABLE_TRANSACTIONS, values, "$COL_ID = ?", arrayOf(tx.id.toString()))
    }

    fun deleteTransaction(id: Long): Int {
        val db = writableDatabase
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
}
