package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.EntryEconomics8329
import com.lifecyclebot.engine.truth.PayingCell8723
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8723 — the market cell that pays is bought; the fixed chase cap binds only on its own record. */
class Aate8723PayingCellTest {
    private val other = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"
    private val pump = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmpump"

    @Test fun theOutcomeIsWhatAPositionBanks() {
        assertEquals("a loss stops at the stop plus the round trip", -39.0, PayingCell8723.outcome8723(-55.0, 35.0, 4.0), 1e-9)
        assertEquals("a label through twice that distance was a pull: it counts as it printed", -98.0, PayingCell8723.outcome8723(-98.0, 35.0, 4.0), 1e-9)
        assertEquals(-100.0, PayingCell8723.outcome8723(-250.0, 35.0, 4.0), 1e-9)
        assertEquals(60.0, PayingCell8723.outcome8723(60.0, 35.0, 4.0), 1e-9)
        assertTrue(PayingCell8723.outcome8723(5_000.0, 35.0, 4.0) < 5_000.0)
        assertEquals("OTHER_A1_L3", PayingCell8723.key8723(other, 2.0, 20_000.0))
        assertEquals("PUMP_A1_L3", PayingCell8723.key8723(pump, 2.0, 20_000.0))
        assertNull("an unread pool or birth is no cell", PayingCell8723.key8723(other, Double.NaN, 20_000.0))
        assertNull(PayingCell8723.key8723(other, 2.0, 0.0))
    }

    @Test fun aCellIsProvenOnTwoStandardErrorsOneLabelPerCoin() {
        PayingCell8723.resetForTest8723()
        try {
            // one coin labelled 40 times inside a quarter hour is one label
            repeat(40) { PayingCell8723.addForTest8723("${other}A", 2.0, 20_000.0, 300.0, 1_000_000L + it) }
            assertEquals(1.0, PayingCell8723.recordForTest8723(other, 2.0, 20_000.0)!![0], 1e-9)
            // 60 coins: half lose to the stop, half make +90 %
            repeat(60) { i -> PayingCell8723.addForTest8723("$other$i", 2.0, 20_000.0, if (i % 2 == 0) -60.0 else 90.0, 2_000_000L + i) }
            val r = PayingCell8723.recordForTest8723(other, 2.0, 20_000.0)!!
            val e = PayingCell8723.evidenceOf8723("OTHER_A1_L3", r)
            assertNotNull("proven: mean ~ +30 %, two SE ~ 20", e)
            assertTrue(e!!.lowerPct > 0.0 && e.kind == PayingCell8723.KIND_8723)
            assertTrue("it is admission evidence as it stands", CandidateAdmission8136.supported8136(e, 4.0))
            // the same pool on a pump.fun coin is its own cell with no record
            assertNull(PayingCell8723.recordForTest8723(pump, 2.0, 20_000.0))
            // a thin outlier cell is not proven at two SE: 40 coins, four +400 %, the rest stopped
            repeat(40) { i -> PayingCell8723.addForTest8723("$pump$i", 2_000.0, 2_000.0, if (i % 10 == 0) 400.0 else -60.0, 3_000_000L + i) }
            assertNull(PayingCell8723.evidenceOf8723("x", PayingCell8723.recordForTest8723(pump, 2_000.0, 2_000.0)))
            assertFalse(PayingCell8723.paying8723(29.0, 29.0 * 50.0, 29.0 * 2_500.0))
        } finally { PayingCell8723.resetForTest8723() }
    }

    @Test fun theCellCarriesTheLaneWidePriorsAndTheChaseCapEarnsItsBind() {
        val adm = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertTrue(adm.contains("val market8723 = LiveAdmissionProof8290.shift8290(c.marketCell8723, c.liveShortfall8290)?.takeIf { supported8136(it, c.costPct) }"))
        assertTrue(adm.contains("marketCell8723 = PayingCell8723.evidence8723(ts, lane, nowMs),"))
        assertTrue("safety is read before the cell", adm.indexOf("\"SAFETY_REFUSED_8136\"") in 1 until adm.indexOf("val market8723 ="))
        val lane = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaneAuthority8577.kt").readText()
        assertTrue(lane.contains("if (PayingCell8723.pays8723(ts, lane, nowMs)) {"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/EdgeModel8537.kt").readText().contains("PayingCell8723.learn8723(s.mint, s.raw.lane, s.raw.ageMin, s.raw.liq, netPct, s.raw.costPct, s.atMs)"))

        assertFalse("no record: the fixed cap does not bind", EntryEconomics8329.chaseCapBinds8723(0, Double.NaN, Double.NaN))
        assertFalse("the coins it turned away did not lose", EntryEconomics8329.chaseCapBinds8723(60, 4.0, 3.0))
        assertTrue(EntryEconomics8329.chaseCapBinds8723(60, -9.0, 3.0))
        // uncapped, the economics still decide: paying 13.5 % up leaves enough of a 40 % move, not of a 20 % one
        assertNull(EntryEconomics8329.consumed8329(13.5, EntryEconomics8329.UNCAPPED_8723, 40.0, 5.0))
        assertEquals("EDGE_CONSUMED", EntryEconomics8329.consumed8329(13.5, EntryEconomics8329.UNCAPPED_8723, 20.0, 5.0))
        assertEquals("EXHAUSTED_RALLY", EntryEconomics8329.consumed8329(0.5, Double.NaN, 40.0, 5.0))
    }
}
