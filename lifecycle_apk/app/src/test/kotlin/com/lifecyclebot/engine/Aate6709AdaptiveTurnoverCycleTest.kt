package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.6709 source locks for adaptive round-trip inventory pacing. */
class Aate6709AdaptiveTurnoverCycleTest {

    private fun slotHealthSource(): String =
        File("src/main/kotlin/com/lifecyclebot/engine/SlotHealthGate.kt").readText()

    @Test
    fun `6709 keeps 6692 no static 24 position choke doctrine`() {
        val src = slotHealthSource()
        assertTrue(src.contains("memeTurnoverAbsoluteCap6689(): Int = Int.MAX_VALUE"))
        assertFalse(src.contains("MEME_TURNOVER_ABSOLUTE_CAP_6689 = 24"))
        assertTrue(src.contains("MEME_EXIT_PRIORITY_ADVISORY_6692"))
    }

    @Test
    fun `paper inventory pressure progressively paces entries instead of growing unbounded`() {
        val src = slotHealthSource()
        assertTrue(src.contains("TURNOVER_SOFT_START_6709 = 48"))
        assertTrue(src.contains("TURNOVER_MEDIUM_START_6709 = 72"))
        assertTrue(src.contains("TURNOVER_HIGH_START_6709 = 96"))
        assertTrue(src.contains("TURNOVER_SEVERE_START_6709 = 120"))
        assertTrue(src.contains("adaptiveTurnoverCadence6709"))
        assertTrue(src.contains("MEME_TURNOVER_PRESSURE_DEFER_6709"))
        assertTrue(src.contains("MEME_TURNOVER_PRESSURE_ADMIT_6709"))
        assertTrue(src.contains("seq6709 % cadence6709.toLong()"))
    }

    @Test
    fun `confirmed high edge bypasses turnover pacing`() {
        val src = slotHealthSource()
        val cadenceBlock = src.substringAfter("if (cadence6709 > 1)")
            .substringBefore("if (!candidateConfirmedHighEdge)")
        assertTrue(cadenceBlock.contains("candidateConfirmedHighEdge"))
        assertTrue(cadenceBlock.contains("MEME_TURNOVER_HIGH_EDGE_BYPASS_6709"))
    }

    @Test
    fun `paper forced open advisory no longer escapes turnover governor`() {
        val src = slotHealthSource()
        val forcedBlock = src.substringAfter("if (forced > FORCED_OPEN_DIRTY)")
            .substringBefore("if (supervisorActive.get()")
        assertTrue(forcedBlock.contains("PAPER_FORCED_OPEN_ADVISORY_6709"))
        assertFalse(forcedBlock.contains("return DeferDecision(false, \"PAPER_FORCED_OPEN_FAIL_OPEN"))
        assertTrue(src.indexOf("PAPER_FORCED_OPEN_ADVISORY_6709") < src.indexOf("adaptiveTurnoverCadence6709(openNow6709"))
    }

    @Test
    fun `operator report exposes cadence and admission counters`() {
        val src = slotHealthSource()
        assertTrue(src.contains("cadence=1/\${turnoverCadenceNow6709.get()}"))
        assertTrue(src.contains("turnover6709[admit=\${turnoverAdmitted6709.get()} defer=\${turnoverDeferred6709.get()} highEdge=\${turnoverHighEdgeBypass6709.get()}]"))
    }
}

// V5.0.6709 retry marker: rerun guarded exit-authority transform from corrected test head.
