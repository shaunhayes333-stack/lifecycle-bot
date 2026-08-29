package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class AateBrainContribution6512(
    val brain: String, val role: String, val weight: Double, val effect: Double,
    val pWin: Double? = null, val expectedPnlPct: Double? = null,
    val moonshotP: Double? = null, val rugP: Double? = null,
    val scoreDelta: Double = 0.0, val sizeMultiplier: Double = 1.0, val tactic: String = "",
)

data class AateStrategyContext6512(
    val candidateId: String, val runtimeGeneration: Long, val mode: String,
    val mint: String, val symbol: String, val candidateVersion: Long,
    val primaryStrategy: String, val source: String, val regime: String,
)

data class AateDecisionEnvelope6512(
    val envelopeId: String, val revision: Long, val context: AateStrategyContext6512,
    val action: String, val pWin: Double, val expectedPnlPct: Double,
    val moonshotP: Double, val rugP: Double, val scoreBase: Double, val scoreFinal: Double,
    val sizeBase: Double, val sizeFinal: Double, val tactic: String,
    val hardSafety: List<String>, val contributors: List<AateBrainContribution6512>,
    val learningState: String, val executionTicket: String = "", val sealed: Boolean = false,
    val positionId: String = "", val createdAtMs: Long = System.currentTimeMillis(),
)

object PolicySynthesizer6512 {
    private val revisions = AtomicLong(0L)
    fun synthesize(
        context: AateStrategyContext6512, proposedAction: String,
        scoreBase: Double, scoreFinal: Double, sizeBase: Double, sizeFinal: Double,
        tactic: String, hardSafety: List<String>, contributors: List<AateBrainContribution6512>,
        learningState: String,
    ): AateDecisionEnvelope6512 {
        val bounded = contributors.map { it.copy(
            weight = it.weight.coerceIn(0.0, 1.0), effect = it.effect.coerceIn(-1.0, 1.0),
            pWin = it.pWin?.coerceIn(0.0, 1.0), moonshotP = it.moonshotP?.coerceIn(0.0, 1.0),
            rugP = it.rugP?.coerceIn(0.0, 1.0), sizeMultiplier = it.sizeMultiplier.coerceIn(0.05, 3.0),
        ) }
        val wp = bounded.mapNotNull { c -> c.pWin?.let { it to c.weight } }
        val pWin = if (wp.isEmpty()) 0.5 else wp.sumOf { it.first * it.second } / wp.sumOf { it.second }.coerceAtLeast(0.0001)
        val we = bounded.mapNotNull { c -> c.expectedPnlPct?.let { it to c.weight } }
        val ev = if (we.isEmpty()) 0.0 else we.sumOf { it.first * it.second } / we.sumOf { it.second }.coerceAtLeast(0.0001)
        val moon = bounded.mapNotNull { c -> c.moonshotP?.let { it to c.weight } }.maxOfOrNull { it.first } ?: 0.0
        val rug = bounded.mapNotNull { c -> c.rugP?.let { it to c.weight } }.maxOfOrNull { it.first } ?: 0.0
        val action = if (hardSafety.isNotEmpty()) "BLOCK" else proposedAction.uppercase()
        val rev = revisions.incrementAndGet()
        return AateDecisionEnvelope6512(
            envelopeId = "${context.runtimeGeneration}:${context.mode}:${context.mint}:${context.candidateVersion}:$rev",
            revision = rev, context = context, action = action, pWin = pWin,
            expectedPnlPct = ev, moonshotP = moon, rugP = rug,
            scoreBase = scoreBase, scoreFinal = scoreFinal, sizeBase = sizeBase,
            sizeFinal = sizeFinal.coerceAtLeast(0.0), tactic = tactic,
            hardSafety = hardSafety.distinct(), contributors = bounded, learningState = learningState,
        )
    }
}

