package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.TradeShapeLearner7883
import com.lifecyclebot.engine.truth.TradeShapeLearner7883.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7883TradeShapeLearnerTest {
    private fun stat(n: Int, net: Double, spread: Double = 4.0, runners: Int = 0) =
        TradeShapeLearner7883.Stat().also { s ->
            repeat(n) { i ->
                val v = net + if (i % 2 == 0) spread else -spread
                s.add(v, if (i < runners) 80.0 else 0.0)
            }
        }

    @Test fun binsSplitOnEdgesAndUnknownHasNoBin() {
        assertEquals(0, TradeShapeLearner7883.binOf(Feature.TOP_HOLDER_PCT, 5.0))
        assertEquals(1, TradeShapeLearner7883.binOf(Feature.TOP_HOLDER_PCT, 10.0))
        assertEquals(3, TradeShapeLearner7883.binOf(Feature.TOP_HOLDER_PCT, 35.0))
        assertEquals(4, TradeShapeLearner7883.binOf(Feature.TOP_HOLDER_PCT, 90.0))
        assertEquals(-1, TradeShapeLearner7883.binOf(Feature.LIQUIDITY_USD, -1.0))
        assertEquals(-1, TradeShapeLearner7883.binOf(Feature.AGE_MIN, Double.NaN))
        assertEquals("30-50", TradeShapeLearner7883.binLabel(Feature.TOP_HOLDER_PCT, 3))
        assertEquals(">=50", TradeShapeLearner7883.binLabel(Feature.TOP_HOLDER_PCT, 4))
        assertEquals("<3k", TradeShapeLearner7883.binLabel(Feature.LIQUIDITY_USD, 0))
    }

    @Test fun aFreshInstallHasNoShapeRules() {
        // Too few labels: never a rule, however bad.
        assertFalse(TradeShapeLearner7883.provenLosing(stat(29, -20.0), 0.0, false))
    }

    @Test fun aProvenLosingBinBecomesALaneRule() {
        assertTrue(TradeShapeLearner7883.provenLosing(stat(40, -8.0), 0.0, false))
        // Not worse than the lane itself: the shape is not the cause.
        assertFalse(TradeShapeLearner7883.provenLosing(stat(40, -8.0), -7.0, false))
        // Noisy enough that +1 SE reaches -2%: not proven.
        assertFalse(TradeShapeLearner7883.provenLosing(stat(40, -3.0, spread = 30.0), 0.0, false))
    }

    @Test fun runnerLanesKeepFatTailBins() {
        // Mean loses, but 5 of 40 ran +50%: a runner lane keeps buying that shape.
        val tail = stat(40, -8.0, runners = 5)
        assertFalse(TradeShapeLearner7883.provenLosing(tail, 0.0, true))
        assertTrue(TradeShapeLearner7883.provenLosing(tail, 0.0, false))
    }
}
