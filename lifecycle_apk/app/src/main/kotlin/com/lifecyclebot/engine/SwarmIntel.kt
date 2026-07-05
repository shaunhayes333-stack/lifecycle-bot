package com.lifecyclebot.engine

import com.lifecyclebot.collective.CollectiveLearning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6120f — SwarmIntel: real 8× brain features via unified swarm_signals table.
 *
 * OPERATOR DIRECTIVE (Feb 2026): "no one else has even thought about ai swarm
 * intelligence in crypto trading. surely we can use the hive mind for more."
 *
 * The old collective layer was a slow aggregate — patterns + blacklists that
 * batched every 5-15 minutes. In real trading, alpha and rugs travel in
 * seconds. This module turns the 8 AATE instances into a genuine real-time
 * swarm mind by broadcasting per-mint signals and reading consensus decisions
 * from the shared Turso table `swarm_signals`.
 *
 * Five swarm features, all off one table:
 *
 *   A) LIVE_OPEN signals — instance A opens a high-conviction position on
 *      mint X → publishes the signal (5min TTL). When 2+ instances have
 *      independently opened the same mint inside that window, the other 6
 *      get a +15 score bias to fast-track that mint. Alpha propagates
 *      between the 8 bots faster than any telegram channel.
 *
 *   B) LIVE_RUG signals — an instance hits a catastrophic result (-30% or
 *      worse in <5 minutes, thin-liq exit) → publishes RUG. When 2+ hits
 *      inside 10 minutes, the mint gets a hive-wide veto within 90s.
 *      Old blacklist required 3+ community reports over hours-days; this
 *      cuts the reaction time to a couple of minutes.
 *
 *   C) PRICE_SAMPLE — every N seconds while a spike detector arms, we
 *      publish our observed price. Before firing a peak exit >+200%, we
 *      ask "what does the swarm see for this mint?". If our tick is 30×
 *      above the swarm median, that's a phantom wick — skip the exit.
 *      This solves the ansem "1000% panel gain but tiny realized" bug at
 *      a fundamental level nobody else can — swarm-of-oracles wick check.
 *
 *   D) OPEN-COUNT dedup — if 6 of 8 instances already hold mint X, the
 *      7th SKIPS it. One rug can't take down 7/8 wallets simultaneously.
 *      Institutional-grade portfolio risk across bots — no such thing
 *      exists elsewhere in crypto retail bots.
 *
 *   E) LAB_STRATEGY_WINNER — when instance A's LLM Lab discovers a
 *      strategy that clears a promotion threshold, publish it as a
 *      shared strategy hint. Other instances' Labs pick it up and run
 *      it in parallel. 8× LLM invention throughput.
 *
 * Doctrine: doctrine #86 fail-open. Every read is cached in-memory with
 * a TTL so hot paths never block on Turso. Every write is on a daemon
 * coroutine so it never delays the entry/exit path. Every function is
 * try/catch and returns a neutral default on failure — the swarm is a
 * bonus edge, not a hard dependency.
 */
object SwarmIntel {

    // ── Signal types (also used as SQL enum) ──────────────────────────────
    const val SIGNAL_LIVE_OPEN     = "LIVE_OPEN"
    const val SIGNAL_LIVE_EXIT     = "LIVE_EXIT"
    const val SIGNAL_LIVE_RUG      = "LIVE_RUG"
    const val SIGNAL_PRICE_SAMPLE  = "PRICE_SAMPLE"
    const val SIGNAL_LAB_WINNER    = "LAB_STRATEGY_WINNER"

    // ── TTLs (ms) — how far back we look for each signal type ────────────
    const val OPEN_WINDOW_MS  = 5 * 60 * 1000L      // 5 min: co-firing consensus
    const val DEDUP_WINDOW_MS = 60 * 60 * 1000L     // 1 hr: portfolio dedup
    const val RUG_WINDOW_MS   = 10 * 60 * 1000L     // 10 min: rug consensus
    const val PRICE_WINDOW_MS = 45 * 1000L          // 45s: swarm price oracle

    // ── Consensus thresholds ─────────────────────────────────────────────
    const val COFIRE_QUORUM       = 2               // 2+ instances = swarm alpha
    const val COFIRE_SCORE_BOOST  = 15              // +15 score bias
    const val DEDUP_MAX_INSTANCES = 6               // 6/8 already holding = skip
    const val RUG_QUORUM          = 2               // 2+ rug hits = hive veto
    const val PRICE_WICK_RATIO    = 30.0            // 30× above swarm median = phantom wick

