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

/**
 * V5.0.8723 / 8724 — a lane buys the market cell its OWN record proves pays (no stop assumed, no other lane's labels), under
 * a fixed precedence; the fixed chase cap binds only on its own record.
 */
class Aate8723PayingCellTest {
    private val other = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"
    private val pump = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmpump"

    @Test fun theOutcomeIsTheLabelAsItPrintedAndTheCellBelongsToOneLane() {
        assertEquals("no stop is assumed to fill", -55.0, PayingCell8723.outcome8723(-55.0), 1e-9)
        assertEquals(-100.0, PayingCell8723.outcome8723(-250.0), 1e-9)
        assertEquals(60.0, PayingCell8723.outcome8723(60.0), 1e-9)
        assertTrue("one outlier cannot prove a cell", PayingCell8723.outcome8723(5_000.0) < 5_000.0)
        assertEquals("MOONSHOT_OTHER_A1_L3", PayingCell8723.key8723("moonshot", other, 2.0, 20_000.0))
        assertEquals("EXPRESS_PUMP_A1_L3", PayingCell8723.key8723("EXPRESS", pump, 2.0, 20_000.0))
        assertNull("an unread pool or birth is no cell", PayingCell8723.key8723("MOONSHOT", other, Double.NaN, 20_000.0))
        assertNull(PayingCell8723.key8723("MOONSHOT", other, 2.0, 0.0))
        assertNull(PayingCell8723.key8723("", other, 2.0, 20_000.0))
    }

    @Test fun aCellIsProvenOnTwoStandardErrorsOneLabelPerCoinPerLane() {
        PayingCell8723.resetForTest8723()
        try {
            // one coin labelled 40 times inside a quarter hour is one label
            repeat(40) { PayingCell8723.addForTest8723("MOONSHOT", "${other}A", 2.0, 20_000.0, 300.0, 1_000_000L + it) }
            assertEquals(1.0, PayingCell8723.recordForTest8723("MOONSHOT", other, 2.0, 20_000.0)!![0], 1e-9)
            // 80 coins: half lose 60 % (as printed), half make +120 %
            repeat(80) { i -> PayingCell8723.addForTest8723("MOONSHOT", "$other$i", 2.0, 20_000.0, if (i % 2 == 0) -60.0 else 120.0, 2_000_000L + i) }
            val r = PayingCell8723.recordForTest8723("MOONSHOT", other, 2.0, 20_000.0)!!
            val e = PayingCell8723.evidenceOf8723("MOONSHOT_OTHER_A1_L3", r)
            assertNotNull("proven: mean ~ +30 %, two SE ~ 21", e)
            assertTrue(e!!.lowerPct > 0.0 && e.kind == PayingCell8723.KIND_8723)
            assertTrue("it is admission evidence as it stands", CandidateAdmission8136.supported8136(e, 4.0))
            // MOONSHOT's labels authorise MOONSHOT only: the same coins are no record for another lane or another origin
            assertNull(PayingCell8723.recordForTest8723("EXPRESS", other, 2.0, 20_000.0))
            assertNull(PayingCell8723.recordForTest8723("MOONSHOT", pump, 2.0, 20_000.0))
            // a thin outlier cell is not proven at two SE: 40 coins, four +400 %, the rest -60 %
            repeat(40) { i -> PayingCell8723.addForTest8723("SHITCOIN", "$pump$i", 2_000.0, 2_000.0, if (i % 10 == 0) 400.0 else -60.0, 3_000_000L + i) }
            assertNull(PayingCell8723.evidenceOf8723("x", PayingCell8723.recordForTest8723("SHITCOIN", pump, 2_000.0, 2_000.0)))
            assertFalse(PayingCell8723.paying8723(29.0, 29.0 * 50.0, 29.0 * 2_500.0))
        } finally { PayingCell8723.resetForTest8723() }
    }

