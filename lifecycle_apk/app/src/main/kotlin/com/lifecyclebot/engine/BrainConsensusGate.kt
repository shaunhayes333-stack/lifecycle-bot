package com.lifecyclebot.engine

import com.lifecyclebot.data.CandidateDecision
import com.lifecyclebot.data.TokenState

/**
 * V5.9.806 — P0 binding intelligence: cross-brain consensus gate.
 *
 * The operator's diagnosis was correct: SentientPersonality / MetaCognitionAI /
 * AdaptiveLearningEngine / BehaviorLearning / FluidLearningAI exist in the
 * codebase but their outputs were ADVISORY — they wrote logs and adjusted
 * thresholds at the margins, but nothing in the decision path would say
 * "the bot's character doesn't want this trade — abort". This module makes
 * a subset of the brains BIND to the decision.
 *
 * Inputs (every brain consulted in try-catch so any single failure cannot
 * crash an entry):
 *   • SentientPersonality.getCurrentMood()
 *   • RegimeDetector.currentRegime()
 *   • SecondScorer.score(ts)
 *   • LosingPatternMemory.stats(tradingMode, v3Score)
 *   • StrategyTelemetry.isDisabled(tradingMode)
 *
 * Output: ConsensusVerdict.ALLOW / SOFT_BLOCK / HARD_BLOCK
 *
 *   ALLOW       — no brain objects, trade proceeds with normal size
 *   SOFT_BLOCK  — at least one brain objects in a relaxed regime; logged
 *                 but trade still proceeds. Operator can see how often
 *                 this fires before we promote to HARD_BLOCK behaviour.
 *   HARD_BLOCK  — retained as a verdict type for future explicit operator
 *                 whitelist vetoes only. Mood/personality/regime disagreement
 *                 is advisory telemetry and must not kill throughput.
 *
 * The gate is intentionally CONSERVATIVE — it must take a clear stack of
 * objections to fire HARD_BLOCK. We're guarding against the failure mode
 * the operator explicitly warned about: "don't fuck up what's already
 * there. don't regress me back a month".
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
        // V5.9.1355 P1 — proven-dead trainable veto fields.
        val provenDead: Boolean = false,
        val normalEntryBlocked: Boolean = false,
        val probeAllowed: Boolean = false,
    )

    // V5.9.1355 P1 — 1-in-25 probe cadence per proven-dead context.
    private const val PROBE_EVERY = 25
    private val deadContextCounter = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    // V5.0.4089 — RE-EDUCATE THE BLEEDERS (operator: "don't disable, re-educate
    // and succeed. 2x-5x daily wallet growth target"). Pre-4089 the proven-dead
    // gate required wins<=1 which only catches catastrophic buckets. Real-world
    // bleeders like STANDARD|S0-10 (losses=32 wins=7 meanPnl=-3.58%) carry a
    // handful of token-pumps that fool the wins<=1 gate but still hemorrhage
    // money (-0.035 SOL realized). Relax to: ANY mature (n>=20) bucket with a
    // 75%+ loss rate AND non-trivially negative mean is "proven dead" for
    // normal-size purposes — the 1-in-25 dust-probe cadence kicks in so the
    // bucket keeps learning at minimum size and CAN heal back to full sizing
    // when WR improves. This is the operator's "re-educate" doctrine.
    private fun isProvenDead(tradingMode: String, v3: Int): Boolean = try {
        val b = LosingPatternMemory.stats(tradingMode, v3)
        val mature = b.losses + b.wins >= 20
        if (!mature || b.meanPnl >= 0.0) false
        else {
            val lossRate = if ((b.losses + b.wins) > 0) b.losses.toDouble() / (b.losses + b.wins) else 0.0
            val severe = b.losses >= 20 && b.wins <= 1            // V5.9.1355 original
            val bleedingMature = b.losses >= 15 && lossRate >= 0.75 && b.meanPnl <= -1.0  // V5.0.4089 widened
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

        // --- 1. Regime check ---
        val regime = try { RegimeDetector.currentRegime() } catch (_: Throwable) { RegimeDetector.Regime.NORMAL }

        // --- 2. SentientPersonality mood ---
        val mood = try {
            com.lifecyclebot.engine.SentientPersonality.getCurrentMood().name
        } catch (_: Throwable) { "UNKNOWN" }

        // V5.9.1150 — doctrine correction: Sentience mood is NOT an allowed
        // executable hard veto. Runtime 5.0.3117 showed 327/386 FDG blocks were
        // BRAIN_CONSENSUS_VETO:SENTIENCE_VETO=mood, causing the personality loop
        // to choke the very trades it needs to learn from. Keep the signal as
        // advisory telemetry only; FDG/rug/liquidity/original whitelist vetoes
        // remain the hard execution guards.
        val lifetimeTrades = try {
            (com.lifecyclebot.engine.CanonicalLearningCounters.settledWins.get() + com.lifecyclebot.engine.CanonicalLearningCounters.settledLosses.get()).toInt()
        } catch (_: Throwable) { 0 }
        if (mood in setOf("HUMBLED", "SELF_CRITICAL") && regime == RegimeDetector.Regime.DUMP) {
            objections += "SENTIENCE_ADVISORY=mood=$mood+regime=DUMP+trades=$lifetimeTrades"
        }

        // --- 3. SecondScorer disagreement (P4) ---
        val dispute = try { SecondScorer.isDisputed(v3, candidate, ts) } catch (_: Throwable) { null }
        val secondScore = dispute?.secondScore ?: -1
        if (dispute != null && dispute.disputed && dispute.secondScoreSaysWorse) {
            objections += "SECOND_SCORER_DISAGREE=v3=${dispute.v3Score}_2nd=${dispute.secondScore}_gap=${dispute.gap}"
        }

        // --- 4. LosingPatternMemory danger zone (P3) ---
        val isDanger = try { LosingPatternMemory.isDangerZone(tradingMode, v3) } catch (_: Throwable) { false }
        if (isDanger) {
            objections += "LOSING_PATTERN_DANGER_ZONE=$tradingMode|s=$v3"
        }

        // --- 5. Strategy bleeding advisory (V5.9.1053: advisory only, never hard-block) ---
        // isDisabled() always returns false per operator directive. This block
        // is kept as telemetry: if a strategy has historically bled, it's noted
        // as an advisory objection so the dump is informative, but it cannot
        // gate a trade. The strategy self-heals via continued learning.
        val isStrategyDead = false  // isDisabled() is always false — no auto-retire
        // No objection added — advisory telemetry only

        // ---------------- Verdict composition ----------------
        // V5.9.1150 — BCG is telemetry/soft-shape only unless a future operator
        // explicitly adds a hard-veto whitelist item here. The performance doctrine
        // forbids broad personality/regime vetoes because they kill sample volume.
        // SecondScorer, losing-pattern, strategy bleed, and Sentience mood all stay
        // visible as SOFT_BLOCK objections without blocking execution.
        val hardBlock = false

        // SOFT_BLOCK = at least one objection but doesn't hit HARD_BLOCK
        // (logged but doesn't gate the trade — pure telemetry first round).
        val verdict = when {
            hardBlock              -> Verdict.HARD_BLOCK
            objections.isNotEmpty() -> Verdict.SOFT_BLOCK
            else                    -> Verdict.ALLOW
        }

        // V5.9.1355 P1 — PROVEN-DEAD TRAINABLE VETO. A statistically dead context
        // (losses>=20, wins<=1, mean<0) must stop taking NORMAL-size entries but
        // must NEVER be permanently disabled. Allow a 1-in-25 dust-probe so the
        // bucket keeps learning and can recover when metrics improve.
        var provenDead = false
        var normalEntryBlocked = false
        var probeAllowed = false
        if (isProvenDead(tradingMode, v3)) {
            provenDead = true
            val pdKey = "${tradingMode}|${LosingPatternMemory.scoreBand(v3)}"
            val n = deadContextCounter.computeIfAbsent(pdKey) { java.util.concurrent.atomic.AtomicLong(0) }.incrementAndGet()
            if (n % PROBE_EVERY == 0L) {
                probeAllowed = true
                objections += "PROVEN_DEAD_PROBE=$pdKey"
                try {
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("BRAIN_CONSENSUS_PROBE_ALLOWED")
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PROVEN_DEAD_CONTEXT_PROBE_ONLY")
                } catch (_: Throwable) {}
            } else {
                normalEntryBlocked = true
                objections += "PROVEN_DEAD_NORMAL_VETO=$pdKey"
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("BRAIN_CONSENSUS_NORMAL_ENTRY_VETO") } catch (_: Throwable) {}
            }
        }

        return ConsensusReport(
            verdict     = if (provenDead && verdict == Verdict.ALLOW) Verdict.SOFT_BLOCK else verdict,
            objections  = objections,
            mood        = mood,
            regime      = regime.name,
            v3Score     = v3,
            secondScore = secondScore,
            provenDead  = provenDead,
            normalEntryBlocked = normalEntryBlocked,
            probeAllowed = probeAllowed,
        )
    }

    // Soft-block counters surfaced in the pipeline-health dump so the
    // operator can see how often the brains object even when the trade
    // is still allowed through.
    private val softBlockCounter = java.util.concurrent.atomic.AtomicLong(0)
    private val hardBlockCounter = java.util.concurrent.atomic.AtomicLong(0)
    private val allowCounter     = java.util.concurrent.atomic.AtomicLong(0)
    fun recordOutcome(verdict: Verdict) {
        when (verdict) {
            Verdict.ALLOW       -> allowCounter.incrementAndGet()
            Verdict.SOFT_BLOCK  -> softBlockCounter.incrementAndGet()
            Verdict.HARD_BLOCK  -> hardBlockCounter.incrementAndGet()
        }
    }

    fun formatForPipelineDump(): String {
        val a = allowCounter.get(); val s = softBlockCounter.get(); val h = hardBlockCounter.get()
        if (a + s + h == 0L) return ""
        return "\n===== Brain Consensus Gate (V5.9.806) =====\n" +
               "  ALLOW=$a   SOFT_BLOCK=$s   HARD_BLOCK=$h\n" +
               "  → soft-block rate=${if (a+s+h > 0) String.format("%.1f%%", (s.toDouble() / (a+s+h)) * 100) else "0%"}" +
               "  hard-block rate=${if (a+s+h > 0) String.format("%.1f%%", (h.toDouble() / (a+s+h)) * 100) else "0%"}\n"
    }
}
