package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6301 — BAND-LOSS PROBE DAMPER (data-driven, self-relearning).
 *
 * OPERATOR MANDATE (FinalDecisionGate.kt L4598):
 *   "NEVER ZERO A LANE ON STRATEGY GROUNDS ... a weak/dead bucket trades
 *    SMALL, not never — the brain keeps getting real outcomes and can
 *    trade back through."
 *
 * Reads live danger state from LosingPatternMemory (single source of
 * truth). When a bucket flips DANGEROUS and has ≥ MIN_SAMPLES, sizing
 * shrinks toward PROBE_FLOOR. When the bucket exits danger (WR climbs
 * back or samples dilute), sizing auto-recovers to 1.0×.
 *
 * Auto-relearn is INHERENT — the damper follows the memory. There is no
 * TTL to expire, no separate ingest side to bug-out. If the band starts
 * winning, LosingPatternMemory clears the danger flag → damper returns
 * 1.0 on the very next call.
 *
 * Applied at sizing time in Executor multiplier stack (fail-open to 1.0).
 */
object BandLossVetoGuard {
    private const val PROBE_FLOOR = 0.10
    private const val ELIGIBLE_LANE_PREFIXES = "MOONSHOT|QUALITY|SHITCOIN|BLUECHIP|PRESALE_SNIPE|TREASURY|EXPRESS|STANDARD"

    // Simple debounce: only log/emit label once per key per session change.
    private val engaged = ConcurrentHashMap<String, Long>()

    /**
     * Returns PROBE_FLOOR..1.0. Never zero. Reads LosingPatternMemory
     * for the current danger state and shrinks toward PROBE_FLOOR while
     * the band is dangerous. Recovers immediately once the band exits
     * danger — no timers, no persistence, no stale state.
     */
    fun sizeMultiplier(laneTag: String, scoreBand: String): Double {
        return try {
            if (laneTag.isBlank()) return 1.0
            if (!ELIGIBLE_LANE_PREFIXES.split('|').contains(laneTag)) return 1.0
            // Only S26-40 and S41-60 are eligible for damping (from tape
            // danger buckets). Other bands untouched.
            if (scoreBand != "S26-40" && scoreBand != "S41-60") return 1.0

            // Map scoreBand back to a representative score so we can query
            // LosingPatternMemory (which keys off score, not band tag).
            val repScore = when (scoreBand) {
                "S26-40" -> 33
                "S41-60" -> 50
                else -> return 1.0
            }
            val stats = LosingPatternMemory.stats(laneTag, repScore)
            if (!stats.isDangerous) {
                // Auto-recovered. Log revival once when we transition.
                if (engaged.remove("$laneTag|$scoreBand") != null) {
                    try {
                        PipelineHealthCollector.labelInc("BAND_DAMPER_RECOVERED_6301")
                        ForensicLogger.lifecycle(
                            "BAND_DAMPER_RECOVERED_6301",
                            "lane=$laneTag band=$scoreBand"
                        )
                    } catch (_: Throwable) {}
                }
                return 1.0
            }

            // Danger active. Log engagement once, then apply PROBE_FLOOR.
            val key = "$laneTag|$scoreBand"
            if (engaged.putIfAbsent(key, System.currentTimeMillis()) == null) {
                try {
                    PipelineHealthCollector.labelInc("BAND_DAMPER_ARMED_6301")
                    ForensicLogger.lifecycle(
                        "BAND_DAMPER_ARMED_6301",
                        "lane=$laneTag band=$scoreBand losses=${stats.losses} wins=${stats.wins}"
                    )
                } catch (_: Throwable) {}
            }
            PROBE_FLOOR
        } catch (_: Throwable) { 1.0 }
    }

    fun snapshotForReport(): Map<String, Long> = engaged.toMap()
}
