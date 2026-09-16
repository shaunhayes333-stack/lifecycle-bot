package com.lifecyclebot.engine

import com.lifecyclebot.data.CandidateDecision
import com.lifecyclebot.data.TokenState

/**
 * V5.0.6782 — AATE COGNITIVE AUTHORITY.
 *
 * This is the existing FinalDecisionGate fusion point, promoted from mostly
 * advisory telemetry into the binding cognitive authority for entries.
 *
 * Architecture:
 *   - specialist/lane rules propose and describe a candidate;
 *   - the learned AATE stack predicts whether the candidate has edge;
 *   - this gate fuses independent learned evidence;
 *   - FinalDecisionGate remains the constitutional/safety boundary and honours
 *     HARD_BLOCK as a terminal no-entry verdict.
 *
 * Historical throughput doctrine deliberately kept weak/dead contexts trading
 * small so the unfinished app could accumulate learning volume. That doctrine
 * is retired here. Canonical capital is no longer spent merely to keep a losing
 * bucket alive. Counterfactual/replay/lab/shadow systems remain the exploration
 * path; production/paper canonical entries must earn predictive support.
 *
 * No external/network/LLM call is made here. External intelligence continues to
 * shape the persisted/shared AATE state asynchronously; this hot path consumes
 * local learned state only.
 */
object BrainConsensusGate {

    enum class Verdict { ALLOW, SOFT_BLOCK, HARD_BLOCK }

    data class ConsensusReport(
        val verdict: Verdict,
        val objections: List<String>,
        val mood: String,
        val regime: String,
        val v3Score: Int,
        val secondScore: Int,
        val provenDead: Boolean = false,
        val normalEntryBlocked: Boolean = false,
        val probeAllowed: Boolean = false,
        // V5.0.6782 — expose the fused predictive state to diagnostics.
        val forwardPWin: Double = 0.50,
        val forwardExpectedPnl: Double = 0.0,
        val forwardSamples: Long = 0L,
        val livePWin: Double = 0.50,
        val liveExpectedPnl: Double = 0.0,
        val liveSamples: Long = 0L,
        val metaConviction: Double = 1.0,
        val predictiveAuthority: Boolean = false,
    )

    private const val MIN_MATURE_SAMPLES_6782 = 8L
    private const val MIN_STRONG_SAMPLES_6782 = 15L
    private const val REJECT_PWIN_6782 = 0.30
    private const val CATASTROPHIC_PWIN_6782 = 0.18
    private const val STRONG_NEGATIVE_E_6782 = -8.0

    private fun isProvenDead(tradingMode: String, v3: Int): Boolean = try {
        val b = LosingPatternMemory.stats(tradingMode, v3)
        val n = b.losses + b.wins
        if (n < 20 || b.meanPnl >= 0.0) false
        else {
            val lossRate = if (n > 0) b.losses.toDouble() / n.toDouble() else 0.0
            val severe = b.losses >= 20 && b.wins <= 1
            val bleedingMature = b.losses >= 15 && lossRate >= 0.75 && b.meanPnl <= -1.0
            severe || bleedingMature
        }
    } catch (_: Throwable) { false }

