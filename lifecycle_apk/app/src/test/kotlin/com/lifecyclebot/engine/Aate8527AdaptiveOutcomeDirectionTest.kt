package com.lifecyclebot.engine

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class Aate8527AdaptiveOutcomeDirectionTest {
    private val repaired = listOf("mcap", "buyRatio", "holderConc", "holderGrowth", "emaFan")

    @Before fun before() { AdaptiveLearningEngine.reset() }
    @After fun after() { AdaptiveLearningEngine.reset() }

    private fun features(pnl: Double) = AdaptiveLearningEngine.captureFeatures(
        entryMcapUsd = 20_000.0, tokenAgeMinutes = 10.0,
        buyRatioPct = 70.0, volumeUsd = 60_000.0, liquidityUsd = 60_000.0,
        holderCount = 600, topHolderPct = 10.0, holderGrowthRate = 12.0,
        devWalletPct = 2.0, bondingCurveProgress = 50.0, rugcheckScore = 90.0,
        emaFanState = "BULL_FAN", entryScore = 70.0, priceFromAth = 10.0,
        pnlPct = pnl, maxGainPct = if (pnl > 0.0) 30.0 else 2.0,
        maxDrawdownPct = if (pnl < 0.0) -pnl else 2.0,
        timeToPeakMins = 4.0, holdTimeMins = 10.0,
        exitReason = if (pnl > 0.0) "TAKE_PROFIT" else "STOP_LOSS",
        stableTradeKey = "8527-direction-" + UUID.randomUUID().toString(),
    )

    @Test fun losingPositiveFeaturesLoseWeightInsteadOfReinforcingTheBadEntry() {
        val before = AdaptiveLearningEngine.getDetailedWeights()
        AdaptiveLearningEngine.learnFromTrade(features(-25.0), TradeEnvironment.LIVE)
        val after = AdaptiveLearningEngine.getDetailedWeights()
        for (key in repaired) assertTrue(key + " must decrease", after.getValue(key) < before.getValue(key))
        assertTrue("age already carries outcome sign", after.getValue("age") < before.getValue("age"))
        assertTrue("vol/liq already carries outcome sign", after.getValue("volLiqRatio") < before.getValue("volLiqRatio"))
    }

    @Test fun winningPositiveFeaturesGainWeight() {
        val before = AdaptiveLearningEngine.getDetailedWeights()
        AdaptiveLearningEngine.learnFromTrade(features(25.0), TradeEnvironment.LIVE)
        val after = AdaptiveLearningEngine.getDetailedWeights()
        for (key in repaired) assertTrue(key + " must increase", after.getValue(key) > before.getValue(key))
        assertTrue(after.getValue("age") > before.getValue("age"))
    }

    private fun negativeShape(pnl: Double) = features(pnl).copy(
        entryMcapUsd = 600_000.0, buyRatioPct = 35.0, topHolderPct = 55.0,
        holderGrowthRate = -10.0, emaFanState = "BEAR_FAN",
    )

    @Test fun losingNegativeFeaturesStrengthenTheirPenaltyRatherThanRewardingThem() {
        val before = AdaptiveLearningEngine.getDetailedWeights()
        AdaptiveLearningEngine.learnFromTrade(negativeShape(-25.0), TradeEnvironment.LIVE)
        val after = AdaptiveLearningEngine.getDetailedWeights()
        for (key in repaired) assertTrue(key + " penalty must increase", after.getValue(key) > before.getValue(key))
    }

    @Test fun winningAgainstNegativeFeaturesWeakensThosePenalties() {
        val before = AdaptiveLearningEngine.getDetailedWeights()
        AdaptiveLearningEngine.learnFromTrade(negativeShape(25.0), TradeEnvironment.LIVE)
        val after = AdaptiveLearningEngine.getDetailedWeights()
        for (key in repaired) assertTrue(key + " penalty must decrease", after.getValue(key) < before.getValue(key))
    }

    @Test fun ScratchDoesNotChooseAFictitiousPositiveOutcomeDirection() {
        val before = AdaptiveLearningEngine.getDetailedWeights()
        AdaptiveLearningEngine.learnFromTrade(features(0.0), TradeEnvironment.LIVE)
        val after = AdaptiveLearningEngine.getDetailedWeights()
        for (key in repaired) assertEquals(key, before.getValue(key), after.getValue(key), 0.0)
    }

    @Test fun ExistingModeIsolationStableIdentityAndPositiveBoundsArePreserved() {
        val f = features(-25.0)
        val initial = AdaptiveLearningEngine.getDetailedWeights()
        AdaptiveLearningEngine.learnFromTrade(f, TradeEnvironment.PAPER)
        AdaptiveLearningEngine.learnFromTrade(f, TradeEnvironment.SHADOW)
        assertEquals(initial, AdaptiveLearningEngine.getDetailedWeights())
        AdaptiveLearningEngine.learnFromTrade(f, TradeEnvironment.LIVE)
        val learned = AdaptiveLearningEngine.getDetailedWeights()
        AdaptiveLearningEngine.learnFromTrade(f, TradeEnvironment.LIVE)
        assertEquals(learned, AdaptiveLearningEngine.getDetailedWeights())
        for (i in 1..15) AdaptiveLearningEngine.learnFromTrade(features(-25.0), TradeEnvironment.LIVE)
        for (weight in AdaptiveLearningEngine.getDetailedWeights().values) {
            assertTrue(weight.isFinite())
            assertTrue(weight > 0.0)
            assertTrue(weight <= 3.0)
        }
    }
}
