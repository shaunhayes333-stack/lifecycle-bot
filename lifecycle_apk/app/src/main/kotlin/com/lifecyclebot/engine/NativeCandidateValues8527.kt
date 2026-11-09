package com.lifecyclebot.engine

import com.lifecyclebot.data.CandidateDecision

/** Transfer the executing desk's observations, not maxima borrowed from a different strategy.
 * Admission floors and inverse selection priority are decisions, not confidence or expected return.
 * This helper never grants BUY, removes a veto, or changes the observed EdgeOptimizer/strategy metadata. */
internal object NativeCandidateValues8527 {
    private fun observed8527(value: Double): Double = value.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 0.0

    fun apply8527(base: CandidateDecision, nativeScore: Double, nativeConfidence: Double): CandidateDecision =
        base.copy(entryScore = observed8527(nativeScore), aiConfidence = observed8527(nativeConfidence))

    /**
     * V5.0.8651 — CORE compares voters on the same 0..100 scale. MOONSHOT scores to 150;
     * its clipped `Opinion.score=100` used to outrank a 90/100 specialist even when
     * MOONSHOT had only 105/150 (70%) conviction. The ensemble then inherited
     * 100 instead of 70, mis-ranking both CORE ownership and its forecast input.
     */
    fun coreScorePct8651(opinion: SpecialistBrainBridge7542.Opinion): Double =
        (opinion.rawScore8535.toDouble() * 100.0 / opinion.scaleMax8535.coerceAtLeast(100)).coerceIn(0.0, 100.0)

    fun coreRank8651(opinion: SpecialistBrainBridge7542.Opinion): Double =
        maxOf(coreScorePct8651(opinion), opinion.confidence.toDouble().coerceIn(0.0, 100.0))

    /** A missing buy-pressure observation (the bridge's default 50%) never qualifies the CORE fallback. */
    fun coreFallback8651(qualifiedSpecialists: Int, v3Score: Int, v3Confidence: Int,
                         observedBuyPressurePct: Double?, liquidityUsd: Double, safetyBlocked: Boolean): Boolean =
        qualifiedSpecialists == 0 && v3Score >= 40 && v3Confidence >= 45 &&
            observedBuyPressurePct != null && observedBuyPressurePct.isFinite() && observedBuyPressurePct >= 50.0 &&
            liquidityUsd.isFinite() && liquidityUsd > 0.0 && !safetyBlocked

    /** Keep the existing ensemble's voters; average the normalized score and actual confidence independently. */
    fun coreEnsemble8527(voters: List<SpecialistBrainBridge7542.Opinion>, fallbackScore: Int,
                         fallbackConfidence: Int): Pair<Int, Int> {
        val score = if (voters.isEmpty()) fallbackScore else voters.take(4).map(::coreScorePct8651).average().toInt()
        val confidence = if (voters.isEmpty()) fallbackConfidence else voters.take(4).map { it.confidence }.average().toInt()
        return score.coerceIn(0, 100) to confidence.coerceIn(0, 100)
    }
}