    fun evaluate(
        ts: TokenState,
        candidate: CandidateDecision,
        tradingMode: String,
    ): ConsensusReport {
        val objections = mutableListOf<String>()
        val v3 = candidate.entryScore.toInt()
        val lane = tradingMode.uppercase().ifBlank { "STANDARD" }

        val regimeEnum = try { RegimeDetector.currentRegime() } catch (_: Throwable) { RegimeDetector.Regime.NORMAL }
        val regime = regimeEnum.name

        val mood = try { SentientPersonality.getCurrentMood().name } catch (_: Throwable) { "UNKNOWN" }
        if (mood in setOf("HUMBLED", "SELF_CRITICAL") && regimeEnum == RegimeDetector.Regime.DUMP) {
            objections += "SENTIENCE_ADVISORY=mood=$mood+regime=DUMP"
        }

        val dispute = try { SecondScorer.isDisputed(v3, candidate, ts) } catch (_: Throwable) { null }
        val secondScore = dispute?.secondScore ?: -1
        if (dispute != null && dispute.disputed && dispute.secondScoreSaysWorse) {
            objections += "SECOND_SCORER_DISAGREE=v3=${dispute.v3Score}_2nd=${dispute.secondScore}_gap=${dispute.gap}"
        }

        val losingPatternDanger = try { LosingPatternMemory.isDangerZone(lane, v3) } catch (_: Throwable) { false }
        if (losingPatternDanger) objections += "LOSING_PATTERN_DANGER_ZONE=$lane|s=$v3"

        // ── Full-stack predictive fusion ────────────────────────────────────
        // ForwardOutcomeModel: historical/counterfactual trajectory learner.
        val fwd = try {
            ForwardOutcomeModel.forecast(lane, v3, candidate.setupQuality, regime, candidate.edgePhase)
        } catch (_: Throwable) { null }

        // LiveProbabilityEngine already fuses ForwardOutcomeModel,
        // UnifiedPolicyHead, lane priors and learned strategy state. Calling it
        // here makes that intelligence authoritative rather than size-only.
        val live = try {
            LiveProbabilityEngine.forecast(
                rawLane = lane,
                score = v3,
                quality = candidate.setupQuality,
                regime = regime,
                edgePhase = candidate.edgePhase,
            )
        } catch (_: Throwable) { null }

        // AutonomousMetaPolicy is an independently learned lane×score×regime
        // posterior. It participates as evidence, not merely a sizing modifier.
        val metaConv = try { AutonomousMetaPolicy.conviction(lane, v3, regime) } catch (_: Throwable) { 1.0 }

        val fwdP = fwd?.pWin ?: 0.50
        val fwdE = fwd?.expectedPnl ?: 0.0
        val fwdN = fwd?.samples ?: 0L
        val liveP = live?.pWin ?: 0.50
        val liveE = live?.expectedPnlPct ?: 0.0
        val liveN = live?.samples ?: 0L

        val toxicLane = try { LiveProbabilityEngine.isLaneLearnedToxic6379(lane) } catch (_: Throwable) { false }
        if (toxicLane) objections += "LEARNED_TOXIC_LANE=$lane"

        val provenDead = isProvenDead(lane, v3)
        if (provenDead) objections += "PROVEN_DEAD_CONTEXT=$lane|${LosingPatternMemory.scoreBand(v3)}"

        val fwdMatureNegative = fwdN >= MIN_MATURE_SAMPLES_6782 && fwdP < REJECT_PWIN_6782 && fwdE < 0.0
        val liveMatureNegative = liveN >= MIN_MATURE_SAMPLES_6782 && liveP < REJECT_PWIN_6782 && liveE < 0.0
        val dualNegative = fwdMatureNegative && liveMatureNegative
        val catastrophicForecast =
            (fwdN >= MIN_STRONG_SAMPLES_6782 && fwdP < CATASTROPHIC_PWIN_6782 && fwdE <= STRONG_NEGATIVE_E_6782) ||
            (liveN >= MIN_STRONG_SAMPLES_6782 && liveP < CATASTROPHIC_PWIN_6782 && liveE <= STRONG_NEGATIVE_E_6782)

        // Existing dual-brain veto from AutonomousMetaPolicy remains useful, but
        // 6782 removes its old 1-in-25 canonical escape hatch as an authority
        // principle: fresh evidence belongs in shadow/replay, not a known grave.
        val metaDualVeto = try {
            AutonomousMetaPolicy.shouldVeto(lane, v3, regime, fwdP, fwdE, fwdN)
        } catch (_: Throwable) { false }

        val metaStrongNegative = metaConv <= 0.70 && (fwdMatureNegative || liveMatureNegative)
        if (fwdMatureNegative) objections += "FORWARD_NEGATIVE=pWin=${"%.2f".format(fwdP)} E=${"%+.1f".format(fwdE)} n=$fwdN"
        if (liveMatureNegative) objections += "LIVE_PROB_NEGATIVE=pWin=${"%.2f".format(liveP)} E=${"%+.1f".format(liveE)} n=$liveN"
        if (metaStrongNegative) objections += "META_POLICY_NEGATIVE=conv=${"%.2f".format(metaConv)}"

        // Binding authority rules:
        //  1) statistically proven grave => no canonical entry;
        //  2) learned toxic lane => no canonical entry;
        //  3) independent predictive models agree the context loses => no entry;
        //  4) catastrophic forward forecast => no entry;
        //  5) meta-policy + one mature predictive brain strongly disagree => no entry.
        val hardBlock = provenDead || toxicLane || dualNegative || catastrophicForecast || metaDualVeto || metaStrongNegative

        // A single mature negative model, losing-pattern danger, scorer dispute or
        // sentience/regime objection remains a SOFT_BLOCK. It is visible to FDG
        // shaping while avoiding a single-model monopoly over uncertain contexts.
        val predictiveAuthority = (fwdN >= MIN_MATURE_SAMPLES_6782 || liveN >= MIN_MATURE_SAMPLES_6782)
        val verdict = when {
            hardBlock -> Verdict.HARD_BLOCK
            objections.isNotEmpty() -> Verdict.SOFT_BLOCK
            else -> Verdict.ALLOW
        }

        try {
            PipelineHealthCollector.labelInc("AATE_COGNITIVE_AUTHORITY_EVAL_6782")
            if (hardBlock) PipelineHealthCollector.labelInc("AATE_COGNITIVE_HARD_VETO_6782")
            else if (verdict == Verdict.SOFT_BLOCK) PipelineHealthCollector.labelInc("AATE_COGNITIVE_SOFT_SHAPE_6782")
            if (dualNegative) PipelineHealthCollector.labelInc("AATE_DUAL_PREDICTOR_NEGATIVE_6782")
            if (toxicLane) PipelineHealthCollector.labelInc("AATE_LEARNED_TOXIC_VETO_6782")
        } catch (_: Throwable) {}

        return ConsensusReport(
            verdict = verdict,
            objections = objections,
            mood = mood,
            regime = regime,
            v3Score = v3,
            secondScore = secondScore,
            provenDead = provenDead,
            normalEntryBlocked = hardBlock,
            probeAllowed = false,
            forwardPWin = fwdP,
            forwardExpectedPnl = fwdE,
            forwardSamples = fwdN,
            livePWin = liveP,
            liveExpectedPnl = liveE,
            liveSamples = liveN,
            metaConviction = metaConv,
            predictiveAuthority = predictiveAuthority,
        )
    }

