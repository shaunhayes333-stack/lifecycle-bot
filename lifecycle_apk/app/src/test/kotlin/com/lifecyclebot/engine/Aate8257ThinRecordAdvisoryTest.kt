package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8257 — a thin book must not lock itself: a 12+ label record refuses live only when its loss is clear on its own
 * sample (mean two SE below zero, or the n>=30 proven form). The operator's learning reset on 5.0.8256 (0 buys → buys)
 * is the reproduction.
 */
class Aate8257ThinRecordAdvisoryTest {
    private fun setup(id: String, n: Int, mean: Double, se: Double) = Evidence8136(CandidateAdmission8136.KIND_SETUP, id, n, mean, se)
    private fun cand(sel: Evidence8136, timing: Evidence8136? = null) = CandidateAdmission8136.Candidate8136(
        mint = "mintT", lane = "SHITCOIN", selectedSetup = sel.id, stage = "FRESH_LAUNCH", priceAgeMs = 2_000L,
        expectedGrossPct = 120.0, costPct = 9.0, setups = listOf(sel), timing = timing, genericScore = 70.0, genericFloor = 50.0,
    )

    @Test fun clearLossesStillRefuseAndTheOneSeBandIsAdvisory() {
        assertTrue(CandidateAdmission8136.clearlyLosing8136(setup("A", 40, -8.0, 2.0)))     // proven
        assertTrue(CandidateAdmission8136.clearlyLosing8136(setup("A", 25, -23.6, 5.0)))    // thin but clear
        assertTrue(CandidateAdmission8136.clearlyLosing8136(setup("A", 15, -6.0, 2.0)))     // thin but clear
        assertFalse(CandidateAdmission8136.clearlyLosing8136(setup("A", 12, -3.0, 2.0)))    // one SE below, two above
        assertFalse(CandidateAdmission8136.clearlyLosing8136(setup("A", 20, -1.5, 1.0)))
        assertFalse(CandidateAdmission8136.clearlyLosing8136(setup("A", 8, -30.0, 1.0)))    // below the 12-label bar
        assertTrue(CandidateAdmission8136.losing8136(setup("A", 12, -3.0, 2.0)))           // still "losing" for ranking
    }

    @Test fun aThinOneSeLossNoLongerRefusesTheSelectedSetupOrItsTiming() {
        val thin = CandidateAdmission8136.decide8136(cand(setup("LAUNCH_CONTINUATION", 14, -3.0, 2.0)))
        assertTrue(thin.compact, thin.reason != "SETUP_RECORD_LOSING_8136")
        assertTrue(thin.trace.any { it == "setupLosingThin=advisory" })
        val clear = CandidateAdmission8136.decide8136(cand(setup("LAUNCH_CONTINUATION", 14, -9.0, 2.0)))
        assertEquals(Terminal8136.REFUSE, clear.terminal)
        assertEquals("SETUP_RECORD_LOSING_8136", clear.reason)
        val timingThin = Evidence8136(CandidateAdmission8136.KIND_TIMING, "X@PEAK", 13, -2.0, 1.5)
        val t = CandidateAdmission8136.decide8136(cand(setup("X", 40, 9.0, 3.0), timingThin))
        assertTrue(t.compact, t.reason != "TIMING_ADVERSE_8136")
        assertTrue(t.trace.any { it == "timingThin=advisory" })
        val timingClear = Evidence8136(CandidateAdmission8136.KIND_TIMING, "X@PEAK", 15, -6.0, 2.0)
        assertEquals("TIMING_ADVERSE_8136", CandidateAdmission8136.decide8136(cand(setup("X", 40, 9.0, 3.0), timingClear)).reason)
    }

    @Test fun theCurveTickIsFiledAsAReservesReading() {
        val ct = java.io.File("src/main/kotlin/com/lifecyclebot/network/CurveTicks7968.kt").readText()
        assertTrue(ct.contains("CurveReserves7951.noteAccount7951(mint, cur.vSol / 1e9, cur.vTok / 1e6, cur.realSol / 1e9)"))
        assertTrue(ct.contains("val vTok = u64(8); val vSol = u64(16); val realSol = u64(32)"))
    }
}
