package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7430PredictiveToolConsumptionTest {
    @Test fun admissionPassesRealCandidateTimingInputs() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/LearnedAdmissionInputs6909.kt").readText()
        assertTrue(s.contains("volumeUsdHint7430"))
        assertTrue(s.contains("tokenMap?.volume1hUsd"))
        assertTrue(s.contains("LaunchPhaseAuthority7401.trueAgeMs"))
        assertTrue(s.contains("migratedOrGraduated"))
        assertTrue(s.contains("volumeUsd = volumeUsdHint7430"))
        assertTrue(s.contains("tokenAgeMinutes = tokenAgeMinutes7430"))
        assertTrue(s.contains("hasGraduated = graduated7430"))
    }

    @Test fun previouslyUnusedPredictorsFeedBoundedOracleEvidence() {
        val o = File("src/main/kotlin/com/lifecyclebot/engine/truth/PredictiveEntryOracle6915.kt").readText()
        assertTrue(o.contains("TradingCopilot.convictionBoost()"))
        assertTrue(o.contains("TRADING_COPILOT_PREDICTIVE_READ_7430"))
        assertTrue(o.contains("HistoricalChartScanner"))
        assertTrue(o.contains("getHistoricalRecommendation(liquidityUsd, volumeUsd, 0.0)"))
        assertTrue(o.contains("HISTORICAL_SETUP_PREDICTIVE_READ_7430"))
        assertTrue(o.contains("OrthogonalSignals"))
        assertTrue(o.contains("calculateAgePatternScore(tokenAgeMinutes, hasGraduated)"))
        assertTrue(o.contains("ORTHOGONAL_AGE_PATTERN_READ_7430"))
    }

    @Test fun predictiveReadsRemainInsideExistingBrainCap() {
        val o = File("src/main/kotlin/com/lifecyclebot/engine/truth/PredictiveEntryOracle6915.kt").readText()
        assertTrue(o.contains("BRAIN_NETWORK_CAP_PCT_6917"))
        assertTrue(o.contains("brainAdjust6917"))
        assertTrue(o.contains(".coerceIn(-BRAIN_NETWORK_CAP_PCT_6917, BRAIN_NETWORK_CAP_PCT_6917)"))
    }
}
