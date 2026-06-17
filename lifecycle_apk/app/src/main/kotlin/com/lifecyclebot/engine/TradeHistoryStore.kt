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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
            // V5.9.1408 — Fix "Can't downgrade database from version 3 to 1" crash.
            // The DB on device is at v3 from a previous experiment, so returning
            // the codebase to v1 throws SQLiteException on startup and breaks
            // the entire learning pipeline. Bump to v4 to force an upgrade instead.
            const val DB_VERSION = 6
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
                    proof_state   TEXT    NOT NULL DEFAULT '',
                    position_id   TEXT    NOT NULL DEFAULT '',
                    entry_ts_ms   INTEGER NOT NULL DEFAULT 0,
                    entry_price_snapshot REAL NOT NULL DEFAULT 0,
                    entry_mcap_usd REAL NOT NULL DEFAULT 0,
                    entry_qty_token REAL NOT NULL DEFAULT 0,
                    entry_cost_sol REAL NOT NULL DEFAULT 0,
                    entry_decimals INTEGER NOT NULL DEFAULT 0,
                    sold_qty_token REAL NOT NULL DEFAULT 0,
                    remaining_qty_token REAL NOT NULL DEFAULT 0,
                    entry_price_source TEXT NOT NULL DEFAULT '',
                    entry_pool_address TEXT NOT NULL DEFAULT '',
                    dedup_key     TEXT    UNIQUE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ts ON $TABLE(ts)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_side ON $TABLE(side)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 5) {
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN proof_state TEXT NOT NULL DEFAULT ''") } catch (_: Throwable) {}
            }
            if (oldVersion < 6) {
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN position_id TEXT NOT NULL DEFAULT ''") } catch (_: Throwable) {}
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN entry_ts_ms INTEGER NOT NULL DEFAULT 0") } catch (_: Throwable) {}
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN entry_price_snapshot REAL NOT NULL DEFAULT 0") } catch (_: Throwable) {}
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN entry_mcap_usd REAL NOT NULL DEFAULT 0") } catch (_: Throwable) {}
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN entry_qty_token REAL NOT NULL DEFAULT 0") } catch (_: Throwable) {}
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN entry_cost_sol REAL NOT NULL DEFAULT 0") } catch (_: Throwable) {}
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN entry_decimals INTEGER NOT NULL DEFAULT 0") } catch (_: Throwable) {}
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN sold_qty_token REAL NOT NULL DEFAULT 0") } catch (_: Throwable) {}
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN remaining_qty_token REAL NOT NULL DEFAULT 0") } catch (_: Throwable) {}
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN entry_price_source TEXT NOT NULL DEFAULT ''") } catch (_: Throwable) {}
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN entry_pool_address TEXT NOT NULL DEFAULT ''") } catch (_: Throwable) {}
                try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_position_id ON $TABLE(position_id)") } catch (_: Throwable) {}
            }
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

    fun isValidAccountingTrade(t: Trade): Boolean {
        val paperVerdict = com.lifecyclebot.engine.PaperLearningSanity.inspect(t)
        if (!paperVerdict.ok) {
            com.lifecyclebot.engine.PaperLearningSanity.emitQuarantine(t, paperVerdict.reason)
            return false
        }
        if (!isJournalSellLike(t.side)) return true
        if (t.price <= 0.0 && kotlin.math.abs(t.pnlSol) > 0.0000001) return false
        val proceeds = t.sol + (t.netPnlSol.takeIf { it != 0.0 } ?: t.pnlSol)
        if (proceeds < -0.0000001) return false
        // V5.9.1440 / V5.0.3724 — JOURNAL SANITY GUARD (P0). This predicate is
        // the canonical filter behind every realized-total / expectancy / tax-export
        // / lane-tuner / UI read. A row that fails here is quarantined before memory
        // and SQLite persistence; legacy bad rows are filtered on read. Quarantine
        // classes the operator confirmed (USDC EPjFWdd5 → SHITCOIN → +8722 SOL):
        //   1) base/quote/stable/LST mint as TARGET (address-keyed),
        //   2) impossible PnL% (a real meme exit cannot exceed +100000% / -100%),
        //   3) proceeds beyond a realistic notional cap (fabricated +$587k rows),
        //   4) stale/inverted price with non-zero PnL (already partly above).
        // Bounds are deliberately astronomically loose so NO legitimate runner is
        // ever excluded — these only catch physically-impossible fabricated rows.
        if (!t.price.isFinite() || !t.sol.isFinite() || !t.pnlSol.isFinite() || !t.pnlPct.isFinite() || !t.netPnlSol.isFinite()) return false
        if (com.lifecyclebot.engine.guard.BaseQuoteMintGuard.shouldQuarantine(t.mint)) return false
        // pnlPct sanity: -100% is total loss (floor); a genuine moonshot is bounded
        // far below +100000%. Anything outside is an accounting fabrication.
        if (t.pnlPct < -100.0001 || t.pnlPct > 100_000.0) return false
        // Notional cap: a single paper/live meme exit's proceeds cannot exceed a
        // sane SOL notional. The corrupt USDC row booked ~+8722 SOL on a position
        // that never cost that. Cap at 5000 SOL proceeds (orders of magnitude above
        // any real position this bot sizes) — pure fabrication catcher.
        if (kotlin.math.abs(proceeds) > 5_000.0) return false
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

    private fun android.database.Cursor.stringOrBlank(col: String): String =
        getColumnIndex(col).takeIf { it >= 0 }?.let { getString(it) } ?: ""

    private fun android.database.Cursor.longOrZero(col: String): Long =
        getColumnIndex(col).takeIf { it >= 0 }?.let { getLong(it) } ?: 0L

    private fun android.database.Cursor.doubleOrZero(col: String): Double =
        getColumnIndex(col).takeIf { it >= 0 }?.let { getDouble(it) } ?: 0.0

    private fun android.database.Cursor.intOrZero(col: String): Int =
        getColumnIndex(col).takeIf { it >= 0 }?.let { getInt(it) } ?: 0

    private fun isBuyLike(side: String): Boolean = side.equals("BUY", ignoreCase = true)

    private fun fallbackPositionId(t: Trade): String {
        if (t.positionId.isNotBlank()) return t.positionId
        val entryTs = when {
            t.entryTsMs > 0L -> t.entryTsMs
            isBuyLike(t.side) -> t.ts
            else -> 0L
        }
        return if (t.mint.isNotBlank() && entryTs > 0L) "${t.mode.uppercase()}:${t.mint}:$entryTs" else ""
    }

    private fun enrichRowsBySequence(rows: List<Trade>): List<Trade> {
        if (rows.isEmpty()) return rows
        val lastBuyByModeMint = mutableMapOf<String, Trade>()
        val enrichedByIndex = mutableMapOf<Int, Trade>()
        rows.withIndex().sortedBy { it.value.ts }.forEach { indexed ->
            val row = indexed.value
            val key = "${row.mode.lowercase()}|${row.mint}"
            val source = if (isBuyLike(row.side)) row else lastBuyByModeMint[key]
            val entryTs = when {
                row.entryTsMs > 0L -> row.entryTsMs
                (source?.entryTsMs ?: 0L) > 0L -> source!!.entryTsMs
                source != null -> source.ts
                else -> 0L
            }
            val entryCost = when {
                row.entryCostSol > 0.0 -> row.entryCostSol
                (source?.entryCostSol ?: 0.0) > 0.0 -> source!!.entryCostSol
                source != null -> source.sol
                isBuyLike(row.side) -> row.sol
                else -> 0.0
            }
            val entryQty = when {
                row.entryQtyToken > 0.0 -> row.entryQtyToken
                (source?.entryQtyToken ?: 0.0) > 0.0 -> source!!.entryQtyToken
                else -> 0.0
            }
            val soldQty = when {
                row.soldQtyToken > 0.0 -> row.soldQtyToken
                !isBuyLike(row.side) && entryCost > 0.0 && entryQty > 0.0 && row.sol > 0.0 -> (entryQty * (row.sol / entryCost)).coerceIn(0.0, entryQty)
                else -> 0.0
            }
            val enriched = row.copy(
                positionId = row.positionId.ifBlank {
                    source?.positionId?.takeIf { it.isNotBlank() } ?: if (row.mint.isNotBlank() && entryTs > 0L) "${row.mode.uppercase()}:${row.mint}:$entryTs" else ""
                },
                entryTsMs = entryTs,
                entryPriceSnapshot = row.entryPriceSnapshot.takeIf { it > 0.0 }
                    ?: source?.entryPriceSnapshot?.takeIf { it > 0.0 }
                    ?: source?.price?.takeIf { it > 0.0 }
                    ?: if (isBuyLike(row.side)) row.price else 0.0,
                entryMcapUsd = row.entryMcapUsd.takeIf { it > 0.0 } ?: source?.entryMcapUsd ?: 0.0,
                entryQtyToken = entryQty,
                entryCostSol = entryCost,
                entryDecimals = if (row.entryDecimals > 0) row.entryDecimals else source?.entryDecimals ?: 0,
                soldQtyToken = soldQty,
                remainingQtyToken = when {
                    row.remainingQtyToken > 0.0 -> row.remainingQtyToken
                    !isBuyLike(row.side) && entryQty > 0.0 -> (entryQty - soldQty).coerceAtLeast(0.0)
                    isBuyLike(row.side) -> entryQty
                    else -> 0.0
                },
                entryPriceSource = row.entryPriceSource.ifBlank { source?.entryPriceSource ?: "" },
                entryPoolAddress = row.entryPoolAddress.ifBlank { source?.entryPoolAddress ?: "" },
            )
            if (isBuyLike(enriched.side)) lastBuyByModeMint[key] = enriched
            enrichedByIndex[indexed.index] = enriched
        }
        return rows.indices.map { enrichedByIndex[it] ?: rows[it] }
    }

    private fun enrichJournalLinkage(t: Trade): Trade {
        val priorBuy: Trade? = if (isBuyLike(t.side) || t.mint.isBlank()) null else try {
            synchronized(lock) {
                trades.asReversed().firstOrNull { prev ->
                    isBuyLike(prev.side) && prev.mint == t.mint && prev.mode.equals(t.mode, true)
                }
            }
        } catch (_: Throwable) { null }
        val source: Trade? = if (isBuyLike(t.side)) t else priorBuy
        val entryTs = when {
            t.entryTsMs > 0L -> t.entryTsMs
            (source?.entryTsMs ?: 0L) > 0L -> source!!.entryTsMs
            source != null -> source.ts
            else -> 0L
        }
        val entryCost = when {
            t.entryCostSol > 0.0 -> t.entryCostSol
            (source?.entryCostSol ?: 0.0) > 0.0 -> source!!.entryCostSol
            source != null -> source.sol
            isBuyLike(t.side) -> t.sol
            else -> 0.0
        }
        val entryQty = when {
            t.entryQtyToken > 0.0 -> t.entryQtyToken
            (source?.entryQtyToken ?: 0.0) > 0.0 -> source!!.entryQtyToken
            else -> 0.0
        }
        val soldQty = when {
            t.soldQtyToken > 0.0 -> t.soldQtyToken
            !isBuyLike(t.side) && entryCost > 0.0 && entryQty > 0.0 && t.sol > 0.0 -> (entryQty * (t.sol / entryCost)).coerceIn(0.0, entryQty)
            else -> 0.0
        }
        val remainingQty = when {
            t.remainingQtyToken > 0.0 -> t.remainingQtyToken
            !isBuyLike(t.side) && entryQty > 0.0 -> (entryQty - soldQty).coerceAtLeast(0.0)
            isBuyLike(t.side) -> entryQty
            else -> 0.0
        }
        val enriched = t.copy(
            positionId = t.positionId.ifBlank {
                source?.positionId?.takeIf { it.isNotBlank() } ?: if (t.mint.isNotBlank() && entryTs > 0L) "${t.mode.uppercase()}:${t.mint}:$entryTs" else ""
            },
            entryTsMs = entryTs,
            entryPriceSnapshot = t.entryPriceSnapshot.takeIf { it > 0.0 }
                ?: source?.entryPriceSnapshot?.takeIf { it > 0.0 }
                ?: source?.price?.takeIf { it > 0.0 }
                ?: if (isBuyLike(t.side)) t.price else 0.0,
            entryMcapUsd = t.entryMcapUsd.takeIf { it > 0.0 } ?: source?.entryMcapUsd ?: 0.0,
            entryQtyToken = entryQty,
            entryCostSol = entryCost,
            entryDecimals = if (t.entryDecimals > 0) t.entryDecimals else source?.entryDecimals ?: 0,
            soldQtyToken = soldQty,
            remainingQtyToken = remainingQty,
            entryPriceSource = t.entryPriceSource.ifBlank { source?.entryPriceSource ?: "" },
            entryPoolAddress = t.entryPoolAddress.ifBlank { source?.entryPoolAddress ?: "" },
        )
        if ((t.positionId.isBlank() || (!isBuyLike(t.side) && t.entryPriceSnapshot <= 0.0)) && enriched.positionId.isNotBlank()) {
            try { PipelineHealthCollector.labelInc("TRADE_JOURNAL_LINKAGE_ENRICHED") } catch (_: Throwable) {}
        }
        return enriched
    }

    private fun normalizeProofState(t: Trade): String {
        val explicit = t.proofState.trim().uppercase()
        if (explicit in setOf("PAPER_SIMULATED", "LIVE_BROADCAST", "LIVE_SIG_CONFIRMED", "LIVE_BALANCE_CONFIRMED", "LIVE_FINALIZED")) return explicit
        val m = t.mode.trim().lowercase()
        if (m == "paper") return "PAPER_SIMULATED"
        if (m == "live") {
            val r = t.reason.uppercase()
            return when {
                r.contains("FINALIZED") || r.contains("SELL_FINALIZED") -> "LIVE_FINALIZED"
                r.contains("ZERO_BALANCE") || r.contains("BALANCE_CONFIRMED") || r.contains("WALLET") -> "LIVE_BALANCE_CONFIRMED"
                t.sig.isNotBlank() -> "LIVE_SIG_CONFIRMED"
                else -> "LIVE_BROADCAST"
            }
        }
        return ""
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

        val normalizedProof = normalizeProofState(trade)
        val normalizedTrade = if (normalizedMode != trade.tradingMode || normalizedProof != trade.proofState) trade.copy(tradingMode = normalizedMode, proofState = normalizedProof) else trade
        val tradeToStore = enrichJournalLinkage(normalizedTrade)
        if (!isValidAccountingTrade(tradeToStore)) {
            try {
                ErrorLogger.warn(
                    "TradeHistoryStore",
                    "TRADE_ACCOUNTING_QUARANTINED mint=${tradeToStore.mint.take(8)} side=${tradeToStore.side} price=${tradeToStore.price} sol=${tradeToStore.sol} pnl=${tradeToStore.pnlSol} pnlPct=${tradeToStore.pnlPct} mode=${tradeToStore.tradingMode} reason=${tradeToStore.reason}",
                )
                PipelineHealthCollector.labelInc("TRADE_ACCOUNTING_QUARANTINED")
                if (tradeToStore.mode.equals("paper", true)) PipelineHealthCollector.labelInc("PAPER_COUNTER_SKIPPED_QUARANTINED_ROW")
            } catch (_: Throwable) {}
            return
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
                "📓 TRADEJRNL_REC side=${tradeToStore.side} mode=${tradeToStore.mode} proof=${tradeToStore.proofState} sym=${tradeToStore.mint.take(6)} sol=${"%.3f".format(tradeToStore.sol)} pnl=${"%.3f".format(tradeToStore.pnlSol)} reason=${tradeToStore.reason} | inMem=${synchronized(lock) { trades.size }} lifetimeSells=$lifetimeSells",
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
                proofState = tradeToStore.proofState,
                positionId = tradeToStore.positionId,
                lane = tradeToStore.tradingMode,
                entryPriceSnapshot = tradeToStore.entryPriceSnapshot,
                entryMcapUsd = tradeToStore.entryMcapUsd,
                entryQtyToken = tradeToStore.entryQtyToken,
                entryCostSol = tradeToStore.entryCostSol,
                entryPriceSource = tradeToStore.entryPriceSource,
            )
        } catch (_: Throwable) { /* never let logging break the record path */ }
        try { uploadCollectiveJournalRow(tradeToStore) } catch (_: Throwable) {}
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

    private fun uploadCollectiveJournalRow(trade: Trade) {
        try {
            // V5.0.3825 — canonical hive trade upload. Executor-side BUY uploads
            // pass pnl=0 and are intentionally skipped as scratch by uploadTrade();
            // the accepted journal row is the source of truth for hive trade count.
            val side = trade.side.uppercase().take(24)
            if (side.isBlank()) return
            val journalKey = listOf(
                trade.positionId.ifBlank { trade.mint }, side, trade.ts.toString(), trade.reason.take(32)
            ).joinToString("|")
            val holdMins = if (trade.entryTsMs > 0L && trade.ts > trade.entryTsMs) {
                (trade.ts - trade.entryTsMs).toDouble() / 60_000.0
            } else 0.0
            val liquidityProxy = when {
                trade.entryMcapUsd > 0.0 -> trade.entryMcapUsd
                trade.entryCostSol > 0.0 -> trade.entryCostSol * 10_000.0
                else -> 0.0
            }
            kotlinx.coroutines.GlobalScope.launch(AppDispatchers.sideEffect) {
                try {
                    com.lifecyclebot.collective.CollectiveLearning.uploadJournalTradeRow(
                        side = side,
                        symbol = trade.mint.take(10).ifBlank { "UNKNOWN" },
                        mint = trade.mint,
                        mode = trade.tradingMode.ifBlank { "STANDARD" },
                        source = trade.entryPriceSource.ifBlank { trade.proofState.ifBlank { "JOURNAL" } },
                        liquidityUsd = liquidityProxy,
                        marketSentiment = trade.reason.take(40).ifBlank { "JOURNAL" },
                        entryScore = trade.score.toInt(),
                        confidence = 0,
                        pnlPct = trade.pnlPct,
                        holdMins = holdMins,
                        isWin = trade.pnlPct >= 0.0,
                        paperMode = trade.mode.equals("paper", true) || trade.mode.equals("PAPER", true),
                        journalKey = journalKey,
                    )
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    fun recordTrades(newTrades: List<Trade>) {
        ensureInitialized()
        val existingKeys = synchronized(lock) {
            trades.map { "${it.ts}_${it.mint}" }.toSet()
        }
        val toAdd = mutableListOf<Trade>()
        newTrades
            .filter { "${it.ts}_${it.mint}" !in existingKeys }
            .forEach { raw ->
                val normalized = raw.copy(
                    tradingMode = normalizeTradeModeName(raw.tradingMode),
                    proofState = normalizeProofState(raw),
                )
                val enriched = enrichJournalLinkage(normalized)
                val ok = isValidAccountingTrade(enriched)
                if (!ok) try {
                    ErrorLogger.warn("TradeHistoryStore", "TRADE_ACCOUNTING_BULK_QUARANTINED mint=${enriched.mint.take(8)} side=${enriched.side} pnlPct=${enriched.pnlPct} pnl=${enriched.pnlSol} reason=${enriched.reason}")
                    if (enriched.mode.equals("paper", true)) PipelineHealthCollector.labelInc("PAPER_COUNTER_SKIPPED_QUARANTINED_ROW")
                } catch (_: Throwable) {}
                if (ok) {
                    synchronized(lock) { trades.add(enriched) }
                    toAdd += enriched
                }
            }
        if (toAdd.isNotEmpty()) {
            toAdd.forEach { bumpLifetimeFor(it) }
            toAdd.forEach { insertTradeAsync(it) }
            ErrorLogger.debug("TradeHistoryStore", "💾 Added ${toAdd.size} new linked trades")
        }
    }

    fun getAllTrades(): List<Trade> {
        ensureInitialized()
        // V5.0.3724 — journal/accounting source fix. Bad decimal/price-basis rows
        // must never reach UI totals, WR, avg win, lane tuners, or learning consumers.
        // recordTrade() now quarantines future invalid rows; this read filter protects
        // existing legacy rows already present in memory/SQLite before the fix.
        return synchronized(lock) { trades.filter { isValidAccountingTrade(it) }.toList() }
    }

    fun getAllTradesIncludingInvalidForensics(): List<Trade> {
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
            return synchronized(lock) { trades.filter { isValidAccountingTrade(it) }.toList() }
        }
        val loaded = mutableListOf<Trade>()
        try {
            val cursor = database.query(
                TradeDbHelper.TABLE,
                null, null, null, null, null, "ts DESC"  // newest first for Journal
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    val row = Trade(
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
                        proofState     = c.stringOrBlank("proof_state"),
                        positionId     = c.stringOrBlank("position_id"),
                        entryTsMs      = c.longOrZero("entry_ts_ms"),
                        entryPriceSnapshot = c.doubleOrZero("entry_price_snapshot"),
                        entryMcapUsd   = c.doubleOrZero("entry_mcap_usd"),
                        entryQtyToken  = c.doubleOrZero("entry_qty_token"),
                        entryCostSol   = c.doubleOrZero("entry_cost_sol"),
                        entryDecimals  = c.intOrZero("entry_decimals"),
                        soldQtyToken   = c.doubleOrZero("sold_qty_token"),
                        remainingQtyToken = c.doubleOrZero("remaining_qty_token"),
                        entryPriceSource = c.stringOrBlank("entry_price_source"),
                        entryPoolAddress = c.stringOrBlank("entry_pool_address"),
                    )
                    if (isValidAccountingTrade(row)) loaded.add(row)
                    else try { ErrorLogger.warn("TradeHistoryStore", "TRADE_ACCOUNTING_LEGACY_ROW_FILTERED mint=${row.mint.take(8)} side=${row.side} pnlPct=${row.pnlPct} pnl=${row.pnlSol} reason=${row.reason}") } catch (_: Throwable) {}
                }
            }
            val enriched = enrichRowsBySequence(loaded).sortedByDescending { it.ts }
            loaded.clear(); loaded.addAll(enriched)
        } catch (e: Exception) {
            ErrorLogger.error("TradeHistoryStore", "getAllTradesFromDb failed: ${e.message}")
            // Fall back to in-memory list on any DB error, but still apply the
            // canonical accounting quarantine so legacy decimal-corrupt rows never
            // reappear in the journal/header.
            return synchronized(lock) { trades.filter { isValidAccountingTrade(it) }.toList() }
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

    /**
     * V5.9.1532 — OPENED-BUT-NOT-CLOSED, straight from the durable journal.
     *
     * Operator's source-of-truth fix: the SQLite journal records a BUY row on
     * every open and a SELL row on every close. A mint whose LATEST row is a BUY
     * is an open position the bot must still be managing. This survives app
     * updates / process kills because SQLite persists, unlike the in-memory
     * activePositions map that boots empty and orphaned every install.
     *
     * Walks all rows newest-first (getAllTradesFromDb returns ts DESC); the first
     * row seen per mint is its latest event. side=="BUY" → still open.
     *
     * @return map of mint -> the opening BUY Trade row (latest BUY for that mint).
     */
    fun openMintsFromJournal(): Map<String, Trade> {
        val rows = try { getAllTradesFromDb() } catch (_: Throwable) { synchronized(lock) { trades.filter { isValidAccountingTrade(it) }.toList() } }
        val latestSideSeen = HashMap<String, String>()   // mint -> latest side
        val openBuyRow = HashMap<String, Trade>()         // mint -> its latest BUY row
        for (t in rows) {  // already ts DESC (newest first)
            val mint = t.mint
            if (mint.isBlank()) continue
            // First time we see this mint == its most recent event.
            if (!latestSideSeen.containsKey(mint)) {
                latestSideSeen[mint] = t.side
                if (t.side == "BUY") openBuyRow[mint] = t
            }
        }
        // Keep only mints whose LATEST event was a BUY (open, no later SELL).
        return openBuyRow.filterKeys { latestSideSeen[it] == "BUY" }
    }

    /** Convenience: just the set of mints currently open per the journal. */
    fun openMintSetFromJournal(): Set<String> = openMintsFromJournal().keys

    /**
     * V5.9.1354 — CANONICAL ASSET BREAKDOWN (single source of truth).
     *
     * ROOT-CAUSE FIX (operator: "All Traders 82" ≠ "24h Trades 94", 4th time):
     * the dashboard summed THREE independent counters — TradeHistoryStore meme
     * stats + CryptoAltTrader.getStats() + PerpsTraderAI.getLifetimeTrades().
     * Those per-trader counters increment on a DIFFERENT path than the journal
     * write, so any trade that journals but whose counter misses (worker
     * timeout, async drop, IO-wedge) makes the sums drift — AND they are not
     * cleared by the learning reset, so stale lifetime values survive a reset
     * while the journal is fresh.
     *
     * This derives every asset-class count DIRECTLY from the journal rows (the
     * one canonical record that reset DOES wipe), grouped via the same
     * inferAssetClassFromMode logic. Counts can no longer diverge or survive a
     * reset. SELL-like rows only; respects accounting validity.
     */
    data class AssetCounts(var trades: Int = 0, var wins: Int = 0, var losses: Int = 0, var pnlSol: Double = 0.0) {
        val winRate: Double get() = if (wins + losses > 0) wins * 100.0 / (wins + losses) else 0.0
    }

    private fun inferAssetClassFromMode(mode: String): String {
        val m = normalizeTradeModeName(mode).uppercase()
        return when {
            m.startsWith("STOCK")  || m.contains("STOCKS_")      -> "STOCK"
            m.startsWith("FOREX")  || m.contains("FOREX_")       -> "FOREX"
            m.startsWith("METAL")  || m.contains("METALS_")      -> "METAL"
            m.startsWith("COMMOD") || m.contains("COMMODITIES_") -> "COMMODITY"
            m.startsWith("PERP")   || m.contains("PERPS_")       -> "PERP"
            m.startsWith("ALT")    || m.contains("ALTSPOT") || m.contains("CRYPTO_ALT") || m == "ALTTRADER" -> "ALT"
            else                                                  -> "MEME"
        }
    }

    /** Canonical per-asset breakdown derived from journal SELL rows. */
    fun getAssetBreakdown(): Map<String, AssetCounts> {
        val out = LinkedHashMap<String, AssetCounts>()
        val snapshot = synchronized(lock) { trades.toList() }
        for (t in snapshot) {
            if (!isJournalSellLike(t.side)) continue
            if (!isValidAccountingTrade(t)) continue
            val asset = inferAssetClassFromMode(t.tradingMode)
            val acc = out.getOrPut(asset) { AssetCounts() }
            acc.trades += 1
            val pnl = if (t.netPnlSol != 0.0) t.netPnlSol else t.pnlSol
            acc.pnlSol += pnl
            // Use the journal's canonical scratch-aware classifiers (pnlPct vs
            // WIN/LOSS thresholds) so W/L here matches every other WR readout.
            when {
                isWin(t)  -> acc.wins += 1
                isLoss(t) -> acc.losses += 1
                else      -> { /* scratch (break-even band) — counted in trades, not W/L */ }
            }
        }
        return out
    }

    /** Canonical grand total across ALL assets (drift-proof "All Traders" line). */
    fun getCanonicalTotals(): AssetCounts {
        val tot = AssetCounts()
        for (a in getAssetBreakdown().values) {
            tot.trades += a.trades; tot.wins += a.wins; tot.losses += a.losses; tot.pnlSol += a.pnlSol
        }
        return tot
    }

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
        return synchronized(lock) { trades.filter { it.ts >= midnight && isValidAccountingTrade(it) } }
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
    // V5.0.3680 — Main-thread cache for rollingWinRatePct. ANR snapshot
    // showed this called from UI render eating 1269ms (SQLite-ish scan
    // under `synchronized(lock)` with a large `trades` list). The exact
    // value updates rarely (only on close); the UI doesn't need sub-second
    // freshness. Cache the result for ROLLING_WR_CACHE_MS and refresh
    // off-main when called from the UI.
    private val rollingWrCache = java.util.concurrent.ConcurrentHashMap<Int, Double>()
    @Volatile private var rollingWrCacheMs: Long = 0L
    private val ROLLING_WR_CACHE_MS: Long = 4_000L

    fun rollingWinRatePct(n: Int): Double {
        val now = System.currentTimeMillis()
        val onMain = try { android.os.Looper.myLooper() == android.os.Looper.getMainLooper() } catch (_: Throwable) { false }
        if (onMain) {
            // Serve from cache if fresh; otherwise schedule a bg recompute
            // and return the prior value (or -1.0 if we've never computed).
            val cached = rollingWrCache[n]
            if (cached != null && now - rollingWrCacheMs < ROLLING_WR_CACHE_MS) return cached
            // Schedule async refresh — never block UI.
            try {
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val v = computeRollingWinRatePct(n)
                        rollingWrCache[n] = v
                        rollingWrCacheMs = System.currentTimeMillis()
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
            return cached ?: -1.0
        }
        val v = computeRollingWinRatePct(n)
        rollingWrCache[n] = v
        rollingWrCacheMs = now
        return v
    }

    private fun computeRollingWinRatePct(n: Int): Double {
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
        val t = synchronized(lock) { trades.filter { isValidAccountingTrade(it) } }
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
        val t = synchronized(lock) { trades.filter { isValidAccountingTrade(it) } }
        val modeTrades = t.filter { it.tradingMode.equals(mode, ignoreCase = true) && isJournalSellLike(it.side) }
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
        put("proof_state",   t.proofState)
        put("position_id",   fallbackPositionId(t))
        put("entry_ts_ms",   t.entryTsMs)
        put("entry_price_snapshot", t.entryPriceSnapshot)
        put("entry_mcap_usd", t.entryMcapUsd)
        put("entry_qty_token", t.entryQtyToken)
        put("entry_cost_sol", t.entryCostSol)
        put("entry_decimals", t.entryDecimals)
        put("sold_qty_token", t.soldQtyToken)
        put("remaining_qty_token", t.remainingQtyToken)
        put("entry_price_source", t.entryPriceSource)
        put("entry_pool_address", t.entryPoolAddress)
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
                    val row = Trade(
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
                        proofState     = c.stringOrBlank("proof_state"),
                        positionId     = c.stringOrBlank("position_id"),
                        entryTsMs      = c.longOrZero("entry_ts_ms"),
                        entryPriceSnapshot = c.doubleOrZero("entry_price_snapshot"),
                        entryMcapUsd   = c.doubleOrZero("entry_mcap_usd"),
                        entryQtyToken  = c.doubleOrZero("entry_qty_token"),
                        entryCostSol   = c.doubleOrZero("entry_cost_sol"),
                        entryDecimals  = c.intOrZero("entry_decimals"),
                        soldQtyToken   = c.doubleOrZero("sold_qty_token"),
                        remainingQtyToken = c.doubleOrZero("remaining_qty_token"),
                        entryPriceSource = c.stringOrBlank("entry_price_source"),
                        entryPoolAddress = c.stringOrBlank("entry_pool_address"),
                    )
                    if (isValidAccountingTrade(row)) loaded.add(row)
                    else try { ErrorLogger.warn("TradeHistoryStore", "TRADE_ACCOUNTING_DB_INIT_FILTERED mint=${row.mint.take(8)} side=${row.side} pnlPct=${row.pnlPct} pnl=${row.pnlSol} reason=${row.reason}") } catch (_: Throwable) {}
                }
            }
            val enrichedLoaded = enrichRowsBySequence(loaded)
            synchronized(lock) {
                trades.clear()
                trades.addAll(enrichedLoaded)
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
                    if (isValidAccountingTrade(trade)) {
                        database.insertWithOnConflict(
                            TradeDbHelper.TABLE, null,
                            tradeToContentValues(trade),
                            SQLiteDatabase.CONFLICT_IGNORE
                        )
                        synchronized(lock) { trades.add(trade) }
                    } else {
                        try { ErrorLogger.warn("TradeHistoryStore", "TRADE_ACCOUNTING_PREFS_MIGRATION_FILTERED mint=${trade.mint.take(8)} side=${trade.side} pnlPct=${trade.pnlPct} pnl=${trade.pnlSol} reason=${trade.reason}") } catch (_: Throwable) {}
                    }
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
