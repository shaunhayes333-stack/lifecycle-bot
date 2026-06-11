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

    const val WORKER_TIMEOUT_THRESHOLD = 5
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
        workerTimeouts = workerTimeoutCount
        orphanLivePositions = orphanLive
        hostTrackerOpenCount = hostOpen
        positionStoreOpenCount = storeOpen
        closedWithNonDustBalance = closedNonDust
        staleLivePriceExitActive = staleLivePriceExit
        lastSignalMs = System.currentTimeMillis()
        recompute()
    }

    private fun providerBackoffActive(): Boolean {
        return try {
            val ab = com.lifecyclebot.engine.ApiBackoff
            ab.isLockedOut("quote-api.jup.ag") ||
            ab.isLockedOut("jup.ag") ||
            ab.isLockedOut("pumpportal.fun") ||
            ab.isLockedOut("pump.fun") ||
            ab.isLockedOut("mainnet.helius-rpc.com") ||
            ab.isLockedOut("api.mainnet-beta.solana.com")
        } catch (_: Throwable) { false }
    }

    fun workerTimeoutStorm(): Boolean = workerTimeouts > WORKER_TIMEOUT_THRESHOLD

    private fun recompute() {
        val fresh = (System.currentTimeMillis() - lastSignalMs) < SIGNAL_TTL_MS
        val reasons = ArrayList<String>(6)
        if (fresh) {
            if (pendingSellQueueSize > 0) reasons += "pendingSellQueue=$pendingSellQueueSize"
            if (sellReconcilerActiveJobs > 0) reasons += "activeJobs=$sellReconcilerActiveJobs"
            if (workerTimeoutStorm()) reasons += "workerTimeoutStorm=$workerTimeouts>$WORKER_TIMEOUT_THRESHOLD"
            if (orphanLivePositions > 0) reasons += "orphanLive=$orphanLivePositions"
            if (hostTrackerOpenCount != positionStoreOpenCount)
                reasons += "openCountMismatch host=$hostTrackerOpenCount store=$positionStoreOpenCount"
            if (closedWithNonDustBalance > 0) reasons += "closedWithNonDust=$closedWithNonDustBalance"
            if (staleLivePriceExitActive) reasons += "staleLivePriceExit"
        }
        if (providerBackoffActive()) reasons += "providerBackoff"

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
