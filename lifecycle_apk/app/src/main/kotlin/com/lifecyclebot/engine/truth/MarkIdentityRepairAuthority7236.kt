package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7236 §MARK_IDENTITY_REPAIR_AUTHORITY — operator directive:
 *
 *   "Mark Identity everywhere: it needs to CORRECT the data so the stop
 *    can fire correctly. Don't just skip."
 *
 * PURPOSE — when TokenMetricsAuthority7069 stamps a mark as
 * identity-broken (implied vs reported price disagreement, unverifiable
 * cascade), the exit path historically had two bad options:
 *   1. Fire the stop with a corrupt currentPrice → catastrophic false
 *      -N% closures on -1.5% market moves (EYMBTN 5.0.7229).
 *   2. Skip the stop entirely → a real -20% move goes unstopped.
 *
 * Neither option lifts trade quality. The correct third option is to
 * REPAIR the mark before firing: consult the PriceResolverFallback
 * cascade (DexScreener → GeckoTerminal → Jupiter price → Raydium →
 * Pump.fun) for a corroborated fresh price, cache it, and expose it to
 * consumers. If a repair succeeds within the staleness window, exit
 * evaluators use the repaired price rather than the broken one.
 *
 * SCOPE — additive; never modifies the observed mark in place, only
 * publishes a repaired shadow-price consumers may query.
 *
 * TIMING — repair is fire-and-forget from the observation site so the
 * hot path never blocks on network I/O. The repaired value is available
 * to the NEXT tick of the exit evaluator, which is <5s. For a token
 * whose mark just went identity-broken, that is fast enough — the
 * repair races the next stop evaluation, not the market.
 */
object MarkIdentityRepairAuthority7236 {

    /** Cached repaired price is trusted for this window. */
    private const val REPAIR_FRESH_MS: Long = 30_000L

    /** Repair attempts for the same mint are debounced. */
    private const val REPAIR_DEBOUNCE_MS: Long = 3_000L

    private data class Repaired(
        val priceUsd: Double,
        val source: String,
        val tsMs: Long,
    )

    private val cache = ConcurrentHashMap<String, Repaired>()
    private val lastAttempt = ConcurrentHashMap<String, Long>()

    private val repairAttempts = AtomicLong(0L)
    private val repairSucceeded = AtomicLong(0L)
    private val repairFailed = AtomicLong(0L)
    private val cacheHits = AtomicLong(0L)
    private val cacheMissesStale = AtomicLong(0L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Called by TokenMetricsAuthority7069 (or any other observation site)
     * when a mark's identity is broken. Fires an async cascade fetch so
     * the next evaluation tick can see a corroborated price. Never
     * blocks.
     */
    fun requestRepair(mint: String, context: String) {
        if (mint.isBlank()) return
        val now = System.currentTimeMillis()
        val prev = lastAttempt[mint] ?: 0L
        if (now - prev < REPAIR_DEBOUNCE_MS) return
        lastAttempt[mint] = now
        repairAttempts.incrementAndGet()
        try { PipelineHealthCollector.labelInc("MARK_REPAIR_REQUESTED_7236") } catch (_: Throwable) {}
        scope.launch {
            try {
                val solUsd = try {
                    com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
                } catch (_: Throwable) { 0.0 }
                val resolved = com.lifecyclebot.engine.sell.PriceResolverFallback.resolve(mint, solUsd)
                if (resolved != null && resolved.priceUsd.isFinite() && resolved.priceUsd > 0.0) {
                    // A cross-source cascade returned a positive price.
                    // The cascade internally rotates on health, so a
                    // returned value already carries at least one live
                    // corroboration.
                    cache[mint] = Repaired(resolved.priceUsd, resolved.source.name, System.currentTimeMillis())
                    try { MarkIdentityExecutionGate7230.markRepairedUsable7243(mint) } catch (_: Throwable) {}
                    repairSucceeded.incrementAndGet()
                    try {
                        PipelineHealthCollector.labelInc("MARK_REPAIR_SUCCEEDED_7236")
                        PipelineHealthCollector.labelInc("MARK_REPAIR_SUCCEEDED_7236_${resolved.source.name}")
                        if (repairSucceeded.get() % 25L == 1L) {
                            ForensicLogger.lifecycle(
                                "MARK_REPAIR_SUCCEEDED_7236",
                                "mint=${mint.take(10)} src=${resolved.source.name} " +
                                    "priceUsd=${"%.10g".format(resolved.priceUsd)} " +
                                    "context=${context.take(96)} " +
                                    "action=cached_for_next_exit_evaluation_tick",
                            )
                        }
                    } catch (_: Throwable) {}
                } else {
                    repairFailed.incrementAndGet()
                    try { PipelineHealthCollector.labelInc("MARK_REPAIR_FAILED_7236") } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {
                repairFailed.incrementAndGet()
                try { PipelineHealthCollector.labelInc("MARK_REPAIR_FAILED_7236") } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Consult before rejecting an exit on `CURRENT_PRICE_INVALID` or
     * before deferring on `IDENTITY_BROKEN`. Returns the repaired
     * priceUsd when a fresh (<REPAIR_FRESH_MS) corroborated cache entry
     * exists; null otherwise.
     */
    fun getRepairedPriceIfFresh(mint: String): Double? {
        if (mint.isBlank()) return null
        val entry = cache[mint] ?: return null
        val age = (System.currentTimeMillis() - entry.tsMs).coerceAtLeast(0L)
        if (age > REPAIR_FRESH_MS) {
            cacheMissesStale.incrementAndGet()
            try { PipelineHealthCollector.labelInc("MARK_REPAIR_CACHE_STALE_7236") } catch (_: Throwable) {}
            return null
        }
        cacheHits.incrementAndGet()
        try { PipelineHealthCollector.labelInc("MARK_REPAIR_CACHE_HIT_7236") } catch (_: Throwable) {}
        return entry.priceUsd
    }

    /**
     * Returns the source label of the most recent repair for this mint
     * (never null; "" when no repair known). Used for forensic logging
     * from callers.
     */
    fun getRepairedSource(mint: String): String {
        val entry = cache[mint] ?: return ""
        val age = (System.currentTimeMillis() - entry.tsMs).coerceAtLeast(0L)
        if (age > REPAIR_FRESH_MS) return ""
        return entry.source
    }

    data class Summary(
        val requested: Long,
        val succeeded: Long,
        val failed: Long,
        val cacheHits: Long,
        val cacheMissesStale: Long,
        val cacheSize: Int,
    )

    fun summary(): Summary = Summary(
        requested = repairAttempts.get(),
        succeeded = repairSucceeded.get(),
        failed = repairFailed.get(),
        cacheHits = cacheHits.get(),
        cacheMissesStale = cacheMissesStale.get(),
        cacheSize = cache.size,
    )

    fun statusLine(): String {
        val s = summary()
        return "MarkIdentityRepairAuthority7236 requested=${s.requested} " +
            "ok=${s.succeeded} fail=${s.failed} " +
            "cacheHits=${s.cacheHits} cacheStale=${s.cacheMissesStale} cacheSize=${s.cacheSize}"
    }

    internal fun clearForTest() {
        cache.clear(); lastAttempt.clear()
        repairAttempts.set(0L); repairSucceeded.set(0L); repairFailed.set(0L)
        cacheHits.set(0L); cacheMissesStale.set(0L)
    }
}
