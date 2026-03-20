package com.lifecyclebot.engine

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/**
 * TradeDatabase — persistent SQLite store for every completed trade.
 *
 * Every trade the bot executes is written here with its full context:
 * the signal values, market conditions, phase, and actual outcome.
 * This is the ground truth the learning engine trains on.
 *
 * Schema:
 *   trades        — one row per completed BUY/SELL pair
 *   signal_stats  — aggregated win rates by signal combination (updated live)
 *   param_history — history of parameter adjustments with their effects
 *   market_regimes — detected market regime snapshots
 */
class TradeDatabase(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME    = "lifecycle_trades.db"
        const val DB_VERSION = 4
    }

    override fun onCreate(db: SQLiteDatabase) {
        // ── Core trade log ────────────────────────────────────────────
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS trades (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                ts_entry        INTEGER NOT NULL,
                ts_exit         INTEGER NOT NULL,
                symbol          TEXT NOT NULL,
                mint            TEXT NOT NULL,
                mode            TEXT NOT NULL,   -- LAUNCH / RANGE
                dex             TEXT,
                
                -- Entry context
                entry_price     REAL,
                entry_score     REAL,
                entry_phase     TEXT,
                ema_fan         TEXT,             -- BULL_FAN | FLAT | BEAR_FAN etc
                vol_score       REAL,
                press_score     REAL,
                mom_score       REAL,
                stoch_signal    INTEGER,          -- 0|25|50|80
                vol_div         INTEGER,          -- 0/1
                mtf_5m          TEXT,             -- BULL|BEAR|NEUTRAL
                token_age_hours REAL,
                holder_count    INTEGER,
                holder_growth   REAL,
                liquidity_usd   REAL,
                mcap_usd        REAL,
                pullback_entry  INTEGER,          -- 0/1 was this a pullback entry
                
                -- Exit context
                exit_price      REAL,
                exit_score      REAL,
                exit_phase      TEXT,
                exit_reason     TEXT,
                held_mins       REAL,
                top_up_count    INTEGER,
                partial_sold    REAL,
                
                -- Outcome
                sol_in          REAL,
                sol_out         REAL,
                pnl_sol         REAL,
                pnl_pct         REAL,
                is_win          INTEGER,          -- 1/0
                
                -- Discovery
                source          TEXT,             -- PUMP_FUN_NEW | DEX_TRENDING etc
                
                -- Raw JSON for anything we add later
                extra_json      TEXT
            )
        """.trimIndent())

        // ── Signal statistics — running win rates ─────────────────────
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS signal_stats (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                feature_key     TEXT NOT NULL UNIQUE,  -- e.g. "ema_fan=BULL_FAN+phase=pumping"
                trades          INTEGER DEFAULT 0,
                wins            INTEGER DEFAULT 0,
                total_pnl_pct   REAL DEFAULT 0,
                avg_pnl_pct     REAL DEFAULT 0,
                win_rate        REAL DEFAULT 0,
                last_updated    INTEGER
            )
        """.trimIndent())

        // ── Parameter history ─────────────────────────────────────────
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS param_history (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                ts              INTEGER NOT NULL,
                param_name      TEXT NOT NULL,
                old_value       REAL,
                new_value       REAL,
                reason          TEXT,
                trades_sample   INTEGER,  -- how many trades this was based on
                win_rate_before REAL,
                win_rate_after  REAL      -- filled in after next evaluation
            )
        """.trimIndent())

        // ── Market regime log ─────────────────────────────────────────
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS market_regimes (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                ts              INTEGER NOT NULL,
                regime          TEXT NOT NULL,  -- BULL_TRENDING | BEAR | SIDEWAYS | HIGH_VOL
                sol_price       REAL,
                avg_vol_ratio   REAL,           -- average vol/liq ratio across watched tokens
                momentum_score  REAL,
                pump_rate       REAL            -- how many tokens pumped this hour
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trades_ts ON trades(ts_entry)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trades_win ON trades(is_win)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trades_phase ON trades(entry_phase)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_signal_key ON signal_stats(feature_key)")

        // ── Bad behaviour log ────────────────────────────────────────
        // Permanently records confirmed bad patterns so the bot never re-learns them.
        // Once a pattern is CONFIRMED_BAD (>= MIN_EVIDENCE trades, win rate <= SUPPRESS_THRESHOLD),
        // the brain suppresses it regardless of LLM suggestions.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS bad_behaviour (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                ts_first_seen   INTEGER NOT NULL,
                ts_confirmed    INTEGER,
                feature_key     TEXT NOT NULL UNIQUE,  -- e.g. "phase=dying+ema=BEAR_FAN"
                behaviour_type  TEXT NOT NULL,         -- ENTRY_SIGNAL | EXIT_TIMING | SOURCE | REGIME
                description     TEXT NOT NULL,         -- human-readable explanation
                trades_seen     INTEGER DEFAULT 0,     -- how many trades triggered this pattern
                loss_count      INTEGER DEFAULT 0,
                total_loss_pct  REAL DEFAULT 0,
                avg_loss_pct    REAL DEFAULT 0,
                worst_loss_pct  REAL DEFAULT 0,
                status          TEXT DEFAULT 'MONITORING', -- MONITORING | CONFIRMED_BAD | SUPPRESSED
                suppression_strength REAL DEFAULT 0.0,     -- how hard to penalise (0-100 pts)
                notes           TEXT                   -- brain's explanation of why this is bad
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bad_key ON bad_behaviour(feature_key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bad_status ON bad_behaviour(status)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            runCatching { db.execSQL("ALTER TABLE trades ADD COLUMN source TEXT") }
            runCatching { db.execSQL("ALTER TABLE trades ADD COLUMN extra_json TEXT") }
        }
        if (old < 3) {
            runCatching { db.execSQL("ALTER TABLE trades ADD COLUMN mtf_5m TEXT") }
            runCatching { db.execSQL("ALTER TABLE trades ADD COLUMN pullback_entry INTEGER DEFAULT 0") }
        }
        if (old < 4) {
            // Add bad_behaviour table for self-learning suppression
            runCatching { db.execSQL("""
                CREATE TABLE IF NOT EXISTS bad_behaviour (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts_first_seen INTEGER NOT NULL,
                    ts_confirmed INTEGER,
                    feature_key TEXT NOT NULL UNIQUE,
                    behaviour_type TEXT NOT NULL,
                    description TEXT NOT NULL,
                    trades_seen INTEGER DEFAULT 0,
                    loss_count INTEGER DEFAULT 0,
                    total_loss_pct REAL DEFAULT 0,
                    avg_loss_pct REAL DEFAULT 0,
                    worst_loss_pct REAL DEFAULT 0,
                    status TEXT DEFAULT 'MONITORING',
                    suppression_strength REAL DEFAULT 0.0,
                    notes TEXT
                )
            """.trimIndent())}
        }
    }

    // ── Write a completed trade ───────────────────────────────────────

    /**
     * Prune trades older than 90 days to keep DB responsive.
     * Keeps at least 1000 trades for BotBrain pattern analysis.
     * Called automatically after every insert.
     */
    private fun pruneOldTrades() {
        try {
            val cutoff = System.currentTimeMillis() - (90L * 24 * 60 * 60 * 1000)
            val count  = db.rawQuery("SELECT COUNT(*) FROM trades", null).use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }
            if (count > 1000) {
                db.execSQL("DELETE FROM trades WHERE ts_exit < $cutoff " +
                           "AND id NOT IN (SELECT id FROM trades ORDER BY id DESC LIMIT 1000)")
            }
        } catch (_: Exception) {}
    }

    fun insertTrade(t: TradeRecord): Long {
        val cv = ContentValues().apply {
            put("ts_entry",       t.tsEntry)
            put("ts_exit",        t.tsExit)
            put("symbol",         t.symbol)
            put("mint",           t.mint)
            put("mode",           t.mode)
            put("dex",            t.dex)
            put("entry_price",    t.entryPrice)
            put("entry_score",    t.entryScore)
            put("entry_phase",    t.entryPhase)
            put("ema_fan",        t.emaFan)
            put("vol_score",      t.volScore)
            put("press_score",    t.pressScore)
            put("mom_score",      t.momScore)
            put("stoch_signal",   t.stochSignal)
            put("vol_div",        if (t.volDiv) 1 else 0)
            put("mtf_5m",         t.mtf5m)
            put("token_age_hours",t.tokenAgeHours)
            put("holder_count",   t.holderCount)
            put("holder_growth",  t.holderGrowth)
            put("liquidity_usd",  t.liquidityUsd)
            put("mcap_usd",       t.mcapUsd)
            put("pullback_entry", if (t.pullbackEntry) 1 else 0)
            put("exit_price",     t.exitPrice)
            put("exit_score",     t.exitScore)
            put("exit_phase",     t.exitPhase)
            put("exit_reason",    t.exitReason)
            put("held_mins",      t.heldMins)
            put("top_up_count",   t.topUpCount)
            put("partial_sold",   t.partialSold)
            put("sol_in",         t.solIn)
            put("sol_out",        t.solOut)
            put("pnl_sol",        t.pnlSol)
            put("pnl_pct",        t.pnlPct)
            put("is_win",         if (t.isWin) 1 else 0)
            put("source",         t.source)
            put("extra_json",     t.extraJson)
        }
        val id = writableDatabase.insertWithOnConflict(
            "trades", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        updateSignalStats(t)
        pruneOldTrades()   // keep DB size bounded
        pruneOldTrades()
        return id
    }

    // ── Signal stats aggregation ──────────────────────────────────────

    private fun updateSignalStats(t: TradeRecord) {
        val keys = listOf(
            "ema_fan=${t.emaFan}",
            "phase=${t.entryPhase}",
            "mode=${t.mode}",
            "ema_fan=${t.emaFan}+phase=${t.entryPhase}",
            "phase=${t.entryPhase}+mode=${t.mode}",
            "mtf_5m=${t.mtf5m}",
            "ema_fan=${t.emaFan}+mtf_5m=${t.mtf5m}",
            "source=${t.source}",
        )
        val db = writableDatabase
        val win = if (t.isWin) 1 else 0
        val now = System.currentTimeMillis()
        keys.forEach { key ->
            db.execSQL("""
                INSERT INTO signal_stats (feature_key, trades, wins, total_pnl_pct, avg_pnl_pct, win_rate, last_updated)
                VALUES (?, 1, ?, ?, ?, ?, ?)
                ON CONFLICT(feature_key) DO UPDATE SET
                    trades        = trades + 1,
                    wins          = wins + excluded.wins,
                    total_pnl_pct = total_pnl_pct + excluded.total_pnl_pct,
                    avg_pnl_pct   = (total_pnl_pct + excluded.total_pnl_pct) / (trades + 1),
                    win_rate      = CAST(wins + excluded.wins AS REAL) / (trades + 1) * 100,
                    last_updated  = excluded.last_updated
            """, arrayOf(key, win, t.pnlPct, t.pnlPct, if(t.isWin) 100.0 else 0.0, now))
        }
    }

    // ── Queries for the learning engine ──────────────────────────────

    /** Recent N trades for pattern analysis */
    fun getRecentTrades(limit: Int = 200): List<TradeRecord> {
        val result = mutableListOf<TradeRecord>()
        val c = readableDatabase.rawQuery(
            "SELECT * FROM trades ORDER BY ts_entry DESC LIMIT ?",
            arrayOf(limit.toString()))
        c.use { while (it.moveToNext()) result.add(cursorToRecord(it)) }
        return result
    }

    /** Win rate for a specific signal combination */
    fun getSignalWinRate(featureKey: String): SignalStat? {
        val c = readableDatabase.rawQuery(
            "SELECT * FROM signal_stats WHERE feature_key = ?",
            arrayOf(featureKey))
        return c.use { if (it.moveToFirst()) SignalStat(
            featureKey  = it.getString(it.getColumnIndexOrThrow("feature_key")),
            trades      = it.getInt(it.getColumnIndexOrThrow("trades")),
            wins        = it.getInt(it.getColumnIndexOrThrow("wins")),
            winRate     = it.getDouble(it.getColumnIndexOrThrow("win_rate")),
            avgPnlPct   = it.getDouble(it.getColumnIndexOrThrow("avg_pnl_pct")),
        ) else null }
    }

    /** Top performing signal combinations */
    fun getTopSignals(minTrades: Int = 5, limit: Int = 20): List<SignalStat> {
        val result = mutableListOf<SignalStat>()
        val c = readableDatabase.rawQuery("""
            SELECT * FROM signal_stats
            WHERE trades >= ?
            ORDER BY win_rate DESC, avg_pnl_pct DESC
            LIMIT ?
        """.trimIndent(), arrayOf(minTrades.toString(), limit.toString()))
        c.use { while (it.moveToNext()) result.add(SignalStat(
            featureKey = it.getString(it.getColumnIndexOrThrow("feature_key")),
            trades     = it.getInt(it.getColumnIndexOrThrow("trades")),
            wins       = it.getInt(it.getColumnIndexOrThrow("wins")),
            winRate    = it.getDouble(it.getColumnIndexOrThrow("win_rate")),
            avgPnlPct  = it.getDouble(it.getColumnIndexOrThrow("avg_pnl_pct")),
        ))}
        return result
    }

    /** Get trades by phase for phase-specific analysis */
    fun getTradesByPhase(phase: String): List<TradeRecord> {
        val result = mutableListOf<TradeRecord>()
        val c = readableDatabase.rawQuery(
            "SELECT * FROM trades WHERE entry_phase = ? ORDER BY ts_entry DESC",
            arrayOf(phase))
        c.use { while (it.moveToNext()) result.add(cursorToRecord(it)) }
        return result
    }

    fun getTotalTrades() = readableDatabase
        .rawQuery("SELECT COUNT(*) FROM trades", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun recordParamChange(name: String, old: Double, new: Double, reason: String,
                          sampleSize: Int, winRateBefore: Double) {
        writableDatabase.execSQL("""
            INSERT INTO param_history (ts, param_name, old_value, new_value, reason, trades_sample, win_rate_before)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """, arrayOf(System.currentTimeMillis(), name, old, new, reason, sampleSize, winRateBefore))
    }

    // ── Bad behaviour API ─────────────────────────────────────────────

    companion object {
        const val MIN_EVIDENCE        = 8     // trades before pattern can be CONFIRMED_BAD
        const val SUPPRESS_THRESHOLD  = 38.0  // win rate at or below this = bad pattern
        const val HARD_SUPPRESS_THRESHOLD = 25.0  // this bad = SUPPRESSED (hard block)
    }

    /** Record a loss against a feature key. Called after every losing trade. */
    fun recordBadObservation(featureKey: String, behaviourType: String,
                             description: String, lossPct: Double) {
        val now = System.currentTimeMillis()
        writableDatabase.execSQL("""
            INSERT INTO bad_behaviour
                (ts_first_seen, feature_key, behaviour_type, description,
                 trades_seen, loss_count, total_loss_pct, avg_loss_pct, worst_loss_pct, status)
            VALUES (?, ?, ?, ?, 1, 1, ?, ?, ?, 'MONITORING')
            ON CONFLICT(feature_key) DO UPDATE SET
                trades_seen    = trades_seen + 1,
                loss_count     = loss_count + 1,
                total_loss_pct = total_loss_pct + excluded.total_loss_pct,
                avg_loss_pct   = (total_loss_pct + excluded.total_loss_pct) / (trades_seen + 1),
                worst_loss_pct = MIN(worst_loss_pct, excluded.worst_loss_pct)
        """, arrayOf(now, featureKey, behaviourType, description, lossPct, lossPct, lossPct))
    }

    /** Record a win observation — reduces suppression if pattern recovers */
    fun recordGoodObservation(featureKey: String) {
        writableDatabase.execSQL("""
            UPDATE bad_behaviour SET trades_seen = trades_seen + 1
            WHERE feature_key = ?
        """, arrayOf(featureKey))
    }

    /** Promote patterns from MONITORING to CONFIRMED_BAD or SUPPRESSED based on evidence */
    fun evaluateBadPatterns(): List<BadBehaviourEntry> {
        val now = System.currentTimeMillis()
        val confirmed = mutableListOf<BadBehaviourEntry>()

        val c = readableDatabase.rawQuery(
            "SELECT * FROM bad_behaviour WHERE status != 'CLEARED'", null)
        c.use {
            while (it.moveToNext()) {
                val id        = it.getLong(it.getColumnIndexOrThrow("id"))
                val key       = it.getString(it.getColumnIndexOrThrow("feature_key")) ?: continue
                val seen      = it.getInt(it.getColumnIndexOrThrow("trades_seen"))
                val losses    = it.getInt(it.getColumnIndexOrThrow("loss_count"))
                val avgLoss   = it.getDouble(it.getColumnIndexOrThrow("avg_loss_pct"))
                val worstLoss = it.getDouble(it.getColumnIndexOrThrow("worst_loss_pct"))
                val status    = it.getString(it.getColumnIndexOrThrow("status")) ?: "MONITORING"

                if (seen < MIN_EVIDENCE) continue
                val winRate = (1.0 - losses.toDouble() / seen) * 100.0

                val (newStatus, strength) = when {
                    winRate <= HARD_SUPPRESS_THRESHOLD -> "SUPPRESSED" to 80.0   // hard block
                    winRate <= SUPPRESS_THRESHOLD      -> "CONFIRMED_BAD" to 45.0 // strong penalty
                    winRate <= 50.0                    -> "MONITORING" to 20.0   // soft penalty
                    else                               -> "MONITORING" to 0.0    // recovering
                }

                if (newStatus != status || strength > 0) {
                    val notes = "WR=${winRate.toInt()}% over $seen trades | " +
                                "avgLoss=${avgLoss.toInt()}% | worst=${worstLoss.toInt()}%"
                    writableDatabase.execSQL("""
                        UPDATE bad_behaviour SET
                            status = ?, suppression_strength = ?,
                            ts_confirmed = ?, notes = ?
                        WHERE id = ?
                    """, arrayOf(newStatus, strength, now, notes, id))

                    if (newStatus in listOf("CONFIRMED_BAD","SUPPRESSED")) {
                        confirmed.add(BadBehaviourEntry(key, newStatus, strength, notes,
                            it.getString(it.getColumnIndexOrThrow("behaviour_type")) ?: "",
                            it.getString(it.getColumnIndexOrThrow("description")) ?: ""))
                    }
                }
            }
        }
        return confirmed
    }

    /** Get suppression strength for a feature key (0 = no suppression).
     *
     * Returns penalty for ALL statuses that have strength > 0:
     *   MONITORING + 20pts = soft warning — mild entry penalty applies
     *   CONFIRMED_BAD + 45pts = strong block
     *   SUPPRESSED + 80pts = near-hard block
     *   MONITORING + 0pts = watch only, no penalty yet
     *   CLEARED = no penalty (human override)
     */
    fun getSuppressionStrength(featureKey: String): Double {
        val c = readableDatabase.rawQuery(
            "SELECT suppression_strength, status FROM bad_behaviour WHERE feature_key = ?",
            arrayOf(featureKey))
        return c.use {
            if (it.moveToFirst() && it.getString(1) != "CLEARED")
                it.getDouble(0)   // returns 0.0 for MONITORING with no evidence yet
            else 0.0
        }
    }

    /** Get all active bad patterns for display */
    fun getBadPatterns(statusFilter: String? = null): List<BadBehaviourEntry> {
        val result = mutableListOf<BadBehaviourEntry>()
        val query = if (statusFilter != null)
            "SELECT * FROM bad_behaviour WHERE status = ? ORDER BY suppression_strength DESC"
        else
            "SELECT * FROM bad_behaviour WHERE status != 'CLEARED' ORDER BY suppression_strength DESC"
        val args = if (statusFilter != null) arrayOf(statusFilter) else null
        val c = readableDatabase.rawQuery(query, args)
        c.use {
            while (it.moveToNext()) result.add(BadBehaviourEntry(
                featureKey          = it.getString(it.getColumnIndexOrThrow("feature_key")) ?: "",
                status              = it.getString(it.getColumnIndexOrThrow("status")) ?: "",
                suppressionStrength = it.getDouble(it.getColumnIndexOrThrow("suppression_strength")),
                notes               = it.getString(it.getColumnIndexOrThrow("notes")) ?: "",
                behaviourType       = it.getString(it.getColumnIndexOrThrow("behaviour_type")) ?: "",
                description         = it.getString(it.getColumnIndexOrThrow("description")) ?: "",
            ))
        }
        return result
    }

    /** Manually clear a suppressed pattern (human override) */
    fun clearBadPattern(featureKey: String, reason: String) {
        writableDatabase.execSQL(
            "UPDATE bad_behaviour SET status = 'CLEARED', notes = ? WHERE feature_key = ?",
            arrayOf("CLEARED by human: $reason", featureKey))
    }

    private fun cursorToRecord(c: android.database.Cursor) = TradeRecord(
        id           = c.getLong(c.getColumnIndexOrThrow("id")),
        tsEntry      = c.getLong(c.getColumnIndexOrThrow("ts_entry")),
        tsExit       = c.getLong(c.getColumnIndexOrThrow("ts_exit")),
        symbol       = c.getString(c.getColumnIndexOrThrow("symbol")) ?: "",
        mint         = c.getString(c.getColumnIndexOrThrow("mint")) ?: "",
        mode         = c.getString(c.getColumnIndexOrThrow("mode")) ?: "",
        dex          = c.getString(c.getColumnIndexOrThrow("dex")) ?: "",
        entryPrice   = c.getDouble(c.getColumnIndexOrThrow("entry_price")),
        entryScore   = c.getDouble(c.getColumnIndexOrThrow("entry_score")),
        entryPhase   = c.getString(c.getColumnIndexOrThrow("entry_phase")) ?: "",
        emaFan       = c.getString(c.getColumnIndexOrThrow("ema_fan")) ?: "",
        volScore     = c.getDouble(c.getColumnIndexOrThrow("vol_score")),
        pressScore   = c.getDouble(c.getColumnIndexOrThrow("press_score")),
        momScore     = c.getDouble(c.getColumnIndexOrThrow("mom_score")),
        stochSignal  = c.getInt(c.getColumnIndexOrThrow("stoch_signal")),
        volDiv       = c.getInt(c.getColumnIndexOrThrow("vol_div")) == 1,
        mtf5m        = c.getString(c.getColumnIndexOrThrow("mtf_5m")) ?: "NEUTRAL",
        tokenAgeHours= c.getDouble(c.getColumnIndexOrThrow("token_age_hours")),
        holderCount  = c.getInt(c.getColumnIndexOrThrow("holder_count")),
        holderGrowth = c.getDouble(c.getColumnIndexOrThrow("holder_growth")),
        liquidityUsd = c.getDouble(c.getColumnIndexOrThrow("liquidity_usd")),
        mcapUsd      = c.getDouble(c.getColumnIndexOrThrow("mcap_usd")),
        pullbackEntry= c.getInt(c.getColumnIndexOrThrow("pullback_entry")) == 1,
        exitPrice    = c.getDouble(c.getColumnIndexOrThrow("exit_price")),
        exitScore    = c.getDouble(c.getColumnIndexOrThrow("exit_score")),
        exitPhase    = c.getString(c.getColumnIndexOrThrow("exit_phase")) ?: "",
        exitReason   = c.getString(c.getColumnIndexOrThrow("exit_reason")) ?: "",
        heldMins     = c.getDouble(c.getColumnIndexOrThrow("held_mins")),
        topUpCount   = c.getInt(c.getColumnIndexOrThrow("top_up_count")),
        partialSold  = c.getDouble(c.getColumnIndexOrThrow("partial_sold")),
        solIn        = c.getDouble(c.getColumnIndexOrThrow("sol_in")),
        solOut       = c.getDouble(c.getColumnIndexOrThrow("sol_out")),
        pnlSol       = c.getDouble(c.getColumnIndexOrThrow("pnl_sol")),
        pnlPct       = c.getDouble(c.getColumnIndexOrThrow("pnl_pct")),
        isWin        = c.getInt(c.getColumnIndexOrThrow("is_win")) == 1,
        source       = c.getString(c.getColumnIndexOrThrow("source")) ?: "",
        extraJson    = c.getString(c.getColumnIndexOrThrow("extra_json")) ?: "",
    )
}

// ── Data classes ──────────────────────────────────────────────────────────────

data class TradeRecord(
    val id: Long = 0,
    val tsEntry: Long = 0,
    val tsExit: Long = 0,
    val symbol: String = "",
    val mint: String = "",
    val mode: String = "",
    val dex: String = "",
    val entryPrice: Double = 0.0,
    val entryScore: Double = 0.0,
    val entryPhase: String = "",
    val emaFan: String = "",
    val volScore: Double = 0.0,
    val pressScore: Double = 0.0,
    val momScore: Double = 0.0,
    val stochSignal: Int = 0,
    val volDiv: Boolean = false,
    val mtf5m: String = "NEUTRAL",
    val tokenAgeHours: Double = 0.0,
    val holderCount: Int = 0,
    val holderGrowth: Double = 0.0,
    val liquidityUsd: Double = 0.0,
    val mcapUsd: Double = 0.0,
    val pullbackEntry: Boolean = false,
    val exitPrice: Double = 0.0,
    val exitScore: Double = 0.0,
    val exitPhase: String = "",
    val exitReason: String = "",
    val heldMins: Double = 0.0,
    val topUpCount: Int = 0,
    val partialSold: Double = 0.0,
    val solIn: Double = 0.0,
    val solOut: Double = 0.0,
    val pnlSol: Double = 0.0,
    val pnlPct: Double = 0.0,
    val isWin: Boolean = false,
    val source: String = "",
    val extraJson: String = "",
)

data class SignalStat(
    val featureKey: String,
    val trades: Int,
    val wins: Int,
    val winRate: Double,
    val avgPnlPct: Double,
)

data class BadBehaviourEntry(
    val featureKey: String,
    val status: String,             // MONITORING | CONFIRMED_BAD | SUPPRESSED | CLEARED
    val suppressionStrength: Double,// 0-100 — how many points to subtract from entry score
    val notes: String,              // brain's analysis of why this is bad
    val behaviourType: String,      // ENTRY_SIGNAL | EXIT_TIMING | SOURCE | REGIME
    val description: String,        // human-readable pattern description
)
