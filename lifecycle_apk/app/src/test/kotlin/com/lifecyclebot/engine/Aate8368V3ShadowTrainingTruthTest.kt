package com.lifecyclebot.engine

import com.lifecyclebot.v3.learning.ShadowLearningEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8368: counterfactual outcomes must not invert real directional learning or masquerade as canonical fills. */
class Aate8368V3ShadowTrainingTruthTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun avoidanceAndShortOutcomesAreConvertedBackToTheMarketLongOutcome() {
        val v3 = ShadowLearningEngine
        assertEquals(20.0, v3.directionalLongReturn8368(ShadowLearningEngine.ShadowType.SHADOW_LONG, 20.0)!!, 1e-9)
        assertEquals(-20.0, v3.directionalLongReturn8368(ShadowLearningEngine.ShadowType.SHADOW_AVOID, 20.0)!!, 1e-9)
        assertEquals(20.0, v3.directionalLongReturn8368(ShadowLearningEngine.ShadowType.SHADOW_AVOID, -20.0)!!, 1e-9)
        assertEquals(-15.0, v3.directionalLongReturn8368(ShadowLearningEngine.ShadowType.SHADOW_SHORT, 15.0)!!, 1e-9)
        assertNull(v3.directionalLongReturn8368(ShadowLearningEngine.ShadowType.SHADOW_STRANGLE, 50.0))
        assertNull(v3.directionalLongReturn8368(ShadowLearningEngine.ShadowType.SHADOW_STRADDLE, 50.0))
    }

    @Test fun v3ShadowUsesActualDirectionalCounterfactualForEveryTrainingSink() {
        val s = src("v3/learning/ShadowLearningEngine.kt")
        val feed = s.substringAfter("private fun feedToMetaCognition(trade: ShadowTrade)")
            .substringBefore("private fun processOpenTrades()")
        assertTrue(feed.contains("pnlPct = longPnl8368"))
        assertTrue(feed.contains("laterPnlPct   = longPnl8368"))
        assertTrue(feed.contains("pnlPct               = longPnl8368"))
        assertTrue(feed.contains("isShadowTrade        = true"))
        assertTrue(feed.contains("if (longPnl8368 > 0) longPnl8368 else 0.0"))
        assertTrue(feed.contains("if (longPnl8368 < 0) longPnl8368 else 0.0"))
    }

    @Test fun unpricedExpiryAndInvalidMarksNeverCreateFakeFlatLossLabels() {
        val s = src("v3/learning/ShadowLearningEngine.kt")
        assertTrue(s.contains("if (!currentPrice.isFinite() || currentPrice <= 0.0) return"))
        assertTrue(s.contains("unpricedTimeouts8368.incrementAndGet()"))
        assertTrue(s.contains("openShadowTrades.remove(trade.id, trade)"))
        assertTrue(!s.contains("closeShadowTrade(trade, estimatedExit, pnlPct)"))
        assertTrue(s.contains("takeIf { it.ok }?.pnlPct ?: return"))
    }

    @Test fun educationShadowMetricsUseShadowWeightAndDoNotPolluteRealisedEdge() {
        val edu = src("v3/scoring/EducationSubLayerAI.kt")
        val begin = edu.indexOf("fun recordTradeOutcomeAcrossAllLayers(outcome: TradeOutcomeData)")
        assertTrue(begin >= 0)
        val scope = edu.substring(begin).substringBefore("fun recordTradeOutcomeForSubTrader")
        assertTrue(scope.contains("isShadowTrade = outcome.isShadowTrade"))
        assertTrue(!scope.contains("isShadowTrade = false, assetClass ="))
        assertTrue(edu.contains("if (!isShadowTrade) {"))
    }
}
