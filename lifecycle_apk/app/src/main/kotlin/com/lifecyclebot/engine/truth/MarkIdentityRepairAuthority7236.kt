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
                // V5.0.7298 — ask every feed at once first. The cascade returns
                // the first provider that answers, which for an identity-broken
                // mark is often the very feed that produced the bad tick; the
                // parallel fan-out only answers with a price the feeds do not
                // contest (a corroborated cluster, or a single feed alone).
                val bare7298 = mint.removePrefix("solana|").trim()
                val fan7298 = if (bare7298.isBlank() || bare7298.contains('|')) null else try {
                    com.lifecyclebot.network.ParallelMarkFanout7088.resolve7088(listOf(bare7298))[bare7298]
                        ?.takeIf { !(it.sourceCount >= 2 && !it.corroborated) && it.priceUsd.isFinite() && it.priceUsd > 0.0 }
                } catch (_: Throwable) { null }
                if (fan7298 != null) {
                    val label7298 = if (fan7298.corroborated) "FANOUT_CORROBORATED_7088_x${fan7298.agreeingCount}" else "FANOUT_UNCORROBORATED_7088"
                    cache[mint] = Repaired(fan7298.priceUsd, label7298, System.currentTimeMillis())
                    try { MarkIdentityExecutionGate7230.markRepairedUsable7243(mint) } catch (_: Throwable) {}
                    repairSucceeded.incrementAndGet()
                    try { PipelineHealthCollector.labelInc("MARK_REPAIR_SUCCEEDED_7236_FANOUT_7298") } catch (_: Throwable) {}
                    return@launch
                }
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
    /**
     * V5.0.7301 §AGREEING_FEEDS_ARE_NOT_A_FILL.
     *
     * 7298 accepted an absurd multiple whenever the repaired price agreed with
     * the mark. On 5.0.7300 that fired 7,171 times: two price feeds (a pool
     * aggregator and a DEX pool list) agreed that WOTF/NTDA/WWR sat 1,600x to
     * 31,000x above an entry made at a ~$48k cap. Feeds that read the same
     * broken or thin pool agree with each other; neither is what a sale would
     * receive. The only confirmation now is an executable Jupiter quote —
     * 0.01 SOL routed into the mint, converted with the token's own decimals —
     * i.e. the price a swap actually gets. Debounced per mint, async, cached
     * for [EXEC_FRESH_MS_7301].
     */
    private const val EXEC_FRESH_MS_7301 = 60_000L
    private const val EXEC_DEBOUNCE_MS_7301 = 30_000L
    private const val EXEC_QUOTE_LAMPORTS_7301 = 10_000_000L
    private val executable7301 = ConcurrentHashMap<String, Repaired>()
    private val execAttempt7301 = ConcurrentHashMap<String, Long>()
    private val quoteApi7301 by lazy { com.lifecyclebot.network.JupiterApi("") }

    fun requestExecutableQuote7301(mint: String, tokenDecimals: Int) {
        val bare = mint.removePrefix("solana|").trim()
        if (bare.isBlank() || bare.contains('|') || tokenDecimals !in 0..18) return
        val now = System.currentTimeMillis()
        if (now - (execAttempt7301[mint] ?: 0L) < EXEC_DEBOUNCE_MS_7301) return
        execAttempt7301[mint] = now
        scope.launch {
            try {
                val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
                if (!solUsd.isFinite() || solUsd <= 0.0) return@launch
                val q = quoteApi7301.getQuote(
                    inputMint = com.lifecyclebot.network.JupiterApi.SOL_MINT,
                    outputMint = bare,
                    amountRaw = EXEC_QUOTE_LAMPORTS_7301,
                    slippageBps = 300,
                )
                val tokens = q.outAmount.toDouble() / Math.pow(10.0, tokenDecimals.toDouble())
                val px = if (tokens > 0.0) (EXEC_QUOTE_LAMPORTS_7301 / 1e9 * solUsd) / tokens else 0.0
                if (px.isFinite() && px > 0.0) {
                    executable7301[mint] = Repaired(px, "JUPITER_EXECUTABLE_QUOTE_7301", System.currentTimeMillis())
                    try { PipelineHealthCollector.labelInc("MARK_EXECUTABLE_QUOTE_OK_7301") } catch (_: Throwable) {}
                } else {
                    try { PipelineHealthCollector.labelInc("MARK_EXECUTABLE_QUOTE_EMPTY_7301") } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {
                try { PipelineHealthCollector.labelInc("MARK_EXECUTABLE_QUOTE_NO_ROUTE_7301") } catch (_: Throwable) {}
            }
        }
    }

    fun getExecutablePriceIfFresh7301(mint: String): Double? {
        val e = executable7301[mint] ?: return null
        if (System.currentTimeMillis() - e.tsMs > EXEC_FRESH_MS_7301) return null
        return e.priceUsd
    }

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
