package com.lifecyclebot.engine

import com.lifecyclebot.engine.TokenMetricStageRouter.Stage
import com.lifecyclebot.engine.truth.ExitQuoteCorroboration7928
import com.lifecyclebot.engine.truth.FieldManual7715
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.v3.scoring.CashGenerationAI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7928 — exits never bank a scratch as a profit and never time out a position they cannot read. */
class Aate7928ExitCostTest {
    @Test fun profitLockUnderTheRoundTripIsNotArmed() {
        // A +1.6% lock on a 3% round trip: the peak (4%) has not cleared break-even + 1.5 → pre-trail stop.
        assertEquals(-12.0, FieldManual7715.costArmedStop7928(1.6, 4.0, 3.0) { -12.0 }, 1e-9)
        // The peak cleared it: lock at net break-even.
        assertEquals(3.5, FieldManual7715.costArmedStop7928(2.0, 6.0, 3.0) { -12.0 }, 1e-9)
        // A lock above the round trip is untouched; a negative stop is untouched.
        assertEquals(9.0, FieldManual7715.costArmedStop7928(9.0, 20.0, 3.0) { -12.0 }, 1e-9)
        assertEquals(-10.0, FieldManual7715.costArmedStop7928(-10.0, 1.0, 3.0) { -12.0 }, 1e-9)
        // A pre-trail read is never a positive lock.
        assertEquals(0.0, FieldManual7715.costArmedStop7928(1.0, 2.0, 3.0) { 2.0 }, 1e-9)
    }

    @Test fun liveTakeProfitClearsTheRoundTrip() {
        val small = CashGenerationAI.liveTpFloor7928(0.044)
        assertTrue(small > FieldManual7715.roundTripCostPct7766(0.044, 0.0, 0.0))
        assertTrue(small >= 3.0)
    }

    @Test fun timeExitSweepNeedsAReadableRedPnl() {
        assertFalse(CashGenerationAI.sweepMaySell7928("TIME_EXIT", false, 0.0))
        assertFalse(CashGenerationAI.sweepMaySell7928("TIME_EXIT", true, 1.2))
        assertTrue(CashGenerationAI.sweepMaySell7928("TIME_EXIT", true, -2.0))
        assertTrue(CashGenerationAI.sweepMaySell7928("STOP_LOSS", false, 0.0))
    }

    @Test fun tokenAccountRentIsNotPriceOrProceeds() {
        // Buy: 0.044 SOL swap + 2,039,280 lamports of new-account rent.
        assertEquals(44_005_000L, TradeVerifier.buySolSpent7928(1_000_000_000L, 1_000_000_000L - 44_005_000L - 2_039_280L, 2_039_280L))
        // A rent read larger than the delta is not this tx's rent.
        assertEquals(1_000L, TradeVerifier.buySolSpent7928(10_000L, 9_000L, 2_039_280L))
        assertEquals(50_000_000L, TradeVerifier.sellSolReceived7928(0L, 52_039_280L, 2_039_280L))
        assertEquals(52_039_280L, TradeVerifier.sellSolReceived7928(0L, 52_039_280L, 0L))
    }

    @Test fun deepLiveStopsAreCheckedAgainstTheRoute() {
        assertTrue(ExitQuoteCorroboration7928.applies("RAPID_CATASTROPHE_STOP", -82.0, live = true))
        assertTrue(ExitQuoteCorroboration7928.applies("HARD_STOP", -12.0, live = true))
        assertFalse(ExitQuoteCorroboration7928.applies("HARD_STOP", -5.0, live = true))
        assertFalse(ExitQuoteCorroboration7928.applies("HARD_STOP", -30.0, live = false))
        assertFalse(ExitQuoteCorroboration7928.applies("TAKE_PROFIT", -30.0, live = true))
        assertFalse(ExitQuoteCorroboration7928.applies("MANUAL_STOP", -30.0, live = true))
        // 7thx: trigger -82%, route values the bag at -6% -> contradicted.
        assertEquals(-6.0, ExitQuoteCorroboration7928.quotePnlPct(0.047, 0.05), 1e-9)
        assertTrue(ExitQuoteCorroboration7928.contradicts(-82.0, -6.0))
        assertFalse(ExitQuoteCorroboration7928.contradicts(-12.0, -10.0))
        // No quote never blocks the sell.
        assertTrue(ExitQuoteCorroboration7928.allowSell("m7928a", "X", "HARD_STOP", -40.0, 0.05) { null })
        assertFalse(ExitQuoteCorroboration7928.allowSell("m7928b", "X", "HARD_STOP", -40.0, 0.05) { 0.049 })
        assertTrue(ExitQuoteCorroboration7928.allowSell("m7928c", "X", "HARD_STOP", -40.0, 0.05) { 0.031 })
    }

    @Test fun anObservedMarkIsNotAReceipt() {
        assertTrue(observedMarkBasis7928("OBSERVED_MARK_ADOPTION_7706"))
        assertFalse(observedMarkBasis7928("LIVE_PROOF_COST_BASIS"))
    }

    @Test fun lanesBuyTheirStageAndLearnPastTheSheet() {
        val sheet = TokenMetricStageRouter.LANE_STAGE_SHEET_7928
        assertTrue("SHITCOIN" in sheet.getValue(Stage.FRESH_LAUNCH))
        assertFalse("SHITCOIN" in sheet.getValue(Stage.MID_ACCUMULATION))
        assertNotNull(TokenMetricStageRouter.judgeStage7928("SHITCOIN", Stage.PEAK_EXHAUSTION, false, null))
        assertNull(TokenMetricStageRouter.judgeStage7928("SHITCOIN", Stage.FRESH_LAUNCH, true, null))
        assertNull(TokenMetricStageRouter.judgeStage7928("SHITCOIN", Stage.UNKNOWN, false, null))
        assertNull(TokenMetricStageRouter.judgeStage7928("CASHGEN", Stage.CONTROLLED_MARKUP, false, null))
        assertNotNull(TokenMetricStageRouter.judgeStage7928("BLUECHIP", Stage.RUG_PRONE, false, stat(200, 9.0)))
        // Learned both ways.
        assertNull(TokenMetricStageRouter.judgeStage7928("SHITCOIN", Stage.PEAK_EXHAUSTION, false, stat(60, 4.0)))
        assertNotNull(TokenMetricStageRouter.judgeStage7928("SHITCOIN", Stage.FRESH_LAUNCH, true, stat(60, -6.0)))
        assertEquals("STAGE|SHITCOIN|FRESH_LAUNCH", ForwardReturnLabeler7731.stageKey7928("shitcoin", "FRESH_LAUNCH"))
    }

    private fun stat(n: Int, mean: Double) = ForwardReturnLabeler7731.CellStat("s", n, mean, 0.5, 0.05, 1.0, 0)
}