    private val softBlockCounter = java.util.concurrent.atomic.AtomicLong(0)
    private val hardBlockCounter = java.util.concurrent.atomic.AtomicLong(0)
    private val allowCounter = java.util.concurrent.atomic.AtomicLong(0)

    fun recordOutcome(verdict: Verdict) {
        when (verdict) {
            Verdict.ALLOW -> allowCounter.incrementAndGet()
            Verdict.SOFT_BLOCK -> softBlockCounter.incrementAndGet()
            Verdict.HARD_BLOCK -> hardBlockCounter.incrementAndGet()
        }
    }

    fun formatForPipelineDump(): String {
        val a = allowCounter.get(); val s = softBlockCounter.get(); val h = hardBlockCounter.get()
        if (a + s + h == 0L) return ""
        return "\n===== Brain Consensus Gate / AATE Cognitive Authority (V5.0.6782) =====\n" +
            "  ALLOW=$a   SOFT_BLOCK=$s   HARD_BLOCK=$h\n" +
            "  → soft-block rate=${if (a+s+h > 0) String.format("%.1f%%", (s.toDouble() / (a+s+h)) * 100) else "0%"}" +
            "  hard-block rate=${if (a+s+h > 0) String.format("%.1f%%", (h.toDouble() / (a+s+h)) * 100) else "0%"}\n" +
            "  authority=ForwardOutcomeModel+LiveProbabilityEngine+UnifiedPolicyHead+AutonomousMetaPolicy+LosingPatternMemory+SecondScorer+Sentience/Regime\n"
    }
}
