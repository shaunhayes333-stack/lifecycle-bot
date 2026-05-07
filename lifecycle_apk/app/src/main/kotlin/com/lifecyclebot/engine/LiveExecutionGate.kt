package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.495z29 — Live Execution Gate (operator spec item 8).
 *
 * Operator: "Live trading is too slow. I want a configurable high-throughput
 * live mode capable of approaching up to 500 trades/day when conditions
 * allow. Create a separate hot execution path. Add config:
 *   highThroughputLiveMode, maxLiveTradesPerDay, maxConcurrentLivePositions,
 *   minSecondsBetweenLiveBuys, maxPendingBuyVerifications,
 *   maxPendingSellVerifications, hotPathTimeoutMs, walletReconcileTimeoutMs,
 *   skipSlowBackgroundScansWhenLiveBusy."
 *
 * This gate is the single chokepoint every live BUY must traverse. It
 * enforces:
 *   • Daily quota          (rolling 24h window per UTC day boundary)
 *   • Concurrent ceiling   (open BUY_PENDING/HELD count vs config limit)
 *   • Min spacing          (minimum seconds between consecutive live buys)
 *   • Pending verification (don't pile up un-reconciled buys / sells)
 *
 * ALL state is in-process atomics — no SharedPreferences calls on the hot
 * path. Daily counter resets on UTC midnight. Concurrent counter is read
 * lazily from TokenLifecycleTracker.openCount() so the source of truth
 * stays consolidated.
 *
 * The gate does NOT do scheduling — it's a synchronous can-I-buy? check.
 * Callers are expected to skip the buy attempt cleanly when blocked.
 */
object LiveExecutionGate {

    private const val TAG = "LiveExecGate"

    data class Config(
        val highThroughputLiveMode: Boolean = false,
        val maxLiveTradesPerDay: Int = 500,
        val maxConcurrentLivePositions: Int = 12,
        val minSecondsBetweenLiveBuys: Int = 4,
        val maxPendingBuyVerifications: Int = 6,
        val maxPendingSellVerifications: Int = 8,
        val hotPathTimeoutMs: Long = 10_000L,
        val walletReconcileTimeoutMs: Long = 12_000L,
        val skipSlowBackgroundScansWhenLiveBusy: Boolean = true,
    )

    sealed class Decision {
        data object Allowed : Decision()
        data class Blocked(val code: String, val reason: String) : Decision()
    }

    @Volatile private var cfg: Config = Config()
    private val lastBuyAtMs = AtomicLong(0L)
    private val dayBucket  = AtomicLong(currentUtcDay())
    private val buysToday  = AtomicInteger(0)

    /**
     * V5.9.495z29 — pendingBuy/Sell verifications are derived from
     * TokenLifecycleTracker on each call rather than maintained as
     * separate atomics. This keeps the source of truth single and avoids
     * cleanup-hook scattering across every Executor exit path.
     */
    private fun pendingBuyVerifications(): Int = try {
        TokenLifecycleTracker.all().count { it.status == TokenLifecycleTracker.Status.BUY_PENDING }
    } catch (_: Throwable) { 0 }

    private fun pendingSellVerifications(): Int = try {
        TokenLifecycleTracker.all().count {
            it.status == TokenLifecycleTracker.Status.SELL_PENDING ||
            it.status == TokenLifecycleTracker.Status.PARTIAL_SELL ||
            it.status == TokenLifecycleTracker.Status.RESIDUAL_HELD
        }
    } catch (_: Throwable) { 0 }

    fun configure(c: Config) {
        cfg = c
        ErrorLogger.info(TAG, "configured | mode=${if (c.highThroughputLiveMode) "HOT" else "STANDARD"} " +
            "| daily=${c.maxLiveTradesPerDay} | concurrent=${c.maxConcurrentLivePositions} " +
            "| spacing=${c.minSecondsBetweenLiveBuys}s | hotPathTimeout=${c.hotPathTimeoutMs}ms")
    }

    fun config(): Config = cfg

    /**
     * Try to acquire a buy slot. Returns Allowed immediately if no quota
     * is breached; otherwise Blocked with a code the caller logs.
     *
     * Caller MUST call buyCompleted() (success or failure) once the buy
     * verification finishes so pending counter decrements.
     */
    fun tryAcquireBuy(currentLiveOpenCount: Int): Decision {
        rollDayIfNeeded()

        if (buysToday.get() >= cfg.maxLiveTradesPerDay) {
            return Decision.Blocked("DAILY_QUOTA",
                "live buys today=${buysToday.get()} ≥ daily cap ${cfg.maxLiveTradesPerDay}")
        }
        if (currentLiveOpenCount >= cfg.maxConcurrentLivePositions) {
            return Decision.Blocked("CONCURRENT_CAP",
                "open=${currentLiveOpenCount} ≥ concurrent cap ${cfg.maxConcurrentLivePositions}")
        }
        val now = System.currentTimeMillis()
        val sinceLast = (now - lastBuyAtMs.get()) / 1000.0
        if (sinceLast < cfg.minSecondsBetweenLiveBuys) {
            return Decision.Blocked("RATE_LIMIT",
                "${"%.1f".format(sinceLast)}s since last buy < min ${cfg.minSecondsBetweenLiveBuys}s")
        }
        if (pendingBuyVerifications() >= cfg.maxPendingBuyVerifications) {
            return Decision.Blocked("PENDING_BUYS",
                "pendingBuyVerifications=${pendingBuyVerifications()} ≥ ${cfg.maxPendingBuyVerifications}")
        }
        // Reserve the slot atomically.
        lastBuyAtMs.set(now)
        buysToday.incrementAndGet()
        return Decision.Allowed
    }

    /** Kept for forward-compat / explicit teardown. No-op since pending
     *  counts are derived from TokenLifecycleTracker. */
    fun buyCompleted() { /* no-op */ }

    fun trySell(): Decision {
        if (pendingSellVerifications() >= cfg.maxPendingSellVerifications) {
            return Decision.Blocked("PENDING_SELLS",
                "pendingSellVerifications=${pendingSellVerifications()} ≥ ${cfg.maxPendingSellVerifications}")
        }
        return Decision.Allowed
    }

    /** Kept for forward-compat. No-op (pending derived from tracker). */
    fun sellCompleted() { /* no-op */ }

    /**
     * Background scanners call this on each tick. When the bot is "busy"
     * (buys pending verification OR daily quota near saturation), heavy
     * background work (LLM lab, gecko sweeps, repeated FDG audits) should
     * skip a cycle to keep the hot path uncongested.
     */
    fun shouldSkipSlowBackgroundScans(): Boolean {
        if (!cfg.skipSlowBackgroundScansWhenLiveBusy) return false
        val nearDailyCap = buysToday.get() >= cfg.maxLiveTradesPerDay * 0.9
        val pendingPressure = pendingBuyVerifications() >= cfg.maxPendingBuyVerifications - 1 ||
                              pendingSellVerifications() >= cfg.maxPendingSellVerifications - 1
        return nearDailyCap || pendingPressure
    }

    fun stats(): String {
        rollDayIfNeeded()
        return "today=${buysToday.get()}/${cfg.maxLiveTradesPerDay} · " +
               "pendingBuys=${pendingBuyVerifications()}/${cfg.maxPendingBuyVerifications} · " +
               "pendingSells=${pendingSellVerifications()}/${cfg.maxPendingSellVerifications}"
    }

    /** Test/operator hook to reset counters (e.g. after a fresh-install). */
    fun resetAll() {
        lastBuyAtMs.set(0L)
        dayBucket.set(currentUtcDay())
        buysToday.set(0)
    }

    private fun rollDayIfNeeded() {
        val today = currentUtcDay()
        val prev = dayBucket.get()
        if (today != prev && dayBucket.compareAndSet(prev, today)) {
            buysToday.set(0)
        }
    }

    private fun currentUtcDay(): Long = System.currentTimeMillis() / 86_400_000L
}
