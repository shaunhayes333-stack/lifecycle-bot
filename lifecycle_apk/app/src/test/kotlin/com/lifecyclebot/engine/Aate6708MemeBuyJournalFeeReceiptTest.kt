package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6708MemeBuyJournalFeeReceiptTest {
    @Test
    fun `native meme BUY journal carries exact ledger entry fee`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val marker = src.indexOf("PAPER_LEARNING_ELIGIBILITY_6519")
        assertTrue(marker >= 0)
        val start = src.indexOf("val trade = Trade(", marker)
        val end = src.indexOf("recordTrade(ts, trade)", start)
        assertTrue(start >= 0 && end > start)
        val block = src.substring(start, end)
        assertTrue(block.contains("side = \"BUY\""))
        assertTrue(block.contains("feeSol = fee6485"))
        assertTrue(block.contains("economicEventId = entryFinalityId6497"))
    }
}