object AateDecisionFabric6512 {
    private val byAuthority = ConcurrentHashMap<String, AateDecisionEnvelope6512>()
    private val byAttempt = ConcurrentHashMap<String, AateDecisionEnvelope6512>()
    private val byPosition = ConcurrentHashMap<String, AateDecisionEnvelope6512>()
    private val rewardedPositions = ConcurrentHashMap.newKeySet<String>()
    private val policies = AtomicLong(0L); private val rewards = AtomicLong(0L)

    private fun key(mode: String, mint: String, version: Long, lane: String): String =
        "${BotRuntimeController.currentGeneration()}:${mode.uppercase()}:${mint.trim()}:$version:${lane.uppercase()}"

    fun record(e: AateDecisionEnvelope6512): AateDecisionEnvelope6512 {
        val c = e.context
        byAuthority[key(c.mode, c.mint, c.candidateVersion, c.primaryStrategy)] = e
        policies.incrementAndGet(); emitPolicy(e); return e
    }

    fun get(mode: String, mint: String, version: Long, lane: String): AateDecisionEnvelope6512? =
        byAuthority[key(mode, mint, version, lane)]

    fun sealForExecution(attemptId: String, mode: String, mint: String, version: Long, lane: String): AateDecisionEnvelope6512? {
        if (attemptId.isBlank()) return null
        val prior = get(mode, mint, version, lane) ?: return null
        val sealed = prior.copy(executionTicket = attemptId, sealed = true)
        byAuthority[key(mode, mint, version, lane)] = sealed; byAttempt[attemptId] = sealed
        emitPolicy(sealed); return sealed
    }

    fun attachPosition(positionId: String, mode: String, mint: String, lane: String): Boolean {
        if (positionId.isBlank() || mint.isBlank() || lane.isBlank()) return false
        val e = byAuthority.values.asSequence()
            .filter { it.context.runtimeGeneration == BotRuntimeController.currentGeneration() }
            .filter { it.context.mode.equals(mode, true) && it.context.mint == mint && it.context.primaryStrategy.equals(lane, true) }
            .maxByOrNull { it.revision } ?: return false
        byPosition[positionId] = e.copy(positionId = positionId)
        try { PipelineHealthCollector.labelInc("AATE_POSITION_ATTRIBUTION_LINKED_6512") } catch (_: Throwable) {}
        return true
    }

