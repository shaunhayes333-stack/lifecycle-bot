package com.lifecyclebot.engine

import android.content.Context
import android.content.ContentValues
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.lifecyclebot.data.Trade
import org.json.JSONArray
import org.json.JSONObject

/**
 * TradeHistoryStore
 *
 * Persists trade history across bot restarts so that:
 * - 24h Trades stat persists
 * - Win Rate stat persists
 * - Open positions stat persists
 * - AI Conf stat persists
 *
 * Journal data now persists indefinitely until manually cleared by user.
 *
 * V5.9.250: Migrated from SharedPreferences JSON blob → SQLite.
 * SharedPreferences was crashing the Journal screen ("cache issue") because
 * the entire trade list was serialised into a single JSON string and written
 * via Binder IPC.  At ~991+ trades (~200 bytes each) this regularly exceeded
 * Android's 1 MB Binder transaction limit, producing a
 * TransactionTooLargeException / SharedPreferences corruption that showed
 * as "cache issue" on Journal open.  SQLite stores each trade as an
 * individual row and is safe for arbitrarily large trade histories.
 *
 * Win/loss classification:
 * - WIN    = pnlPct >= 1.0  (V5.9.218 unified threshold)
 * - LOSS   = pnlPct <= -0.5 (V5.9.204)
 * - SCRATCH = between those thresholds
 */
object TradeHistoryStore {

    // ── Legacy SharedPreferences keys (used only for one-time migration) ──
    private const val PREFS_NAME          = "trade_history_store"
    private const val KEY_TRADES          = "trades_json"
    // V5.9.330: Cap in-memory list to prevent OOM when Journal builds Views.
    // SQLite is the source of truth — older trades are always on disk.
    // Journal shows the most recent PAGE_SIZE trades via takeLast().
    private const val MAX_IN_MEMORY_TRADES = 2000
    private const val KEY_LIFETIME_STATS  = "lifetime_stats_json"

    private const val WIN_THRESHOLD_PCT   = 1.0   // V5.9.218
    private const val LOSS_THRESHOLD_PCT  = -0.5  // V5.9.204

    // In-memory cache — loaded once at init, mutated synchronously under `lock`
    private val lock   = Any()
    private val trades = mutableListOf<Trade>()

    // V5.9.115: Persistent lifetime totals. NEVER cleared by clearAllTrades().
    @Volatile private var lifetimeSells:         Int    = 0
    @Volatile private var lifetimeWins:          Int    = 0
    @Volatile private var lifetimeLosses:        Int    = 0
    @Volatile private var lifetimeScratches:     Int    = 0
    @Volatile private var lifetimeWinPnlSum:     Double = 0.0
    @Volatile private var lifetimeRealizedPnlSol:Double = 0.0

    // V5.9.43: Cached proven-edge flag
    @Volatile private var cachedHasProvenEdge:   Boolean = false
    @Volatile private var cachedProvenWinRate:   Double  = 0.0
    @Volatile private var cachedProvenTradeCount:Int     = 0
    @Volatile private var lastStatsCacheMs:      Long    = 0L
    private const val STATS_CACHE_MS = 30_000L

    // SQLite
    private var db:        SQLiteDatabase? = null
    private var ioThread:  HandlerThread?  = null
    private var ioHandler: Handler?        = null

    // SharedPreferences (kept alive only for one-time migration)
    private var prefs: SharedPreferences? = null

    // ── SQLiteOpenHelper ─────────────────────────────────────────────

