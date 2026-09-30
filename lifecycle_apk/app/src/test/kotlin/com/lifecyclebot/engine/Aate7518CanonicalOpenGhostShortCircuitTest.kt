package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7518CanonicalOpenGhostShortCircuitTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

    @Test fun canonical_open_skips_legacy_missing_buy_heuristic() {
        val fn = src().substringAfter("private fun currentPaperOpenMintsFromLedger")
            .substringBefore("private fun rebuildPaperForcedOpenFromLedger")
        assertTrue(fn.contains("val noBuyRow6373c = if (canonicalOpen7351) false else"))
        assertTrue(fn.contains("val ghost6373c = noBuyRow6373c"))
        assertFalse(fn.contains("PAPER_GHOST_PURGE_REFUSED_CANONICAL_OPEN_7351"))
    }

    @Test fun true_noncanonical_ghost_purge_remains() {
        val fn = src().substringAfter("private fun currentPaperOpenMintsFromLedger")
            .substringBefore("private fun rebuildPaperForcedOpenFromLedger")
        assertTrue(fn.contains("PAPER_GHOST_PURGED_6373C_NO_BUY_ROW"))
        assertTrue(fn.contains("recentBuyMintsForGhost6373c"))
        assertTrue(fn.contains("canonicalOpenPaperMints7351"))
    }
}
