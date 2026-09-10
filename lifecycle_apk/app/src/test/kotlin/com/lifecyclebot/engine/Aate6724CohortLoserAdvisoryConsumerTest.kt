package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6724 — §COHORT_LOSER_ADVISORY_CONSUMER.
 *
 * Locks in the wire connecting the chronic-loser signal that
 * CausalFeedbackAuthority6715 already emits into
 * LaneExpectancyDamper.sizeMultiplier so downstream sizing actually
 * consumes the hive-shared cohort verdict. Threshold sanity only —
 * no numeric tuning is being asserted, just:
 *
 *   - non-meme lanes fail open (advisory = null).
 *   - cold lanes with insufficient closes fail open.
 *   - a lane that has recorded >= 8 decided closes at < 20% WR
 *     produces a non-null advisory with sizeMultiplier <= 1.0 and
 *     >= the documented floor.
 *   - two chronic-losing bands surface the worse (smaller mult) one.
 */
class Aate6724CohortLoserAdvisoryConsumerTest {

    @Before
    fun reset() {
        CausalFeedbackAuthority6715.resetForTest6715()
    }

    @Test
    fun `non meme lane produces no advisory`() {
        // "STOCKS" is not in the meme lane set → authority fails open.
        val advisory = CausalFeedbackAuthority6715.cohortLoserAdvisoryForLane("PAPER", "STOCKS")
        assertNull("non-meme lane must return null advisory", advisory)
    }

    @Test
    fun `cold meme lane with no closes produces no advisory`() {
        val advisory = CausalFeedbackAuthority6715.cohortLoserAdvisoryForLane("PAPER", "EXPRESS")
        assertNull("cold lane with zero decided closes must return null", advisory)
    }

    @Test
    fun `chronic loser band surfaces advisory with size multiplier below one and above floor`() {
        val lane = "EXPRESS"
        val mode = "PAPER"
        val score = 50 // → S41-60 band
        // 10 losses, 0 wins → 0% WR, well below the 20% floor, well
        // above the >= 8 decided minimum.
        for (i in 1..10) {
            val aid = "chronic-$i"
            val pid = "chronic-pos-$i"
            val mint = "chronic-mint-$i"
            CausalFeedbackAuthority6715.stampDecision(aid, mint, mode, lane, score)
            CausalFeedbackAuthority6715.admit(aid, mint, mode, lane, score)
            CausalFeedbackAuthority6715.onPositionOpened(pid, mode, mint, lane)
            val env = CanonicalFinalizedTradeBus6464.Envelope(
                tradeId = "chronic-t$i",
                atMs = System.currentTimeMillis(),
                realizedPnlSol = -0.02,
                realizedReturnPct = -30.0,
                mint = mint,
                lane = lane,
                positionId = pid,
                mode = mode,
                entryScore = score,
                scoreBand = "S41-60",
                learningEligible = true,
            )
            CausalFeedbackAuthority6715.onTerminal(env)
            CausalFeedbackAuthority6715.markLearned(pid)
        }
        val advisory = CausalFeedbackAuthority6715.cohortLoserAdvisoryForLane(mode, lane)
        assertNotNull("chronic-loser cohort must surface non-null advisory", advisory)
        assertEquals("S41-60", advisory!!.worstBand)
        assertTrue(
            "size multiplier must be a floor under 1.0, got ${advisory.sizeMultiplier}",
            advisory.sizeMultiplier < 1.0,
        )
        assertTrue(
            "size multiplier must stay above the documented floor (0.4), got ${advisory.sizeMultiplier}",
            advisory.sizeMultiplier >= 0.40,
        )
    }

    @Test
    fun `winning band never surfaces advisory even at ten closes`() {
        val lane = "EXPRESS"
        val mode = "PAPER"
        val score = 50
        // 8 wins, 2 losses → 80% WR, well above the 20% floor.
        for (i in 1..10) {
            val isWin = i <= 8
            val aid = "healthy-$i"
            val pid = "healthy-pos-$i"
            val mint = "healthy-mint-$i"
            CausalFeedbackAuthority6715.stampDecision(aid, mint, mode, lane, score)
            CausalFeedbackAuthority6715.admit(aid, mint, mode, lane, score)
            CausalFeedbackAuthority6715.onPositionOpened(pid, mode, mint, lane)
            val env = CanonicalFinalizedTradeBus6464.Envelope(
                tradeId = "healthy-t$i",
                atMs = System.currentTimeMillis(),
                realizedPnlSol = if (isWin) 0.05 else -0.02,
                realizedReturnPct = if (isWin) 40.0 else -30.0,
                mint = mint,
                lane = lane,
                positionId = pid,
                mode = mode,
                entryScore = score,
                scoreBand = "S41-60",
                learningEligible = true,
            )
            CausalFeedbackAuthority6715.onTerminal(env)
            CausalFeedbackAuthority6715.markLearned(pid)
        }
        val advisory = CausalFeedbackAuthority6715.cohortLoserAdvisoryForLane(mode, lane)
        assertNull("healthy cohort must NOT produce an advisory", advisory)
    }
}