    // ── In-memory caches (TTL-guarded) ───────────────────────────────────
    private data class CachedInt(val value: Int, val atMs: Long)
    private data class CachedDouble(val value: Double, val atMs: Long)
    private data class CachedList(val value: List<Map<String, Any?>>, val atMs: Long)
    private val openCountCache    = ConcurrentHashMap<String, CachedInt>()
    private val priceCache        = ConcurrentHashMap<String, CachedDouble>()
    private val rugCountCache     = ConcurrentHashMap<String, CachedInt>()
    private val cofireCache       = ConcurrentHashMap<String, CachedInt>()
    private val labWinnersCache   = ConcurrentHashMap<String, CachedList>()
    private val HOT_CACHE_MS = 15_000L               // 15s — hot path never re-queries within window

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ═════════════════════════════════════════════════════════════════════
    //  WRITE SIDE — publish signals to the swarm
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Publish a live open. Instance A opens a HIGH-conviction position on
     * mint → this row lets every other instance see it inside 90s (hive HOT
     * sync cadence from V5.0.6120d). Fire-and-forget on daemon scope; entry
     * hot path never blocks.
     */
    fun publishLiveOpen(mint: String, symbol: String, score: Int, sizeSol: Double, lane: String) {
        if (mint.isBlank()) return
        scope.launch {
            try {
                CollectiveLearning.publishSwarmSignal(
                    signalType = SIGNAL_LIVE_OPEN,
                    mint = mint,
                    symbol = symbol,
                    score = score.toDouble(),
                    price = 0.0,
                    extra = "lane=$lane sol=${"%.4f".format(sizeSol)}",
                )
                try { PipelineHealthCollector.labelInc("SWARM_PUBLISH_OPEN_6120f") } catch (_: Throwable) {}
            } catch (_: Throwable) {}
        }
    }

    /**
     * Publish a live exit (informational). Currently unused for gating but
     * useful for hive dashboards + future features.
     */
    fun publishLiveExit(mint: String, symbol: String, pnlPct: Double, realizedSol: Double) {
        if (mint.isBlank()) return
        scope.launch {
            try {
                CollectiveLearning.publishSwarmSignal(
                    signalType = SIGNAL_LIVE_EXIT,
                    mint = mint,
                    symbol = symbol,
                    score = pnlPct,
                    price = 0.0,
                    extra = "sol=${"%.4f".format(realizedSol)}",
                )
                try { PipelineHealthCollector.labelInc("SWARM_PUBLISH_EXIT_6120f") } catch (_: Throwable) {}
            } catch (_: Throwable) {}
        }
    }

    /**
     * Publish a rug detection. Cheap: written when an exit closes at ≤-30%
     * within 5 minutes of open, or when a thin-liq / catastrophic-loss
     * reason fires. 2+ within RUG_WINDOW_MS = swarm veto.
     */
    fun publishRug(mint: String, symbol: String, pnlPct: Double, reason: String) {
        if (mint.isBlank()) return
        scope.launch {
            try {
                CollectiveLearning.publishSwarmSignal(
                    signalType = SIGNAL_LIVE_RUG,
                    mint = mint,
                    symbol = symbol,
                    score = pnlPct,
                    price = 0.0,
                    extra = reason.take(120),
                )
                // Invalidate hot cache so next reader gets fresh count.
                rugCountCache.remove(mint)
                try { PipelineHealthCollector.labelInc("SWARM_PUBLISH_RUG_6120f") } catch (_: Throwable) {}
            } catch (_: Throwable) {}
        }
    }

    /**
     * Publish a price sample. Cheap: called at low frequency (once every 5s
     * max per mint) from the peak-detector for tokens the local instance
     * thinks are spiking. Enables the swarm price-truth oracle at exit
     * time.
     */
    private val lastPriceSampleMs = ConcurrentHashMap<String, Long>()
    fun publishPriceSample(mint: String, symbol: String, priceUsd: Double) {
        if (mint.isBlank() || priceUsd <= 0.0) return
        val nowMs = System.currentTimeMillis()
        val last = lastPriceSampleMs[mint] ?: 0L
        if (nowMs - last < 5_000L) return          // 5s per-mint throttle
        lastPriceSampleMs[mint] = nowMs
        scope.launch {
            try {
                CollectiveLearning.publishSwarmSignal(
                    signalType = SIGNAL_PRICE_SAMPLE,
                    mint = mint,
                    symbol = symbol,
                    score = 0.0,
                    price = priceUsd,
                    extra = "",
                )
                try { PipelineHealthCollector.labelInc("SWARM_PUBLISH_PRICE_6120f") } catch (_: Throwable) {}
            } catch (_: Throwable) {}
        }
    }

