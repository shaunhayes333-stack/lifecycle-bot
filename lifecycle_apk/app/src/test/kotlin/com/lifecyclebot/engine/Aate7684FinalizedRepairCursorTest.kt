package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7684FinalizedRepairCursorTest {
    private fun src() =
        File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedLearningReconciler7423.kt").readText()

    @Test fun durableRepairUsesRotatingCursorInsteadOfRestartingAtZero() {
        val s = src()
        val fn = s.substringAfter("fun repairDurableBusPublishFailures7459")
            .substringBefore("fun statusLine")
        assertTrue(fn.contains("repairCursor7684"))
        assertTrue(fn.contains("Math.floorMod(repairCursor7684.get(), closed7684.size)"))
        assertTrue(fn.contains("repairCursor7684.set(idx7684)"))
        assertTrue(fn.contains("FINALIZED_BUS_REPAIR_CURSOR_WRAP_7684"))
        assertFalse(fn.contains("for (p in CanonicalPositionAuthority6441.closedPositions())"))
    }

    @Test fun proofAndBudgetGuardsRemainIntact() {
        val fn = src().substringAfter("fun repairDurableBusPublishFailures7459")
            .substringBefore("fun statusLine")
        assertTrue(fn.contains("maxWorkMs7514"))
        assertTrue(fn.contains("if (repaired >= limit) break"))
        assertTrue(fn.contains("p.positionId !in repairableIds7544"))
        assertTrue(fn.contains("CanonicalFinalizedTradeBus6464.publish(env)"))
        assertTrue(fn.contains("FINALIZED_BUS_DURABLE_REPAIR_7459"))
    }

    @Test fun fieldAcceptanceEvidenceIsRecorded() {
        val a = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(a.contains("fresh reaching V3/FDG=14"))
        assertTrue(a.contains("EXPRESS canonical handoff runtime acceptance passed"))
    }
}
