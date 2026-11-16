package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.FieldManual7715
import com.lifecyclebot.engine.truth.PocketLedger8610
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8830 — the entry cost's gap read builds nothing and counts nothing until a seeded band has a record. */
class Aate8830CostPathTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theLiquidityBandIsTheOneTheLedgerFiles() {
        for (liq in listOf(500.0, 1_999.0, 2_000.0, 9_999.0, 30_000.0, 100_000.0, 1e6)) {
            val full = PocketLedger8610.bands8610(liq, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, "")["liq"]
            assertEquals(full, PocketLedger8610.liqBand8830(liq))
        }
        assertNull(PocketLedger8610.liqBand8830(Double.NaN))
    }

    @Test fun theStopIsNotReadWithoutABandRecord() {
        var asked = 0
        assertEquals(0.0, PocketLedger8610.gapCostPct8823("EXPRESS", 1_500.0) { asked++; 20.0 }, 0.0)
        assertEquals("no record: the stop is never computed", 0, asked)
    }

    @Test fun aCostReadIsSilentAndTheStopValueIsUnchanged() {
        for (lane in listOf("MOONSHOT", "QUALITY", "EXPRESS")) for (cost in listOf(Double.NaN, 2.0, 9.0, 30.0))
            assertEquals(FieldManual7715.stopPct8392(lane, cost), FieldManual7715.stopPctQuiet8830(lane, cost), 1e-12)
        val quiet = src("engine/truth/FieldManual7715.kt").substringAfter("fun stopPctQuiet8830(").substringBefore("fun laneStopPct8392(")
        assertTrue(!quiet.contains("labelInc"))
        val ne = src("engine/truth/NetEdge8030.kt").substringAfter("fun preTradeCostPct8329(").substringBefore("fun entryRefusal8030(")
        assertTrue(ne.contains("val gap = if (banded > 0.0 && gapExempt8824(mint, System.currentTimeMillis())) 0.0 else banded"))
    }
}
