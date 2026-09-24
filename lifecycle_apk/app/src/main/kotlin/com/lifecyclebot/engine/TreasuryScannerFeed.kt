package com.lifecyclebot.engine

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 💰 TREASURY SCANNER FEED — V5.0.4599
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Dedicated scanner feed for CashGen/Treasury (the free-trading-engine core).
 * Operator directive 2026-07-02: "it's meant to have its own scanner sources
 * and brains specifically for its designed role in AATE."
 *
 * DESIGN INTENT (per CashGenerationAI.kt docstring):
 * - Target: 100+ scalps/day @ 3-5% profit each
 * - Established, liquid, momentum-carrying tokens (NOT fresh launches)
 * - Runs concurrently as "2nd shadow mode"
 * - Compounds user-added funds into daily cashflow → free-money engine
 *
 * SOURCE FEEDS (does NOT compete with PROJECT_SNIPER for pump.fun stream):
 *   • CoinGecko top-100 SOL tokens (established)
 *   • Birdeye trending established (mcap >$1M, 24h vol >$100K)
 *   • DexScreener top-mover established pools (age >7d, liq >$50K)
 *   • Blue-chip watchlist (JUP/WIF/BONK/JITO/RAY/PYTH)
 *
 * This module publishes a dedicated Treasury watchlist that CashGen polls,
 * separate from the shared memetrader ring. Prevents Treasury from
 * getting starved by pump.fun spam and prevents Treasury tokens from
 * competing for slot budget with fresh launches.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object TreasuryScannerFeed {

    const val VERSION = "V5.0.4599_TREASURY_SCANNER_FEED"

    data class TreasuryCandidate(
        val mint: String,
        val symbol: String,
        val mcap: Double,
        val liquidityUsd: Double,
        val vol24hUsd: Double,
        val source: String,
        val discoveredAt: Long = System.currentTimeMillis(),
    )

    // Established token criteria (Treasury scalps NOT fresh launches)
    const val MIN_TREASURY_MCAP = 1_000_000.0         // $1M+ = established
    const val MIN_TREASURY_LIQUIDITY = 50_000.0       // $50K+ liq = tight spreads
    const val MIN_TREASURY_24H_VOL = 100_000.0        // $100K+ vol = tradeable
    const val MIN_TREASURY_AGE_DAYS = 7               // >7d old = past bond/graduation

    private val watchlist = java.util.concurrent.ConcurrentHashMap<String, TreasuryCandidate>()
    private const val MAX_WATCHLIST = 100

    /**
     * Publish a candidate to the Treasury watchlist. Rejects if:
     *   - mcap too low (looks like a fresh launch)
     *   - liq too thin (spread too wide for 3-5% scalps)
     *   - vol too thin (won't fill on entry/exit)
     */
    fun publishCandidate(c: TreasuryCandidate): Boolean {
        if (c.mcap < MIN_TREASURY_MCAP) return false
        if (c.liquidityUsd < MIN_TREASURY_LIQUIDITY) return false
        if (c.vol24hUsd < MIN_TREASURY_24H_VOL) return false
        if (watchlist.size >= MAX_WATCHLIST) {
            // Evict oldest
            watchlist.entries.minByOrNull { it.value.discoveredAt }?.key?.let { watchlist.remove(it) }
        }
        watchlist[c.mint] = c
        try {
            ForensicLogger.lifecycle(
                "TREASURY_CANDIDATE_PUBLISHED_4599",
                "mint=${c.mint.take(10)} sym=${c.symbol} mcap=${c.mcap.toInt()} liq=${c.liquidityUsd.toInt()} vol24h=${c.vol24hUsd.toInt()} src=${c.source}",
            )
        } catch (_: Throwable) {}
        return true
    }

    fun watchlistSnapshot(): List<TreasuryCandidate> = watchlist.values.toList()

    private val recirculatedAt7299 = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val RECIRCULATE_EVERY_MS_7299 = 10L * 60 * 1000

    /**
     * V5.0.7299 §THE_WATCHLIST_CASHGEN_WAS_MEANT_TO_POLL.
     *
     * This feed was built as "a dedicated Treasury watchlist that CashGen
     * polls", and nothing ever read it — candidates were published and then
     * aged out. Each market sweep now re-offers every candidate that is no
     * longer on the live watchlist to intake (at most once per 10 minutes per
     * mint), seeded for TREASURY and CASHGEN. Intake, the lane election and
     * every gate downstream are unchanged; this only stops established,
     * liquid candidates from being dropped once they leave the watchlist.
     */
    fun recirculate7299(): Int {
        val now = System.currentTimeMillis()
        recirculatedAt7299.entries.removeIf { now - it.value > 6 * RECIRCULATE_EVERY_MS_7299 }
        var n = 0
        for (c in watchlist.values) {
            if (now - (recirculatedAt7299[c.mint] ?: 0L) < RECIRCULATE_EVERY_MS_7299) continue
            val watching = try { GlobalTradeRegistry.isWatching(c.mint) } catch (_: Throwable) { true }
            if (watching) continue
            recirculatedAt7299[c.mint] = now
            try {
                TokenMergeQueue.enqueue(
                    mint = c.mint,
                    symbol = c.symbol,
                    scanner = "TREASURY_FEED_7299",
                    marketCapUsd = c.mcap,
                    liquidityUsd = c.liquidityUsd,
                    laneAffinity = setOf("TREASURY", "CASHGEN"),
                )
                n++
            } catch (_: Throwable) {}
        }
        if (n > 0) try { PipelineHealthCollector.labelInc("TREASURY_FEED_RECIRCULATED_7299") } catch (_: Throwable) {}
        return n
    }

    fun size(): Int = watchlist.size

    fun statusLine(): String = "$VERSION size=${watchlist.size}/$MAX_WATCHLIST minMcap=${MIN_TREASURY_MCAP.toInt()} minLiq=${MIN_TREASURY_LIQUIDITY.toInt()} minVol=${MIN_TREASURY_24H_VOL.toInt()}"
}
