package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ForensicLogger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1533 — SELL-ONLY SAFE MODE (operator spec items 1, 6, 9).
 *
 * THE FAILURE (operator screenshot 04:33, runtime doctor LANE_FANOUT_EXPLOSION):
 * Live mode had 7 lanes enabled, 21 wallet-held vs 19 store positions vs 2 orphans,
 * sellReconciler.activeJobs=17, close_lease_duplicate_suppressed>14,000, and
 * unique_closed_positions=0. Two pump mints showed +83,913,533% / +65,021,599%
 * "PnL" from stale/zero/decimal price math. The bot was STAMPEDING stale exits
 * through degraded routes (Jupiter 503/429, Pump 0x1787, Jito decode-fail) while
 * opening NEW buys into the bleed.
 *
 * Single authority deciding whether the runtime is healthy enough to admit NEW live
 * buys. When ANY danger condition is true we enter SELL_ONLY_SAFE_MODE: new live buys
 * are hard-blocked, existing positions may only drain through the controlled sell
 * path. Scanners/watchlist keep running (no discovery loss) — only BUY admission is
 * gated. Self-clearing: reflects live signal state every evaluation, not a sticky
 * latch. A worker-timeout storm (item 9) freezes scanners via the same gate.
 */
object SellOnlySafeMode {

    @Volatile private var pendingSellQueueSize: Int = 0
    @Volatile private var sellReconcilerActiveJobs: Int = 0
    @Volatile private var workerTimeouts: Int = 0
    @Volatile private var orphanLivePositions: Int = 0
    @Volatile private var hostTrackerOpenCount: Int = 0
    @Volatile private var positionStoreOpenCount: Int = 0
    @Volatile private var closedWithNonDustBalance: Int = 0
    @Volatile private var staleLivePriceExitActive: Boolean = false
    @Volatile private var lastSignalMs: Long = 0L

    // V5.9.1561 — DELTA-WINDOW workerTimeout tracker (operator regression 5.0.3659).
    // ───────────────────────────────────────────────────────────────────────────
    // Bug: workerTimeouts is the CUMULATIVE session counter
    // (LIFECYCLE/SUPERVISOR_WORKER_TIMEOUT) which monotonically increases for the
    // lifetime of the run. Comparing it to a static threshold (>5) meant safe
    // mode latched ON forever once the bot accumulated 6 timeouts — typically
    // within the first few minutes of any session — and EVERY live buy was hard-
    // blocked at LiveBuyAdmissionGate for the rest of the session.
    // Operator dump 5.0.3659: workerTimeout=14, FDG_LIVE_ALLOW=95, EXEC_LIVE_ATTEMPT=0.
    //
    // Fix: track a sliding-window DELTA. Storm = ≥ STORM_THRESHOLD new timeouts
    // within STORM_WINDOW_MS. A transient bad-API burst back-pressures live for
    // ~90s, then self-clears once the burst stops. Cumulative count no longer
    // matters — only RECENT rate does.
    private const val STORM_WINDOW_MS = 90_000L
    private const val STORM_THRESHOLD = 5
    @Volatile private var prevWorkerTimeouts: Int = 0
    @Volatile private var stormWindowStartMs: Long = 0L
    @Volatile private var stormWindowDelta: Int = 0

    const val WORKER_TIMEOUT_THRESHOLD = STORM_THRESHOLD  // kept for external probes
    private const val SIGNAL_TTL_MS = 30_000L

    private val _enterCount = AtomicLong(0L)
    private val _blockedBuys = AtomicLong(0L)
    @Volatile private var _active = false
    @Volatile private var _lastReasons: List<String> = emptyList()

    val active: Boolean get() = _active
    val lastReasons: List<String> get() = _lastReasons
    fun blockedBuyCount(): Long = _blockedBuys.get()

    fun updateSignals(
        pendingSellQueue: Int,
        activeJobs: Int,
        workerTimeoutCount: Int,
        orphanLive: Int,
        hostOpen: Int,
        storeOpen: Int,
        closedNonDust: Int,
        staleLivePriceExit: Boolean,
    ) {
        pendingSellQueueSize = pendingSellQueue
        sellReconcilerActiveJobs = activeJobs
        // V5.9.1561 — delta-window storm tracking; see field doc.
        val now = System.currentTimeMillis()
        val delta = (workerTimeoutCount - prevWorkerTimeouts).coerceAtLeast(0)
        prevWorkerTimeouts = workerTimeoutCount
        if (stormWindowStartMs == 0L || (now - stormWindowStartMs) > STORM_WINDOW_MS) {
            // Window expired — start fresh with the new delta.
            stormWindowStartMs = now
            stormWindowDelta = delta
        } else {
            stormWindowDelta += delta
        }
        workerTimeouts = stormWindowDelta  // what recompute() / workerTimeoutStorm() will read
        orphanLivePositions = orphanLive
        hostTrackerOpenCount = hostOpen
        positionStoreOpenCount = storeOpen
        closedWithNonDustBalance = closedNonDust
        staleLivePriceExitActive = staleLivePriceExit
        lastSignalMs = now
        recompute()
    }

