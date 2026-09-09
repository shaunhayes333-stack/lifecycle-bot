#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/com/lifecyclebot"
TEST = ROOT / "app/src/test/kotlin/com/lifecyclebot/engine/Aate6710WinrateRegressionLockTest.kt"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source match, found {count}")
    path.write_text(text.replace(old, new, 1))


# 1) Restore the proven V5.9.1221 collapse behavior at its source.
# V5.9.1223 changed a catastrophic rolling-WR collapse from selective entry
# blocking to tiny canonical probes. The 2026-09-10 runtime showed the direct
# consequence: roll-window 0%, 61 straight losses, yet FDG allowed 553/559.
# Scanner/lane discovery remains alive; only weak canonical opens are stopped.
executor = SRC / "engine/Executor.kt"
replace_once(
    executor,
    "            s.rollingCollapse -> 35\n",
    "            s.rollingCollapse -> 60\n",
    "WR_COLLAPSE_SCORE_FLOOR_6710",
)
replace_once(
    executor,
    '''        // V5.9.1223: collapse is probe mode, not disable mode. Keep
        // thin-regime auto-fit so the bot continues learning in bad markets,
        // just at tiny size and under FDG shaping.
        val delta = when {
''',
    '''        // V5.0.6710 — catastrophic rolling collapse is quality mode.
        // Do not relax the score floor merely because the current candidate
        // distribution is thin: that is exactly how a 0%-WR cohort keeps
        // admitting more weak entries. Rich regimes may still tighten further.
        val delta = if (s.rollingCollapse) {
            if (median > 50) +10 else 0
        } else when {
''',
    "WR_COLLAPSE_THIN_RELAXATION_6710",
)

fdg = SRC / "engine/FinalDecisionGate.kt"
replace_once(
    fdg,
    '''            // V5.9.1223 — collapse is not lane-disable mode. Operator rule:
            // keep learning; do not disable lanes. When roll50 is catastrophic,
            // let non-A setups continue as micro-probes with harsher size
            // shaping instead of hard-blocking them.
            if (wrState.rollingCollapse && !isAGrade) {
                val collapseQualityMult = when (candidate.setupQuality) {
                    "B"  -> 0.35
                    "C"  -> 0.22
                    "D"  -> 0.12
                    else -> 0.20
                }
                wrRecoveryQualityPenaltyMult = minOf(wrRecoveryQualityPenaltyMult, collapseQualityMult)
                tags.add("wr_roll50_collapse_probe")
                checks.add(GateCheck("wr_roll50_collapse_probe", true, "roll50=${"%.1f".format(wrState.rollingWr)}% target=${wrState.targetWr.toInt()}% quality=${candidate.setupQuality} size×${"%.2f".format(wrRecoveryQualityPenaltyMult)}"))
                ErrorLogger.info(
                    "FDG",
                    "🛑 WR_ROLL50_COLLAPSE_PROBE: ${ts.symbol} | roll50=${"%.1f".format(wrState.rollingWr)}% target=${wrState.targetWr.toInt()}% quality=${candidate.setupQuality} size×${"%.2f".format(wrRecoveryQualityPenaltyMult)}"
                )
            } else if (isHighRecovery && !isAGrade) {
''',
    '''            // V5.0.6710 — restore V5.9.1221's selective collapse authority.
            // A catastrophic rolling cohort is evidence the CURRENT entry policy
            // is broken, not a request for more canonical low-grade probes. Keep
            // discovery/shadow evidence flowing, but require A/A+ for new capital
            // until the rolling cohort recovers automatically.
            if (wrState.rollingCollapse && !isAGrade) {
                blockReason = "WR_ROLL50_COLLAPSE_A_GRADE_REQUIRED roll=${"%.1f".format(wrState.rollingWr)} target=${wrState.targetWr.toInt()} quality=${candidate.setupQuality}"
                blockLevel = BlockLevel.CONFIDENCE
                tags.add("wr_roll50_collapse")
                checks.add(GateCheck("wr_roll50_collapse", false, "roll50=${"%.1f".format(wrState.rollingWr)}% target=${wrState.targetWr.toInt()}% requires A/A+ setup"))
                ErrorLogger.info(
                    "FDG",
                    "🛑 WR_ROLL50_COLLAPSE_BLOCK: ${ts.symbol} | roll50=${"%.1f".format(wrState.rollingWr)}% target=${wrState.targetWr.toInt()}% quality=${candidate.setupQuality}"
                )
            } else if (isHighRecovery && !isAGrade) {
''',
    "FDG_COLLAPSE_PROBE_REVERT_6710",
)
replace_once(
    fdg,
    "        var finalSize = proposedSizeSol * wrRecoveryQualityPenaltyMult  // V5.9.809/1223: soft WR-recovery/collapse probe size penalty\n",
    "        var finalSize = proposedSizeSol * wrRecoveryQualityPenaltyMult  // V5.0.6710: soft non-collapse WR-recovery size penalty\n",
    "FDG_STALE_COLLAPSE_COMMENT_6710",
)

