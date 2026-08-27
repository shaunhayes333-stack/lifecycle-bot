package com.lifecyclebot.engine.truth

/**
 * V5.0.6552 — lane output contract.
 * Lane specialists may describe conviction and a requested notional, but they
 * do not own executable SOL caps or mutate a sealed ticket.
 */
data class SizingProposal6552(
    val attemptId: String,
    val mode: String,
    val mint: String,
    val candidateVersion: Long,
    val canonicalLane: String,
    val baseSizeSol: Double,
    val confidence: Double,
    val predictedEv: Double,
    val uncertainty: Double,
    val conviction: Double,
)
