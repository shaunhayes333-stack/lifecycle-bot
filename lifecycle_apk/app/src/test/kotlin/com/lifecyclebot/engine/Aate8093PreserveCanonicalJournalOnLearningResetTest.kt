package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8093PreserveCanonicalJournalOnLearningResetTest {
    @Test fun learningResetNeverErasesFinancialAuditHistory() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/LearningResetSweep7781.kt").readText()
        assertFalse(src.contains("TradeHistoryStore.clearAllTrades()"))
        assertTrue(src.contains("JournalCohort6389.beginNewCohort(generation)"))
        assertTrue(src.contains("RunTracker30D.reset()"))
        assertTrue(src.contains("LEARNING_DBS_7975"))
    }
}
