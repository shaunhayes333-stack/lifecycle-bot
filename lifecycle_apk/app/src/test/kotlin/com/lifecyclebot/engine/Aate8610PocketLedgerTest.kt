package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaneAuthority8577
import com.lifecyclebot.engine.truth.PocketLedger8610
import com.lifecyclebot.engine.truth.PocketLedger8610.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8610 — the bot learns which coin-state pockets pay, in any lane and any state, and live hunts those. */
class Aate8610PocketLedgerTest {
    @Test fun aCellRecordsTheManagedOutcome() {
        assertEquals(-20.0, PocketLedger8610.managed8610(-55.0), 0.0)
        assertEquals(400.0, PocketLedger8610.managed8610(2_000.0), 0.0)
        assertEquals(7.5, PocketLedger8610.managed8610(7.5), 0.0)
    }

    @Test fun cellsAreEachFeatureAndEveryPairMarketWideAndPerLane() {
        val b = PocketLedger8610.bands8610(5_000.0, 30_000.0, 0.5, 70.0, 12.0, Double.NaN, 0.95, Double.NaN, 12.0, "EXPANDING")
        assertEquals(mapOf("phase" to "EXPANDING", "age" to "<1m", "liq" to "<10k", "mcap" to "<50k", "bp" to "<80",
            "chg5m" to "<30", "pxPeak" to "<0.98", "top" to "<15"), b)
        val k = PocketLedger8610.keys8610("quality", b)
        assertEquals(8 + 2 * 28, k.size)
        assertTrue(k.contains("M|phase=EXPANDING"))
        assertTrue(k.contains("QUALITY|phase=EXPANDING&age=<1m"))
    }

    private fun rec(n: Double, mean: Double, sd: Double) = doubleArrayOf(n, mean * n, n * (sd * sd + mean * mean))

    @Test fun aCoinPaysOnlyWhenOneCellProvesProfitAndNoneProvesALoss() {
        assertEquals(Call.PAYS, PocketLedger8610.callOf8610(listOf(rec(60.0, 20.0, 40.0), rec(80.0, -1.0, 30.0))))
        assertEquals("one proven-losing cell vetoes", Call.LOSES, PocketLedger8610.callOf8610(listOf(rec(60.0, 20.0, 40.0), rec(200.0, -5.0, 8.0))))
        assertEquals("a thin cell proves nothing", Call.UNPROVEN, PocketLedger8610.callOf8610(listOf(rec(20.0, 50.0, 10.0))))
        assertEquals("inside two standard errors is unproven", Call.UNPROVEN, PocketLedger8610.callOf8610(listOf(rec(60.0, 5.0, 40.0))))
        assertEquals(Call.UNPROVEN, PocketLedger8610.callOf8610(emptyList()))
    }

    @Test fun theLedgerLearnsFromEveryLabelAndDecidesLiveOnceSeeded() {
        val e = File("src/main/kotlin/com/lifecyclebot/engine/truth/EdgeModel8537.kt").readText()
        assertTrue(e.contains("PocketLedger8610.learn8610(s.raw, netPct)"))
        val a = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaneAuthority8577.kt").readText()
        assertTrue(a.contains("if (pocket != null) return if (pocket == PocketLedger8610.Call.PAYS) null"))
        assertTrue("both pocket refusals bind at the gate", LaneAuthority8577.binding8577(LaneAuthority8577.POCKET_UNPROVEN_8610) &&
            LaneAuthority8577.binding8577(LaneAuthority8577.POCKET_LOSES_8610))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
            .contains("pocket8610 = if (candidate.live) PocketLedger8610.verdict8610(ts, candidate.lane, nowMs) else null"))
    }
}
