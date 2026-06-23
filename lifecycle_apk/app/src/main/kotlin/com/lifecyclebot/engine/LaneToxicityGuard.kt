package com.lifecyclebot.engine

/**
 * V5.0.3857 — LaneToxicityGuard.
 *
 * Source fix for WR collapse where a lane×score bucket is already a proven
 * net-negative danger bucket (example from operator report: MOONSHOT|S41-60
 * losses=38 wins=11 meanPnl=-35.82%) yet style/router fanout keeps electing
 * that lane as primary/rescue. FDG later shrinks size, but WR keeps bleeding.
 *
 * This guard never blocks a trade and never disables a lane globally. It only
 * lets routers avoid routing NEW exposure into a toxic lane when there is an
 * alternate lane available. If no alternate exists, downstream FDG train-first
 * micro/size shaping still owns execution.
 */
object LaneToxicityGuard {
    // V5.0.4072 — use LIVE-ONLY stats for live routing authority. Paper
    // losses must not pollute live danger detection. Falls back to combined
    // stats when live sample is too small (<8) to be statistically meaningful.
    // V5.0.4070 — lower threshold from -8.0 to -5.0 for faster pivot.
    // V5.0.4089 — RE-EDUCATE doctrine (operator: "2x-5x daily wallet growth").
    // -5.0 mean threshold misses the slow-bleed buckets that quietly cost SOL.
    // Example from operator snapshot: STANDARD|S0-10 has 32 losses vs 7 wins
    // (loss rate 82%) yet meanPnl=-3.58% sneaks under the -5.0 trigger because
    // the rare wins partially mask the bleed. Drop threshold to -2.0 AND add
    // a loss-rate trigger so a clearly-losing bucket gets pivoted away from
    // even when the mean is only mildly negative — the routing still re-routes
    // to non-toxic alternatives, the bucket is not disabled.
    fun isNetNegativeDanger(lane: String, score: Int): Boolean = try {
        val liveOnly = LosingPatternMemory.liveStats(lane, score)
        val decisive = liveOnly.losses + liveOnly.wins
        val lossRateLive = if (decisive > 0) liveOnly.losses.toDouble() / decisive else 0.0
        when {
            liveOnly.isDangerous && liveOnly.meanPnl <= -2.0 -> true
            liveOnly.isDangerous && decisive >= 20 && lossRateLive >= 0.75 && liveOnly.meanPnl <= -0.5 -> true
            liveOnly.sample < 8 -> {
                val combined = LosingPatternMemory.stats(lane, score)
                val decisiveC = combined.losses + combined.wins
                val lossRateC = if (decisiveC > 0) combined.losses.toDouble() / decisiveC else 0.0
                (combined.isDangerous && combined.meanPnl <= -2.0) ||
                    (combined.isDangerous && decisiveC >= 20 && lossRateC >= 0.75 && combined.meanPnl <= -0.5)
            }
            else -> false
        }
    } catch (_: Throwable) { false }

    fun chooseNonToxicLane(mint: String, lanes: List<String>, score: Int): String? {
        val clean = lanes.filter { it.isNotBlank() && !isNetNegativeDanger(it, score) }.distinct()
        if (clean.isNotEmpty()) return clean[((mint.hashCode() and 0x7fffffff) % clean.size)]
        // V5.0.4057 — pivot faster in DUMP/weak CHOP. If every offered style lane
        // is toxic, do not blindly fall back to MOONSHOT/SHITCOIN score buckets
        // that are already bleeding. Pick a quality/reclaim lane when available.
        val weak = try {
            val r = RegimeDetector.current()
            r.regime == RegimeDetector.Regime.DUMP || (r.regime == RegimeDetector.Regime.CHOP && r.recentWrPct < 25.0)
        } catch (_: Throwable) { false }
        if (weak) {
            // V5.0.4070 — prefer quality lanes harder in weak regimes. Add
            // WALLET_RECOVERED and LIQUIDITY_DEPTH_QUALITY as preferred pivots.
            val fallback = listOf("BLUECHIP", "QUALITY", "WALLET_RECOVERED", "LIQUIDITY_DEPTH_QUALITY", "TREASURY", "DIP_HUNTER")
                .firstOrNull { lanes.any { offered -> offered.equals(it, ignoreCase = true) } || !isNetNegativeDanger(it, score) }
            if (fallback != null) return fallback
        }
        return lanes.firstOrNull { it.isNotBlank() }
    }

    fun filterNonToxic(lanes: Collection<String>, score: Int): List<String> =
        lanes.filter { it.isNotBlank() && !isNetNegativeDanger(it, score) }.distinct()
}
