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
 *   HARD_BLOCK  — multiple brains object OR a single brain hits a hard
 *                 rule (strategy disabled, mood HUMBLED while regime DUMP,
 *                 etc). Trade is rejected with reason BRAIN_CONSENSUS_VETO.
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
    )

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

        // V5.9.1052 — BOOTSTRAP ESCAPE HATCH.
        // mood=HUMBLED/SELF_CRITICAL + regime=DUMP is a valid hard-block in maturity.
        // In bootstrap (<200 lifetime settled trades) it creates a circular lock:
        // blocked entries → no learning → WR stays low → mood stays HUMBLED → never unblocks.
        // Fix: downgrade to SOFT_BLOCK during bootstrap so trades still flow for learning.
        val lifetimeTrades = try {
            (com.lifecyclebot.engine.CanonicalLearningCounters.settledWins.get() + com.lifecyclebot.engine.CanonicalLearningCounters.settledLosses.get()).toInt()
        } catch (_: Throwable) { 999 }
        // V5.9.1064 — raise bootstrap threshold 200 → 1000.
        // At 648 trades / 6% WR the bot is still deep bootstrap; WR hasn't
        // converged. The 200-trade cutoff was causing mood=HUMBLED+regime=DUMP
        // to fire as a HARD_BLOCK from trade 201 onward, blocking 100% of
        // entries during the exact window where the bot most needs to learn.
        val inBootstrap = lifetimeTrades < 1000
        if (mood in setOf("HUMBLED", "SELF_CRITICAL") && regime == RegimeDetector.Regime.DUMP) {
            if (inBootstrap) {
                objections += "SENTIENCE_ADVISORY=mood=$mood+regime=DUMP+bootstrap(trades=$lifetimeTrades<1000)"
            } else {
                objections += "SENTIENCE_VETO=mood=$mood+regime=DUMP"
            }
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
        // HARD_BLOCK triggers (any one of these is enough):
        //   • strategy auto-disabled (P1)
        //   • mood-veto in DUMP regime (P0 sentience)
        //   • SecondScorer disagrees AND we're in CHOP/DUMP regime
        // V5.9.1052 — mood+DUMP is only HARD_BLOCK in maturity (>= 200 lifetime trades).
        // In bootstrap, mood+DUMP adds an advisory objection (SOFT_BLOCK) so trades still
        // flow for learning, breaking the circular lock.
        // V5.9.1053: isStrategyDead removed from hardBlock — no strategy auto-disabled.
        // V5.9.1064 — hardBlock uses same inBootstrap flag (now < 1000 trades).
        // V5.9.1070 — SecondScorer disagreement removed from hardBlock.
        // SecondScorer fires on nearly every fresh pump.fun token because
        // pool age=0, holders=0, vol=0 on first intake → secondScore≈0-15
        // while V3 scores +30-50 → gap>=20 → disputed=true → HARD_BLOCK in
        // DUMP/CHOP. This was the second major executor lock after SENTIENCE_VETO.
        // The BCG comment already called it "telemetry first round" — restoring
        // that intent. Second-scorer objection remains as a SOFT_BLOCK tag so
        // it is visible in the dump but never gates a trade.
        // Fluid doctrine: gates lower/raise dynamically; they never lock out.
        val hardBlock =
            (!inBootstrap && mood in setOf("HUMBLED", "SELF_CRITICAL") && regime == RegimeDetector.Regime.DUMP)

        // SOFT_BLOCK = at least one objection but doesn't hit HARD_BLOCK
        // (logged but doesn't gate the trade — pure telemetry first round).
        val verdict = when {
            hardBlock              -> Verdict.HARD_BLOCK
            objections.isNotEmpty() -> Verdict.SOFT_BLOCK
            else                    -> Verdict.ALLOW
        }

        return ConsensusReport(
            verdict     = verdict,
            objections  = objections,
            mood        = mood,
            regime      = regime.name,
            v3Score     = v3,
            secondScore = secondScore,
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
