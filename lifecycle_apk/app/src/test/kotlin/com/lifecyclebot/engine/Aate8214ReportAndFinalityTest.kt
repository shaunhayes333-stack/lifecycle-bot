package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 8214 — stale RuntimeDoctor faults are history; finalization repair uses the predicate's own economics. */
class Aate8214ReportAndFinalityTest {
    @Test fun staleOrOtherStateReportIsNotCurrent() {
        val now = 10_000_000L
        assertTrue(RuntimeDoctor.reportIsCurrent8214(now - 5_000L, true, now, true))
        assertFalse("loop ran then, stopped now", RuntimeDoctor.reportIsCurrent8214(now - 5_000L, true, now, false))
        assertFalse("too old", RuntimeDoctor.reportIsCurrent8214(now - 600_000L, true, now, true))
        assertFalse("never taken", RuntimeDoctor.reportIsCurrent8214(0L, true, now, true))
        assertTrue("loop state unknown now: age decides", RuntimeDoctor.reportIsCurrent8214(now - 1_000L, false, now, null))
    }

    @Test fun repairUsesWholeThenRichThenTypedSellAndNamesEverySkip() {
        val f = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedLearningReconciler7423.kt").readText()
        val fn = f.substringAfter("internal fun repairEconomics8214(").substringBefore("fun repairDurableBusPublishFailures7459(")
        val w = fn.indexOf("WholePositionEconomics7835.from(p)"); val r = fn.indexOf("rich.netRealizedPnlSol"); val t = fn.indexOf("sell.realizedPnlSol - sell.exitFeesSol")
        assertTrue(w in 1 until r); assertTrue(r < t)
        val loop = f.substringAfter("fun repairDurableBusPublishFailures7459(")
        assertFalse("no silent economics skip", loop.contains("WholePositionEconomics7835.from(p) } catch (_: Throwable) { continue }"))
        for (s in listOf("ENTRY_EXCLUDED", "NO_TERMINAL_SELL", "NO_FINITE_ECONOMICS", "BLANK_LANE")) assertTrue(s, loop.contains("skip8214(\"$s\")"))
    }
}
