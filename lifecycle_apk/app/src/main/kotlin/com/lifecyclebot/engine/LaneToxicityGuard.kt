package com.lifecyclebot.engine

/**
 * V5.0.3857 — LaneToxicityGuard.
 *
 * Source fix for WR collapse where a lane×score bucket is already a proven
 * net-negative danger bucket (example from operator report: MOONSHOT|S41-60
 * losses=38 wins=11 meanPnl=-35.82%) yet style/router fanout keeps electing
 * that lane as primary/rescue. FDG later shrinks size, but WR keeps bleeding.
 *
 * V5.0.4546 inner-lane re-education doctrine: this guard never blocks a trade,
 * never disables a lane globally, and never chooses a different lane as an escape.
 * It classifies lane×score toxicity so the owning lane can change tactic/style/
 * confirmation/hold/exit behavior and learn how to win internally.
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

    enum class Treatment { NORMAL, REEDUCATE_CONFIRMATION, REEDUCATE_TACTIC, REEDUCATE_EXIT, HARD_SAFETY_ONLY }

    fun treatmentFor(lane: String, score: Int): Treatment = when {
        isNetNegativeDanger(lane, score) && score <= 20 -> Treatment.REEDUCATE_CONFIRMATION
        isNetNegativeDanger(lane, score) -> Treatment.REEDUCATE_TACTIC
        else -> Treatment.NORMAL
    }

    fun chooseNonToxicLane(mint: String, lanes: List<String>, score: Int): String? {
        if (lanes.isEmpty()) return null
        // V5.0.4546 — compatibility shim only. Do NOT choose an alternate lane.
        // Callers still named chooseNonToxicLane receive the original/first lane;
        // toxicity is exposed through treatmentFor() so the lane can re-educate
        // internally instead of outsourcing its lesson to QUALITY/DIP/TREASURY.
        return lanes.firstOrNull { it.isNotBlank() }
    }

    fun filterNonToxic(lanes: Collection<String>, score: Int): List<String> =
        // V5.0.4546 — no amputating toxic lanes from offered lane families.
        // Preserve original lane ownership; use treatmentFor() to alter tactic/style.
        lanes.filter { it.isNotBlank() }.distinct()

}
