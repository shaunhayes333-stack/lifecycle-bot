package com.lifecyclebot.engine.truth

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6405 §1+§2 — CRASH-SAFE PORTFOLIO PERSISTENCE.
 *
 * OPERATOR DIRECTIVE
 * ───────────────────
 * "Room Database with WAL/transactions" — the SharedPreferences-backed
 *  portfolio has no ACID guarantee. A restart mid-write leaves the
 *  in-app state inconsistent with the wallet.
 *
 * IMPLEMENTATION NOTE
 * ────────────────────
 * We back the crash-safe store with Android's platform SQLite (which
 * is the same engine Room compiles onto). This gives us:
 *   • WAL journal mode (enableWriteAheadLogging)
 *   • Real ACID transactions
 *   • Zero new gradle deps (no KSP/KAPT risk on the CI-only build)
 *
 * A follow-up commit can wrap this store with Room's DAO annotations
 * for compile-time query validation; the on-disk shape stays identical.
 *
 * SCHEMA (v1)
 * ───────────
 *   paper_trade_event  (append-only, immutable)
 *     _id INTEGER PRIMARY KEY AUTOINCREMENT
 *     ts_ms INTEGER NOT NULL
 *     wallet TEXT NOT NULL
 *     mint TEXT NOT NULL
 *     position_generation INTEGER NOT NULL
 *     kind TEXT NOT NULL                -- BUY_INTENT, BUY_VERIFIED, SELL_INTENT, SELL_VERIFIED, TERMINAL
 *     raw_qty TEXT NOT NULL             -- BigInteger.toString()
 *     lamports TEXT NOT NULL            -- BigInteger.toString()
 *     source TEXT
 *     note TEXT
 *
 *   paper_position  (durable checkpoint, one row per wallet+mint+gen)
 *     wallet TEXT NOT NULL
 *     mint TEXT NOT NULL
 *     position_generation INTEGER NOT NULL
 *     entry_raw TEXT NOT NULL
 *     sold_raw TEXT NOT NULL
 *     entry_lamports TEXT NOT NULL
 *     is_paper INTEGER NOT NULL          -- 0 = live, 1 = paper
 *     terminal TEXT                      -- NULL = open
 *     terminal_at_ms INTEGER             -- NULL = open
 *     PRIMARY KEY (wallet, mint, position_generation)
 */
object PortfolioStore6405 {

    private const val DB_NAME = "portfolio6405.db"
    private const val DB_VERSION = 1

    private val helperRef = AtomicReference<Helper?>(null)

    fun attach(context: Context) {
        if (helperRef.get() != null) return
        val h = Helper(context.applicationContext)
        // Enable WAL BEFORE any writable-database access.
        h.setWriteAheadLoggingEnabled(true)
        helperRef.set(h)
        try {
            ForensicLogger.lifecycle(
                "PORTFOLIO_STORE_6405_ATTACHED",
                "db=$DB_NAME version=$DB_VERSION wal=true",
            )
            PipelineHealthCollector.labelInc("PORTFOLIO_STORE_6405_ATTACHED")
        } catch (_: Throwable) {}
    }

    fun isAttached(): Boolean = helperRef.get() != null

    /** Insert a canonical trade event. Append-only; never updates. */
    fun appendEvent(
        wallet: String,
        mint: String,
        positionGeneration: Long,
        kind: String,
        rawQty: BigInteger,
        lamports: BigInteger,
        source: String = "",
        note: String = "",
    ): Long {
        val h = helperRef.get() ?: return -1L
        return try {
            val db = h.writableDatabase
            val cv = ContentValues().apply {
                put("ts_ms", System.currentTimeMillis())
                put("wallet", wallet)
                put("mint", mint)
                put("position_generation", positionGeneration)
                put("kind", kind)
                put("raw_qty", rawQty.toString())
                put("lamports", lamports.toString())
                put("source", source)
                put("note", note)
            }
            db.insert("paper_trade_event", null, cv)
        } catch (t: Throwable) {
            ErrorLogger.warn(
                "PortfolioStore6405",
                "appendEvent failed: ${t.message?.take(120)}",
            )
            -1L
        }
    }

    data class PositionRow(
        val wallet: String,
        val mint: String,
        val positionGeneration: Long,
        val entryRaw: BigInteger,
        val soldRaw: BigInteger,
        val entryLamports: BigInteger,
        val isPaper: Boolean,
        val terminal: String?,
        val terminalAtMs: Long?,
    ) {
        val remainingRaw: BigInteger get() = entryRaw.subtract(soldRaw).max(BigInteger.ZERO)
    }