    // V5.0.3919 — EXECUTION-ONLY ALLOWLIST. The labels HealthAwareHttp +
    // SolanaMarketScanner actually write via ApiBackoff.markFailure are the
    // SHORT host labels emitted by hostLabel() ("pumpfun", "pumpportal",
    // "helius", "solana_rpc", "dexscreener", "geckoterminal", "birdeye",
    // "jupiter", "groq", "gemini", "coingecko", "pyth"). The pre-3919 long-
    // DNS keys (e.g. "pumpportal.fun", "mainnet.helius-rpc.com") matched
    // NOTHING — providerBackoff was dead. Worse, any future writer using
    // the long name would have parked the entire live buy path on a
    // scanner-only outage. Explicit allowlist keeps the check scoped to
    // execution venues (buy/finality authorities). Scanner-only labels
    // (dexscreener / geckoterminal / birdeye / coingecko / pyth / groq /
    // gemini / jupiter quote backoff) MUST NEVER block live buys.
    private val executionProviderLabels = arrayOf(
        "pumpportal",
        "pumpfun",
        "helius",
        "solana_rpc",
    )

    @Volatile private var _lastProviderBackoffHost: String? = null
    val lastProviderBackoffHost: String? get() = _lastProviderBackoffHost

    private fun providerBackoffActive(): Boolean {
        return try {
            val ab = com.lifecyclebot.engine.ApiBackoff
            val hit = executionProviderLabels.firstOrNull { ab.isLockedOut(it) }
            _lastProviderBackoffHost = hit
            hit != null
        } catch (_: Throwable) { false }
    }

    fun workerTimeoutStorm(): Boolean = workerTimeouts > WORKER_TIMEOUT_THRESHOLD

    private fun recompute() {
        val fresh = (System.currentTimeMillis() - lastSignalMs) < SIGNAL_TTL_MS
        val reasons = ArrayList<String>(6)
        if (fresh) {
            // V5.0.3911 — drain-mode reasons only block buys when there is actual
            // live exposure to drain/reconcile. Report 3909 had SELL_ONLY_SAFE_MODE
            // blocks with hostOpen=0/storeOpen=0/orphan=0 and sell OKs draining old
            // work; activeJobs/pendingQueue alone then became a global live-buy choke.
            // Keep real danger reasons below hard-blocking, but don't let stale/empty
            // sell work park new live entries when no live position exists.
            val liveExposureToDrain = hostTrackerOpenCount > 0 || positionStoreOpenCount > 0 || orphanLivePositions > 0 || closedWithNonDustBalance > 0 || staleLivePriceExitActive
            if (liveExposureToDrain && pendingSellQueueSize > 0) reasons += "pendingSellQueue=$pendingSellQueueSize"
            if (liveExposureToDrain && sellReconcilerActiveJobs > 0) reasons += "activeJobs=$sellReconcilerActiveJobs"
            // V5.9.1561 — message uses window delta now, not lifetime cumulative.
            if (workerTimeoutStorm()) reasons += "workerTimeoutStorm=$workerTimeouts(>${STORM_THRESHOLD})/90s"
            if (orphanLivePositions > 0) reasons += "orphanLive=$orphanLivePositions"
            // V5.0.3685 — P0: exact equality is too brittle (normal RPC-confirm delay
            // can put these off by 1 transiently). Require diff > 1 to flag mismatch.
            if (kotlin.math.abs(hostTrackerOpenCount - positionStoreOpenCount) > 1)
                reasons += "openCountMismatch host=$hostTrackerOpenCount store=$positionStoreOpenCount"
            // V5.0.3685 — P0: 1 closed-with-dust entry is common during normal settlement
            // (RPC lag between SELL_TX_PARSE_OK and balance zero). Require > 1 to trigger.
            if (closedWithNonDustBalance > 1) reasons += "closedWithNonDust=$closedWithNonDustBalance"
            if (staleLivePriceExitActive) reasons += "staleLivePriceExit"
        }
        if (providerBackoffActive()) reasons += "providerBackoff=${_lastProviderBackoffHost ?: "unknown"}"

        val nowActive = reasons.isNotEmpty()
        if (nowActive && !_active) {
            _enterCount.incrementAndGet()
            try { ForensicLogger.lifecycle("SELL_ONLY_SAFE_MODE_ENTER", "reasons=${reasons.joinToString(",")}") } catch (_: Throwable) {}
        } else if (!nowActive && _active) {
            try { ForensicLogger.lifecycle("SELL_ONLY_SAFE_MODE_CLEAR", "drainComplete=true") } catch (_: Throwable) {}
        }
        _active = nowActive
        _lastReasons = reasons
    }

    fun blockLiveBuyReason(): String? {
        if (!_active) return null
        _blockedBuys.incrementAndGet()
        return "SELL_ONLY_SAFE_MODE active: ${_lastReasons.joinToString(",")}"
    }
}
