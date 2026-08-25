package com.lifecyclebot.engine.truth

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6437 — SQLITE-BACKED IDEMPOTENCY KEY STORE (P1).
 *
 * OPERATOR DIRECTIVE
 * ──────────────────
 * "Idempotency-key Persistence — ensure BUY:runId:positionId and
 *  SELL:runId:positionId:generation keys are persisted to SQLite so
 *  that if the app restarts mid-transaction, it never resubmits a live
 *  order."
 *
 * DESIGN
 * ──────
 * • Backed by Android platform SQLite (same pattern as PortfolioStore6405):
 *   WAL journal mode, ACID transactions, zero new gradle deps.
 * • Attach in AATEApp.onCreate() alongside PortfolioStore6405.
 * • checkAndReserve(key) → InsertResult:
 *      NEW      – key was fresh; caller MAY submit the trade.
 *      DUPLICATE – key already existed; caller MUST NOT resubmit.
 * • markTerminal(key, terminal) → stamps the row as final so the
 *   restart replay can distinguish "still in flight" vs "already
 *   settled".
 * • Existing in-memory idempotency (ExecutionTicketMachine6411) is
 *   preserved; this layer is the persistent belt beneath it.
 *
 * Key format
 * ──────────
 *   BUY:$runId:$positionId
 *   SELL:$runId:$positionId:$generation
 * Both are ASCII, ≤ 96 chars — safe as SQLite TEXT PRIMARY KEY.
 */
object IdempotencyKeyStore6437 {

    private const val DB_NAME = "idempotency6437.db"
    private const val DB_VERSION = 1
    private const val TABLE = "idempotency_key"

    enum class InsertResult { NEW, DUPLICATE }

    private val helperRef = AtomicReference<Helper?>(null)
    private val newCount = AtomicLong(0L)
    private val duplicateCount = AtomicLong(0L)
    private val terminalCount = AtomicLong(0L)

    fun attach(context: Context) {
        if (helperRef.get() != null) return
        val h = Helper(context.applicationContext)
        h.setWriteAheadLoggingEnabled(true)
        helperRef.set(h)
        try {
            ForensicLogger.lifecycle(
                "IDEMPOTENCY_STORE_6437_ATTACHED",
                "db=$DB_NAME version=$DB_VERSION wal=true",
            )
            PipelineHealthCollector.labelInc("IDEMPOTENCY_STORE_6437_ATTACHED")
        } catch (_: Throwable) {}
    }

    fun isAttached(): Boolean = helperRef.get() != null

    /** Canonical BUY key. */
    fun buyKey(runId: String, positionId: String): String = "BUY:$runId:$positionId"

    /** Canonical SELL key including generation for partial/full sell iterations. */
    fun sellKey(runId: String, positionId: String, generation: Long): String =
        "SELL:$runId:$positionId:$generation"

    /**
     * Atomically insert the key. If the row already existed, returns
     * DUPLICATE — the caller MUST NOT resubmit the trade.
     *
     * Safe to call on any thread. Fails-open (returns NEW) if the store
     * is not attached, so unit tests / early-boot paths that never
     * called attach() do not deadlock — pair this with the in-memory
     * ExecutionTicketMachine6411 gate for full safety.
     */
    fun checkAndReserve(key: String, mode: String = "PAPER", note: String = ""): InsertResult {
        val h = helperRef.get() ?: return InsertResult.NEW
        return try {
            val db = h.writableDatabase
            val cv = ContentValues().apply {
                put("key", key)
                put("reserved_at_ms", System.currentTimeMillis())
                put("mode", mode)
                put("terminal", "")
                put("terminal_at_ms", 0L)
                put("note", note)
            }
            val rowId = db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            if (rowId == -1L) {
                duplicateCount.incrementAndGet()
                try { PipelineHealthCollector.labelInc("IDEMPOTENCY_DUPLICATE_6437") } catch (_: Throwable) {}
                try {
                    ForensicLogger.lifecycle(
                        "IDEMPOTENCY_DUPLICATE_6437",
                        "key=${key.take(80)} mode=$mode",
                    )
                } catch (_: Throwable) {}
                InsertResult.DUPLICATE
            } else {
                newCount.incrementAndGet()
                try { PipelineHealthCollector.labelInc("IDEMPOTENCY_NEW_6437") } catch (_: Throwable) {}
                InsertResult.NEW
            }
        } catch (t: Throwable) {
            ErrorLogger.warn(
                "IdempotencyKeyStore6437",
                "checkAndReserve failed key=${key.take(40)}: ${t.message?.take(120)}",
            )
            // Fail-open: prefer submitting a trade twice (in-memory gate catches it)
            // over refusing all trades on a corrupt DB.
            InsertResult.NEW
        }
    }