    /**
     * Upsert a position row atomically (BEGIN/COMMIT). Used for buy
     * fills, sell fills, and terminal marking — the whole sequence
     * is one transaction so a crash mid-write either leaves the prior
     * value intact or the new value fully committed.
     */
    fun upsertPosition(row: PositionRow): Boolean {
        val h = helperRef.get() ?: return false
        return try {
            val db = h.writableDatabase
            db.beginTransactionNonExclusive()
            try {
                val cv = ContentValues().apply {
                    put("wallet", row.wallet)
                    put("mint", row.mint)
                    put("position_generation", row.positionGeneration)
                    put("entry_raw", row.entryRaw.toString())
                    put("sold_raw", row.soldRaw.toString())
                    put("entry_lamports", row.entryLamports.toString())
                    put("is_paper", if (row.isPaper) 1 else 0)
                    if (row.terminal != null) put("terminal", row.terminal)
                    if (row.terminalAtMs != null) put("terminal_at_ms", row.terminalAtMs)
                }
                db.insertWithOnConflict(
                    "paper_position", null, cv, SQLiteDatabase.CONFLICT_REPLACE,
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            true
        } catch (t: Throwable) {
            ErrorLogger.warn(
                "PortfolioStore6405",
                "upsertPosition failed: ${t.message?.take(120)}",
            )
            false
        }
    }

    fun findPosition(wallet: String, mint: String, positionGeneration: Long): PositionRow? {
        val h = helperRef.get() ?: return null
        return try {
            val db = h.readableDatabase
            db.query(
                "paper_position",
                null,
                "wallet=? AND mint=? AND position_generation=?",
                arrayOf(wallet, mint, positionGeneration.toString()),
                null, null, null,
            ).use { c ->
                if (!c.moveToFirst()) null else readPosition(c)
            }
        } catch (_: Throwable) { null }
    }

    fun openPositions(): List<PositionRow> {
        val h = helperRef.get() ?: return emptyList()
        return try {
            val db = h.readableDatabase
            db.query(
                "paper_position", null,
                "terminal IS NULL",
                null, null, null, null,
            ).use { c ->
                val out = mutableListOf<PositionRow>()
                while (c.moveToNext()) out.add(readPosition(c))
                out
            }
        } catch (_: Throwable) { emptyList() }
    }

    private fun readPosition(c: android.database.Cursor): PositionRow {
        fun col(name: String) = c.getColumnIndexOrThrow(name)
        val terminalIdx = c.getColumnIndex("terminal")
        val terminalAtIdx = c.getColumnIndex("terminal_at_ms")
        return PositionRow(
            wallet = c.getString(col("wallet")),
            mint = c.getString(col("mint")),
            positionGeneration = c.getLong(col("position_generation")),
            entryRaw = BigInteger(c.getString(col("entry_raw"))),
            soldRaw = BigInteger(c.getString(col("sold_raw"))),
            entryLamports = BigInteger(c.getString(col("entry_lamports"))),
            isPaper = c.getInt(col("is_paper")) == 1,
            terminal = if (terminalIdx >= 0 && !c.isNull(terminalIdx)) c.getString(terminalIdx) else null,
            terminalAtMs = if (terminalAtIdx >= 0 && !c.isNull(terminalAtIdx)) c.getLong(terminalAtIdx) else null,
        )
    }

    /** Diagnostics — row counts for the operator dashboard. */
    fun rowCounts(): Pair<Int, Int> {
        val h = helperRef.get() ?: return 0 to 0
        return try {
            val db = h.readableDatabase
            val events = db.query("paper_trade_event", arrayOf("COUNT(*)"), null, null, null, null, null)
                .use { if (it.moveToFirst()) it.getInt(0) else 0 }
            val positions = db.query("paper_position", arrayOf("COUNT(*)"), null, null, null, null, null)
                .use { if (it.moveToFirst()) it.getInt(0) else 0 }
            events to positions
        } catch (_: Throwable) { 0 to 0 }
    }

    /** For tests only — wipes the database. */
    internal fun clearForTest() {
        val h = helperRef.get() ?: return
        try {
            val db = h.writableDatabase
            db.beginTransaction()
            try {
                db.delete("paper_trade_event", null, null)
                db.delete("paper_position", null, null)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } catch (_: Throwable) {}
    }

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE paper_trade_event(
                    _id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts_ms INTEGER NOT NULL,
                    wallet TEXT NOT NULL,
                    mint TEXT NOT NULL,
                    position_generation INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    raw_qty TEXT NOT NULL,
                    lamports TEXT NOT NULL,
                    source TEXT,
                    note TEXT
                )""".trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX idx_event_mint_gen ON paper_trade_event(mint, position_generation)",
            )
            db.execSQL(
                """CREATE TABLE paper_position(
                    wallet TEXT NOT NULL,
                    mint TEXT NOT NULL,
                    position_generation INTEGER NOT NULL,
                    entry_raw TEXT NOT NULL,
                    sold_raw TEXT NOT NULL,
                    entry_lamports TEXT NOT NULL,
                    is_paper INTEGER NOT NULL,
                    terminal TEXT,
                    terminal_at_ms INTEGER,
                    PRIMARY KEY (wallet, mint, position_generation)
                )""".trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // No migrations yet; schema v1 is the initial release.
        }
    }
}