# 2) Close the reward-purity hole. CanonicalPaperTransaction already calls
# EconomicPurityGate6504.markUntrusted() for stale/unverified exits, but the
# finality learner path never consulted the gate. This allowed known-bad closes
# to become canonical W/L and poisoned every downstream learner.
eligibility = SRC / "engine/truth/PaperLearningEligibility6519.kt"
replace_once(
    eligibility,
    '''        if (!recorded.eligible) return recorded
        if (pid.isNotBlank() && !CanonicalPerformanceFilter6395.isCanonicalEligible(pid))
''',
    '''        if (!recorded.eligible) return recorded
        // V5.0.6710 — ECONOMIC PURITY IS LEARNING AUTHORITY.
        // Stale/synthetic/unreconciled terminal economics may close inventory,
        // but must never become canonical W/L/PF/EV or train strategy heads.
        val economicExcluded6710 = try {
            EconomicPurityGate6504.shouldExcludeFromAnalytics(mint)
        } catch (_: Throwable) { true }
        if (economicExcluded6710) return Decision(false, "ECONOMIC_PURITY_EXCLUDED_6504")
        if (pid.isNotBlank() && !CanonicalPerformanceFilter6395.isCanonicalEligible(pid))
''',
    "PAPER_ELIGIBILITY_ECONOMIC_PURITY_6710",
)

reward = SRC / "engine/truth/RewardPurityGate6441.kt"
replace_once(
    reward,
    '''            return false
        }
        // V5.0.6699 — per-terminal-event proof survives process restart. The
''',
    '''            return false
        }
        // V5.0.6710 — defence in depth at the canonical reward boundary.
        // EconomicPurityGate6504 is marked by stale/unverified close paths.
        // A CLOSED lifecycle alone is not proof that the exit price was valid.
        val economicExcluded6710 = try {
            EconomicPurityGate6504.shouldExcludeFromAnalytics(pos.mint)
        } catch (_: Throwable) { true }
        if (economicExcluded6710) {
            rejectedAccounting.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("REWARD_PURITY_REJECT_ECONOMIC_6710")
                ForensicLogger.lifecycle(
                    "REWARD_PURITY_REJECT_ECONOMIC_6710",
                    "positionId=$positionId mint=${pos.mint.take(12)} action=exclude_from_canonical_learning",
                )
            } catch (_: Throwable) {}
            return false
        }
        // V5.0.6699 — per-terminal-event proof survives process restart. The
''',
    "REWARD_PURITY_ECONOMIC_GATE_6710",
)

# 3) Source regression lock: pin the exact two contradictions that caused this
# regression so a later patch-rot sweep cannot silently reintroduce them.
TEST.write_text(r'''package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.6710 — catastrophic WR collapse + reward-purity regression locks. */
class Aate6710WinrateRegressionLockTest {

    @Test
    fun `catastrophic rolling collapse requires high grade canonical entries`() {
        val fdg = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(fdg.contains("WR_ROLL50_COLLAPSE_A_GRADE_REQUIRED"))
        assertTrue(fdg.contains("WR_ROLL50_COLLAPSE_BLOCK"))
        assertFalse(fdg.contains("WR_ROLL50_COLLAPSE_PROBE"))

        val exec = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(exec.contains("s.rollingCollapse -> 60"))
        assertTrue(exec.contains("val delta = if (s.rollingCollapse)"))
        assertFalse(exec.contains("s.rollingCollapse -> 35"))
    }

    @Test
    fun `economically untrusted closes cannot enter canonical learners`() {
        val eligibility = File("src/main/kotlin/com/lifecyclebot/engine/truth/PaperLearningEligibility6519.kt").readText()
        val reward = File("src/main/kotlin/com/lifecyclebot/engine/truth/RewardPurityGate6441.kt").readText()
        assertTrue(eligibility.contains("EconomicPurityGate6504.shouldExcludeFromAnalytics(mint)"))
        assertTrue(eligibility.contains("ECONOMIC_PURITY_EXCLUDED_6504"))
        assertTrue(reward.contains("EconomicPurityGate6504.shouldExcludeFromAnalytics(pos.mint)"))
        assertTrue(reward.contains("REWARD_PURITY_REJECT_ECONOMIC_6710"))
    }
}
''')

print("V5.0.6710 WR-collapse and reward-purity repair applied")
