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
    private const val MAX_IN_MEMORY_TRADES = 10000  // V5.9.354: was 2000 — too small for active days. Lifetime counters now back-fill stats so this is just for 24h-window queries.
    private const val KEY_LIFETIME_STATS  = "lifetime_stats_json"
    // V5.9.408: bumped whenever win/loss thresholds change so legacy persisted
    // counters get back-filled from the raw SELL rows instead of shown stale.
    private const val KEY_THRESHOLD_VER   = "threshold_version"
    private const val CURRENT_THRESHOLD_VER = 408

    // V5.9.408 — restored pre-V5.9.218 semantics. The "unified 1% threshold"
    // was silently converting every scratch (0-1% PnL) into a loss on the UI,
    // which is what produced the 13% displayed WR after ~1000 trades. Any
    // strictly positive PnL is a win; any strictly negative is a loss.
    private const val WIN_THRESHOLD_PCT   = 0.0
    private const val LOSS_THRESHOLD_PCT  = 0.0

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

    // V5.9.476 — JOURNAL UNIQUE-KEY COLLISION FIX.
    //
    // Operator: 'journal issue still persists. it works and then stops
    // recoding sells altogether. ensure there is no limits.'
    //
    // Root cause: schema declares `dedup_key TEXT UNIQUE` and the value
    // was '${ts}_${mint}_${side}'. When two sells fire on the SAME mint
    // in the SAME millisecond (partial sell + immediate full-sell, or
    // back-to-back ticks) the dedup_key collides and SQLite's
    // CONFLICT_IGNORE silently DROPS the second row. Once a token has
    // partial sells the user never sees the second one in the journal.
    //
    // Fix: append a monotonically-increasing AtomicLong to every
    // dedup_key. Same trade, same ms, same mint, same side → unique
    // key always. Per-process counter is fine — SQLite UNIQUE only
    // enforces uniqueness within the table, and ts already varies
    // across processes so cross-process collisions are impossible.
    private val tradeSeq = java.util.concurrent.atomic.AtomicLong(0L)

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

        // V5.9.408: one-shot re-backfill whenever win/loss thresholds change,
        // so the displayed WR isn't stuck on numbers computed against the old
        // 1% threshold.
        try {
            val storedVer = prefs?.getInt(KEY_THRESHOLD_VER, 0) ?: 0
            if (storedVer != CURRENT_THRESHOLD_VER &&
                synchronized(lock) { trades.any { it.side == "SELL" } }) {
                backfillLifetimeFromTrades()
                prefs?.edit()?.putInt(KEY_THRESHOLD_VER, CURRENT_THRESHOLD_VER)?.apply()
                ErrorLogger.info("TradeHistoryStore",
                    "♻️ Threshold version changed ($storedVer → $CURRENT_THRESHOLD_VER) — lifetime stats recomputed.")
            }
        } catch (_: Exception) { }

        // V5.9.330: Trim in-memory list to most recent MAX_IN_MEMORY_TRADES after load.
        // SQLite retains the full history — Journal pages from it on export.
        synchronized(lock) {
            if (trades.size > MAX_IN_MEMORY_TRADES) {
                trades.subList(0, trades.size - MAX_IN_MEMORY_TRADES).clear()
            }
        }

        ErrorLogger.info("TradeHistoryStore",
            "📊 Loaded ${synchronized(lock) { trades.size }} trades in-memory (SQLite retains all, lifetime sells=$lifetimeSells)")
        // V5.9.447 — UNIVERSAL JOURNAL COVERAGE summary. User can verify at
        // a glance that every lane writes to the on-device SQLite Journal.
        ErrorLogger.info("TradeHistoryStore",
            "📓 JOURNAL COVERAGE: Executor (memes/perps/stocks/forex/metals/commodities/crypto-alts) ✓ | " +
            "V3JournalRecorder (Shitcoin/Moonshot/Quality/BlueChip/CashGen/Manipulated) ✓ | " +
            "Express ✓ | LlmLab ✓ | Stale-60d refund ✓ — every trade lands in the Journal, no exceptions.")
    }

    // ── Public API ───────────────────────────────────────────────────

    /**
     * V5.9.431 — guarantee init() has run before any write, so a trade can
     * never be silently dropped by a null ioHandler/db. Lazy-inits using
     * AATEApp.appContextOrNull() if nothing else did yet.
     */
    private fun ensureInitialized() {
        if (db != null && ioHandler != null) return
        try {
            val ctx = com.lifecyclebot.AATEApp.appContextOrNull() ?: return
            init(ctx)
            ErrorLogger.warn("TradeHistoryStore",
                "Lazy init triggered — recordTrade called before explicit init(). Journal persistence restored.")
        } catch (e: Exception) {
            ErrorLogger.error("TradeHistoryStore", "Lazy init failed: ${e.message}")
        }
    }

    fun recordTrade(trade: Trade) {
        ensureInitialized()
        synchronized(lock) {
            trades.add(trade)
            // V5.9.330: Trim in-memory list to avoid OOM. SQLite retains everything.
            if (trades.size > MAX_IN_MEMORY_TRADES) {
                trades.subList(0, trades.size - MAX_IN_MEMORY_TRADES).clear()
            }
        }
        bumpLifetimeFor(trade)
        insertTradeAsync(trade)
        // V5.9.658 — operator triage: user reports "Journal shows 0 trades but
        // bot is clearly trading (875 24h trades, 15 open positions)." Emit a
        // structured INFO log on every recordTrade so the operator can grep
        // for "TRADEJRNL_REC" in logcat and immediately see whether the trade
        // execution path is calling this function. If this log is absent for
        // every BUY/SELL the bot reports in its UI counters, the bug is
        // upstream (Executor / V3JournalRecorder lost a recordTrade call).
        // If this log fires N times but JournalActivity still shows 0, the
        // bug is in JournalActivity rendering — totally different RCA.
        try {
            ErrorLogger.info(
                "TradeHistoryStore",
                "📓 TRADEJRNL_REC side=${trade.side} mode=${trade.mode} sym=${trade.mint.take(6)} sol=${"%.3f".format(trade.sol)} pnl=${"%.3f".format(trade.pnlSol)} reason=${trade.reason} | inMem=${synchronized(lock) { trades.size }} lifetimeSells=$lifetimeSells",
            )
            PipelineHealthCollector.onTradeJournal()
        } catch (_: Throwable) { /* never let logging break the record path */ }
        // V5.9.353: Per-mint loss-streak guard (block re-entry after 3 losses in a row).
        // Only emits on close events (SELL side). Buy events ignored.
        try {
            if (trade.side.equals("SELL", true) && trade.mint.isNotBlank()) {
                MemeLossStreakGuard.recordOutcome(trade.mint, isWin = trade.pnlSol > 0.0)
            }
        } catch (_: Exception) { /* non-fatal */ }
        // V5.9.495z7 — operator spec: every closed trade publishes a canonical
        // outcome event. Bus normalizes (rejecting BUY_PHANTOM after TX_PARSE
        // etc.), bumps counters, and routes to consumer layers. SELL only —
        // buys are open-events that the SELL supersedes.
        try {
            CanonicalOutcomeBus.publishFromLegacyTrade(trade)
        } catch (_: Throwable) { /* non-fatal — never break the trade record path */ }
    }

    fun recordTrades(newTrades: List<Trade>) {
        ensureInitialized()
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

    fun getAllTrades(): List<Trade> {
        ensureInitialized()
        return synchronized(lock) { trades.toList() }
    }

    /** MANUAL CLEAR — V5.9.635 unified semantics: clears journal rows AND
     *  every cross-lane counter the UI reads from, so the main-screen
     *  pills (24h Trades, All Traders, lane breakdown, Live Readiness),
     *  the Journal page, and the per-lane sub-trader cards all show 0
     *  together. Learned AI weights, PatternClassifier memory, SmartSizer
     *  trust, FluidLearningAI progress, and the active 30-Day Proof Run
     *  timeline are PRESERVED — only the visible scoreboard resets. */
    fun clearAllTrades() {
        synchronized(lock) { trades.clear() }
        ioHandler?.post {
            try {
                db?.delete(TradeDbHelper.TABLE, null, null)
            } catch (e: Exception) {
                ErrorLogger.error("TradeHistoryStore", "SQLite clear failed: ${e.message}")
            }
        }
        // V5.9.635 — also reset the lifetime counters that fed
        // getStats().totalWins / totalLosses / totalPnlSol — these used to
        // persist across Clear and made the "All Traders" line out-of-sync
        // with the empty Journal.
        lifetimeSells          = 0
        lifetimeWins           = 0
        lifetimeLosses         = 0
        lifetimeScratches      = 0
        lifetimeWinPnlSum      = 0.0
        lifetimeRealizedPnlSol = 0.0
        try { saveLifetimeStats() } catch (_: Exception) {}

        // V5.9.635 — also reset RunTracker30D counters (preserves proof-run
        // timeline) and every lane sub-trader's persisted counters so the
        // lane breakdown pills (M/A/P/S/FX/MT/CD), the top-bar 24h trade
        // pill, and the per-lane cards all align with the empty Journal.
        try { com.lifecyclebot.engine.RunTracker30D.resetTradeStatsForJournalClear() } catch (_: Throwable) {}
        try { com.lifecyclebot.perps.CryptoAltTrader.resetCounters() } catch (_: Throwable) {}
        try { com.lifecyclebot.perps.PerpsTraderAI.resetCounters() } catch (_: Throwable) {}
        try { com.lifecyclebot.perps.TokenizedStockTrader.resetCounters() } catch (_: Throwable) {}
        try { com.lifecyclebot.perps.ForexTrader.resetCounters() } catch (_: Throwable) {}
        try { com.lifecyclebot.perps.MetalsTrader.resetCounters() } catch (_: Throwable) {}
        try { com.lifecyclebot.perps.CommoditiesTrader.resetCounters() } catch (_: Throwable) {}

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
        ensureInitialized()
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

        // V5.9.354: Use the never-trimmed lifetime counters for lifetime
        // totals — the in-memory list is capped at MAX_IN_MEMORY_TRADES
        // and was silently capping the stats at 2000 trades on the UI.
        // Recent in-memory list still drives avgWinPct/profitFactor (those
        // need recent samples; OK to approximate from last N trades).
        val totalWins      = lifetimeWins
        val totalLosses    = lifetimeLosses
        val totalScratches = lifetimeScratches
        val totalCompleted = totalWins + totalLosses

        val allSells       = synchronized(lock) { trades.filter { it.side == "SELL" } }

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
        val totalPnlSol   = lifetimeRealizedPnlSol

        return StatsSnapshot(
            trades24h          = getTrades24h().size,
            winRate24h         = winRate24h,
            pnl24hSol          = sells24h.sumOf { it.pnlSol },
            totalStoredTrades  = lifetimeSells,  // V5.9.354: lifetime, not in-memory size
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
        put("dedup_key",     "${t.ts}_${t.mint}_${t.side}_${tradeSeq.incrementAndGet()}")
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

    private fun isWin(trade: Trade): Boolean  = trade.pnlPct > WIN_THRESHOLD_PCT
    private fun isLoss(trade: Trade): Boolean = trade.pnlPct < LOSS_THRESHOLD_PCT

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
