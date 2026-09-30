package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7526FinalizedBusResidualRepairTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedLearningReconciler7423.kt").readText()

    @Test fun rich_finality_does_not_require_legacy_sell_basis() {
        val s = src()
        val block = s.substringAfter("V5.0.7526 — a rich durable finality event")
            .substringBefore("val netPnl =")
        assertTrue(block.contains("if (rich == null"))
        assertTrue(block.contains("sell.allocatedCostBasisSol <= 0.0"))
        assertTrue(s.contains("rich?.netRealizedPnlSol"))
        assertTrue(s.contains("rich?.netReturnPct"))
    }

    @Test fun restored_entry_terminal_is_published_but_never_trainable() {
        val s = src()
        assertTrue(s.contains("DURABLE_TERMINAL_RESTORED_ENTRY_7526"))
        assertTrue(s.contains("FINALIZED_BUS_RESTORED_ENTRY_PUBLISHED_NONTRAINABLE_7526"))
        assertTrue(s.contains("entryTactic = if (restoredEntry7526) \"\""))
        assertTrue(s.contains("entrySource = if (restoredEntry7526) \"RESTORED_NONTRAINABLE_7526\""))
    }
}
