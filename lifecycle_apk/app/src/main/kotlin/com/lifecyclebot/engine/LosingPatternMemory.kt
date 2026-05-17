package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.806 — Pattern-based pre-emptive defence.
 *
 * The operator's question to the bot: "you have all this architecture,
 * why are you reactive at -10% instead of predictive at -2%?"
 *
 * Answer for this module: because the bot didn't remember which feature
 * BUCKETS had bled before. This module changes that. Every closed losing
 * trade (pnlPct ≤ -5%) is hashed into a coarse feature bucket. New
 * candidates lookup their own bucket; if recent losses on that bucket
 * exceed a threshold, the candidate is flagged "danger-zone".
 *
 * Buckets are tradingMode × scoreBand (both fields are present on the
 * stored Trade record so we can reconstruct buckets purely from the
 * journal, no new persistence required):
 *
 *   tradingMode: STANDARD / TREASURY / BLUE_CHIP / WHALE_FOLLOW /
 *                COPY_TRADE / PRESALE_SNIPE / MOONSHOT / SHITCOIN / …
 *   scoreBand:   S0-10 / S11-25 / S26-40 / S41-60 / S61+
 *
 * Read-only over TradeHistoryStore. Aggregation is lazy + memoised for 60s
 * so sub-trader hot paths don't pay an aggregation cost on every call.
 *
 * The output is ADVISORY — sub-traders and exit logic CONSULT this
 * module, they don't have to obey it.
 */
object LosingPatternMemory {

    data class BucketStats(val losses: Int, val wins: Int, val meanPnl: Double) {
        val sample: Int get() = losses + wins
        val isDangerous: Boolean get() = losses >= 5 && (losses.toDouble() / sample.coerceAtLeast(1)) >= 0.75
    }

    @Volatile private var cache: Map<String, BucketStats> = emptyMap()
    @Volatile private var cacheBuiltAtMs: Long = 0L
    private const val CACHE_TTL_MS = 60_000L

    fun bucketKey(tradingMode: String, score: Int): String {
        val modeKey = tradingMode.ifBlank { "UNKNOWN" }.take(14)
        val scoreBand = when {
            score <= 10  -> "S0-10"
            score <= 25  -> "S11-25"
            score <= 40  -> "S26-40"
            score <= 60  -> "S41-60"
            else         -> "S61+"
        }
        return "$modeKey|$scoreBand"
    }

    private fun refreshIfStale() {
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && (now - cacheBuiltAtMs) < CACHE_TTL_MS) return

        val acc = ConcurrentHashMap<String, IntArray>(64)  // [losses, wins, sumPnlx1000]
        try {
            val sells = TradeHistoryStore.getAllTrades()
                .asSequence()
                .filter { it.side.equals("SELL", ignoreCase = true) }
                .sortedByDescending { it.ts }
                .take(2_000)  // last 2k closes — enough for stable buckets
                .toList()

            for (t in sells) {
                val key = bucketKey(tradingMode = t.tradingMode, score = t.score.toInt())
                val cell = acc.getOrPut(key) { IntArray(3) }
                if (t.pnlPct <= -5.0) cell[0]++
                else if (t.pnlPct >= 1.0) cell[1]++
                cell[2] += (t.pnlPct * 1000).toInt()
            }
        } catch (_: Throwable) {
            // tolerate journal read errors; fall back to whatever cache we have
        }

        val fresh = HashMap<String, BucketStats>(acc.size)
        for ((k, v) in acc) {
            val sample = v[0] + v[1]
            val mean = if (sample > 0) v[2].toDouble() / 1000.0 / sample else 0.0
            fresh[k] = BucketStats(losses = v[0], wins = v[1], meanPnl = mean)
        }
        cache = fresh
        cacheBuiltAtMs = now
    }

    /**
     * Look up a candidate's bucket and return whether recent history says
     * this combination has bled.
     */
    fun stats(tradingMode: String, v3Score: Int): BucketStats {
        refreshIfStale()
        val key = bucketKey(tradingMode, v3Score)
        return cache[key] ?: BucketStats(0, 0, 0.0)
    }

    fun isDangerZone(tradingMode: String, v3Score: Int): Boolean = stats(tradingMode, v3Score).isDangerous

    /**
     * Recommended SL pct override for a position entering a danger bucket.
     * Returns null when the bucket isn't dangerous (caller keeps its own SL).
     */
    fun recommendedSlPct(tradingMode: String, v3Score: Int): Double? {
        val s = stats(tradingMode, v3Score)
        if (!s.isDangerous) return null
        return when {
            s.losses >= 20 -> -3.0   // very high-loss bucket — tight stop
            s.losses >= 10 -> -5.0
            else           -> -7.0
        }
    }

    /** Pipeline-health summary block. */
    fun formatForPipelineDump(): String {
        refreshIfStale()
        if (cache.isEmpty()) return ""
        val dangerous = cache.entries.filter { it.value.isDangerous }
            .sortedByDescending { it.value.losses }
        if (dangerous.isEmpty()) {
            return "\n===== Losing-pattern memory (V5.9.806) =====\n" +
                   "  ✅ No bucket has crossed the danger threshold (≥5 losses, ≥75% loss rate)\n"
        }
        val sb = StringBuilder("\n===== Losing-pattern memory (V5.9.806) =====\n")
        sb.append("  Danger buckets (≥5 losses, ≥75% loss rate). TradingMode × ScoreBand:\n")
        dangerous.take(10).forEach { (k, v) ->
            sb.append("    %-26s  losses=%-3d  wins=%-3d  meanPnl=%+.2f%%\n".format(k, v.losses, v.wins, v.meanPnl))
        }
        return sb.toString()
    }
}