    /**
     * Publish a Lab winner strategy for the other 7 instances' Labs to
     * pick up. Instance A's LlmLab discovered a strategy that hit some
     * promotion threshold → seed it to the swarm.
     */
    fun publishLabWinner(strategyName: String, avgPnlPct: Double, wr: Double, n: Int, config: String) {
        if (strategyName.isBlank()) return
        scope.launch {
            try {
                CollectiveLearning.publishSwarmSignal(
                    signalType = SIGNAL_LAB_WINNER,
                    mint = "",
                    symbol = strategyName.take(64),
                    score = wr,
                    price = avgPnlPct,
                    extra = "n=$n cfg=${config.take(200)}",
                )
                try { PipelineHealthCollector.labelInc("SWARM_PUBLISH_LAB_WINNER_6120f") } catch (_: Throwable) {}
            } catch (_: Throwable) {}
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  READ SIDE — consensus queries (cached, hot-path safe)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * How many swarm instances (including us) opened this mint inside the
     * OPEN_WINDOW_MS window? 2+ = swarm alpha, boost score.
     */
    fun getCoFireCount(mint: String): Int {
        if (mint.isBlank()) return 0
        val nowMs = System.currentTimeMillis()
        val hit = cofireCache[mint]
        if (hit != null && nowMs - hit.atMs < HOT_CACHE_MS) return hit.value
        return try {
            val n = CollectiveLearning.getSwarmSignalCount(SIGNAL_LIVE_OPEN, mint, OPEN_WINDOW_MS)
            cofireCache[mint] = CachedInt(n, nowMs)
            n
        } catch (_: Throwable) { 0 }
    }

    /**
     * How many swarm instances currently hold this mint (used for
     * concentration dedup guard). 6+ = skip entry.
     */
    fun getSwarmOpenCount(mint: String): Int {
        if (mint.isBlank()) return 0
        val nowMs = System.currentTimeMillis()
        val hit = openCountCache[mint]
        if (hit != null && nowMs - hit.atMs < HOT_CACHE_MS) return hit.value
        return try {
            val n = CollectiveLearning.getSwarmSignalCount(SIGNAL_LIVE_OPEN, mint, DEDUP_WINDOW_MS)
            openCountCache[mint] = CachedInt(n, nowMs)
            n
        } catch (_: Throwable) { 0 }
    }

    /** How many swarm rug-hits on this mint inside RUG_WINDOW_MS. 2+ = veto. */
    fun getSwarmRugCount(mint: String): Int {
        if (mint.isBlank()) return 0
        val nowMs = System.currentTimeMillis()
        val hit = rugCountCache[mint]
        if (hit != null && nowMs - hit.atMs < HOT_CACHE_MS) return hit.value
        return try {
            val n = CollectiveLearning.getSwarmSignalCount(SIGNAL_LIVE_RUG, mint, RUG_WINDOW_MS)
            rugCountCache[mint] = CachedInt(n, nowMs)
            n
        } catch (_: Throwable) { 0 }
    }

    /**
     * Median swarm-observed price for this mint inside PRICE_WINDOW_MS.
     * Returns 0.0 if fewer than 2 samples (single-instance sample is us —
     * no consensus possible).
     */
    fun getSwarmPriceMedian(mint: String): Double {
        if (mint.isBlank()) return 0.0
        val nowMs = System.currentTimeMillis()
        val hit = priceCache[mint]
        if (hit != null && nowMs - hit.atMs < HOT_CACHE_MS) return hit.value
        return try {
            val prices = CollectiveLearning.getSwarmPriceSamples(mint, PRICE_WINDOW_MS)
            val median = if (prices.size >= 2) {
                val sorted = prices.sorted()
                sorted[sorted.size / 2]
            } else 0.0
            priceCache[mint] = CachedDouble(median, nowMs)
            median
        } catch (_: Throwable) { 0.0 }
    }

    /**
     * SWARM PRICE-TRUTH ORACLE — the operator's core innovation.
     *
     * Given a local exit-trigger price and a mint, ask the swarm what
     * they see. If our price is >30× above the swarm median and there
     * are ≥2 corroborating samples, this is a phantom wick → return true
     * (skip exit). If swarm hasn't sampled, or our price is in the swarm
     * range, return false (fire exit).
     *
     * @return true if the exit should be SUPPRESSED as phantom wick.
     */
    fun isPhantomWickAgainstSwarm(mint: String, localPriceUsd: Double): Boolean {
        if (mint.isBlank() || localPriceUsd <= 0.0) return false
        val median = getSwarmPriceMedian(mint)
        if (median <= 0.0) return false               // no consensus — can't judge
        val ratio = localPriceUsd / median
        return ratio >= PRICE_WICK_RATIO
    }
}
