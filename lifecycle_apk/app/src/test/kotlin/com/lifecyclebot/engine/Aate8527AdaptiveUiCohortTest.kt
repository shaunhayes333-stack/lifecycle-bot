package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** UI must disclose the measured model cohort instead of manufacturing parity with older economic-leg history. */
class Aate8527AdaptiveUiCohortTest {
    private fun src(name: String) = File("src/main/kotlin/com/lifecyclebot/ui/$name.kt").readText()

    @Test fun modelCountIsDisplayedWithoutAnAllHistoryDriftComparison() {
        val counter = src("LearningCounterActivity")
        val modelRow = counter.substringAfter("// V5.0.8527 — ALE counts")
            .substringBefore("// BehaviorLearning retains")
        assertTrue(modelRow.contains("addKv("))
        assertTrue(modelRow.contains("ALE observed LIVE terminals"))
        assertTrue(modelRow.contains("AdaptiveLearningEngine.getTradeCount().toString()"))
        assertTrue(modelRow.contains("excludes legacy, PAPER/SHADOW and partial-leg history"))
        assertFalse(modelRow.contains("addKvDrift("))
        assertFalse(modelRow.contains("settledTrades,"))
        assertFalse(counter.contains("AdaptiveLearningEngine (canonical-aligned)"))
    }

    @Test fun unrelatedConsumerDriftChecksRetainTheirOriginalCountersAndBaseline() {
        val counter = src("LearningCounterActivity")
        assertTrue(counter.contains("addKvDrift("))
        assertTrue(counter.contains("FluidLearningAI.sessionTrades"))
        assertTrue(counter.contains("BehaviorLearning (canonical-aligned)"))
        assertTrue(counter.contains("BehaviorLearning.getCanonicalAlignedTradeCount().toLong()"))
        assertTrue(counter.contains("MetaCognitionAI.totalTradesAnalyzed"))
        val behavior = counter.substringAfter("\"BehaviorLearning (canonical-aligned)\"")
            .substringBefore("addKvDrift(")
        assertTrue(behavior.contains("settledTrades,"))
    }

    @Test fun eachCryptoAdaptiveCountSurfaceStatesItsLiveTerminalCohortWithoutChangingMaturityMath() {
        val crypto = src("CryptoAltActivity")
        assertTrue(crypto.contains("append(\"LIVE model terminals:"))
        assertTrue(crypto.contains("\"Observed LIVE terminal model\""))
        assertTrue(crypto.contains("addStatChip(alRow, \"Model closes\""))
        assertTrue(crypto.contains("addInfoRow(\"LIVE model terminals\""))
        assertEquals(3, "AdaptiveLearningEngine.getTradeCount()".toRegex(RegexOption.LITERAL).findAll(crypto).count())
        assertEquals(3, "excludes legacy, PAPER/SHADOW and partial-leg history".toRegex(setOf(RegexOption.LITERAL, RegexOption.IGNORE_CASE)).findAll(crypto).count())
        assertTrue(crypto.contains("if (alCount > 100) green else if (alCount > 0) amber else muted"))
        assertTrue(crypto.contains("if (alCount > 100) \"Mature\" else if (alCount > 0) \"Learning\" else \"New\""))
    }
}
