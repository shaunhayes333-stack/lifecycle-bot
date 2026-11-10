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
        assertEquals(-55.0, PocketLedger8610.managed8610(-55.0), 0.0)
        val book = listOf(-80.0, -80.0, -80.0, -80.0, 120.0)
        assertEquals(-40.0, book.map(PocketLedger8610::managed8610).average(), 0.0)
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

    // V5.0.8613 — the coin is scored by the weight of all its evidence (mean clipped t), not vetoed by its single worst cell.
    @Test fun aCoinIsScoredByTheWeightOfAllItsEvidence() {
        assertEquals("a strong paying cell outweighs a mildly losing one", Call.PAYS,
            PocketLedger8610.callOf8610(listOf(rec(60.0, 20.0, 40.0), rec(80.0, -1.0, 30.0))))
        assertEquals("a strong losing cell outweighs the paying one", Call.LOSES,
            PocketLedger8610.callOf8610(listOf(rec(60.0, 20.0, 40.0), rec(200.0, -5.0, 8.0))))
        assertEquals("one losing broad cell no longer vetoes three paying ones", Call.PAYS,
            PocketLedger8610.callOf8610(listOf(rec(60.0, 20.0, 40.0), rec(60.0, 15.0, 40.0), rec(60.0, 12.0, 40.0), rec(3000.0, -2.6, 10.0))))
        assertEquals("a thin cell proves nothing", Call.UNPROVEN, PocketLedger8610.callOf8610(listOf(rec(20.0, 50.0, 10.0))))
        assertEquals("a near-zero score is unproven", Call.UNPROVEN, PocketLedger8610.callOf8610(listOf(rec(60.0, 0.5, 40.0))))
        assertEquals(Call.UNPROVEN, PocketLedger8610.callOf8610(emptyList()))
    }

    // V5.0.8617 — an implausible 5-minute outcome (a mark from the wrong basis) never trains the model or the ledger.
    @Test fun implausibleOutcomesNeverTrain() {
        val e = com.lifecyclebot.engine.truth.EdgeModel8537
        assertTrue("a fresh launch can 20x", e.plausible8617(8_000.0, 2_000.0))
        assertTrue(!e.plausible8617(2_956_010.0, 1_406_280_000.0))
        assertTrue(!e.plausible8617(112_372.0, 518_638.0))
        assertTrue(e.plausible8617(2_956_010.0, -60.0))
        assertTrue(!e.plausible8617(Double.NaN, 1_000.0))
        assertTrue(!e.plausible8617(50_000.0, Double.NaN))
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/EdgeModel8537.kt").readText()
        assertTrue(src.indexOf("if (!plausible8617(s.raw.mcap, netPct, chainWitness))") in 0 until src.indexOf("PocketLedger8610.learn8610(s, netPct)"))
        // Pocket replay now shares the model's complete validity predicate, including
        // venue-scoped evidence and plausible returns; it must not bypass either one.
        val pocketSrc = File("src/main/kotlin/com/lifecyclebot/engine/truth/PocketLedger8610.kt").readText()
        assertTrue(pocketSrc.contains("if (!EdgeModel8537.replayAccept8642(o)) return@forEachLine"))
        assertTrue(src.contains("return plausible8617(row.optDouble(\"mcap\", Double.NaN), net, row.optBoolean(\"witness\", false))"))
    }

    @Test fun theLedgerLearnsFromEveryLabelAndDecidesLiveOnceSeeded() {
        val e = File("src/main/kotlin/com/lifecyclebot/engine/truth/EdgeModel8537.kt").readText()
        assertTrue(e.contains("PocketLedger8610.learn8610(s, netPct)"))
        val a = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaneAuthority8577.kt").readText()
        assertTrue(a.contains("if (pocket != null) return if (pocket == PocketLedger8610.Call.PAYS) null"))
        assertTrue("V5.0.8615 — a coin the ledger scores PAYS is bought even in a lane paper proves losing",
            a.contains("if (pocket8610 != PocketLedger8610.Call.PAYS && !specificBeatsLanePrior8643(d) &&"))
        assertTrue(a.contains("val r = applyLane8577(c, d, highRefusal8582, pocket8610)"))
        assertTrue("both pocket refusals bind at the gate", LaneAuthority8577.binding8577(LaneAuthority8577.POCKET_UNPROVEN_8610) &&
            LaneAuthority8577.binding8577(LaneAuthority8577.POCKET_LOSES_8610))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
            .contains("pocket8610 = if (candidate.live) PocketLedger8610.verdict8610(ts, candidate.lane, nowMs) else null"))
    }
}
