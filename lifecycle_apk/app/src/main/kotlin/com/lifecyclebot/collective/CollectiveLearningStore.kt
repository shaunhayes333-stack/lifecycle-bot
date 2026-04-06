package com.lifecyclebot.collective

import android.util.Log

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * COLLECTIVE LEARNING STORE
 * 
 * Native LibSQL/Turso database connection using tech.turso.libsql SDK.
 * Provides embedded replica with local SQLite + remote Turso sync.
 * 
 * TABLES:
 *   - trade_outcomes: Individual trade results
 *   - collective_memory: Aggregated learning patterns
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object CollectiveLearningStore {
    private const val TAG = "CollectiveLearningStore"
    private var db: tech.turso.libsql.Database? = null

    fun init(path: String, url: String, token: String) {
        db = tech.turso.libsql.Libsql.open(
            path = path,
            url = url,
            authToken = token
        )
        db!!.connect().use { conn ->
            conn.execute("""
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
            """.trimIndent())

            conn.execute("""
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
            """.trimIndent())
        }
        Log.i(TAG, "✅ CollectiveLearningStore initialized with LibSQL native SDK")
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
    ) {
        val win = if (pnlPct > 0.0) 1 else 0
        val ts = System.currentTimeMillis()
        val key = listOf(mint, strategy, setupClass ?: "UNK", regime ?: "UNK").joinToString("|")

        db!!.connect().use { conn ->
            conn.execute("""
                INSERT INTO trade_outcomes
                (mint, symbol, strategy, setup_class, regime, liquidity_band, hold_class, pnl_pct, win, confidence, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
                mint, symbol, strategy, setupClass, regime, liquidityBand, holdClass, pnlPct, win, confidence, ts
            )

            conn.execute("""
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
                key, mint, strategy, setupClass, regime,
                if (win == 1) 1 else 0,
                if (win == 0) 1 else 0,
                pnlPct,
                ts
            )
        }
        Log.d(TAG, "📤 Recorded: $symbol ($strategy) ${if (win == 1) "WIN" else "LOSS"} ${pnlPct.toInt()}%")
    }

    fun getCollectiveBias(
        mint: String,
        strategy: String,
        setupClass: String?,
        regime: String?
    ): Double {
        val key = listOf(mint, strategy, setupClass ?: "UNK", regime ?: "UNK").joinToString("|")

        db!!.connect().use { conn ->
            val rs = conn.query("""
                SELECT samples, wins, avg_pnl
                FROM collective_memory
                WHERE key = ?
            """.trimIndent(), key)

            if (rs.next()) {
                val samples = rs.getInt(0)
                val wins = rs.getInt(1)
                val avgPnl = rs.getDouble(2)

                val winRate = if (samples > 0) wins.toDouble() / samples else 0.0
                return (winRate * 20.0) + (avgPnl * 0.5)
            }
        }
        return 0.0
    }
    
    /**
     * Check if store is initialized.
     */
    fun isReady(): Boolean = db != null
    
    /**
     * Sync local replica with remote Turso.
     */
    fun sync() {
        try {
            db?.sync()
            Log.d(TAG, "📥 Synced with remote Turso")
        } catch (e: Exception) {
            Log.w(TAG, "Sync warning: ${e.message}")
        }
    }
    
    /**
     * Close the database connection.
     */
    fun close() {
        try {
            db?.close()
            db = null
            Log.i(TAG, "Database closed")
        } catch (e: Exception) {
            Log.w(TAG, "Close warning: ${e.message}")
        }
    }
}
