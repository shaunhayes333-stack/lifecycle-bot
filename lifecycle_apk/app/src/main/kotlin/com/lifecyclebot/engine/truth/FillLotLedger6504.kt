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
 * V5.0.6504 §1 — FILL LOT LEDGER (IMMUTABLE QUANTITY TRUTH).
 *
 * OPERATOR MANDATE (verbatim):
 *
 *   "Canonical entry fill qtyToken becomes immutable lot truth.
 *    Never recompute position qtyToken from current price, mark
 *    value, mcap or restored valuation.
 *    Position qty = sum(open fill lots) - sum(finalized sell lots).
 *    On divergence, rebuild position qty from FillLotLedger before
 *    rejecting the sell.
 *    QTY_DIVERGES_FROM_CANONICAL must AUTO-REPAIR paper positions
 *    where fill lots are authoritative."
 *
 * SCHEMA (v1) — SQLite WAL, ACID transactions, same pattern as
 * `PortfolioStore6405`:
 *
 *   fill_lot
 *     _id INTEGER PRIMARY KEY AUTOINCREMENT
 *     ts_ms INTEGER NOT NULL
 *     mint TEXT NOT NULL
 *     lot_id TEXT NOT NULL                -- BUY sig / paper attemptId
 *     side TEXT NOT NULL                  -- BUY | SELL
 *     qty_token_raw TEXT NOT NULL         -- BigInteger.toString() (immutable)
 *     lamports TEXT NOT NULL              -- BigInteger.toString()
 *     finalized INTEGER NOT NULL DEFAULT 0 -- 1 = terminal (definitively settled)
 *     is_paper INTEGER NOT NULL           -- 0 = live, 1 = paper
 *     source TEXT
 *     note TEXT
 *   INDEX(mint, side, finalized)
 *
 * IMMUTABLE. Rows are INSERT-ONLY except for the finalized flag which
 * flips 0→1 exactly once when a sell terminal-confirms. Nothing else
 * mutates a lot row — recompute always sums the ledger.
 */
object FillLotLedger6504 {

    private const val DB_NAME = "fill_lots_6504.db"
    private const val DB_VERSION = 1

    private val helperRef = AtomicReference<Helper?>(null)

    fun attach(context: Context) {
        if (helperRef.get() != null) return
        val h = Helper(context.applicationContext)
        h.setWriteAheadLoggingEnabled(true)
        helperRef.set(h)
        try {
            ForensicLogger.lifecycle(
                "FILL_LOT_LEDGER_6504_ATTACHED",
                "db=$DB_NAME version=$DB_VERSION wal=true",
            )
            PipelineHealthCollector.labelInc("FILL_LOT_LEDGER_6504_ATTACHED")
        } catch (_: Throwable) {}
    }

    fun isAttached(): Boolean = helperRef.get() != null

    data class Lot(
        val id: Long,
        val tsMs: Long,
        val mint: String,
        val lotId: String,
        val side: String, // BUY | SELL
        val qtyTokenRaw: BigInteger,
        val lamports: BigInteger,
        val finalized: Boolean,
        val isPaper: Boolean,
        val source: String,
        val note: String,
    )