    @Test fun precedenceMeasuredStrategyEvidenceOutranksTheCell() {
        val adm = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        val market = adm.indexOf("val market8723 = LiveAdmissionProof8290.shift8290(c.marketCell8723, c.liveShortfall8290)?.takeIf { supported8136(it, c.costPct) }")
        assertTrue(market > 0)
        assertTrue(adm.contains("marketCell8723 = PayingCell8723.evidence8723(ts, lane, nowMs),"))
        // 1. safety and structure are decided before the cell is read, and a RUG_PRONE wait is never answered by it
        assertTrue(adm.indexOf("return done(Terminal8136.REFUSE, \"SAFETY_REFUSED_8136\", true, c.selectedSetup)") in 1 until market)
        assertTrue(adm.indexOf("return done(Terminal8136.REFUSE, \"STRUCTURAL_RISK_8136\", true, c.selectedSetup)") in 1 until market)
        assertTrue(adm.contains("!(carried8723 && wait != \"RUG_PRONE\")"))
        // 4. the selected setup's losing record, the cheat sheet, the lane's losing cell, timing and realised losses still refuse
        for (binding in listOf(
            "if (clearlyLosing8136(selectedRec) && doorBinds8393(\"SETUP_RECORD_LOSING_8136\") && !probe8429(c, trace, \"SETUP_RECORD_LOSING_8136\")) return",
            "if (doorBinds8393(\"SHEET_AVOID_8323\") && !probe8429(c, trace, \"SHEET_AVOID_8323\")) return",
            "if (!probe8429(c, trace, \"CELL_PROVEN_LOSING_8136\")) return",
            "if (clearlyLosing8136(timingLoss8324) && doorBinds8393(\"TIMING_ADVERSE_8136\") && !probe8429(c, trace, \"TIMING_ADVERSE_8136\")) return",
            "if (doorBinds8393(why)) return done(Terminal8136.REFUSE, why, true, chosen)"))
            assertTrue(binding, adm.contains(binding))
        assertFalse("the cell supplies no economics of its own beyond the proven-net rule every edge has", adm.contains("grossFromMarketCell8723"))
        // 5/6. it answers the lane-wide stage record and is an admitting edge
        assertTrue(adm.contains("if (provenLosing8136(stageLoss) && !cellSupported && !carried8723 && !stageLatePays8198(stageLoss) && doorBinds8393(\"STAGE_PROVEN_LOSING_8136\")) {"))
        assertTrue(adm.contains("market8723?.takeIf { !losing8136(cellLoss) })   // V5.0.8723"))
        val lane = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaneAuthority8577.kt").readText()
        val state = lane.substring(lane.indexOf("private fun memeEntryState8696("))
        assertTrue(state.contains("if (!cellPays8723 && originLoses8703(ts, l, nowMs)) return LIVE_ORIGIN_COHORT_LOSES_8703"))
        assertTrue("the coin's own chart shapes refuse before the cell is consulted",
            state.indexOf("expertAvoid8692(ts, nowMs)?.let { return it }") in 1 until state.indexOf("if (cellPays8723) return setupLoses8714(ts, l, nowMs)"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/EdgeModel8537.kt").readText().contains("PayingCell8723.learn8723(s.mint, s.raw.lane, s.raw.ageMin, s.raw.liq, netPct, s.atMs)"))
    }

    @Test fun theChaseCapEarnsItsBind() {
        assertFalse("no record: the fixed cap does not bind", EntryEconomics8329.chaseCapBinds8723(0, Double.NaN, Double.NaN))
        assertFalse("the coins it turned away did not lose", EntryEconomics8329.chaseCapBinds8723(60, 4.0, 3.0))
        assertTrue(EntryEconomics8329.chaseCapBinds8723(60, -9.0, 3.0))
        // uncapped, the economics still decide: paying 13.5 % up leaves enough of a 40 % move, not of a 20 % one
        assertNull(EntryEconomics8329.consumed8329(13.5, EntryEconomics8329.UNCAPPED_8723, 40.0, 5.0))
        assertEquals("EDGE_CONSUMED", EntryEconomics8329.consumed8329(13.5, EntryEconomics8329.UNCAPPED_8723, 20.0, 5.0))
        assertEquals("EXHAUSTED_RALLY", EntryEconomics8329.consumed8329(0.5, Double.NaN, 40.0, 5.0))
    }
}
