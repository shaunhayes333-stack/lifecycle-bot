package com.lifecyclebot.engine

/** One score contract for CYCLIC's native opinion, normal ring and starvation probe.
 * Physical checks, ring sizing, FDG, ownership and the ring's position lifecycle stay with their existing owners. */
internal object CyclicEntryPolicy8527 {
    /** Unknown birth is not an old/mature token. Long.MAX_VALUE is the launch authority's unknown sentinel. */
    fun observedAgeMinutes8527(safetyAgeMinutes: Double, canonicalAgeMs: Long?): Double? =
        safetyAgeMinutes.takeIf { it.isFinite() && it >= 0.0 }
            ?: canonicalAgeMs?.takeIf { it >= 0L && it != Long.MAX_VALUE }?.div(60_000.0)

    fun baseFloor8527(base: Double, consecutiveLosses: Int, behaviorAdjustment: Double): Double {
        if (!base.isFinite()) return Double.POSITIVE_INFINITY
        val behavior = behaviorAdjustment.takeIf { it.isFinite() } ?: 0.0
        return (base + consecutiveLosses.coerceIn(0, 3) * 5.0 + behavior).coerceIn(25.0, 90.0)
    }

    fun memoryFloor8527(base: Double, badExpectancy: Boolean, danger: Boolean): Double {
        if (!base.isFinite() || base < 0.0) return Double.POSITIVE_INFINITY
        return (base + (if (badExpectancy) 5.0 else 0.0) + (if (danger) 5.0 else 0.0)).coerceAtMost(90.0)
    }

    fun scoreAllows8527(score: Double, baseFloor: Double, badExpectancy: Boolean, danger: Boolean,
                        choice: NativeOpportunityPolicy8522.Decision? = null): Boolean =
        score.isFinite() && score >= memoryFloor8527(baseFloor, badExpectancy, danger) &&
            NativeOpportunityPolicy8522.doorEligible8522(true, "CYCLIC_NATIVE_RING",
                choice?.takeIf { it.lane == "CYCLIC" })

    /** Native score and confidence are distinct observations; never promote confidence to the score floor. */
    fun candidate8527(score: Int, confidence: Int?, phase: String): com.lifecyclebot.data.CandidateDecision {
        val s = score.coerceIn(0, 100).toDouble()
        val c = (confidence ?: 50).coerceIn(0, 100).toDouble()
        return com.lifecyclebot.data.CandidateDecision(
            entryScore = s, exitScore = 0.0, phase = phase.ifBlank { "cyclic" },
            signal = "BUY", setupQuality = "B", edgeQuality = "B", finalQuality = "B",
            edgePhase = "CYCLIC", edgeConfidence = c, isOptimalEntry = true, edgeVeto = false,
            shouldTrade = true, finalSignal = "BUY", blockReason = "", qualityPenalty = 1.0,
            aiConfidence = c, meta = com.lifecyclebot.data.StrategyMeta(),
        )
    }

    fun rank8527(score: Double, confidence: Int?, nativeReady: Boolean,
                 choice: NativeOpportunityPolicy8522.Decision?): Double {
        val priority = choice?.takeIf { it.lane == "CYCLIC" &&
            it.action == NativeOpportunityPolicy8522.Action.TAKE_CANDIDATE }
            ?.selectionPriority?.takeIf { it.isFinite() }
        return if (priority == null) LaneDispatch8418.cyclicRank8418(score, confidence, nativeReady)
            else priority.coerceIn(0.0, 100.0) + if (nativeReady) 1_000.0 else 0.0
    }
}
