package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8438 — the report opens with a diagnosis brief: a verdict on money, edge per lane, every door's forward record,
 * exits, learning, inputs and code faults, and a fix queue ranked by the money each item represents.
 */
class Aate8438DiagnosisBriefTest {
    @Test fun doorsAreJudgedOnWhatTheyRefused() {
        assertEquals("THIN", DiagnosisBrief8438.gateVerdict8438(12.0, 9.0, 1.0))
        assertEquals("COSTING_MONEY", DiagnosisBrief8438.gateVerdict8438(79.0, 3.9, 1.5))
        assertEquals("PROTECTING", DiagnosisBrief8438.gateVerdict8438(27.0, -10.3, 3.0))
        assertEquals("NEUTRAL", DiagnosisBrief8438.gateVerdict8438(40.0, 0.9, 2.0))
    }

    @Test fun lanesAndMoneyGetAVerdict() {
        assertEquals("LEAK_EDGE_NOT_BOUGHT", DiagnosisBrief8438.laneVerdict8438(1021, 44.2, 0L, 0, 0.0))
        assertEquals("BLEEDING_LIVE", DiagnosisBrief8438.laneVerdict8438(128, -4.8, 3L, 3, -0.004))
        assertEquals("NO_EDGE_SEEN", DiagnosisBrief8438.laneVerdict8438(1002, -12.4, 0L, 0, 0.0))
        assertEquals("THIN", DiagnosisBrief8438.laneVerdict8438(12, 30.0, 0L, 0, 0.0))
        assertEquals("OK", DiagnosisBrief8438.laneVerdict8438(200, 5.0, 4L, 1, 0.01))
        assertEquals("UNPROVEN", DiagnosisBrief8438.moneyVerdict8438(3, -0.009))
        assertEquals("LOSING", DiagnosisBrief8438.moneyVerdict8438(25, -0.001))
        assertEquals("PROFITABLE", DiagnosisBrief8438.moneyVerdict8438(25, 0.002))
    }

    @Test fun theFixQueueIsRankedByMoney() {
        val q = DiagnosisBrief8438.rank8438(listOf(
            DiagnosisBrief8438.Fix8438(10.0, "FAULT", "a"), DiagnosisBrief8438.Fix8438(4500.0, "LEAK", "b"),
            DiagnosisBrief8438.Fix8438(300.0, "COSTING", "c")), max = 2)
        assertEquals(listOf("b", "c"), q.map { it.text })
    }

    @Test fun theBriefRendersEverySectionAndNeverThrows() {
        val r = DiagnosisBrief8438.render8438(1_800_000_000_000L)
        assertTrue(r.startsWith(DiagnosisBrief8438.TITLE_8438))
        for (s in listOf("A RUN", "B MONEY", "C EDGE", "D DOORS", "E EXITS", "F LEARNING", "G INPUTS", "H CODE", "I FIX QUEUE")) {
            assertTrue("$s present (rendered or SECTION_FAILED)", r.contains("  $s"))
        }
        val phc = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        val header = phc.substringAfter("===== AATE Pipeline Health Snapshot =====").substringBefore("Cortex scoreboard")
        assertTrue("the brief heads the report", header.contains("appendTop8438(sb)"))
        assertTrue(phc.contains("sb.append('\\n').append(DiagnosisBrief8438.render8438())"))
    }
}
