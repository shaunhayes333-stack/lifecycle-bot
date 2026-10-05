package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.7809 — ONE CANONICAL-OPEN ATTRIBUTION BOUNDARY (Field Manual L356).
 *
 * Several learners judged a close by looking up mint-keyed state at SETTLEMENT
 * time: the oracle's latest forecast for the mint (rewritten by every later
 * evaluation of that mint), the lane hunter's claim (a 30-minute TTL that a
 * held position usually outlived), the resident specialist book (no outcome
 * path at all). Settlement-time lookups grade whatever happens to be there, not
 * what produced the entry.
 *
 * CanonicalPositionAuthority6441.openPosition calls [onCanonicalOpen7809] once,
 * next to AateDecisionFabric6512.attachPosition (which binds UnifiedPolicyHead
 * and StrategyHypothesisEngine). Each learner freezes the OWNER lane's own
 * entry state against the positionId; the clean finalized close
 * (CanonicalTradeFinalizedBus6450.isCleanForLearning7807) consumes it. Nothing
 * here sizes, gates or trades.
 */
object LearningAttributionBinder7809 {

    fun onCanonicalOpen7809(positionId: String, mint: String, ownerLane: String) {
        if (positionId.isBlank() || mint.isBlank() || ownerLane.isBlank()) return
        val oracle = try { OracleEdgeProof7263.bindPosition7809(positionId, mint, ownerLane) } catch (_: Throwable) { false }
        val hunter = try { com.lifecyclebot.engine.market.LaneHunter7297.bindPosition7809(positionId, mint, ownerLane) } catch (_: Throwable) { false }
        val resident = try {
            com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.bindPosition7809(positionId, mint, ownerLane)
        } catch (_: Throwable) { false }
        try {
            PipelineHealthCollector.labelInc("LEARNING_ATTRIBUTION_OPEN_BOUND_7809")
            if (oracle) PipelineHealthCollector.labelInc("LEARNING_ATTRIBUTION_OPEN_BOUND_7809_ORACLE")
            if (hunter) PipelineHealthCollector.labelInc("LEARNING_ATTRIBUTION_OPEN_BOUND_7809_HUNTER")
            if (resident) PipelineHealthCollector.labelInc("LEARNING_ATTRIBUTION_OPEN_BOUND_7809_RESIDENT")
        } catch (_: Throwable) {}
    }
}
