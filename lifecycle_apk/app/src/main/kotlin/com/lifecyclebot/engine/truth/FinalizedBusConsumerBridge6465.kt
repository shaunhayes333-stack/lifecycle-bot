package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6465 §P0-#2 — CONSUMER BRIDGE for CanonicalFinalizedTradeBus6464.
 *
 * OPERATOR MANDATE:
 *   "Have each of the 8 registered bus consumers (LearnerRewardBridge,
 *    LosingStreakReflex, GrowthRewardShaper, TacticSwitcher, Governor,
 *    CapitalCreed, EVEstimator, Dashboard) actually call
 *    CanonicalFinalizedTradeBus6464.ack(name, tradeId) on each
 *    finalized trade."
 *
 * DESIGN
 * ──────
 * SellFinalizationCoordinator publishes to the bus and calls
 * `deliverToConsumers(envelope, ::deliver)` — this dispatcher routes
 * each envelope to the right consumer's real API and returns TRUE
 * when the consumer accepted the delivery (bus keeps the ack), FALSE
 * when the consumer refused (bus removes the ack so the parity report
 * shows the miss).
 *
 * Consumers that don't exist as objects (Governor, CapitalCreed,
 * EVEstimator, Dashboard) still receive a passive "counted" ack so
 * they don't sit at zero forever — the bus telemetry captures the
 * fanout, and future ships can hook their real APIs.
 *
 * All calls are best-effort. Exceptions are absorbed so a single
 * consumer crash cannot break the fanout for the other seven.
 */
object FinalizedBusConsumerBridge6465 {

    private val delivered = AtomicLong(0L)
    private val refused = AtomicLong(0L)

    fun deliver(consumer: String, env: CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        // V5.0.6470 §P1 — learning purity gate. Any envelope whose position id
        // (encoded as env.tradeId) or mint has been quarantined by
        // `LearningQuarantineGate6470` is dropped before it reaches any
        // learner. Learning consumers only see clean canonical outcomes.
        if (consumer !in NON_LEARNING_CONSUMERS && !env.learningEligible) {
            try {
                PipelineHealthCollector.labelInc("FINALIZED_LEARNING_INELIGIBLE_ACK_NO_MUTATION_6519_${consumer}".take(60))
                ForensicLogger.lifecycle("FINALIZED_LEARNING_INELIGIBLE_6519", "consumer=$consumer positionId=${env.positionId} mint=${env.mint.take(10)} reason=${env.learningEligibilityReason.take(120)}")
            } catch (_: Throwable) {}
            return true
        }
        if (consumer !in NON_LEARNING_CONSUMERS &&
            LearningQuarantineGate6470.shouldDropForLearning(positionId = env.tradeId, mint = env.mint)
        ) {
            try {
                PipelineHealthCollector.labelInc("LEARNING_QUARANTINE_HANDLED_NO_MUTATION_6486_${consumer}".take(60))
            } catch (_: Throwable) {}
            return true
        }
        val ok = when (consumer) {
            "LearnerRewardBridge" -> deliverToLearnerRewardBridge(env)
            "LosingStreakReflex"  -> deliverToLosingStreakReflex(env)
            "GrowthRewardShaper"  -> deliverToGrowthRewardShaper(env)
            "TacticSwitcher"      -> deliverToTacticSwitcher(env)
            "Governor"            -> deliverToGovernor(env)
            "CapitalCreed"        -> deliverToCapitalCreed(env)
            "EVEstimator"         -> deliverToEvEstimator(env)
            "AatePolicyReward"    -> deliverToAatePolicyReward(env)
            "StrategyHypothesisEngine" -> deliverToStrategyHypothesis(env)
            "MemeCausalLearning6568" -> deliverToMemeCausalLearning6568(env)
            "Dashboard"           -> deliverToDashboard(env)
            else -> false
        }
        if (ok) delivered.incrementAndGet() else refused.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc(
                if (ok) "FINALIZED_CONSUMER_DELIVERED_${consumer}_6465"
                else "FINALIZED_CONSUMER_REFUSED_${consumer}_6465"
            )
        } catch (_: Throwable) {}
        return ok
    }

    /** Consumers that are NOT learning targets — quarantine does not gate them. */
    private val NON_LEARNING_CONSUMERS = setOf("Dashboard")

    private fun deliverToLearnerRewardBridge(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.truth.LearnerRewardBridge6440.acceptFinalized6486(
            env.positionId, env.mint, env.lane, env.entryTactic, env.mode,
            env.realizedReturnPct, env.realizedPnlSol, env.holdingTimeMs / 60_000.0,
        )
    } catch (_: Throwable) { false }

    private fun deliverToLosingStreakReflex(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.truth.LosingStreakReflex6439.onTradeClosed(env.realizedPnlSol, env.mint, env.mode, env.lane)
        true
    } catch (_: Throwable) { false }

    private fun deliverToGrowthRewardShaper(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.truth.GrowthAlignedRewardShaper6439.shape(
            env.realizedPnlSol, (env.atMs - env.holdingTimeMs).coerceAtLeast(0L), env.atMs, env.mint,
        )
        true
    } catch (_: Throwable) { false }

    private fun deliverToTacticSwitcher(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        val band = env.scoreBand.ifBlank { com.lifecyclebot.engine.LosingPatternMemory.scoreBand(env.entryScore) }
        com.lifecyclebot.engine.learning.TacticSwitcher.onCanonicalTradeClosed6486(
            env.lane, band, env.entryTactic, env.realizedReturnPct,
        )
        true
    } catch (_: Throwable) { false }

    private fun deliverToGovernor(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.LiveLaneGovernor.recordBypassOutcome(env.mint, env.realizedReturnPct)
        true
    } catch (_: Throwable) { false }

    private fun deliverToCapitalCreed(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.truth.CapitalPreservationCreed6439.recordFinalized6486(env.positionId, env.realizedPnlSol)
    } catch (_: Throwable) { false }

    private fun deliverToEvEstimator(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.ForwardOutcomeModel.recordOutcome(env.mint, env.realizedReturnPct)
        true
    } catch (_: Throwable) { false }

    private fun deliverToAatePolicyReward(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        AateDecisionFabric6512.onFinalized(env)
    } catch (_: Throwable) { false }

    private fun deliverToStrategyHypothesis(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.StrategyHypothesisEngine.recordOutcome(env.mint, env.realizedReturnPct)
        true
    } catch (_: Throwable) { false }

    private fun deliverToMemeCausalLearning6568(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        val memeLane = env.lane.uppercase() in setOf("MEME","STANDARD","SHITCOIN","EXPRESS","MOONSHOT","BLUECHIP","BLUE_CHIP","QUALITY","MANIPULATED","CASHGEN","CYCLIC","DIP_HUNTER","TREASURY","PROJECT_SNIPER")
        if (memeLane) {
            val win = env.realizedReturnPct > 0.5; val loss = env.realizedReturnPct < -0.5
            com.lifecyclebot.engine.runtime.ColdStreakDamper.noteOutcome(env.lane, env.mode.equals("paper", true), win, loss)
            com.lifecyclebot.engine.runtime.DamageControlGate.noteOutcome(env.realizedReturnPct)
            MemeCausalLearning6568.record(env)
        } else true
    } catch (_: Throwable) { false }

    private fun deliverToDashboard(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.DashboardDataProvider.onCanonicalTradeFinalized6485(env)
        true
    } catch (_: Throwable) { false }

    fun statusLine(): String = "delivered=${delivered.get()} refused=${refused.get()}"

    internal fun resetForTest() { delivered.set(0L); refused.set(0L) }
}
