package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.EdgeModel8537
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8839 — the model's refusal binds once the coins it refuses are proven to lose, whatever it keeps. */
class Aate8839RefusalEarnedByLossesTest {
    @Test fun theReportsLanesEachEarnTheirRefusal() {
        // 5.0.8837 report, refuse8715=n/mean/se per lane, with the kept side the model barely used
        val lanes = listOf(Triple(474, -8.8, 2.0), Triple(248, -10.3, 1.5), Triple(983, -11.4, 1.5), Triple(850, -13.0, 1.8),
            Triple(864, -12.0, 1.7), Triple(521, -17.9, 2.4), Triple(900, -12.2, 1.7), Triple(394, -8.6, 2.1), Triple(188, -8.8, 2.8))
        for ((n, m, se) in lanes) assertTrue("n$n $m±$se", EdgeModel8537.refusalAuthority8715(n, m, se, 3, 1.0, 5.0))
    }

    @Test fun anOutOfSampleRecordOfLosersRefusesInLiveAndPaper() {
        val o = EdgeModel8537.Oos()
        val r = java.util.Random(8839)
        repeat(300) { o.add(EdgeModel8537.target8567(-15.0), -12.0 + r.nextGaussian() * 25.0) }
        repeat(4) { o.add(EdgeModel8537.target8567(5.0), 10.0) }
        assertTrue(o.keepN < 30)
        assertTrue(EdgeModel8537.refusalAuthority8715(o.refN, o.refMean, o.refSe, o.keepN, o.keepMean, o.keepSe))
        val v = EdgeModel8537.Verdict(-9.0, false, "MOONSHOT", 900L, 0, Double.NaN, Double.NaN, -8.0, refuses8715 = true)
        val c = CandidateAdmission8136.Candidate8136("m8839", "MOONSHOT", null, expectedGrossPct = 20.0, costPct = 3.3,
            genericScore = 80.0, genericFloor = 15.0, edge8537 = v)
        assertEquals(CandidateAdmission8136.EDGE_MODEL_NEGATIVE_8537, CandidateAdmission8136.edgeModelRefusal8537(c.copy(live = true), null, mutableListOf()))
        assertEquals(CandidateAdmission8136.EDGE_MODEL_NEGATIVE_8537, CandidateAdmission8136.edgeModelRefusal8537(c, null, mutableListOf()))
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/EdgeModel8537.kt").readText()
        assertTrue(src.contains("refN >= AUTH_MIN_N && refMean.isFinite() && refSe.isFinite() && refMean + REFUSAL_Z_8839 * refSe < 0.0"))
    }
}
