package com.lifecyclebot.engine

import com.lifecyclebot.v3.core.DecisionBand
import com.lifecyclebot.v3.core.TradingConfigV3
import com.lifecyclebot.v3.decision.ConfidenceBreakdown
import com.lifecyclebot.v3.decision.FinalDecisionEngine
import com.lifecyclebot.v3.risk.FatalRiskResult
import com.lifecyclebot.v3.scoring.FluidLearningAI
import com.lifecyclebot.v3.scoring.ScoreCard
import com.lifecyclebot.v3.scoring.ScoreComponent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Aate8527DecisionScoreDirectionTest {
    @Before fun before() { FinalDecisionEngine.recordExecute() }
    @After fun after() { FinalDecisionEngine.recordExecute() }

    private val confidence = ConfidenceBreakdown(100, 100, 100, 100)
    private fun card(total: Int) = ScoreCard(listOf(
        ScoreComponent("momentum", 6, "measured positive"),
        ScoreComponent("volume", 6, "measured positive"),
        ScoreComponent("other", total - 12, "remaining actual components"),
    ))
    private fun isExecute(band: DecisionBand) = band in setOf(
        DecisionBand.EXECUTE_SMALL, DecisionBand.EXECUTE_STANDARD, DecisionBand.EXECUTE_AGGRESSIVE,
    )

    @Test fun negativeStandardFloorNeverExecutesANegativeFinalScoreInEitherAgePath() {
        val engine = FinalDecisionEngine(TradingConfigV3(executeStandardMin = 0, watchScoreMin = -100))
        for (age in listOf(1.0, 60.0)) for (score in -10..-1) {
            FinalDecisionEngine.recordExecute()
            val result = engine.decide(card(score), confidence, FatalRiskResult(false),
                marketCapUsd = 20_000.0, ageMinutes = age)
            assertFalse("score=$score age=$age band=" + result.band, isExecute(result.band))
            assertEquals(score, result.finalScore)
        }
    }

    @Test fun PreRunnerCannotPromoteNegativeNearMissWithStrongMomentumAndVolume() {
        // The real dynamic sparse relief is cancelled in the config to make the
        // standard floor 3 and the near-miss small floor 2, independent of other tests' maturity.
        val sparse = FluidLearningAI.getSparseLayerRelief()
        val engine = FinalDecisionEngine(TradingConfigV3(executeStandardMin = sparse + 3, watchScoreMin = -100))
        val before = FinalDecisionEngine.preRunnerPromoted.get()
        val result = engine.decide(card(-1), confidence, FatalRiskResult(false),
            marketCapUsd = 0.0, ageMinutes = 60.0)
        assertEquals(DecisionBand.WATCH, result.band)
        assertEquals(before, FinalDecisionEngine.preRunnerPromoted.get())
    }

    @Test fun zeroFreshProbeAndPositiveKnownEntriesKeepTheirExistingBands() {
        val engine = FinalDecisionEngine(TradingConfigV3())
        assertEquals(DecisionBand.EXECUTE_SMALL,
            engine.decide(card(0), confidence, FatalRiskResult(false), ageMinutes = 1.0).band)
        assertEquals(DecisionBand.EXECUTE_SMALL,
            engine.decide(card(90), confidence, FatalRiskResult(false), ageMinutes = 1.0).band)
        assertEquals(DecisionBand.EXECUTE_AGGRESSIVE,
            engine.decide(card(90), confidence, FatalRiskResult(false), ageMinutes = 60.0).band)
    }

    @Test fun fatalAuthorityRemainsFirstAndNegativeScoresAreNotFlippedToPositive() {
        val result = FinalDecisionEngine(TradingConfigV3()).decide(
            card(-5), confidence, FatalRiskResult(true, "PHYSICAL_SAFETY"), ageMinutes = 1.0,
        )
        assertEquals(DecisionBand.BLOCK_FATAL, result.band)
        assertEquals("PHYSICAL_SAFETY", result.fatalReason)
        assertEquals(-5, result.finalScore)
    }
}
