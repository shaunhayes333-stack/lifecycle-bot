package com.lifecyclebot.collective

import android.util.Log

/**
 * Collective Learning Store
 *
 * Native LibSQL/Turso database connection using tech.turso.libsql SDK.
 * Provides embedded replica with local SQLite + remote Turso sync.
 *
 * TABLES:
 *   - trade_outcomes: Individual trade results
 *   - collective_memory: Aggregated learning patterns
 */
object CollectiveLearningStore {

    private const val TAG = "CollectiveLearningStore"

    @Volatile
    private var db: tech.turso.libsql.Database? = null

    private val lock = Any()

    fun init(path: String, url: String, token: String): Boolean {
        synchronized(lock) {
            if (db != null) {
                Log.d(TAG, "Already initialized")
                return true
            }

            if (path.isBlank() || url.isBlank() || token.isBlank()) {
                Log.e(TAG, "Init failed: path/url/token must not be blank")
                return false
            }

            return try {
                db = tech.turso.libsql.Libsql.open(
                    path = path,
                    url = url,
                    authToken = token
                )

                db!!.connect().use { conn ->
                    conn.execute(
                        """
                        CREATE TABLE IF NOT EXISTS trade_outcomes (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          mint TEXT NOT NULL,
                          symbol TEXT,
                          strategy TEXT NOT NULL,
                          setup_class TEXT,
                          regime TEXT,
                          liquidity_band TEXT,
                          hold_class TEXT,
                          pnl_pct REAL NOT NULL,
                          win INTEGER NOT NULL,
                          confidence REAL,
                          created_at INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )

                    conn.execute(
                        """
                        CREATE TABLE IF NOT EXISTS collective_memory (
                          key TEXT PRIMARY KEY,
                          mint TEXT,
                          strategy TEXT,
                          setup_class TEXT,
                          regime TEXT,
                          samples INTEGER NOT NULL DEFAULT 0,
                          wins INTEGER NOT NULL DEFAULT 0,
                          losses INTEGER NOT NULL DEFAULT 0,
                          avg_pnl REAL NOT NULL DEFAULT 0,
                          last_seen INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )

                    conn.execute(
                        """
                        CREATE INDEX IF NOT EXISTS idx_trade_outcomes_mint_strategy
                        ON trade_outcomes(mint, strategy)
                        """.trimIndent()
                    )

                    conn.execute(
                        """
                        CREATE INDEX IF NOT EXISTS idx_trade_outcomes_created_at
                        ON trade_outcomes(created_at)
                        """.trimIndent()
                    )

                    conn.execute(
                        """
                        CREATE INDEX IF NOT EXISTS idx_collective_memory_strategy
                        ON collective_memory(strategy)
                        """.trimIndent()
                    )
                }

                Log.i(TAG, "CollectiveLearningStore initialized with LibSQL native SDK")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Init failed: ${e.message}", e)
                try {
                    db?.close()
                } catch (_: Exception) {
                }
                db = null
                false
            }
        }
    }

    fun recordOutcome(
        mint: String,
        symbol: String?,
        strategy: String,
        setupClass: String?,
        regime: String?,
        liquidityBand: String?,
        holdClass: String?,
        pnlPct: Double,
        confidence: Double?
    ): Boolean {
        synchronized(lock) {
            val database = db
            if (database == null) {
                Log.w(TAG, "recordOutcome skipped: database not initialized")
                return false
            }

            if (mint.isBlank() || strategy.isBlank()) {
                Log.w(TAG, "recordOutcome skipped: mint/strategy blank")
                return false
            }

            val safePnl = sanitizeDouble(pnlPct)
            val safeConfidence = confidence?.let { sanitizeDouble(it) }
            val win = if (safePnl > 0.0) 1 else 0
            val loss = if (win == 1) 0 else 1
            val ts = System.currentTimeMillis()
            val key = buildMemoryKey(mint, strategy, setupClass, regime)

            return try {
                database.connect().use { conn ->
                    conn.execute(
                        """
                        INSERT INTO trade_outcomes
                        (mint, symbol, strategy, setup_class, regime, liquidity_band, hold_class, pnl_pct, win, confidence, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        mint,
                        symbol,
                        strategy,
                        setupClass,
                        regime,
                        liquidityBand,
                        holdClass,
                        safePnl,
                        win,
                        safeConfidence,
                        ts
                    )

                    conn.execute(
                        """
                        INSERT INTO collective_memory
                        (key, mint, strategy, setup_class, regime, samples, wins, losses, avg_pnl, last_seen)
                        VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?)
                        ON CONFLICT(key) DO UPDATE SET
                          samples = collective_memory.samples + 1,
                          wins = collective_memory.wins + excluded.wins,
                          losses = collective_memory.losses + excluded.losses,
                          avg_pnl = (
                            (collective_memory.avg_pnl * collective_memory.samples) + excluded.avg_pnl
                          ) / (collective_memory.samples + 1),
                          last_seen = excluded.last_seen
                        """.trimIndent(),
                        key,
                        mint,
                        strategy,
                        setupClass,
                        regime,
                        if (win == 1) 1 else 0,
                        if (loss == 1) 1 else 0,
                        safePnl,
                        ts
                    )
                }

                Log.d(
                    TAG,
                    "Recorded: ${symbol ?: mint} ($strategy) ${if (win == 1) "WIN" else "LOSS"} ${safePnl.toInt()}%"
                )
                true
            } catch (e: Exception) {
                Log.e(TAG, "recordOutcome failed: ${e.message}", e)
                false
            }
        }
    }

    fun getCollectiveBias(
        mint: String,
        strategy: String,
        setupClass: String?,
        regime: String?
    ): Double {
        synchronized(lock) {
            val database = db ?: return 0.0
            if (mint.isBlank() || strategy.isBlank()) return 0.0

            val key = buildMemoryKey(mint, strategy, setupClass, regime)

            return try {
                database.connect().use { conn ->
                    val rows = conn.query(
                        """
                        SELECT samples, wins, avg_pnl
                        FROM collective_memory
                        WHERE key = ?
                        """.trimIndent(),
                        key
                    )

                    for (row in rows) {
                        val samples = parseInt(row.getOrNullSafe(0))
                        val wins = parseInt(row.getOrNullSafe(1))
                        val avgPnl = parseDouble(row.getOrNullSafe(2))

                        if (samples <= 0) return 0.0

                        val winRate = wins.toDouble() / samples.toDouble()
                        val bias = (winRate * 20.0) + (avgPnl * 0.5)
                        return sanitizeDouble(bias)
                    }

                    0.0
                }
            } catch (e: Exception) {
                Log.w(TAG, "getCollectiveBias failed: ${e.message}")
                0.0
            }
        }
    }

    fun isReady(): Boolean {
        return db != null
    }

    fun close() {
        synchronized(lock) {
            try {
                db?.close()
                db = null
                Log.i(TAG, "Database closed")
            } catch (e: Exception) {
                Log.w(TAG, "Close warning: ${e.message}")
            }
        }
    }

    private fun buildMemoryKey(
        mint: String,
        strategy: String,
        setupClass: String?,
        regime: String?
    ): String {
        return listOf(
            mint.trim(),
            strategy.trim(),
            (setupClass ?: "UNK").trim().ifBlank { "UNK" },
            (regime ?: "UNK").trim().ifBlank { "UNK" }
        ).joinToString("|")
    }

    private fun sanitizeDouble(value: Double): Double {
        return if (value.isNaN() || value.isInfinite()) 0.0 else value
    }

    private fun parseInt(value: Any?): Int {
        return when (value) {
            null -> 0
            is Int -> value
            is Long -> value.toInt()
            is Double -> value.toInt()
            is Float -> value.toInt()
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: value.toDoubleOrNull()?.toInt() ?: 0
            else -> 0
        }
    }

    private fun parseDouble(value: Any?): Double {
        return when (value) {
            null -> 0.0
            is Double -> sanitizeDouble(value)
            is Float -> sanitizeDouble(value.toDouble())
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            is Number -> sanitizeDouble(value.toDouble())
            is String -> sanitizeDouble(value.toDoubleOrNull() ?: 0.0)
            else -> 0.0
        }
    }

    private fun List<Any?>.getOrNullSafe(index: Int): Any? {
        return if (index in indices) this[index] else null
    }
}