    /**
     * Mark a reserved key as terminal (COMPLETE, FAILED, CANCELLED, ...).
     * Idempotent: subsequent calls overwrite the terminal + timestamp.
     */
    fun markTerminal(key: String, terminal: String): Boolean {
        val h = helperRef.get() ?: return false
        return try {
            val db = h.writableDatabase
            val cv = ContentValues().apply {
                put("terminal", terminal)
                put("terminal_at_ms", System.currentTimeMillis())
            }
            val rows = db.update(TABLE, cv, "key=?", arrayOf(key))
            if (rows > 0) {
                terminalCount.incrementAndGet()
                try { PipelineHealthCollector.labelInc("IDEMPOTENCY_TERMINAL_6437") } catch (_: Throwable) {}
                true
            } else false
        } catch (t: Throwable) {
            ErrorLogger.warn(
                "IdempotencyKeyStore6437",
                "markTerminal failed key=${key.take(40)}: ${t.message?.take(120)}",
            )
            false
        }
    }

    /**
     * True if the key exists AND is not yet terminal (still in-flight).
     * Used by restart replay to detect submitted-but-unconfirmed trades.
     */
    fun terminalFor(key: String): String? {
        val h = helperRef.get() ?: return null
        return try {
            val db = h.readableDatabase
            db.query(TABLE, arrayOf("terminal"), "key=?", arrayOf(key), null, null, null).use { c ->
                if (!c.moveToFirst()) null else c.getString(0)?.takeIf { it.isNotBlank() }
            }
        } catch (_: Throwable) { null }
    }

    fun isInFlight(key: String): Boolean {
        val h = helperRef.get() ?: return false
        return try {
            val db = h.readableDatabase
            db.query(
                TABLE, arrayOf("terminal"),
                "key=?", arrayOf(key), null, null, null,
            ).use { c ->
                if (!c.moveToFirst()) false
                else {
                    val term = c.getString(0) ?: ""
                    term.isBlank()
                }
            }
        } catch (_: Throwable) { false }
    }

    fun rowCount(): Int {
        val h = helperRef.get() ?: return 0
        return try {
            val db = h.readableDatabase
            db.query(TABLE, arrayOf("COUNT(*)"), null, null, null, null, null)
                .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (_: Throwable) { 0 }
    }

    fun statusLine(): String {
        val n = newCount.get()
        val d = duplicateCount.get()
        val t = terminalCount.get()
        val rows = rowCount()
        return "new=$n dup=$d terminal=$t rows=$rows attached=${isAttached()}"
    }

    /** For tests only. */
    internal fun clearForTest() {
        val h = helperRef.get() ?: return
        try {
            val db = h.writableDatabase
            db.beginTransaction()
            try {
                db.delete(TABLE, null, null)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } catch (_: Throwable) {}
        newCount.set(0L)
        duplicateCount.set(0L)
        terminalCount.set(0L)
    }

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE $TABLE(
                    key TEXT PRIMARY KEY,
                    reserved_at_ms INTEGER NOT NULL,
                    mode TEXT NOT NULL,
                    terminal TEXT,
                    terminal_at_ms INTEGER,
                    note TEXT
                )""".trimIndent(),
            )
            db.execSQL("CREATE INDEX idx_idem_terminal ON $TABLE(terminal)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // No migrations yet.
        }
    }
}
