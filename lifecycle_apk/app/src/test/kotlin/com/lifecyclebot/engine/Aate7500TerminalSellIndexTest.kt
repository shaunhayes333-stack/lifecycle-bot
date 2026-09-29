package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7500TerminalSellIndexTest {
    @Test fun economic_snapshot_carries_terminal_sell_index() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/EconomicEventSchema6464.kt").readText()
        assertTrue(s.contains("fullTerminalByPosition7500"))
        assertTrue(s.contains("fun fullTerminalSellsByPosition7500()"))
        assertTrue(s.contains("fun fullTerminalPositionIds7500()"))
    }

    @Test fun finalized_reconciler_reuses_terminal_index() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedLearningReconciler7423.kt").readText()
        assertTrue(s.contains("EconomicEventSchema6464.fullTerminalPositionIds7500()"))
        assertTrue(s.contains("EconomicEventSchema6464.fullTerminalSellsByPosition7500()"))
    }
}
