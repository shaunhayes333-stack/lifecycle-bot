package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6324 — TACTIC BLEED PIVOT (operator hotfix §6).
 *
 * When a lane / tactic bucket bleeds, do NOT disable it. Reduce size,
 * raise floor, rotate tactic, probe-first, keep the lane active for
 * observation.
 *
 * Example: QUALITY | S61+ | REACCUMULATION | N=2 | 0W/2L | mean -48.1%
 *   → rotate to next contextually valid tactic
 *   → probe-only sizing
 *   → fresh confirmation
 *   → do NOT disable QUALITY
 */
object TacticBleedPivot {

    val CANDIDATE_TACTICS = listOf(
        "MOMENTUM",
        "PULLBACK",
        "REACCUMULATION",
        "BREAKOUT",
        "REVERSAL",
        "LIQUIDITY_RECOVERY",
        "LAB_PROPOSED",
    )

    data class BucketPerf(
        val lane: String,
        val scoreBand: String,
        val tactic: String,
        val n: Int,
        val wins: Int,
        val losses: Int,
        val meanReturnPct: Double,
        val lossSeverityPct: Double,
    )

    data class PivotDecision(
        val bucket: BucketPerf,
        val previousTactic: String,
        val newTactic: String,
        val probeOnly: Boolean,
        val sizeMultiplier: Double,
        val floorAdjustment: Double,
        val evidence: String,
        val reason: String,
    )

    private val lastPivotByBucket = ConcurrentHashMap<String, PivotDecision>()

    /**
     * Decide whether a bucket is bleeding and if so which tactic to
     * rotate to. Returns null when the bucket is healthy or has too
     * little data. NEVER disables the lane — only rotates tactics /
     * reduces size / raises floors / requires probe.
     */
    fun evaluate(bucket: BucketPerf, regime: String = "NORMAL"): PivotDecision? {
        // Small-N decisive bleed: N>=2 AND mean below -25% AND zero wins.
        val decisiveBleed = bucket.n >= 2 && bucket.meanReturnPct <= -25.0 && bucket.wins == 0
        // Persistent bleed: N>=5 with WR<25% or mean below -10%.
        val persistentBleed = bucket.n >= 5 &&
            ((bucket.wins.toDouble() / bucket.n) < 0.25 || bucket.meanReturnPct < -10.0)
        if (!decisiveBleed && !persistentBleed) return null

        val bucketKey = "${bucket.lane}|${bucket.scoreBand}|${bucket.tactic}"
        val newTactic = nextTactic(bucket.tactic, regime)
        val probeOnly = decisiveBleed || bucket.meanReturnPct <= -40.0
        val sizeMult = if (probeOnly) 0.25 else 0.55
        val floorAdj = if (probeOnly) 8.0 else 4.0
        val decision = PivotDecision(
            bucket = bucket,
            previousTactic = bucket.tactic,
            newTactic = newTactic,
            probeOnly = probeOnly,
            sizeMultiplier = sizeMult,
            floorAdjustment = floorAdj,
            evidence = "n=${bucket.n} w=${bucket.wins} l=${bucket.losses} mean=${"%.1f".format(bucket.meanReturnPct)}% loss=${"%.1f".format(bucket.lossSeverityPct)}% regime=$regime",
            reason = if (decisiveBleed) "DECISIVE_SMALL_N_BLEED" else "PERSISTENT_BLEED",
        )
        lastPivotByBucket[bucketKey] = decision
        try {
            ForensicLogger.lifecycle(
                "TACTIC_BLEED_PIVOT_6324",
                "bucket=$bucketKey previousTactic=${decision.previousTactic} newTactic=${decision.newTactic} probeOnly=${decision.probeOnly} sizeMult=${decision.sizeMultiplier} floorAdj=${decision.floorAdjustment} evidence=${decision.evidence} reason=${decision.reason}",
            )
            PipelineHealthCollector.labelInc("TACTIC_BLEED_PIVOT_6324")
        } catch (_: Throwable) {}
        return decision
    }

    /** Pick the next tactic in the rotation. Simple cyclic strategy for
     *  now (spec allows regime-aware selection later — we just avoid
     *  returning the same tactic). */
    private fun nextTactic(current: String, regime: String): String {
        val idx = CANDIDATE_TACTICS.indexOfFirst { it.equals(current, true) }
        if (idx < 0) return CANDIDATE_TACTICS.first()
        // Regime hint: in "TREND" prefer MOMENTUM/BREAKOUT; in "RANGE" prefer
        // PULLBACK/REVERSAL. Fall back to next-in-list otherwise.
        return when (regime.uppercase()) {
            "TREND" -> if (current == "MOMENTUM") "BREAKOUT" else "MOMENTUM"
            "RANGE" -> if (current == "PULLBACK") "REVERSAL" else "PULLBACK"
            else -> CANDIDATE_TACTICS[(idx + 1) % CANDIDATE_TACTICS.size]
        }
    }

    fun getLastPivot(lane: String, scoreBand: String, tactic: String): PivotDecision? =
        lastPivotByBucket["$lane|$scoreBand|$tactic"]
}