    private class TradeDbHelper(ctx: Context) :
        SQLiteOpenHelper(ctx, "trade_history.db", null, DB_VERSION) {

        companion object {
            const val DB_VERSION = 1
            const val TABLE = "trades"
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts            INTEGER NOT NULL,
                    side          TEXT    NOT NULL,
                    sol           REAL    NOT NULL DEFAULT 0,
                    price         REAL    NOT NULL DEFAULT 0,
                    pnl_sol       REAL    NOT NULL DEFAULT 0,
                    pnl_pct       REAL    NOT NULL DEFAULT 0,
                    reason        TEXT    NOT NULL DEFAULT '',
                    score         REAL    NOT NULL DEFAULT 0,
                    mode          TEXT    NOT NULL DEFAULT 'paper',
                    mint          TEXT    NOT NULL DEFAULT '',
                    trading_mode  TEXT    NOT NULL DEFAULT '',
                    trading_emoji TEXT    NOT NULL DEFAULT '',
                    fee_sol       REAL    NOT NULL DEFAULT 0,
                    net_pnl_sol   REAL    NOT NULL DEFAULT 0,
                    dedup_key     TEXT    UNIQUE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ts ON $TABLE(ts)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_side ON $TABLE(side)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Future migrations go here
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    data class ProvenEdgeSnapshot(
        val hasProvenEdge:   Boolean,
        val winRate:         Double,
        val meaningfulTrades:Int,
    )

    fun getProvenEdgeCached(): ProvenEdgeSnapshot {
        val now = System.currentTimeMillis()
        if (now - lastStatsCacheMs > STATS_CACHE_MS) {
            try {
                val s = getStats()
                val meaningful = s.totalWins + s.totalLosses
                cachedProvenTradeCount = meaningful
                cachedProvenWinRate    = s.winRate
                cachedHasProvenEdge    = meaningful >= 300 && s.winRate >= 50.0
                lastStatsCacheMs       = now
            } catch (_: Exception) {}
        }
        return ProvenEdgeSnapshot(cachedHasProvenEdge, cachedProvenWinRate, cachedProvenTradeCount)
    }

    fun init(ctx: Context) {
        // Start background IO thread
        if (ioThread == null) {
            val t = HandlerThread("TradeHistoryIO", Process.THREAD_PRIORITY_BACKGROUND)
            t.start()
            ioThread  = t
            ioHandler = Handler(t.looper)
        }

        // Open SQLite
        val helper = TradeDbHelper(ctx)
        db = helper.writableDatabase

        // Load in-memory list from SQLite
        loadTradesFromDb()

        // One-time migration from SharedPreferences if needed
        prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        migrateFromPrefsIfNeeded(ctx)

        // Load lifetime stats (still stored in SharedPreferences — small payload, safe)
        loadLifetimeStats()

        if (lifetimeSells == 0 && synchronized(lock) { trades.any { it.side == "SELL" } }) {
            backfillLifetimeFromTrades()
        }

        // V5.9.330: Trim in-memory list to most recent MAX_IN_MEMORY_TRADES after load.
        // SQLite retains the full history — Journal pages from it on export.
        synchronized(lock) {
            if (trades.size > MAX_IN_MEMORY_TRADES) {
                trades.subList(0, trades.size - MAX_IN_MEMORY_TRADES).clear()
            }
        }

        ErrorLogger.info("TradeHistoryStore",
            "📊 Loaded ${synchronized(lock) { trades.size }} trades in-memory (SQLite retains all, lifetime sells=$lifetimeSells)")
    }

    // ── Public API ───────────────────────────────────────────────────

    fun recordTrade(trade: Trade) {
        synchronized(lock) {
            trades.add(trade)
            // V5.9.330: Trim in-memory list to avoid OOM. SQLite retains everything.
            if (trades.size > MAX_IN_MEMORY_TRADES) {
                trades.subList(0, trades.size - MAX_IN_MEMORY_TRADES).clear()
            }
        }
        bumpLifetimeFor(trade)
        insertTradeAsync(trade)
    }

    fun recordTrades(newTrades: List<Trade>) {
        val existingKeys = synchronized(lock) {
            trades.map { "${it.ts}_${it.mint}" }.toSet()
        }
        val toAdd = newTrades.filter { "${it.ts}_${it.mint}" !in existingKeys }
        if (toAdd.isNotEmpty()) {
            synchronized(lock) { trades.addAll(toAdd) }
            toAdd.forEach { bumpLifetimeFor(it) }
            toAdd.forEach { insertTradeAsync(it) }
            ErrorLogger.debug("TradeHistoryStore", "💾 Added ${toAdd.size} new trades")
        }
    }

    fun getAllTrades(): List<Trade> = synchronized(lock) { trades.toList() }

    /** MANUAL CLEAR — only clears visual journal rows; lifetime counters preserved. */
    fun clearAllTrades() {
        synchronized(lock) { trades.clear() }
        ioHandler?.post {
            try {
                db?.delete(TradeDbHelper.TABLE, null, null)
            } catch (e: Exception) {
                ErrorLogger.error("TradeHistoryStore", "SQLite clear failed: ${e.message}")
            }
        }
        ErrorLogger.info("TradeHistoryStore",
            "🗑️ MANUAL CLEAR: Journal rows cleared (lifetime=${lifetimeSells} sells preserved — learned AI intact)")
    }

    /** FULL RESET — wipe journal AND lifetime counters. Only from BehaviorActivity / FluidLearningAI. */
    fun fullResetIncludingLifetime() {
        synchronized(lock) { trades.clear() }
        lifetimeSells          = 0
        lifetimeWins           = 0
        lifetimeLosses         = 0
        lifetimeScratches      = 0
        lifetimeWinPnlSum      = 0.0
        lifetimeRealizedPnlSol = 0.0
        ioHandler?.post {
            try { db?.delete(TradeDbHelper.TABLE, null, null) } catch (_: Exception) {}
        }
        saveLifetimeStats()
        ErrorLogger.warn("TradeHistoryStore", "🔄 FULL RESET: Trades + lifetime counters wiped (learned AI reset)")
    }

    fun getTotalTradeCount(): Int = synchronized(lock) { trades.size }

    fun recordPartialProfit(mint: String, profitSol: Double, pnlPct: Double) {
        val partialTrade = Trade(
            side  = "PARTIAL_SELL",
            mode  = "paper",
            sol   = profitSol,
            price = 0.0,
            ts    = System.currentTimeMillis(),
            pnlSol= profitSol,
            pnlPct= pnlPct,
            reason= "CHUNK_SELL",
            mint  = mint,
        )
        synchronized(lock) { trades.add(partialTrade) }
        lifetimeRealizedPnlSol += profitSol
        saveLifetimeStats()
        insertTradeAsync(partialTrade)
        ErrorLogger.debug("TradeHistoryStore", "📊 PARTIAL PROFIT: ${profitSol.fmt(4)} SOL @ ${pnlPct.toInt()}%")
    }

    fun recordTradeForML(
        trade: Trade,
        candlesAtEntry: List<com.lifecyclebot.data.Candle>,
        candlesAtExit: List<com.lifecyclebot.data.Candle>,
        liquidityAtEntry: Double,
        liquidityAtExit: Double,
        holdersAtEntry: Int,
        holdersAtExit: Int,
        rugcheckScore: Int,
        mintRevoked: Boolean,
        freezeRevoked: Boolean,
        topHolderPct: Double,
        rsi: Double,
        emaAlignment: String,
        wasRug: Boolean,
    ) {
        try {
            com.lifecyclebot.ml.OnDeviceMLEngine.recordTrade(
                trade              = trade,
                candlesAtEntry     = candlesAtEntry,
                candlesAtExit      = candlesAtExit,
                liquidityAtEntry   = liquidityAtEntry,
                liquidityAtExit    = liquidityAtExit,
                holdersAtEntry     = holdersAtEntry,
                holdersAtExit      = holdersAtExit,
                rugcheckScore      = rugcheckScore,
                mintRevoked        = mintRevoked,
                freezeRevoked      = freezeRevoked,
                topHolderPct       = topHolderPct,
                rsi                = rsi,
                emaAlignment       = emaAlignment,
                wasRug             = wasRug,
            )
            ErrorLogger.debug("TradeHistoryStore",
                "🧠 ML TRAINING: Recorded trade for ${trade.mint.take(8)}... | pnl=${trade.pnlPct.toInt()}%")
        } catch (e: Exception) {
            ErrorLogger.debug("TradeHistoryStore", "ML record error: ${e.message}")
        }
    }

    // ── Time-windowed queries ────────────────────────────────────────

    fun getTrades24h(): List<Trade> {
        val midnight = midnightTs()
        return synchronized(lock) { trades.filter { it.ts >= midnight } }
    }

    fun getSells24h(): List<Trade> {
        val midnight = midnightTs()
        return synchronized(lock) { trades.filter { it.side == "SELL" && it.ts >= midnight } }
    }

    fun getAllSells(): List<Trade> =
        synchronized(lock) { trades.filter { it.side == "SELL" }.toList() }

    fun getWinRate24h(): Int {
        val sells = getSells24h()
        if (sells.isEmpty()) return 0
        val wins   = sells.count { isWin(it) }
        val losses = sells.count { isLoss(it) }
        val decisive = wins + losses
        return if (decisive > 0) ((wins.toDouble() * 100.0) / decisive).toInt() else 0
    }

    fun getTradeCount24h(): Int = getTrades24h().size

    fun getPnl24hSol(): Double = getSells24h().sumOf { it.pnlSol }

    // ── Stats ────────────────────────────────────────────────────────

    data class LifetimeSnapshot(
        val totalSells:     Int,
        val totalWins:      Int,
        val totalLosses:    Int,
        val totalScratches: Int,
        val winRate:        Double,
        val avgWinPct:      Double,
        val realizedPnlSol: Double,
    )

    fun getLifetimeStats(): LifetimeSnapshot {
        val decisive = lifetimeWins + lifetimeLosses
        val winRate  = if (decisive > 0) lifetimeWins * 100.0 / decisive else 50.0
        val avgWin   = if (lifetimeWins > 0) lifetimeWinPnlSum / lifetimeWins else 10.0
        return LifetimeSnapshot(
            totalSells     = lifetimeSells,
            totalWins      = lifetimeWins,
            totalLosses    = lifetimeLosses,
            totalScratches = lifetimeScratches,
            winRate        = winRate,
            avgWinPct      = avgWin,
            realizedPnlSol = lifetimeRealizedPnlSol,
        )
    }

    data class StatsSnapshot(
        val trades24h:          Int,
        val winRate24h:         Int,
        val pnl24hSol:          Double,
        val totalStoredTrades:  Int,
        val totalTrades:        Int    = 0,
        val winRate:            Double = 50.0,
        val avgWinPct:          Double = 10.0,
        val avgLossPct:         Double = -5.0,
        val profitFactor:       Double = 1.0,
        val totalPnlSol:        Double = 0.0,
        val avgHoldTimeMinutes: Int    = 10,
        val totalWins:          Int    = 0,
        val totalLosses:        Int    = 0,
        val totalScratches:     Int    = 0,
        val wins24h:            Int    = 0,
        val losses24h:          Int    = 0,
        val scratches24h:       Int    = 0,
    )

    fun getStats(): StatsSnapshot {
        val sells24h      = getSells24h()
        val wins24h       = sells24h.count { isWin(it) }
        val losses24h     = sells24h.count { isLoss(it) }
        val decisive24h   = wins24h + losses24h
        val scratches24h  = sells24h.size - decisive24h

        val allSells      = synchronized(lock) { trades.filter { it.side == "SELL" } }
        val totalWins     = allSells.count { isWin(it) }
        val totalLosses   = allSells.count { isLoss(it) }
        val totalCompleted= totalWins + totalLosses
        val totalScratches= allSells.size - totalCompleted

        val lifetimeWR    = if (totalCompleted > 0)
            totalWins * 100.0 / totalCompleted.toDouble() else 50.0

        val winRate24h    = if (decisive24h > 0)
            ((wins24h.toDouble() * 100.0) / decisive24h).toInt() else 0

        val winningTrades = allSells.filter { isWin(it) }
        val losingTrades  = allSells.filter { isLoss(it) }
        val avgWinPct     = if (winningTrades.isNotEmpty()) winningTrades.map { it.pnlPct }.average() else 10.0
        val avgLossPct    = if (losingTrades.isNotEmpty())  losingTrades.map  { it.pnlPct }.average() else -5.0
        val profitFactor  = when {
            losingTrades.isNotEmpty() && avgLossPct < 0.0 && winningTrades.isNotEmpty() ->
                (avgWinPct * totalWins) / (Math.abs(avgLossPct) * totalLosses)
            winningTrades.isNotEmpty() -> 2.0
            else -> 0.0
        }
        val totalPnlSol   = allSells.sumOf { it.pnlSol }

        return StatsSnapshot(
            trades24h          = getTrades24h().size,
            winRate24h         = winRate24h,
            pnl24hSol          = sells24h.sumOf { it.pnlSol },
            totalStoredTrades  = synchronized(lock) { trades.size },
            totalTrades        = totalCompleted,
            winRate            = lifetimeWR,
            avgWinPct          = avgWinPct,
            avgLossPct         = avgLossPct,
            profitFactor       = profitFactor,
            totalPnlSol        = totalPnlSol,
            avgHoldTimeMinutes = 10,
            totalWins          = totalWins,
            totalLosses        = totalLosses,
            totalScratches     = totalScratches,
            wins24h            = wins24h,
            losses24h          = losses24h,
            scratches24h       = scratches24h,
        )
    }

    fun getTopMode(): String? {
        val t = synchronized(lock) { trades.toList() }
        if (t.isEmpty()) return null
        return t.filter { it.tradingMode.isNotBlank() }
            .groupBy { it.tradingMode }
            .maxByOrNull { it.value.size }?.key
    }

    // ── SQLite persistence ───────────────────────────────────────────

    /** Insert a single trade row asynchronously (off main thread). */
    private fun insertTradeAsync(trade: Trade) {
        ioHandler?.post {
            try {
                val cv = tradeToContentValues(trade)
                db?.insertWithOnConflict(
                    TradeDbHelper.TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            } catch (e: Exception) {
                ErrorLogger.error("TradeHistoryStore", "SQLite insert failed: ${e.message}")
            }
        }
    }

    private fun tradeToContentValues(t: Trade): ContentValues = ContentValues().apply {
        put("ts",            t.ts)
        put("side",          t.side)
        put("sol",           t.sol)
        put("price",         t.price)
        put("pnl_sol",       t.pnlSol)
        put("pnl_pct",       t.pnlPct)
        put("reason",        t.reason)
        put("score",         t.score)
        put("mode",          t.mode)
        put("mint",          t.mint)
        put("trading_mode",  t.tradingMode)
        put("trading_emoji", t.tradingModeEmoji)
        put("fee_sol",       t.feeSol)
        put("net_pnl_sol",   t.netPnlSol)
        put("dedup_key",     "${t.ts}_${t.mint}_${t.side}")
    }

    private fun loadTradesFromDb() {
        val database = db ?: return
        val loaded   = mutableListOf<Trade>()
        try {
            val cursor = database.query(
                TradeDbHelper.TABLE,
                null, null, null, null, null, "ts ASC"
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    loaded.add(Trade(
                        ts             = c.getLong(c.getColumnIndexOrThrow("ts")),
                        side           = c.getString(c.getColumnIndexOrThrow("side")),
                        sol            = c.getDouble(c.getColumnIndexOrThrow("sol")),
                        price          = c.getDouble(c.getColumnIndexOrThrow("price")),
                        pnlSol         = c.getDouble(c.getColumnIndexOrThrow("pnl_sol")),
                        pnlPct         = c.getDouble(c.getColumnIndexOrThrow("pnl_pct")),
                        reason         = c.getString(c.getColumnIndexOrThrow("reason")),
                        score          = c.getDouble(c.getColumnIndexOrThrow("score")),
                        mode           = c.getString(c.getColumnIndexOrThrow("mode")),
                        mint           = c.getString(c.getColumnIndexOrThrow("mint")),
                        tradingMode    = c.getString(c.getColumnIndexOrThrow("trading_mode")),
                        tradingModeEmoji = c.getString(c.getColumnIndexOrThrow("trading_emoji")),
                        feeSol         = c.getDouble(c.getColumnIndexOrThrow("fee_sol")),
                        netPnlSol      = c.getDouble(c.getColumnIndexOrThrow("net_pnl_sol")),
                    ))
                }
            }
            synchronized(lock) {
                trades.clear()
                trades.addAll(loaded)
            }
            ErrorLogger.debug("TradeHistoryStore", "SQLite: loaded ${loaded.size} trades")
        } catch (e: Exception) {
            ErrorLogger.error("TradeHistoryStore", "SQLite load failed: ${e.message}")
        }
    }

    // ── One-time migration from SharedPreferences ────────────────────

    private fun migrateFromPrefsIfNeeded(ctx: Context) {
        val p = prefs ?: return
        val json = p.getString(KEY_TRADES, null) ?: return   // nothing to migrate
        val database = db ?: return

        // Already migrated if we have rows in SQLite
        val existing = synchronized(lock) { trades.size }
        if (existing > 0) {
            // Wipe the old prefs blob to free memory
            p.edit().remove(KEY_TRADES).apply()
            return
        }

        ErrorLogger.info("TradeHistoryStore", "📦 Migrating trades from SharedPreferences → SQLite…")
        try {
            val arr = JSONArray(json)
            database.beginTransaction()
            try {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val trade = Trade(
                        ts             = obj.getLong("ts"),
                        side           = obj.getString("side"),
                        sol            = obj.getDouble("sol"),
                        price          = obj.getDouble("price"),
                        pnlSol         = obj.optDouble("pnlSol", 0.0),
                        pnlPct         = obj.optDouble("pnlPct", 0.0),
                        reason         = obj.optString("reason", ""),
                        score          = obj.optDouble("score", 0.0),
                        mode           = obj.optString("mode", "paper"),
                        mint           = obj.optString("mint", ""),
                        tradingMode    = obj.optString("tradingMode", ""),
                        tradingModeEmoji = obj.optString("tradingModeEmoji", ""),
                        feeSol         = obj.optDouble("feeSol", 0.0),
                        netPnlSol      = obj.optDouble("netPnlSol", 0.0),
                    )
                    database.insertWithOnConflict(
                        TradeDbHelper.TABLE, null,
                        tradeToContentValues(trade),
                        SQLiteDatabase.CONFLICT_IGNORE
                    )
                    synchronized(lock) { trades.add(trade) }
                }
                database.setTransactionSuccessful()
                ErrorLogger.info("TradeHistoryStore",
                    "✅ Migrated ${arr.length()} trades from SharedPreferences → SQLite")
            } finally {
                database.endTransaction()
            }
        } catch (e: Exception) {
            ErrorLogger.error("TradeHistoryStore", "Migration failed: ${e.message}")
        }

        // Wipe old blob regardless — don't risk it being re-read
        p.edit().remove(KEY_TRADES).apply()
    }

    // ── Lifetime stats (tiny payload — SharedPreferences is fine) ────

    private fun loadLifetimeStats() {
        try {
            val json = prefs?.getString(KEY_LIFETIME_STATS, null) ?: return
            val obj  = JSONObject(json)
            lifetimeSells          = obj.optInt("sells", 0)
            lifetimeWins           = obj.optInt("wins", 0)
            lifetimeLosses         = obj.optInt("losses", 0)
            lifetimeScratches      = obj.optInt("scratches", 0)
            lifetimeWinPnlSum      = obj.optDouble("winPnlSum", 0.0)
            lifetimeRealizedPnlSol = obj.optDouble("realizedPnlSol", 0.0)
        } catch (e: Exception) {
            ErrorLogger.error("TradeHistoryStore", "Failed to load lifetime stats: ${e.message}")
        }
    }

    private fun saveLifetimeStats() {
        try {
            val obj = JSONObject().apply {
                put("sells",         lifetimeSells)
                put("wins",          lifetimeWins)
                put("losses",        lifetimeLosses)
                put("scratches",     lifetimeScratches)
                put("winPnlSum",     lifetimeWinPnlSum)
                put("realizedPnlSol",lifetimeRealizedPnlSol)
            }
            prefs?.edit()?.putString(KEY_LIFETIME_STATS, obj.toString())?.apply()
        } catch (e: Exception) {
            ErrorLogger.error("TradeHistoryStore", "Failed to save lifetime stats: ${e.message}")
        }
    }

    private fun bumpLifetimeFor(trade: Trade) {
        if (trade.side != "SELL") return
        lifetimeSells++
        when {
            isWin(trade)  -> { lifetimeWins++;    lifetimeWinPnlSum += trade.pnlPct }
            isLoss(trade) ->   lifetimeLosses++
            else          ->   lifetimeScratches++
        }
        lifetimeRealizedPnlSol += trade.pnlSol
        saveLifetimeStats()
    }

    private fun backfillLifetimeFromTrades() {
        val sells = synchronized(lock) { trades.filter { it.side == "SELL" } }
        if (sells.isEmpty()) return
        lifetimeSells          = sells.size
        lifetimeWins           = sells.count { isWin(it) }
        lifetimeLosses         = sells.count { isLoss(it) }
        lifetimeScratches      = sells.size - lifetimeWins - lifetimeLosses
        lifetimeWinPnlSum      = sells.filter { isWin(it) }.sumOf { it.pnlPct }
        lifetimeRealizedPnlSol = sells.sumOf { it.pnlSol }
        saveLifetimeStats()
        ErrorLogger.info("TradeHistoryStore",
            "📊 Back-filled lifetime stats from ${sells.size} existing SELL trades (wins=$lifetimeWins, losses=$lifetimeLosses)")
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun isWin(trade: Trade): Boolean  = trade.pnlPct >= WIN_THRESHOLD_PCT
    private fun isLoss(trade: Trade): Boolean = trade.pnlPct <= LOSS_THRESHOLD_PCT

    private fun midnightTs(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun Double.fmt(d: Int = 4) = "%.${d}f".format(this)
}
