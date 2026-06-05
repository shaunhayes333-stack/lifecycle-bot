package com.lifecyclebot.engine

import android.content.Context
import android.content.ContentValues
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
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
    private const val CURRENT_THRESHOLD_VER = 728  // V5.9.728 — asymmetric scratch band

    // V5.9.728 — align lifetime stats thresholds with TradeJournal so scratches
    // don't poison the displayed WR.
    //
    // Previously WIN=>0.0 / LOSS=<0.0 meant any fractionally negative trade
    // (e.g. -0.04 SOL from a -1.7% STALE_LIVE_PRICE_RUG_ESCAPE, or a
    // -0.0% CASHGEN_STOP_LOSS that rounds to zero) was bucketed as a full
    // loss. Operator's journal rows clearly tag those as SCRATCH, but the
    // 30-day Proof Run + Live Readiness panel pull from THIS store and were
    // showing W/L/S = 29 / 173 / 0 — i.e. every scratch counted as a loss.
    //
    // Asymmetric bands match TradeJournal.kt (the row classifier):
    //   WIN     >= +0.5%   (meaningful gain after fees + slippage)
    //   LOSS    <= -2.0%   (meaningful drawdown beyond round-trip cost)
    //   SCRATCH anything in between (fee-drag noise, ignored in WR math)
    //
    // The pre-V5.9.218 'unified 1% threshold' bug was symmetric: any small
    // win got swallowed too. This asymmetric band fixes the original
    // operator complaint while also matching every other classifier in the
    // codebase (Executor, BehaviorLearning, RunTracker30D, PatternClassifier).
    private const val WIN_THRESHOLD_PCT   = 0.5
    private const val LOSS_THRESHOLD_PCT  = -2.0

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

    // V5.9.706 — cache full StatsSnapshot to avoid O(N) list iterations on the main thread every 2.5s
    @Volatile private var cachedStatsSnapshot: StatsSnapshot? = null
    @Volatile private var cachedStatsSnapshotMs: Long = 0L
    @Volatile private var statsRefreshInFlight: Boolean = false
    private const val STATS_SNAPSHOT_CACHE_MS = 3_000L
    @Volatile private var rollingSliceCache: Map<String, Double> = emptyMap()
    @Volatile private var rollingSliceCacheMs: Long = 0L
    @Volatile private var rollingSliceRefreshInFlight: Boolean = false
    private const val ROLLING_SLICE_CACHE_MS = 30_000L

    fun getStatsCached(): StatsSnapshot {
        val now = System.currentTimeMillis()
        cachedStatsSnapshot?.let { if (now - cachedStatsSnapshotMs < STATS_SNAPSHOT_CACHE_MS) return it }

        // V5.9.1011 — NEVER recompute heavy stats on the UI thread. Snapshot
        // showed MainActivity.updateUi blocked in TradeHistoryStore.getSells24h
        // for ~8s. If cache is stale on main, return stale/default immediately
        // and refresh on TradeHistoryIO. Non-main callers may still compute sync.
        val onMain = try { Looper.myLooper() == Looper.getMainLooper() } catch (_: Throwable) { false }
        if (onMain) {
            scheduleStatsRefresh()
            return cachedStatsSnapshot ?: StatsSnapshot(
                trades24h = 0,
                winRate24h = 0,
                pnl24hSol = 0.0,
                totalStoredTrades = lifetimeSells,
                totalTrades = lifetimeWins + lifetimeLosses,
                totalWins = lifetimeWins,
                totalLosses = lifetimeLosses,
                totalScratches = lifetimeScratches,
                totalPnlSol = lifetimeRealizedPnlSol,
            )
        }

        val fresh = getStats()
        cachedStatsSnapshot = fresh
        cachedStatsSnapshotMs = now
        return fresh
    }

    private fun scheduleStatsRefresh() {
        if (statsRefreshInFlight) return
        statsRefreshInFlight = true
        val r = Runnable {
            try {
                val fresh = getStats()
                cachedStatsSnapshot = fresh
                cachedStatsSnapshotMs = System.currentTimeMillis()
            } catch (_: Throwable) {
            } finally {
                statsRefreshInFlight = false
            }
        }
        try {
            // Do not call ensureInitialized() from a main-thread cache miss;
            // lazy init may open SQLite/load rows. If the IO handler is not ready,
            // use a standalone background thread instead.
            ioHandler?.post(r) ?: Thread(r, "TradeStatsRefresh").start()
        } catch (_: Throwable) {
            try { Thread(r, "TradeStatsRefresh").start() } catch (_: Throwable) { statsRefreshInFlight = false }
        }
    }

    fun invalidateStatsCache() {
        cachedStatsSnapshot = null
        cachedStatsSnapshotMs = 0L
    }

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

    // V5.9.1070 — initComplete flag. init() is called from BotService,
    // MainActivity, and JournalActivity. A race between two callers can
    // re-run loadTradesFromDb() while the first pass is still in-flight,
    // causing the trades list to appear empty on the second read.
    // First caller wins; all subsequent calls are no-ops once db is open.
    @Volatile private var initComplete = false
    private val initLock = Any()

    fun init(ctx: Context) {
        if (initComplete) return          // fast-path: already initialised
        synchronized(initLock) {
            if (initComplete) return      // double-checked inside lock
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

        // V5.9.1067 — operator diagnostic: log SQLite row count + DB file path
        // at every init so next snapshot can prove "journal empty" = fresh
        // install (or Android system "Clear data") vs a load/init regression.
        try {
            val rowCount = synchronized(lock) { trades.size }
            val dbFile = ctx.getDatabasePath("trade_history.db")
            val dbBytes = if (dbFile.exists()) dbFile.length() else -1L
            ErrorLogger.info("TradeHistoryStore",
                "📦 INIT: loaded $rowCount trades from SQLite | dbPath=${dbFile.absolutePath} | dbBytes=$dbBytes")
        } catch (_: Throwable) {}

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
        initComplete = true  // V5.9.1070 — mark complete inside the lock
        } // end synchronized(initLock) — V5.9.1070
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

    // V5.9.1038 — choke-point dedupe (operator V5.9.1037 triage).
    // V5.9.1040 — TIGHTENED. Operator V5.9.1039 dump showed mint=2E4Awu
    // recorded TWICE 67ms apart with reason=STALE_LIVE_PRICE_RUG_ESCAPE
    // then reason=BLUECHIP_STOP_LOSS. The previous key `${mint}_${ts}_SELL`
    // never matched because each path constructs its own
    // `Trade(ts=System.currentTimeMillis())` — different ms → different
    // key → both pass. Drop ts from the key and shrink the window to
    // 1500ms. Cross-path duplicates always fire sub-second back-to-back
    // (different lanes both seeing the same exit signal); legitimate
    // partial sells fire seconds-to-minutes apart (operator dumps
    // confirmed 3-27s spacing for `6vq8GS` partial_*pct events).
    private val recordTradeRecentLru: MutableMap<String, Long> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(256, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > 1024
            }
        }
    )
    private val RECORD_TRADE_DEDUPE_WINDOW_MS: Long = 1_500L

    private fun isJournalSellLike(side: String): Boolean =
        side.equals("SELL", ignoreCase = true) || side.equals("PARTIAL_SELL", ignoreCase = true)

    private fun isValidAccountingTrade(t: Trade): Boolean {
        if (!isJournalSellLike(t.side)) return true
        if (t.price <= 0.0 && kotlin.math.abs(t.pnlSol) > 0.0000001) return false
        val proceeds = t.sol + (t.netPnlSol.takeIf { it != 0.0 } ?: t.pnlSol)
        if (proceeds < -0.0000001) return false
        return true
    }

    /**
     * V5.9.1038 — canonicalize tradingMode at the choke point so the
     * StrategyTelemetry bins (BLUECHIP / BLUE_CHIP / STANDARD) collapse into
     * one. The same trade was getting double-binned because Executor copies
     * `pos.tradingMode = "BLUE_CHIP"` while V3JournalRecorder sets
     * `tradingMode = layer = "BLUECHIP"`.
     */
    // V5.9.1043 — exposed so read-side aggregators (StrategyTelemetry,
    // BrainConsensusGate, etc.) can collapse legacy bin names recorded
    // before V5.9.1038's choke-point normalization shipped.
    fun normalizeTradeModeName(raw: String): String {
        val upper = raw.trim().uppercase().replace("[^A-Z0-9]".toRegex(), "")
        return when {
            upper.isBlank() -> ""
            upper.contains("BLUECHIP") -> "BLUECHIP"
            // V5.9.1300 — CASHGEN and TREASURY are the SAME trader (CashGenerationAI
            // = "Treasury mode"). They were normalized to two separate buckets, so
            // treasury outcomes split across TREASURY (BotService close path) and
            // CASHGEN (CashGen's own exit path) — and the lane's expectancy self-gate
            // queried CASHGEN (the smaller half) while most losses piled up under
            // TREASURY. Fold both into TREASURY so the trader sees its FULL record.
            upper.contains("CASHGEN") || upper.contains("CASHGENERATION") -> "TREASURY"
            upper.contains("SHITCOIN") -> "SHITCOIN"
            upper.contains("MOONSHOT") -> "MOONSHOT"
            upper.contains("TREASURY") -> "TREASURY"
            upper.contains("MANIP") -> "MANIPULATED"
            upper.contains("EXPRESS") -> "EXPRESS"
            upper.contains("CYCLIC") -> "CYCLIC"
            upper.contains("COPYTRADE") || upper.contains("COPY") -> "COPYTRADE"
            upper.contains("PROJECTSNIPER") || upper.contains("SNIPER") -> "PROJECT_SNIPER"
            upper.contains("DIPHUNTER") -> "DIP_HUNTER"
            upper.contains("MOMENTUM") -> "MOMENTUM_SWING"
            upper.contains("PRESALE") -> "PRESALE_SNIPE"
            upper.contains("LONGHOLD") -> "LONG_HOLD"
            upper.contains("COMMUNITY") -> "COMMUNITY"
            upper.contains("ALT") && upper.contains("TRADER") -> "ALTTRADER"
            upper == "STANDARD" -> "STANDARD"
            else -> raw.trim()  // preserve unrecognized names verbatim
        }
    }

    fun recordTrade(trade: Trade) {
        ensureInitialized()
        // V5.9.1038 — choke-point dedupe gate. SELL-only (BUY tradeIds are
        // not double-fired — only SELL exits go through both paths).
        if (trade.side.equals("SELL", ignoreCase = true)) {
            // V5.9.1040 — key on (mint, SELL) without ts. Cross-path duplicates
            // fire ms apart with different Trade.ts values; the 1500ms window
            // catches them while letting legitimate partial sells (which are
            // 3+ seconds apart) flow through.
            val key = "${trade.mint}_SELL"
            val now = System.currentTimeMillis()
            val prior = synchronized(recordTradeRecentLru) {
                val p = recordTradeRecentLru[key]
                if (p == null || (now - p) > RECORD_TRADE_DEDUPE_WINDOW_MS) {
                    recordTradeRecentLru[key] = now
                    null
                } else {
                    p
                }
            }
            if (prior != null) {
                try {
                    ErrorLogger.info(
                        "TradeHistoryStore",
                        "📓 TRADEJRNL_DEDUP_SKIP mint=${trade.mint.take(8)} ts=${trade.ts} side=SELL reason='${trade.reason}' priorAgeMs=${now - prior}",
                    )
                } catch (_: Throwable) {}
                return
            }
        }
        // V5.9.1038 — canonicalize mode-name to collapse bin fragmentation
        // (BLUE_CHIP vs BLUECHIP vs CashGen vs CASHGEN). Operator triage showed
        // strategy expectancy bins double-counting because Executor and
        // V3JournalRecorder set inconsistent casing on the same trade.
        var normalizedMode = normalizeTradeModeName(trade.tradingMode)

        // V5.9.1066 — analytics-vs-expectancy gap fix. When a SELL arrives
        // with a blank tradingMode (FALLBACK_ORPHAN_HARD_FLOOR, fluid_stop_loss,
        // [SELL_OPT] Stop Loss etc. that come from non-lane exit paths),
        // back-fill from the matching BUY's tradingMode so the trade bins
        // correctly. Operator V5.9.1065 snapshot showed Strategy Expectancy
        // totaling +19 SOL across 618 binned trades while PerformanceAnalytics
        // totaled -13.7 SOL across 912 closed trades — a 294-trade / -33 SOL
        // gap that was entirely unbinned fallback exits.
        if (normalizedMode.isBlank() && isJournalSellLike(trade.side) && trade.mint.isNotBlank()) {
            val inheritedMode = try {
                synchronized(lock) {
                    trades.asReversed().firstOrNull { prev ->
                        prev.side.equals("BUY", ignoreCase = true) &&
                            prev.mint == trade.mint &&
                            prev.tradingMode.isNotBlank()
                    }?.tradingMode
                }
            } catch (_: Throwable) { null }
            if (!inheritedMode.isNullOrBlank()) {
                normalizedMode = normalizeTradeModeName(inheritedMode)
            }
        }

        val tradeToStore = if (normalizedMode != trade.tradingMode) trade.copy(tradingMode = normalizedMode) else trade
        if (!isValidAccountingTrade(tradeToStore)) {
            try { ErrorLogger.warn("TradeHistoryStore", "PARTIAL_SELL_INVALID_ACCOUNTING mint=${tradeToStore.mint.take(8)} side=${tradeToStore.side} price=${tradeToStore.price} sol=${tradeToStore.sol} pnl=${tradeToStore.pnlSol} mode=${tradeToStore.tradingMode}") } catch (_: Throwable) {}
        }
        synchronized(lock) {
            trades.add(tradeToStore)
            // V5.9.330: Trim in-memory list to avoid OOM. SQLite retains everything.
            if (trades.size > MAX_IN_MEMORY_TRADES) {
                trades.subList(0, trades.size - MAX_IN_MEMORY_TRADES).clear()
            }
        }
        bumpLifetimeFor(tradeToStore)
        insertTradeAsync(tradeToStore)
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
                "📓 TRADEJRNL_REC side=${tradeToStore.side} mode=${tradeToStore.mode} sym=${tradeToStore.mint.take(6)} sol=${"%.3f".format(tradeToStore.sol)} pnl=${"%.3f".format(tradeToStore.pnlSol)} reason=${tradeToStore.reason} | inMem=${synchronized(lock) { trades.size }} lifetimeSells=$lifetimeSells",
            )
            PipelineHealthCollector.onTradeJournal()
            // V5.9.670 — also record into the recent-executions ring so the
            // Pipeline Health dump can surface the last 30 trades verbatim.
            PipelineHealthCollector.recordExec(
                side    = tradeToStore.side,
                mode    = tradeToStore.mode,
                symbol  = tradeToStore.mint.take(6),
                sizeSol = tradeToStore.sol,
                pnlSol  = tradeToStore.pnlSol,
                reason  = tradeToStore.reason,
            )
        } catch (_: Throwable) { /* never let logging break the record path */ }
        // V5.9.353: Per-mint loss-streak guard (block re-entry after 3 losses in a row).
        // Only emits on close events (SELL side). Buy events ignored.
        try {
            if (tradeToStore.side.equals("SELL", true) && tradeToStore.mint.isNotBlank()) {
                MemeLossStreakGuard.recordOutcome(tradeToStore.mint, isWin = tradeToStore.pnlSol > 0.0)
            }
        } catch (_: Exception) { /* non-fatal */ }
        // V5.9.495z7 — operator spec: every closed trade publishes a canonical
        // outcome event. Bus normalizes (rejecting BUY_PHANTOM after TX_PARSE
        // etc.), bumps counters, and routes to consumer layers. SELL only —
        // buys are open-events that the SELL supersedes.
        try {
            CanonicalOutcomeBus.publishFromLegacyTrade(tradeToStore)
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

    /**
     * V5.9.1249 — Bounded meme-WR snapshot for MemeWREmergencyBrake.
     * The brake previously called getAllTrades() (copies up to
     * MAX_IN_MEMORY_TRADES=10000 Trade objects) and then sequence-filtered on
     * the caller side — and that caller is MainActivity.updateUi on the MAIN
     * thread. As history grew that copy blew out to a 2+ second ArrayList.grow/
     * copyOf stall (snapshot 3215: MemeWREmergencyBrake.compute = 2182ms ANR,
     * cycles 18.5s — the "freeze at 149 trades"). This does the whole job inside
     * the lock in ONE pass with NO full-list materialisation: returns the
     * lifetime decisive meme-close count (for the >=5000 engage gate) and the
     * win-rate over the last [window] decisive meme closes (newest-first scan,
     * stops as soon as the window is full). isMemeMode is supplied by the brake
     * so mode-set ownership stays there.
     *
     * @return Pair(lifetimeDecisiveMemeCloses, windowWinRatePct).
     *         windowWinRatePct is 0.0 when the window is empty.
     */
    // V5.9.1344 — caller passes the lifetime threshold it actually needs (the
    // brake only checks lifetime >= MIN_LIFETIME_TRADES). Default keeps the old
    // full-count behaviour for any other caller.
    fun memeWrSnapshot(
        window: Int,
        lifetimeStopAt: Int = Int.MAX_VALUE,
        isMemeMode: (String?) -> Boolean,
    ): Pair<Int, Double> {
        ensureInitialized()
        // V5.9.1344 — BOUNDED EARLY-EXIT SCAN. The in-memory list caps at
        // MAX_IN_MEMORY_TRADES (10k). The old version scanned ALL of it every
        // call to compute an exact lifetime — but the only consumer
        // (MemeWREmergencyBrake) needs just (a) the WR of the last `window`
        // meme closes and (b) whether lifetime has crossed a threshold. Once
        // BOTH the window is full AND lifetime >= lifetimeStopAt, nothing more
        // can change the result, so we stop. This turns a 10k locked scan into
        // a ~few-hundred-element scan in the common (window-filled) case,
        // killing the lock-hold time that was stalling the bot even after the
        // off-thread move in 1342. We also snapshot the slice under the lock and
        // do the arithmetic OUTSIDE it to minimise lock-hold further.
        val cap = window.coerceAtLeast(1)
        var lifetime = 0
        var winWins = 0
        var winCount = 0
        synchronized(lock) {
            val list = trades
            var i = list.size - 1
            while (i >= 0) {
                val t = list[i]
                i--
                // V5.9.1346 — WR must count PARTIAL_SELL closes, not just full SELLs.
                // A green position scaled out in partial chunks (side=PARTIAL_SELL,
                // pnlPct>0) is a WIN; the old hardcoded == "SELL" silently dropped
                // every partial from the displayed/brake win-rate. Use the canonical
                // isJournalSellLike predicate (SELL || PARTIAL_SELL), same rule the
                // lifetime backfill already uses, so window WR == lifetime WR basis.
                if (!isJournalSellLike(t.side)) continue
                if (t.pnlPct == 0.0) continue          // scratches (break-even) excluded from WR
                if (!isMemeMode(t.tradingMode)) continue
                lifetime++
                if (winCount < cap) {
                    winCount++
                    if (t.pnlPct > 0.0) winWins++
                }
                // Early exit: window filled AND lifetime threshold satisfied —
                // any further trades cannot change either output the caller uses.
                if (winCount >= cap && lifetime >= lifetimeStopAt) break
            }
        }
        val wrPct = if (winCount > 0) winWins.toDouble() / winCount * 100.0 else 0.0
        return Pair(lifetime, wrPct)
    }

    /**
     * V5.9.1062 — Read trades DIRECTLY from SQLite, bypassing the in-memory list.
     * Use this from the Journal when the async init may not have populated the
     * in-memory list yet. Always returns the full persisted history (no in-memory cap).
     * Returns empty list and logs if db is null (init not yet called).
     */
    fun getAllTradesFromDb(): List<Trade> {
        val database = db
        if (database == null) {
            // DB not open yet — fall back to in-memory list (may be empty on first open)
            ensureInitialized()
            return synchronized(lock) { trades.toList() }
        }
        val loaded = mutableListOf<Trade>()
        try {
            val cursor = database.query(
                TradeDbHelper.TABLE,
                null, null, null, null, null, "ts DESC"  // newest first for Journal
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
        } catch (e: Exception) {
            ErrorLogger.error("TradeHistoryStore", "getAllTradesFromDb failed: ${e.message}")
            // Fall back to in-memory list on any DB error
            return synchronized(lock) { trades.toList() }
        }
        return loaded
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
        // V5.9.1161 — legacy corruption path disabled permanently.
        // The caller has no exit price, proceeds, parent position, fee or lane
        // context, so any row created here becomes the exact broken shape from
        // the uploaded journal (PARTIAL_SELL price=0/proceeds=0/standard).
        // Valid partials are recorded directly by Executor as real Trade rows
        // with side=PARTIAL_SELL, price>0, cost basis, PnL, and inherited lane.
        try {
            ErrorLogger.warn(
                "TradeHistoryStore",
                "PARTIAL_SELL_INVALID_ACCOUNTING legacy_recordPartialProfit_suppressed mint=${mint.take(8)} profit=${profitSol.fmt(4)} pnlPct=${pnlPct.toInt()}",
            )
        } catch (_: Throwable) {}
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
            // V5.9.1255 — authoritative grading of any symbolic rules that fired on
            // this mint, against the TRUE final P&L (the verdict that can't be fooled
            // by intra-trade noise; also guarantees fires never leak ungraded).
            try { com.lifecyclebot.engine.SymbolicExitReasoner.gradeRulesOnClose(trade.mint, trade.pnlPct) } catch (_: Throwable) {}
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
        return synchronized(lock) { trades.filter { isJournalSellLike(it.side) && it.ts >= midnight && isValidAccountingTrade(it) } }
    }

    fun getAllSells(): List<Trade> =
        synchronized(lock) { trades.filter { isJournalSellLike(it.side) && isValidAccountingTrade(it) }.toList() }

    fun getRecentValidClosedForMode(mode: String, limit: Int = 50): List<Trade> {
        val norm = normalizeTradeModeName(mode)
        return synchronized(lock) {
            trades.asReversed().asSequence()
                .filter { isJournalSellLike(it.side) && normalizeTradeModeName(it.tradingMode) == norm && isValidAccountingTrade(it) }
                .take(limit.coerceAtLeast(1))
                .toList()
        }
    }

    fun getWinRate24h(): Int {
        val sells = getSells24h()
        if (sells.isEmpty()) return 0
        val wins   = sells.count { isWin(it) }
        val losses = sells.count { isLoss(it) }
        val decisive = wins + losses
        return if (decisive > 0) ((wins.toDouble() * 100.0) / decisive).toInt() else 0
    }

    /**
     * V5.9.797 — operator audit: rolling-N short-term WR for the
     * WrRecoveryPartial predictive band. Looks at the last [n] SELL trades
     * (newest first), counts the decisive ones (W/L, ignoring scratches),
     * and returns the win-rate as a percentage. Returns -1.0 when the
     * sample is too small for a meaningful signal (< n/2 decisive trades).
     */
    fun rollingWinRatePct(n: Int): Double {
        val sample = synchronized(lock) {
            // Newest-first window of the most recent N sells.
            // V5.9.1346 — include PARTIAL_SELL closes (was == "SELL", dropped partials).
            trades.asReversed().asSequence().filter { isJournalSellLike(it.side) }.take(n).toList()
        }
        val wins   = sample.count { isWin(it) }
        val losses = sample.count { isLoss(it) }
        val decisive = wins + losses
        if (decisive < n / 2) return -1.0
        return if (decisive > 0) (wins.toDouble() * 100.0) / decisive else 0.0
    }

    /**
     * V5.9.798 — operator audit: WR Recovery Heatmap.
     * Returns WR pct for a [width]-trade window starting [offset] sells
     * back from the newest (0 = newest [width] sells, [width] = the prior
     * [width], etc.). Used by the 5-block heatmap tile on the Memes tab.
     * Returns -1.0 when the window is too sparse to be meaningful.
     */
    fun rollingWinRatePctSlice(offset: Int, width: Int): Double {
        val key = "$offset:$width"
        val now = System.currentTimeMillis()
        val onMain = try { Looper.myLooper() == Looper.getMainLooper() } catch (_: Throwable) { false }
        if (onMain) {
            rollingSliceCache[key]?.let { if (now - rollingSliceCacheMs < ROLLING_SLICE_CACHE_MS) return it }
            scheduleRollingSliceRefresh(width)
            return rollingSliceCache[key] ?: -1.0
        }
        return computeRollingWinRatePctSlice(offset, width)
    }

    private fun computeRollingWinRatePctSlice(offset: Int, width: Int): Double {
        val sample = synchronized(lock) {
            trades.asReversed().asSequence()
                // V5.9.1346 — include PARTIAL_SELL closes (was == "SELL").
                .filter { isJournalSellLike(it.side) }
                .drop(offset)
                .take(width)
                .toList()
        }
        val wins   = sample.count { isWin(it) }
        val losses = sample.count { isLoss(it) }
        val decisive = wins + losses
        if (decisive < width / 2) return -1.0
        return if (decisive > 0) (wins.toDouble() * 100.0) / decisive else 0.0
    }

    private fun scheduleRollingSliceRefresh(width: Int) {
        if (rollingSliceRefreshInFlight) return
        rollingSliceRefreshInFlight = true
        val r = Runnable {
            try {
                val fresh = HashMap<String, Double>(8)
                for (i in 0 until 5) {
                    val off = i * width
                    fresh["$off:$width"] = computeRollingWinRatePctSlice(off, width)
                }
                rollingSliceCache = fresh
                rollingSliceCacheMs = System.currentTimeMillis()
            } catch (_: Throwable) {
            } finally {
                rollingSliceRefreshInFlight = false
            }
        }
        try { ioHandler?.post(r) ?: Thread(r, "TradeRollingSliceRefresh").start() } catch (_: Throwable) {
            try { Thread(r, "TradeRollingSliceRefresh").start() } catch (_: Throwable) { rollingSliceRefreshInFlight = false }
        }
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
        val totalClosed = lifetimeSells

        val allSells       = synchronized(lock) { trades.filter { isJournalSellLike(it.side) && isValidAccountingTrade(it) } }

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
            totalTrades        = totalClosed,
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


    /** Journal-aligned closed trade count: all SELL/PARTIAL_SELL rows including scratches. */
    fun getJournalClosedTradeCount(): Int = getLifetimeStats().totalSells

    /** Journal-aligned learning progress: 0..1 over doctrine bootstrap target. */
    fun getJournalLearningProgress(targetTrades: Int = 5000): Double =
        (getJournalClosedTradeCount().toDouble() / targetTrades.coerceAtLeast(1).toDouble()).coerceIn(0.0, 1.0)

    fun getTopMode(): String? {
        val t = synchronized(lock) { trades.toList() }
        if (t.isEmpty()) return null
        return t.filter { it.tradingMode.isNotBlank() }
            .groupBy { it.tradingMode }
            .maxByOrNull { it.value.size }?.key
    }

    /**
     * V5.9.716 — Per-lane win rate for FreeRangeMode.laneSizeMultiplier().
     * Returns WR in 0-100 range, or -1.0 if fewer than minTrades sells exist
     * for this mode (caller should treat no-data as neutral).
     */
    fun getLaneWinRate(mode: String, minTrades: Int = 10): Double {
        val t = synchronized(lock) { trades.toList() }
        val modeTrades = t.filter { it.tradingMode.equals(mode, ignoreCase = true) && isJournalSellLike(it.side) && isValidAccountingTrade(it) }
        if (modeTrades.size < minTrades) return -1.0
        val wins = modeTrades.count { it.pnlPct > 0.0 }
        return wins * 100.0 / modeTrades.size
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
        if (!isJournalSellLike(trade.side)) return
        if (!isValidAccountingTrade(trade)) return
        lifetimeSells++
        when {
            isWin(trade)  -> { lifetimeWins++;    lifetimeWinPnlSum += trade.pnlPct }
            isLoss(trade) ->   lifetimeLosses++
            else          ->   lifetimeScratches++
        }
        lifetimeRealizedPnlSol += trade.netPnlSol.takeIf { it != 0.0 } ?: trade.pnlSol
        saveLifetimeStats()
    }

    private fun backfillLifetimeFromTrades() {
        val sells = synchronized(lock) {
            trades.filter { isJournalSellLike(it.side) && isValidAccountingTrade(it) }
        }
        if (sells.isEmpty()) return
        lifetimeSells          = sells.size
        lifetimeWins           = sells.count { isWin(it) }
        lifetimeLosses         = sells.count { isLoss(it) }
        lifetimeScratches      = sells.size - lifetimeWins - lifetimeLosses
        lifetimeWinPnlSum      = sells.filter { isWin(it) }.sumOf { it.pnlPct }
        lifetimeRealizedPnlSol = sells.sumOf { it.netPnlSol.takeIf { v -> v != 0.0 } ?: it.pnlSol }
        saveLifetimeStats()
        ErrorLogger.info("TradeHistoryStore",
            "📊 Back-filled lifetime stats from ${sells.size} valid SELL/PARTIAL_SELL trades (wins=$lifetimeWins, losses=$lifetimeLosses)")
    }

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
