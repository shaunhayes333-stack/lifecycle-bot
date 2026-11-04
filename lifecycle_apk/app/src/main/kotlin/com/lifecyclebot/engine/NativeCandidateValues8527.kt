package com.lifecyclebot.engine

import com.lifecyclebot.data.CandidateDecision

/** Transfer the executing desk's observations, not maxima borrowed from a different strategy.
 * Admission floors and inverse selection priority are decisions, not confidence or expected return.
 * This helper never grants BUY, removes a veto, or changes the observed EdgeOptimizer/strategy metadata. */
internal object NativeCandidateValues8527 {
    private fun observed8527(value: Double): Double = value.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 0.0

    fun apply8527(base: CandidateDecision, nativeScore: Double, nativeConfidence: Double): CandidateDecision =
        base.copy(entryScore = observed8527(nativeScore), aiConfidence = observed8527(nativeConfidence))

    /** Keep the existing ensemble's voters; average each measurement in its own units. */
    fun coreEnsemble8527(voters: List<SpecialistBrainBridge7542.Opinion>, fallbackScore: Int,
                         fallbackConfidence: Int): Pair<Int, Int> {
        val score = if (voters.isEmpty()) fallbackScore else voters.take(4).map { it.score }.average().toInt()
        val confidence = if (voters.isEmpty()) fallbackConfidence else voters.take(4).map { it.confidence }.average().toInt()
        return score.coerceIn(0, 100) to confidence.coerceIn(0, 100)
    }
}
