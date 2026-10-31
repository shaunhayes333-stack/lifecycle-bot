package com.lifecyclebot.engine

import com.lifecyclebot.engine.TokenMetricStageRouter.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8473 — owner: "its not buying or discovering tokens in the right part of the chart / token life cycle". 8471: 55,765
 * UNKNOWN stagings; the unstaged coins were the entries — at their high early in a move (peakPos 0.96-1.00, runup 24-31 %,
 * buyers 66-100 %) or a tight base with heavy buying (runup 1 %, peakPos 0.98, bp 80), all with an unread launch age.
 */
class Aate8473ChartStageTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val nan = Double.NaN

    @Test fun anEarlyBreakoutAtItsHighIsAMarkup() {
        // 8471's unstaged coins: Biscuit (0.96, +31 %, bp 66) and PARANOID (1.00, +24 %, bp 100)
        assertEquals(Stage.CONTROLLED_MARKUP, TokenMetricStageRouter.chartStage8473(8, true, nan, 0.96, 31.0, 4.0, 66.0, 33.0, 2.0, 16_138.0))
        assertEquals(Stage.CONTROLLED_MARKUP, TokenMetricStageRouter.chartStage8473(8, true, nan, 1.00, 24.0, 0.0, 100.0, 0.0, 5.0, 58_224.0))
        assertNull("extended: exhaustion's territory", TokenMetricStageRouter.chartStage8473(8, true, nan, 0.99, 75.0, 1.0, 70.0, 30.0, 5.0, 50_000.0))
        assertNull("sellers in control", TokenMetricStageRouter.chartStage8473(8, true, nan, 0.99, 30.0, 1.0, 45.0, 55.0, 5.0, 50_000.0))
        assertNull("thin pool", TokenMetricStageRouter.chartStage8473(8, true, nan, 0.99, 30.0, 1.0, 70.0, 30.0, 5.0, 4_000.0))
    }

    @Test fun aTightBaseWithBuyersIsABaseAtAnyAge() {
        // LARPCHECK in 8471: runup 1 %, peakPos 0.98, bp 80, liq $70k, age unread
        assertEquals(Stage.BASE_START, TokenMetricStageRouter.chartStage8473(8, true, nan, 0.98, 1.0, 2.0, 80.0, 20.0, 0.5, 70_295.0))
        assertEquals("past the launch window", Stage.BASE_START, TokenMetricStageRouter.chartStage8473(8, true, 95.0, 0.95, 6.0, 5.0, 60.0, 40.0, 0.0, 9_000.0))
        assertNull("inside the window the age-gated BASE_START band decides", TokenMetricStageRouter.chartStage8473(8, true, 6.0, 0.95, 6.0, 5.0, 60.0, 40.0, 0.0, 9_000.0))
        assertNull("off the high: a pullback, not a base", TokenMetricStageRouter.chartStage8473(8, true, nan, 0.80, 6.0, 20.0, 60.0, 40.0, 0.0, 9_000.0))
    }

    @Test fun noChartOrNoBookIsNoChartStage() {
        assertNull("too few prints", TokenMetricStageRouter.chartStage8473(4, true, nan, 1.0, 24.0, 0.0, 100.0, 0.0, 5.0, 58_224.0))
        assertNull("default book", TokenMetricStageRouter.chartStage8473(8, false, nan, 1.0, 24.0, 0.0, 100.0, 0.0, 5.0, 58_224.0))
        assertNull("an acute drop", TokenMetricStageRouter.chartStage8473(8, true, nan, 1.0, 24.0, 0.0, 100.0, 0.0, -9.0, 58_224.0))
        assertNull(TokenMetricStageRouter.chartStage8473(8, true, nan, 1.0, 24.0, 0.0, 100.0, 0.0, nan, 58_224.0))
        // 8484: a coin frozen for hours is not a base (8483 bought COLORS / ANON as BASE_START) — no live tape, no chart stage
        assertNull(TokenMetricStageRouter.chartStage8473(8, true, nan, 0.98, 1.0, 2.0, 80.0, 20.0, 0.5, 70_295.0, tapeLive = false))
        assertNull(TokenMetricStageRouter.chartStage8473(8, true, nan, 0.96, 31.0, 4.0, 66.0, 33.0, 2.0, 16_138.0, tapeLive = false))
        // one burst candle is not a live tape: prints must span three distinct minutes
        assertTrue(FlatChart8027.deadTapePrints8476(6, 1))
        assertFalse(FlatChart8027.deadTapePrints8476(6, 3))
    }

    @Test fun theRouterUsesItAfterTheCleanBandsAndRoutesItToThePlay() {
        val r = src("engine/TokenMetricStageRouter.kt")
        val order = r.substringAfter("val stage = when {").substringBefore("Snapshot(stage")
        assertTrue(order.indexOf("freshLaunch -> Stage.FRESH_LAUNCH") in 0 until order.indexOf("breakout8473 -> Stage.CONTROLLED_MARKUP"))
        assertTrue(order.indexOf("baseAnyAge8473 -> Stage.BASE_START") in 0 until order.indexOf("else -> tape8316?.stage ?: Stage.UNKNOWN"))
        assertTrue("MOONSHOT", "MOONSHOT" in TokenMetricStageRouter.LANE_STAGE_SHEET_7928.getValue(Stage.BASE_START))
        val brief = src("engine/DiagnosisBrief8438.kt")
        assertTrue(brief.contains("where it pays — stage records (5-min net n/mean/run)"))
        assertTrue(brief.contains("ForwardReturnLabeler7731.sourceStats8473(10)"))
    }
}
