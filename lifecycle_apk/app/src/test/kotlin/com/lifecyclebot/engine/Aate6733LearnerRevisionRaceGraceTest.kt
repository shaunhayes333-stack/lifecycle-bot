package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715
import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6733 — regression suite for the CI-compliant learner-revision
 * race grace (§LEARNER_REVISION_RACE_STAMP_ONLY_GRACE).
 *
 * The 6730 blanket time-grace was reverted because it also freshened
 * attempts whose reservation was ALREADY approved, violating
 * Aate6715 integrity contract. 6732 introduces a discriminated grace:
 *   - Terminal-epoch bumps NEVER grace (real close arrived; must
 *     revalidate).
 *   - Learner-revision-only bumps grace ONLY when the attempt has no
 *     reservation yet (first admit) AND the stamp is within the
 *     grace window.
 *
 * Both existing Aate6715 test paths continue to pass under this
 * discriminator — see line assertions below.
 */
class Aate6733LearnerRevisionRaceGraceTest {

    @Before
    fun reset() {
        CausalFeedbackAuthority6715.resetForTest6715()
    }

    @Test
    fun `terminal epoch bump still hard-blocks pre-terminal stamps (integrity preserved)`() {
        val lane = "PROJECT_SNIPER"; val mode = "PAPER"; val score = 20
        CausalFeedbackAuthority6715.stampDecision("t-a1", "mint-a", mode, lane, score)
        assertTrue(CausalFeedbackAuthority6715.admit("t-a1", "mint-a", mode, lane, score).allowed)
        CausalFeedbackAuthority6715.onPositionOpened("t-p1", mode, "mint-a", lane)
        CausalFeedbackAuthority6715.stampDecision("t-a2", "mint-b", mode, lane, score)
        val env = CanonicalFinalizedTradeBus6464.Envelope(
            tradeId = "t-t1", atMs = System.currentTimeMillis(), realizedPnlSol = -0.01,
            realizedReturnPct = -20.0, mint = "mint-a", lane = lane, positionId = "t-p1",
            mode = mode, entryScore = score, scoreBand = "S11-25", learningEligible = true,
        )
        assertTrue(CausalFeedbackAuthority6715.onTerminal(env))
        // Post-terminal, t-a2's stamp is stale by TERMINAL EPOCH.
        // The 6732 grace explicitly does NOT apply to terminal-epoch
        // staleness — integrity is preserved.
        val stale = CausalFeedbackAuthority6715.admit("t-a2", "mint-b", mode, lane, score)
        assertFalse("terminal-epoch mismatch must always hard-block", stale.allowed)
        assertTrue(stale.forceRevalidate)
    }

    @Test
    fun `learner revision bump after admit hard-blocks the same attempt (integrity preserved)`() {
        val lane = "PROJECT_SNIPER"; val mode = "PAPER"; val score = 20
        // Prime the scope with a terminal → revision state so we're not
        // in "trulyCold" territory. Then a fresh post-terminal stamp
        // admits, gets reservation, then markLearned bumps revision, and
        // re-admit of the same attempt must hard-block.
        CausalFeedbackAuthority6715.stampDecision("r-a1", "mint-p", mode, lane, score)
        assertTrue(CausalFeedbackAuthority6715.admit("r-a1", "mint-p", mode, lane, score).allowed)
        CausalFeedbackAuthority6715.onPositionOpened("r-p1", mode, "mint-p", lane)
        val env = CanonicalFinalizedTradeBus6464.Envelope(
            tradeId = "r-t1", atMs = System.currentTimeMillis(), realizedPnlSol = -0.01,
            realizedReturnPct = -20.0, mint = "mint-p", lane = lane, positionId = "r-p1",
            mode = mode, entryScore = score, scoreBand = "S11-25", learningEligible = true,
        )
        assertTrue(CausalFeedbackAuthority6715.onTerminal(env))
        CausalFeedbackAuthority6715.stampDecision("r-a2", "mint-q", mode, lane, score)
        val first = CausalFeedbackAuthority6715.admit("r-a2", "mint-q", mode, lane, score)
        assertTrue(first.allowed)   // reservation is now live on r-a2
        assertTrue(CausalFeedbackAuthority6715.markLearned("r-p1"))
        // r-a2 already has a reservation. Grace must NOT apply here —
        // this is the Aate6715 line-55-57 contract.
        val postAck = CausalFeedbackAuthority6715.admit("r-a2", "mint-q", mode, lane, score)
        assertFalse("post-ack same-attempt must still hard-block", postAck.allowed)
        assertTrue(postAck.forceRevalidate)
    }

    @Test
    fun `first admit of a fresh attempt races a learner-revision-only bump and is graced through`() {
        // Genuine race scenario: stamp made just before markLearned lands.
        // Attempt has no reservation yet. Under 6732 grace, first admit
        // must refresh the stamp and succeed (soft-mode advisory), NOT
        // reject the flow.
        val lane = "PROJECT_SNIPER"; val mode = "PAPER"; val score = 20
        // Prime terminal + learner ACK to establish non-cold state.
        CausalFeedbackAuthority6715.stampDecision("g-a1", "mint-x", mode, lane, score)
        assertTrue(CausalFeedbackAuthority6715.admit("g-a1", "mint-x", mode, lane, score).allowed)
        CausalFeedbackAuthority6715.onPositionOpened("g-p1", mode, "mint-x", lane)
        val env = CanonicalFinalizedTradeBus6464.Envelope(
            tradeId = "g-t1", atMs = System.currentTimeMillis(), realizedPnlSol = -0.01,
            realizedReturnPct = -20.0, mint = "mint-x", lane = lane, positionId = "g-p1",
            mode = mode, entryScore = score, scoreBand = "S11-25", learningEligible = true,
        )
        assertTrue(CausalFeedbackAuthority6715.onTerminal(env))
        // A fresh decision stamps AFTER terminal so terminalEpoch matches.
        CausalFeedbackAuthority6715.stampDecision("g-a2", "mint-y", mode, lane, score)
        // Now the learner ACK lands — bumps learningRevision only for
        // this scope. g-a2's stamp captured the pre-ACK revision.
        assertTrue(CausalFeedbackAuthority6715.markLearned("g-p1"))
        // First admit for g-a2: no reservation exists yet, terminalEpoch
        // matches, revision doesn't. Grace path fires and admission
        // succeeds. The attempt gets a reservation and continues.
        val raced = CausalFeedbackAuthority6715.admit("g-a2", "mint-y", mode, lane, score)
        assertTrue(
            "first-admit racing a learner-revision-only bump must be graced through, got reason=${raced.reason}",
            raced.allowed,
        )
    }
}