    fun onFinalized(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        if (!rewardedPositions.add(env.positionId)) return true
        val e = byPosition[env.positionId] ?: byAuthority.values.asSequence()
            .filter { it.context.mint == env.mint && it.context.primaryStrategy.equals(env.lane, true) }.maxByOrNull { it.revision }
        val contributors = e?.contributors.orEmpty(); val updated = mutableListOf<String>()
        val uphBefore = UnifiedPolicyHead.trainedCount()
        try { UnifiedPolicyHead.recordOutcome(env.mint, env.realizedReturnPct) } catch (_: Throwable) {}
        if (UnifiedPolicyHead.trainedCount() > uphBefore) updated += "UnifiedPolicyHead"
        val metaBefore = AutonomousMetaPolicy.totalUpdateCount6512()
        try { AutonomousMetaPolicy.recordOutcome(env.mint, env.realizedReturnPct) } catch (_: Throwable) {}
        if (AutonomousMetaPolicy.totalUpdateCount6512() > metaBefore) updated += "AutonomousMetaPolicy"
        val hypoBefore = StrategyHypothesisEngine.outcomeUpdateCount6512()
        try { StrategyHypothesisEngine.recordOutcome(env.mint, env.realizedReturnPct) } catch (_: Throwable) {}
        if (StrategyHypothesisEngine.outcomeUpdateCount6512() > hypoBefore) updated += "StrategyHypothesisEngine"
        contributors.filter { it.role == "MEME_SPECIALIST_DESK" && it.brain.startsWith("MemeDesk:") }.forEach { c ->
            val lane = c.brain.substringAfter("MemeDesk:").substringBefore(':').uppercase()
            val scoreBand = try { LosingPatternMemory.scoreBand(e?.scoreFinal?.toInt() ?: 0) } catch (_: Throwable) { "UNKNOWN" }
            val outcome = CanonicalOutcomeClassifier6576.classifyReadonly(env.realizedReturnPct)
            try {
                // Primary lane is already trained exactly once by V3JournalRecorder.
                // Only secondary desk contributors need this causal outcome fanout.
                if (!lane.equals(env.lane, true)) {
                    com.lifecyclebot.engine.learning.LanePolicy.recordOutcome(
                        lane, scoreBand,
                        outcome == CanonicalOutcomeClassifier6576.Class.WIN,
                        outcome == CanonicalOutcomeClassifier6576.Class.LOSS,
                    )
                }
                ToolkitSignalSheet.recordDeskStage(lane, "LEARNING", env.positionId)
            } catch (_: Throwable) {}
        }
        val graphBefore = SemanticPatternGraph.nodeCount6512()
        val graphId = try { SemanticPatternGraph.recordOutcome(
            lane = env.lane, source = e?.context?.source ?: "CANONICAL_FINALITY",
            setup = contributors.joinToString("|") { "${it.brain}:${"%.3f".format(it.weight)}:${"%.3f".format(it.effect)}" }.ifBlank { "lane=${env.lane}|tactic=${env.entryTactic}" },
            exitReason = env.proofState, pnlPct = env.realizedReturnPct,
        ) } catch (_: Throwable) { "" }
        if (graphId.isNotBlank() && SemanticPatternGraph.nodeCount6512() > graphBefore) updated += "SemanticPatternGraph"
        rewards.incrementAndGet()
        val credit = contributors.joinToString(",") { c ->
            val v = if (env.realizedPnlSol >= 0.0) c.weight * c.effect else -c.weight * c.effect
            "${c.brain}:${"%.4f".format(v)}"
        }
        try {
            ForensicLogger.lifecycle("AATE_REWARD", "tradeId=${env.tradeId} pnl=${env.realizedPnlSol} MFE=${env.mfePct} MAE=${env.maePct} hold=${env.holdingTimeMs} contributors=${contributors.joinToString(",") { it.brain }} creditAssigned=[$credit] learnersUpdated=[${updated.joinToString(",")}] hypothesesUpdated=${updated.contains("StrategyHypothesisEngine")} metaPolicyUpdated=${updated.contains("AutonomousMetaPolicy")} tacticUpdated=true patternGraphUpdated=${graphId.isNotBlank()}")
            PipelineHealthCollector.labelInc("AATE_REWARD_6512")
        } catch (_: Throwable) {}
        return true
    }

    private fun emitPolicy(e: AateDecisionEnvelope6512) {
        val cs = e.contributors.joinToString(",") { "${it.brain}:${"%.3f".format(it.weight)}:${"%.3f".format(it.effect)}" }
        try {
            ForensicLogger.lifecycle("AATE_POLICY", "candidateId=${e.context.candidateId} revision=${e.revision} action=${e.action} pWin=${e.pWin} EV=${e.expectedPnlPct} moonshotP=${e.moonshotP} rugP=${e.rugP} primaryStrategy=${e.context.primaryStrategy} contributors=[$cs] scoreBase=${e.scoreBase} scoreFinal=${e.scoreFinal} sizeBase=${e.sizeBase} sizeFinal=${e.sizeFinal} tactic=${e.tactic} hardSafety=[${e.hardSafety.joinToString(",")}] learningState=${e.learningState} executionTicket=${e.executionTicket}")
            PipelineHealthCollector.labelInc("AATE_POLICY_6512")
        } catch (_: Throwable) {}
    }

    fun statusLine(): String = "policies=${policies.get()} sealed=${byAttempt.size} attributed=${byPosition.size} rewards=${rewards.get()}"
    internal fun resetForTest() { byAuthority.clear(); byAttempt.clear(); byPosition.clear(); rewardedPositions.clear(); policies.set(0L); rewards.set(0L) }
}
