package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8932 — in LIVE the lane cards show the live books only (paper lots are being closed out, not held); every LIVE paper
 * close-out pass says how far its lots got.
 */
class Aate8932LiveBookOnlyTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun liveShowsTheLiveBookOnly() {
        val ui = src("ui/MainActivity.kt")
        assertTrue(ui.contains("return if (isLive) live() else paper() + live()"))
        assertFalse("no card adds the paper book to the live one unconditionally",
            Regex("getActivePositionsForMode\\(true\\) \\+\\s*\\n\\s*com\\.lifecyclebot\\.v3\\.scoring\\.\\w+\\.getActivePositionsForMode\\(false\\)").containsMatchIn(ui))
        assertEquals("treasury card and mini row", 2, Regex("uiBook8932\\(\\{ com\\.lifecyclebot\\.v3\\.scoring\\.CashGenerationAI\\.getPositionsForMode\\(true\\) \\}").findAll(ui).count())
        assertTrue("the stop guard still counts both books",
            ui.contains("CashGenerationAI.getPositionsForMode(true).size +\n                        com.lifecyclebot.v3.scoring.CashGenerationAI.getPositionsForMode(false).size"))
    }

    @Test fun eachCloseOutPassSaysHowFarItsLotsGot() {
        LiveNoPaper8184.noteCloseStages8932(100, 40, 10, 50, 3, 2)
        val line = LiveNoPaper8184.statusLine8184()
        assertTrue(line, line.contains("lastPass8932{requested=100 solanaProjected=40 marketsHeld=10 noOwner=50 freshMarkAttempted=3 closed=2}"))
        assertTrue(src("engine/BotService.kt").contains("(targets - tokens.keys - markets.requestedIds).size"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 120. Live shows the live book (8932)"))
    }
}
