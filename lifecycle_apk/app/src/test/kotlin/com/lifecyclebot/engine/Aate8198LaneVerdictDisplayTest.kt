package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexGradeBook8144
import com.lifecyclebot.engine.cortex.CortexGradeRecord8144
import com.lifecyclebot.engine.cortex.LearningEntryTruth8153
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 8198 — the Cortex lane panel headline is the typical coin's gross move; the net cost stack is explained, not hidden. */
class Aate8198LaneVerdictDisplayTest {
    private fun rec(id: String, bucket: Int, at: Long) =
        CortexGradeRecord8144('D', id, "m$id|CORE", at, "CORE", "S", "-", "T", "C", "COH", bucket, 0.0, true, true, false)

    @Test fun grossMedianIsThePriceMoveWhileNetMedianCarriesTheCostStack() {
        val book = CortexGradeBook8144()
        book.execOffset8187 = { 6.0 }
        val strong = 2
        // Five flat STRONG coins (gross 0) and two runners: the net read is cost+haircut+offset on a flat coin.
        listOf(0.0, 0.0, 0.0, 0.0, 0.0, 180.0, 40.0).forEachIndexed { i, gross ->
            val r = rec("s$i", strong, 1_000L + i); assertTrue(book.addDecision(r))
            assertTrue(book.label(r.id, gross - 1.5, gross, gross - 1.5 - 3.0, true))
        }
        val v = LearningEntryTruth8153.verdictRow8153(book, "CORE")
        assertEquals(0.0, v.strongGrossMedian8198, 1e-9)
        assertEquals(-1.5 - 3.0 - 6.0, v.strongMedian, 1e-9)
        assertEquals(7, v.strongN)
        assertTrue(v.refuseGrossMedian8198.isNaN())
    }

    @Test fun panelHeadlineUsesAateAdmissionDecisionsAndKeepsCortexPredictionsSeparate() {
        val ui = File("src/main/kotlin/com/lifecyclebot/ui/LearningCounterActivity.kt").readText()
        assertTrue(ui.contains("aateTakeGrossMedian8269"))
        assertTrue(ui.contains("aateRefuseGrossMedian8269"))
        // superseded by V5.0.8322: the lane row leads with the net mean (same statistic as the report and authority);
        // gross medians are shown beside it, labelled.
        assertTrue(ui.contains("l.aateTakeNetMean8269 - l.aateRefuseNetMean8269"))
        assertTrue(ui.contains("Cortex predicted STRONG"))
        assertTrue(ui.contains("exec offset -"))
        assertTrue(ui.contains("aateTakeNetMedian8269"))
        val cx = File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
        assertTrue(cx.contains("execOffset8198 = execOffsetByLane8187[lane] ?: 0.0"))
        assertTrue(cx.contains("aateTakeGrossMedian8269 = d8269.takeGrossMedian"))
    }

}
