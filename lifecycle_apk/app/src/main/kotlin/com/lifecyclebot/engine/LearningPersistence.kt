package com.lifecyclebot.engine

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.9.438 — LEARNING PERSISTENCE
 *
 * User report (Feb 2026): "absolutely every learnt edge behaviour
 * sentience llm symbolic reasoning is all meant to be persistent."
 *
 * V5.9.435/436 shipped three outcome-attribution trackers
 * (ScoreExpectancyTracker, HoldDurationTracker, ExitReasonTracker) but
 * they held their rolling windows purely in memory — a reboot wiped
 * days of edge. This layer mirrors all three to a tiny SQLite kv table.
 *
 * Design:
 *   - One row per (tracker, key) — value is a JSON array of pnlPct doubles.
 *   - saveAll() called every N records + on orderly shutdown.
 *   - loadAll() called at boot from TradeHistoryStore.init().
 *
 * Fail-open everywhere: persistence errors NEVER block trading.
 */
object LearningPersistence {

    private const val TAG = "LearningPersist"

    /** Save to disk after this many total tracker record() calls. */
    private const val SAVE_EVERY_N = 50

    private val recordCounter = AtomicInteger(0)

    private var db: SQLiteDatabase? = null

    private class KvHelper(ctx: Context) :
        SQLiteOpenHelper(ctx, "learning_kv.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS kv (
                    tracker TEXT NOT NULL,
                    bucket  TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    updated INTEGER NOT NULL,
                    PRIMARY KEY (tracker, bucket)
                )
            """.trimIndent())
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
    }

    /** Init DB and restore every tracker's rolling window state. */
    fun init(ctx: Context) {
        try {
            db = KvHelper(ctx).writableDatabase
            loadAll()
            ErrorLogger.info(TAG, "✅ Learning persistence ready — trackers restored from disk.")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "init error: ${e.message}")
        }
    }

    /**
     * Invoked by each tracker's record(...) call. When counter crosses
     * SAVE_EVERY_N we flush all trackers to disk. Non-blocking for
     * callers beyond the occasional SQLite write (millisecond-scale).
     */
    fun onRecord() {
        val n = recordCounter.incrementAndGet()
        if (n % SAVE_EVERY_N == 0) {
            try { saveAll() } catch (_: Exception) {}
        }
    }

    /** Force-flush all trackers. Call on onDestroy / shutdown. */
    fun saveAll() {
        val d = db ?: return
        try {
            d.beginTransaction()
            saveTracker(d, "SCORE", ScoreExpectancyTracker.exportState())
            saveTracker(d, "HOLD",  HoldDurationTracker.exportState())
            saveTracker(d, "EXIT",  ExitReasonTracker.exportState())
            d.setTransactionSuccessful()
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "saveAll error: ${e.message}")
        } finally {
            try { d.endTransaction() } catch (_: Exception) {}
        }
    }

    /** Restore every tracker from the kv table. */
    private fun loadAll() {
        val d = db ?: return
        ScoreExpectancyTracker.importState(loadTracker(d, "SCORE"))
        HoldDurationTracker.importState(loadTracker(d, "HOLD"))
        ExitReasonTracker.importState(loadTracker(d, "EXIT"))
    }

    private fun saveTracker(d: SQLiteDatabase, tracker: String, state: Map<String, List<Double>>) {
        // Clear tracker's rows then insert all buckets. Single TX = atomic.
        d.execSQL("DELETE FROM kv WHERE tracker = ?", arrayOf(tracker))
        val now = System.currentTimeMillis()
        state.forEach { (bucket, pnls) ->
            val payload = JSONArray()
            pnls.forEach { payload.put(it) }
            d.execSQL(
                "INSERT OR REPLACE INTO kv (tracker, bucket, payload, updated) VALUES (?,?,?,?)",
                arrayOf(tracker, bucket, payload.toString(), now),
            )
        }
    }

    private fun loadTracker(d: SQLiteDatabase, tracker: String): Map<String, List<Double>> {
        val out = mutableMapOf<String, List<Double>>()
        try {
            d.rawQuery("SELECT bucket, payload FROM kv WHERE tracker = ?", arrayOf(tracker)).use { c ->
                while (c.moveToNext()) {
                    val bucket = c.getString(0)
                    val payload = c.getString(1)
                    val arr = JSONArray(payload)
                    val pnls = ArrayList<Double>(arr.length())
                    for (i in 0 until arr.length()) pnls.add(arr.getDouble(i))
                    out[bucket] = pnls
                }
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "loadTracker($tracker) error: ${e.message}")
        }
        return out
    }
}
