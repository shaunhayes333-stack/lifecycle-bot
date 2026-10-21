package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexGradeBook8144
import com.lifecyclebot.engine.cortex.CortexGradeRecord8144
import com.lifecyclebot.engine.cortex.CortexRefusalTruth8269
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import com.lifecyclebot.engine.cortex.CortexWalkForward8144
import com.lifecyclebot.engine.cortex.LearningEntryTruth8153
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 8269: AATE would-TAKE/refuse decisions and Cortex predictions are different axes. */
class Aate8269CortexVerdictTruthTest {
    private val minute = 60_000L
    private fun timed(n: Int, from: Long, y: (Int) -> Double): List<CortexWalkForward8144.Timed8144> =
        (0 until n).map { i -> CortexWalkForward8144.Timed8144(from + i * minute, y(i)) }

    @Test fun oldLegacyNegativeCountsCannotBlockOnAnUnprovedTradeableCohort() {
        val tiny = timed(10, 10_000_000L) { -40.0 }
        assertFalse(CortexRefusalTruth8269.proven8269(emptyList(), emptyList(), 1.0, 40))
        assertFalse(CortexRefusalTruth8269.proven8269(tiny, emptyList(), 1.0, 40))
        assertFalse(CortexRefusalTruth8269.proven8269(timed(50, 10_000_000L) { -12.0 },
            emptyList(), 0.55, 40))
    }

    @Test fun refusalMustPredictNegativeHeldOutLabelsNotJustEarlierLosses() {
        val t = 10_000_000L
        val losing = timed(60, t) { if (it % 2 == 0) -12.0 else -8.0 }
        val healthyRivals = timed(30, t + 30 * minute) { if (it % 2 == 0) 4.0 else 7.0 }
        assertTrue(CortexRefusalTruth8269.proven8269(losing, healthyRivals, 0.95, 40))
        val flipped = timed(60, t) { if (it < 36) -12.0 else 14.0 }
        assertFalse(CortexRefusalTruth8269.proven8269(flipped, healthyRivals, 0.95, 40))
        val refusersActuallyBetter = timed(30, t + 30 * minute) { -23.0 }
        assertFalse(CortexRefusalTruth8269.proven8269(losing, refusersActuallyBetter, 0.95, 40))
        assertFalse(CortexRefusalTruth8269.proven8269(losing, healthyRivals, 0.95, 40,
            runnerLane = true, runnerRate = 0.15))
    }

    @Test fun relativeRefusalRequiresStrongHeldAuthorityAndSameWindowRivals() {
        val t = 10_000_000L
        val bad = timed(15, t) { -10.0 }
        val farOlder = timed(30, t - 100 * minute) { +20.0 }
        assertFalse(CortexRefusalTruth8269.proven8269(bad, farOlder, 1.0, 10, relative = true))
        val sameTime = timed(8, t + 7 * minute) { +7.0 }
        assertTrue(CortexRefusalTruth8269.proven8269(bad, sameTime, 1.0, 10, relative = true))
    }

    private fun decision(book: CortexGradeBook8144, id: String, at: Long, take: Boolean,
                         cortexBucket: Int, net: Double, gross: Double) {
        val r = CortexGradeRecord8144('D', id, "$id|CORE", at, "CORE",
            "MOMENTUM", "R", "TIMING", "C3_6", "COH", cortexBucket, 0.0, take, true, false)
        assertTrue(book.addDecision(r))
        assertTrue(book.label(id, net, gross, net, true))
    }

    @Test fun restoresWhatAateWouldTakeOrRefuseWithoutReclassifyingCortex() {
        val book = CortexGradeBook8144()
        decision(book, "a", 1_000L, true, CortexScoreboard7885.Bucket.STRONG.ordinal, 12.0, 16.0)
        decision(book, "b", 2_000L, true, CortexScoreboard7885.Bucket.REFUSE.ordinal, 6.0, 10.0)
        decision(book, "c", 3_000L, false, CortexScoreboard7885.Bucket.STRONG.ordinal, 95.0, 100.0)
        val d = LearningEntryTruth8153.decisionRow8269(book, "CORE")
        assertEquals(2, d.takeN)
        assertEquals(1, d.refuseN)
        assertEquals(13.0, d.takeGrossMedian, 1e-8)
        assertEquals(100.0, d.refuseGrossMedian, 1e-8)
        assertEquals(9.0, d.takeNetMedian, 1e-8)
        val cx = LearningEntryTruth8153.verdictRow8153(book, "CORE")
        assertEquals(2, cx.strongN)
        assertEquals(1, cx.refuseN)
    }

    @Test fun productionVetoIsTruthBoundAndUiKeepsIndependentAxes() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
        val live = src.substringAfter("fun entryRefusal(").substringBefore("fun strongSetupNegative8094(")
        assertTrue(live.contains("refusalAuthority8269(a.lane, a.runnerLane, paper)"))
        assertFalse(live.contains("board.refusalAuthority(a.lane, a.runnerLane, paper)"))
        assertTrue(live.contains("heldFraction8153(a.lane) >= OVERRULE_FRACTION_7955"))
        val screen = File("src/main/kotlin/com/lifecyclebot/ui/LearningCounterActivity.kt").readText()
        assertTrue(screen.contains("aateTakeGrossMedian8269"))
        assertTrue(screen.contains("aateRefuseGrossMedian8269"))
        assertTrue(screen.contains("Cortex predicted STRONG"))
    }
}
