package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7432ExactStrategyLegacyIsolationTest {
    @Test fun legacyUnstampedRowsDoNotSeedExactStrategyEv() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExactStrategyPerformance7429.kt").readText()
        val record = src.substringAfter("fun record(").substringBefore("ensureRestored7431()")
        assertTrue(record.contains("listOf(e.entryTradeType, e.entrySetup, e.entryStyle, e.entryTactic).any { it.isBlank() }"))
        assertTrue(record.contains("EXACT_STRATEGY_ATTRIBUTION_INCOMPLETE_7429"))
        assertTrue(record.contains("return false"))
        assertFalse(record.contains("commitOnce8090"))
    }

    @Test fun restoredUnstampedPlaybooksAreFiltered() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExactStrategyPerformance7429.kt").readText()
        assertTrue(src.contains("parts.any { it.startsWith(\"UNSTAMPED_\") }"))
        assertTrue(src.contains("continue"))
    }

    @Test fun exactStrategyEvidenceRequiresStampedIdentity() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExactStrategyPerformance7429.kt").readText()
        val evidence = src.substringAfter("fun evidenceFor7431(")
        assertTrue(evidence.contains("tradeType.isBlank()"))
        assertTrue(evidence.contains("setup.isBlank()"))
        assertTrue(evidence.contains("style.isBlank()"))
        assertTrue(evidence.contains("tactic.isBlank()"))
        assertFalse(evidence.substringBefore("fun snapshots()").contains("UNSTAMPED_TYPE"))
    }
}
