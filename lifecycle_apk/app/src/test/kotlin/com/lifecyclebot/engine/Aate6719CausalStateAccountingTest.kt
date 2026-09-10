package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6719 — §CAUSAL_STATE_ACCOUNTING.
 *
 * Diagnosis from operator: 5.0.6718 dump showed
 *   EXEC_GATE allow=56 block=813 (93.6% blocked)
 *   Of 813 blocks: UNRESOLVED_FEEDBACK_CAP_6715=663 (81.5%)
 *                  FEEDBACK_IDENTITY_DRIFT_REVALIDATE_6715=108 (13.3%)
 *
 * Both are state-accounting bugs in CausalFeedbackAuthority6715, not
 * threshold policy:
 *
 *   Bug 1 (identity drift): admit() rejected the attempt whenever the
 *   admit-time scoreBand differed from the stamp-time scoreBand. But
 *   entryScore legitimately drifts between decision-stamp and admit as
 *   V3 ticks arrive with fresher marks, so this fired on ~13% of valid
 *   attempts. Fix: absorb score-band drift by using the STAMPED band
 *   for scope lookup; only trigger identity-drift on mode/lane
 *   mismatch (the real integrity boundary).
 *
 *   Bug 2 (ghost-position cap inflation): admit() computed
 *   laneUnresolved = maxOf(openPositions.size, canonicalLaneOpen) +
 *   reservedAttempts.size. When attachPosition failed for a position
 *   (attribution missing, MEME_REGISTRY_RESTORE, pre-authority open),
 *   it lived in canonicalLaneOpen but NEVER in this authority's own
 *   openPositions set. Those GHOST positions could never reach
 *   onTerminal through this authority (no matching envelope path) →
 *   they inflated the cap FOREVER. The cap ratchet stayed saturated
 *   and every fresh admit hit UNRESOLVED_FEEDBACK_CAP_6715. Fix: count
 *   only the authority's OWN tracked openPositions against the cap.
 *   The cap value is unchanged — no threshold tuning.
 *
 * Non-regressions:
 *   - Same cap formula: cap = 1 + floor(sqrt(cleanLearnedCloses)),
 *     clamped 1..6 (lane) / 1..3 (band).
 *   - No qty/price/cost field written.
 *   - Learning-pending gate still fires when this authority's own
 *     scope has pendingLearning.
 *   - Stale-epoch and mode/lane identity-drift gates still fire.
 */
class Aate6719CausalStateAccountingTest {

    private val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CausalFeedbackAuthority6715.kt").readText()

    @Test
    fun `identity drift only fires on mode or lane mismatch not score-band drift`() {
        assertTrue(
            "V5.0.6719 §CAUSAL_STATE_ACCOUNTING marker must appear",
            src.contains("V5.0.6719 §CAUSAL_STATE_ACCOUNTING"),
        )
        // Identity-drift comparison must no longer include scoreBand.
        val admitFn = src.substringAfter("fun admit(attemptId: String, mint: String, mode: String, lane: String, score: Int): Admission")
            .substringBefore("\n    /** Exact canonical OPEN boundary")
        assertTrue(
            "Identity drift check must be mode+lane only",
            admitFn.contains("if (stamp.mode != nm || stamp.lane != nl)") &&
                admitFn.contains("IDENTITY_DRIFT_MODE_OR_LANE"),
        )
        assertFalse(
            "Legacy triple check (mode || lane || scoreBand) must be removed",
            admitFn.contains("stamp.mode != nm || stamp.lane != nl || stamp.scoreBand != band"),
        )
    }

    @Test
    fun `admit uses stamped scoreBand for scope lookup absorbing score drift`() {
        val admitFn = src.substringAfter("fun admit(attemptId: String, mint: String, mode: String, lane: String, score: Int): Admission")
            .substringBefore("\n    /** Exact canonical OPEN boundary")
        assertTrue(
            "Admit must compute admitBand then use stamp?.scoreBand ?: admitBand",
            admitFn.contains("val admitBand = scoreBand(score)") &&
                admitFn.contains("val band = stamp?.scoreBand ?: admitBand"),
        )
    }

    @Test
    fun `cap counting drops ghost positions from CanonicalPositionAuthority`() {
        val admitFn = src.substringAfter("fun admit(attemptId: String, mint: String, mode: String, lane: String, score: Int): Admission")
            .substringBefore("\n    /** Exact canonical OPEN boundary")
        // The maxOf(openPositions.size, canonicalLaneOpen) inflation must be gone.
        assertFalse(
            "Legacy maxOf(openPositions.size, canonicalLaneOpen) inflation must be removed",
            admitFn.contains("maxOf(laneState.openPositions.size, canonicalLaneOpen)"),
        )
        assertTrue(
            "laneUnresolved must count only authority's own tracked openPositions",
            admitFn.contains("val laneUnresolved = laneState.openPositions.size + laneState.reservedAttempts.size"),
        )
        // The CanonicalPositionAuthority6441 lookup must be gone entirely from admit.
        assertFalse(
            "CanonicalPositionAuthority6441.openPositions() must not be called from admit any more",
            admitFn.contains("CanonicalPositionAuthority6441.openPositions()"),
        )
    }
}
