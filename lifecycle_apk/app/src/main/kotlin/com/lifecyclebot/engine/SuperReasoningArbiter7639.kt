
package com.lifecyclebot.engine

/**
 * V5.0.7639 - recursive reasoning arbiter.
 *
 * Chooses how much trust to place in world-model projection, adversarial critic,
 * episodic retrieval and policy-tree search for this candidate. It does not add
 * a new trade vote: it only reweights already-bounded reasoning components.
 */
object SuperReasoningArbiter7639 {
    data class Decision(
        val worldWeight: Double,
        val criticWeight: Double,
        val memoryWeight: Double,
        val treeWeight: Double,
        val dominant: String,
        val metaConfidence: Double,
        val reason: String,
    ) {
        fun contributionTag(): String = String.format(
            java.util.Locale.US,
            "arbiter7639(dom=%s,w=%.2f,c=%.2f,m=%.2f,t=%.2f,meta=%.2f,%s)",
            dominant,
            worldWeight,
            criticWeight,
            memoryWeight,
            treeWeight,
            metaConfidence,
            reason,
        )
    }

    fun arbitrate(
        world: SuperWorldModel7634.Snapshot,
        critic: SuperAdversarialCritic7635.Review,
        memory: SuperEpisodicRetriever7638.Retrieval,
        tree: SuperPolicyTree7638.Result,
    ): Decision {
        val horizonTrust = SuperWorldModel7634.Horizon.entries.map {
            try { SuperIntelligenceCalibration7636.horizonReliability(it) } catch (_: Throwable) { 1.0 }
        }.average().coerceIn(0.60, 1.20)

        val worldWeight = (
            0.55 +
                horizonTrust * 0.25 +
                (1.0 - world.disagreement) * 0.20
            ).coerceIn(0.55, 1.20)

        val criticWeight = (
            0.55 +
                critic.criticConfidence * 0.35 +
                critic.thesisFragility * 0.20
            ).coerceIn(0.55, 1.20)

        val memoryWeight = (
            0.45 +
                memory.confidence * 0.60
            ).coerceIn(0.45, 1.10)

        val treeWeight = (
            0.50 +
                tree.confidence * 0.45 +
                memory.confidence * 0.15
            ).coerceIn(0.50, 1.15)

        val weights = linkedMapOf(
            "WORLD" to worldWeight,
            "CRITIC" to criticWeight,
            "MEMORY" to memoryWeight,
            "TREE" to treeWeight,
        )
        val dominant = weights.maxByOrNull { it.value }?.key ?: "WORLD"

        val conflict = listOf(
            world.disagreement,
            critic.thesisFragility,
            1.0 - tree.confidence,
            1.0 - memory.confidence,
        ).average().coerceIn(0.0, 1.0)

        val metaConfidence = (
            0.35 +
                (1.0 - conflict) * 0.45 +
                ((weights.values.maxOrNull() ?: 1.0) - 0.55).coerceAtLeast(0.0) * 0.20
            ).coerceIn(0.0, 1.0)

        val reason = when {
            critic.thesisFragility >= 0.70 -> "critic_dominates_fragile_thesis"
            memory.confidence >= 0.70 -> "episodic_memory_strong"
            tree.confidence >= 0.75 -> "policy_tree_clear_margin"
            world.disagreement <= 0.20 -> "world_models_coherent"
            else -> "mixed_reasoning_evidence"
        }

        return Decision(
            worldWeight = worldWeight,
            criticWeight = criticWeight,
            memoryWeight = memoryWeight,
            treeWeight = treeWeight,
            dominant = dominant,
            metaConfidence = metaConfidence,
            reason = reason,
        )
    }
}