    /**
     * Record a BUY fill lot. INSERT-ONLY. Idempotent by (mint, lotId, side)
     * so a duplicate paperBuy fill retry cannot double-count.
     * Returns row _id, or -1 on failure.
     */
    fun recordBuyFill(
        mint: String,
        lotId: String,
        qtyTokenRaw: BigInteger,
        lamports: BigInteger,
        isPaper: Boolean,
        source: String = "",
        note: String = "",
    ): Long {
        val h = helperRef.get() ?: return -1L
        if (mint.isBlank() || lotId.isBlank() || qtyTokenRaw.signum() <= 0) return -1L
        return try {
            val db = h.writableDatabase
            db.beginTransactionNonExclusive()
            try {
                // Idempotency check: same (mint, lotId, BUY) row already present?
                val existing = db.query(
                    "fill_lot", arrayOf("_id"),
                    "mint=? AND lot_id=? AND side='BUY'",
                    arrayOf(mint, lotId), null, null, null, "1",
                ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
                if (existing > 0) {
                    db.setTransactionSuccessful()
                    return@use existing
                }
                val cv = ContentValues().apply {
                    put("ts_ms", System.currentTimeMillis())
                    put("mint", mint)
                    put("lot_id", lotId)
                    put("side", "BUY")
                    put("qty_token_raw", qtyTokenRaw.toString())
                    put("lamports", lamports.toString())
                    put("finalized", 1) // A confirmed buy fill IS the terminal event for that lot; sells reference it
                    put("is_paper", if (isPaper) 1 else 0)
                    put("source", source.take(48))
                    put("note", note.take(120))
                }
                val id = db.insert("fill_lot", null, cv)
                db.setTransactionSuccessful()
                try { PipelineHealthCollector.labelInc("FILL_LOT_BUY_RECORDED_6504") } catch (_: Throwable) {}
                id
            } finally { db.endTransaction() }
        } catch (t: Throwable) {
            ErrorLogger.warn("FillLotLedger6504", "recordBuyFill failed: ${t.message?.take(120)}")
            -1L
        }
    }

    /**
     * Record a SELL fill lot. Terminal (`finalized=true`) means the sell
     * is definitively settled and MUST be included in the canonical qty
     * subtraction. Non-terminal (`finalized=false`) represents an
     * in-flight partial that has NOT yet been confirmed — sums use
     * finalized lots only.
     *
     * Idempotent by (mint, lotId, side).
     */
    fun recordSellFill(
        mint: String,
        lotId: String,
        qtyTokenRaw: BigInteger,
        lamports: BigInteger,
        finalized: Boolean,
        isPaper: Boolean,
        source: String = "",
        note: String = "",
    ): Long {
        val h = helperRef.get() ?: return -1L
        if (mint.isBlank() || lotId.isBlank() || qtyTokenRaw.signum() <= 0) return -1L
        return try {
            val db = h.writableDatabase
            db.beginTransactionNonExclusive()
            try {
                val existing = db.query(
                    "fill_lot", arrayOf("_id", "finalized"),
                    "mint=? AND lot_id=? AND side='SELL'",
                    arrayOf(mint, lotId), null, null, null, "1",
                ).use { c ->
                    if (c.moveToFirst()) c.getLong(0) to (c.getInt(1) == 1)
                    else -1L to false
                }
                val existingId = existing.first
                val existingFinal = existing.second
                if (existingId > 0) {
                    if (finalized && !existingFinal) {
                        // Flip 0→1 finalized on definitive settlement.
                        val cv = ContentValues().apply { put("finalized", 1) }
                        db.update("fill_lot", cv, "_id=?", arrayOf(existingId.toString()))
                        try { PipelineHealthCollector.labelInc("FILL_LOT_SELL_FINALIZED_6504") } catch (_: Throwable) {}
                    }
                    db.setTransactionSuccessful()
                    return@use existingId
                }
                val cv = ContentValues().apply {
                    put("ts_ms", System.currentTimeMillis())
                    put("mint", mint)
                    put("lot_id", lotId)
                    put("side", "SELL")
                    put("qty_token_raw", qtyTokenRaw.toString())
                    put("lamports", lamports.toString())
                    put("finalized", if (finalized) 1 else 0)
                    put("is_paper", if (isPaper) 1 else 0)
                    put("source", source.take(48))
                    put("note", note.take(120))
                }
                val id = db.insert("fill_lot", null, cv)
                db.setTransactionSuccessful()
                try {
                    PipelineHealthCollector.labelInc(
                        if (finalized) "FILL_LOT_SELL_FINALIZED_6504"
                        else "FILL_LOT_SELL_PENDING_6504"
                    )
                } catch (_: Throwable) {}
                id
            } finally { db.endTransaction() }
        } catch (t: Throwable) {
            ErrorLogger.warn("FillLotLedger6504", "recordSellFill failed: ${t.message?.take(120)}")
            -1L
        }
    }

    /**
     * Canonical quantity for a mint per the operator mandate:
     *   `Σ finalized BUY lots − Σ finalized SELL lots`
     * Returns BigInteger.ZERO when the ledger is empty or detached.
     */
    fun canonicalQtyOf(mint: String, isPaper: Boolean? = null): BigInteger {
        val h = helperRef.get() ?: return BigInteger.ZERO
        if (mint.isBlank()) return BigInteger.ZERO
        return try {
            val db = h.readableDatabase
            val where = StringBuilder("mint=? AND finalized=1")
            val args = arrayListOf(mint)
            if (isPaper != null) {
                where.append(" AND is_paper=?")
                args.add(if (isPaper) "1" else "0")
            }
            db.query(
                "fill_lot", arrayOf("side", "qty_token_raw"),
                where.toString(), args.toTypedArray(),
                null, null, null,
            ).use { c ->
                var buy = BigInteger.ZERO
                var sell = BigInteger.ZERO
                while (c.moveToNext()) {
                    val side = c.getString(0)
                    val qty = try { BigInteger(c.getString(1)) } catch (_: Throwable) { BigInteger.ZERO }
                    when (side) {
                        "BUY" -> buy = buy.add(qty)
                        "SELL" -> sell = sell.add(qty)
                    }
                }
                buy.subtract(sell).max(BigInteger.ZERO)
            }
        } catch (_: Throwable) { BigInteger.ZERO }
    }

    fun lotsOf(mint: String): List<Lot> {
        val h = helperRef.get() ?: return emptyList()
        if (mint.isBlank()) return emptyList()
        return try {
            val db = h.readableDatabase
            db.query("fill_lot", null, "mint=?", arrayOf(mint), null, null, "ts_ms ASC").use { c ->
                val out = ArrayList<Lot>(c.count)
                while (c.moveToNext()) out.add(readLot(c))
                out
            }
        } catch (_: Throwable) { emptyList() }
    }

    /**
     * V5.0.6504 §10 — PURGE + REBUILD.
     * Rebuild total realized SOL from FILL LOTS ONLY (no journal,
     * no cached position, no contaminated ledger row).
     *
     * Formula per lot pair: realized = sell.lamports - matched_buy.lamports_pro_rata
     * We do FIFO matching within each mint: iterate finalized BUY lots
     * in ts order, apply finalized SELL qty against them, credit realized
     * = sell_lamports_share − buy_lamports_share.
     *
     * Returns total realized SOL (lamports/1e9). Caller writes back
     * into PaperAccountLedger6430.realizedPnlPico for the operator
     * mandate "Rebuild contaminated PAPER performance from immutable
     * fills after repair".
     */
    fun rebuildRealizedSol(isPaperOnly: Boolean = true): Double {
        val h = helperRef.get() ?: return 0.0
        return try {
            val db = h.readableDatabase
            // Group by mint
            val paperWhere = if (isPaperOnly) " AND is_paper=1" else ""
            val mints = db.query(
                "fill_lot", arrayOf("DISTINCT mint"),
                "finalized=1$paperWhere", null, null, null, null,
            ).use { c ->
                val out = ArrayList<String>(c.count)
                while (c.moveToNext()) out.add(c.getString(0))
                out
            }
            var totalRealizedLamports = BigInteger.ZERO
            for (mint in mints) {
                val lots = db.query(
                    "fill_lot", arrayOf("side", "qty_token_raw", "lamports"),
                    "mint=? AND finalized=1$paperWhere",
                    arrayOf(mint), null, null, "ts_ms ASC",
                ).use { c ->
                    val out = ArrayList<Triple<String, BigInteger, BigInteger>>(c.count)
                    while (c.moveToNext()) {
                        val side = c.getString(0)
                        val qty = try { BigInteger(c.getString(1)) } catch (_: Throwable) { BigInteger.ZERO }
                        val lamp = try { BigInteger(c.getString(2)) } catch (_: Throwable) { BigInteger.ZERO }
                        out.add(Triple(side, qty, lamp))
                    }
                    out
                }
                // FIFO match sells against buys within this mint.
                val buyQueue = ArrayDeque<Pair<BigInteger, BigInteger>>() // (remainingQty, costPerToken lamports * 1e18 fixedPt)
                for ((side, qty, lamp) in lots) {
                    if (qty.signum() <= 0) continue
                    if (side == "BUY") {
                        // Cost basis per token: lamp / qty (kept as pair for pro-rata math)
                        buyQueue.addLast(qty to lamp)
                    } else if (side == "SELL") {
                        var remainingSellQty = qty
                        val sellLampPerToken = if (qty.signum() > 0) lamp else BigInteger.ZERO
                        while (remainingSellQty.signum() > 0 && buyQueue.isNotEmpty()) {
                            val (buyQty, buyLamp) = buyQueue.first()
                            val take = remainingSellQty.min(buyQty)
                            // Pro-rata lamports on both sides.
                            val proceedsShare = if (qty.signum() > 0) sellLampPerToken.multiply(take).divide(qty) else BigInteger.ZERO
                            val costShare = if (buyQty.signum() > 0) buyLamp.multiply(take).divide(buyQty) else BigInteger.ZERO
                            totalRealizedLamports = totalRealizedLamports.add(proceedsShare.subtract(costShare))
                            // Reduce the buy lot proportionally.
                            val newBuyQty = buyQty.subtract(take)
                            val newBuyLamp = if (buyQty.signum() > 0) buyLamp.multiply(newBuyQty).divide(buyQty) else BigInteger.ZERO
                            buyQueue.removeFirst()
                            if (newBuyQty.signum() > 0) buyQueue.addFirst(newBuyQty to newBuyLamp)
                            remainingSellQty = remainingSellQty.subtract(take)
                        }
                        // Any residual sell without a matching buy is a data error — skip (log later).
                    }
                }
            }
            // lamports → SOL (1e9 lamports per SOL)
            val realizedSol = totalRealizedLamports.toDouble() / 1_000_000_000.0
            try {
                ForensicLogger.lifecycle(
                    "FILL_LOT_REALIZED_REBUILD_6504",
                    "mints=${mints.size} totalRealizedSol=${"%.6f".format(realizedSol)} isPaperOnly=$isPaperOnly",
                )
                PipelineHealthCollector.labelInc("FILL_LOT_REALIZED_REBUILD_6504")
            } catch (_: Throwable) {}
            realizedSol
        } catch (t: Throwable) {
            ErrorLogger.warn("FillLotLedger6504", "rebuildRealizedSol failed: ${t.message?.take(120)}")
            0.0
        }
    }

    /**
     * V5.0.6504 §1 — QTY invariant assertion. Non-mutating; emits
     * `FILL_LOT_QTY_INVARIANT_BROKEN_6504` when the caller-observed qty
     * (typically Position.qtyToken × 10^decimals converted to raw)
     * differs from the ledger's canonical qty by more than tolerance.
     */
    fun assertMatches(mint: String, observedRaw: BigInteger, toleranceRaw: BigInteger = BigInteger.ZERO): Boolean {
        val canonical = canonicalQtyOf(mint)
        val delta = canonical.subtract(observedRaw).abs()
        val ok = delta <= toleranceRaw
        if (!ok) {
            try {
                ForensicLogger.lifecycle(
                    "FILL_LOT_QTY_INVARIANT_BROKEN_6504",
                    "mint=${mint.take(10)} observedRaw=$observedRaw canonicalRaw=$canonical delta=$delta tolerance=$toleranceRaw",
                )
                PipelineHealthCollector.labelInc("FILL_LOT_QTY_INVARIANT_BROKEN_6504")
            } catch (_: Throwable) {}
        }
        return ok
    }

    /** Diagnostic — total lot rows + distinct mint count. */
    fun snapshot(): Pair<Int, Int> {
        val h = helperRef.get() ?: return 0 to 0
        return try {
            val db = h.readableDatabase
            val rows = db.query("fill_lot", arrayOf("COUNT(*)"), null, null, null, null, null)
                .use { if (it.moveToFirst()) it.getInt(0) else 0 }
            val mints = db.query("fill_lot", arrayOf("COUNT(DISTINCT mint)"), null, null, null, null, null)
                .use { if (it.moveToFirst()) it.getInt(0) else 0 }
            rows to mints
        } catch (_: Throwable) { 0 to 0 }
    }

    fun statusLine(): String {
        val (rows, mints) = snapshot()
        return "attached=${isAttached()} rows=$rows mints=$mints"
    }

    private fun readLot(c: android.database.Cursor): Lot {
        fun col(name: String) = c.getColumnIndexOrThrow(name)
        return Lot(
            id = c.getLong(col("_id")),
            tsMs = c.getLong(col("ts_ms")),
            mint = c.getString(col("mint")),
            lotId = c.getString(col("lot_id")),
            side = c.getString(col("side")),
            qtyTokenRaw = try { BigInteger(c.getString(col("qty_token_raw"))) } catch (_: Throwable) { BigInteger.ZERO },
            lamports = try { BigInteger(c.getString(col("lamports"))) } catch (_: Throwable) { BigInteger.ZERO },
            finalized = c.getInt(col("finalized")) == 1,
            isPaper = c.getInt(col("is_paper")) == 1,
            source = c.getString(col("source")) ?: "",
            note = c.getString(col("note")) ?: "",
        )
    }

    internal fun clearForTest() {
        val h = helperRef.get() ?: return
        try {
            val db = h.writableDatabase
            db.beginTransaction()
            try {
                db.delete("fill_lot", null, null)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } catch (_: Throwable) {}
    }

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE fill_lot(
                    _id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts_ms INTEGER NOT NULL,
                    mint TEXT NOT NULL,
                    lot_id TEXT NOT NULL,
                    side TEXT NOT NULL,
                    qty_token_raw TEXT NOT NULL,
                    lamports TEXT NOT NULL,
                    finalized INTEGER NOT NULL DEFAULT 0,
                    is_paper INTEGER NOT NULL,
                    source TEXT,
                    note TEXT
                )""".trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX idx_fill_lot_mint_side_final ON fill_lot(mint, side, finalized)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX uq_fill_lot_mint_lot_side ON fill_lot(mint, lot_id, side)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }
}
