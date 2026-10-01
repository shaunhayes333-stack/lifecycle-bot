package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7669ProjectSniperPartialStateRepairTest {
    @Test fun sniperLadderUsesCanonicalCumulativePartialState() {
        val s = File("src/main/kotlin/com/lifecyclebot/v3/scoring/ProjectSniperAI.kt").readText()
        assertTrue(s.contains("syncExtractedFromCanonicalPartial7669"))
        assertTrue(s.contains("sold.multiply(java.math.BigInteger.valueOf(100L))"))
        assertTrue(s.contains("if (pct > mission.extractedPct)"))
    }

    @Test fun journalProjectionRequiresTerminalCanonicalPartialTruth() {
        val h = File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        assertTrue(h.contains("LiveTerminalSemanticsAuthority7236"))
        assertTrue(h.contains("tradeToStore.side.equals(\"PARTIAL_SELL\", true)"))
        assertTrue(h.contains("lane7669 == \"PROJECT_SNIPER\""))
        assertTrue(h.contains("tradeToStore.canonicalConsumedRaw > java.math.BigInteger.ZERO"))
        assertTrue(h.contains("ProjectSniperAI.syncExtractedFromCanonicalPartial7669("))
    }

    @Test fun advisoryExitSignalDoesNotAdvanceExtraction() {
        val s = File("src/main/kotlin/com/lifecyclebot/v3/scoring/ProjectSniperAI.kt").readText()
        val check = s.substringAfter("fun checkExit(").substringBefore("fun completeMission")
        assertTrue(!check.contains("syncExtractedFromCanonicalPartial7669("))
    }
}
