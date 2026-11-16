package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PocketLedger8610
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8823 — the loss a stop cannot save on a band's dumps is part of the entry's cost. */
class Aate8823GapCostTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theGapIsTheExpectedLossBeyondTheStopOnDumps() {
        // 100 observations, 20 lost >= 50 % (1,500 % between them): beyond a 20 % stop each lost 55 % more -> 11 % a trade
        assertEquals(11.0, PocketLedger8610.gapPct8823(doubleArrayOf(100.0, 20.0, 1_500.0), 20.0), 1e-9)
        assertEquals("a band without dumps costs nothing extra", 0.0, PocketLedger8610.gapPct8823(doubleArrayOf(300.0, 0.0, 0.0), 20.0), 0.0)
        assertTrue("a thin band is not judged", PocketLedger8610.gapPct8823(doubleArrayOf(39.0, 10.0, 700.0), 20.0).isNaN())
        assertEquals("a wider stop leaves less beyond it", 7.0, PocketLedger8610.gapPct8823(doubleArrayOf(100.0, 20.0, 1_500.0), 40.0), 1e-9)
        assertEquals("unseeded ledger: no charge", 0.0, PocketLedger8610.gapCostPct8823("EXPRESS", 1_500.0, 20.0), 0.0)
    }

    @Test fun theEntryCostCarriesItAndEachObservationCountsOnce() {
        val ne = src("engine/truth/NetEdge8030.kt").substringAfter("fun preTradeCostPct8329(").substringBefore("fun entryRefusal8030(")
        assertTrue(ne.contains("PocketLedger8610.gapCostPct8823(lane, liquidityUsd, FieldManual7715.stopPct8392(lane, model))"))
        assertTrue(ne.contains("?: return model + gap") && ne.contains("return maxOf(model, measured) + gap"))
        val pl = src("engine/truth/PocketLedger8610.kt")
        assertTrue(pl.contains("if (first) bands[\"liq\"]?.let { noteGap8823(horizon, it, net) }"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("NetEdge8030.gapLine8823()"))
    }
}
