package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AgeProof9001
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9001 — a record pooled over every age is read at the coin's own age: the points its age band runs below the lane's
 * pooled admitted labels come off setup / timing / Cortex / runner-grab records at the support tests. No refusal is added.
 */
class Aate9001ProofReadAtOwnAgeTest {
    @Test fun bands() {
        assertEquals("UNKNOWN", AgeProof9001.band9001(Double.NaN))
        assertEquals("UNDER_15M", AgeProof9001.band9001(2.0))
        assertEquals("15M_1H", AgeProof9001.band9001(15.0))
        assertEquals("1H_24H", AgeProof9001.band9001(60.0))
        assertEquals("OVER_24H", AgeProof9001.band9001(1_440.0))
    }

    @Test fun theGapIsTheLaneMeanLessTheBandsUpperMeanAndNeverNegative() {
        // 25 Nov export, MOONSHOT admitted labels: lane about +3.9 %, an hour old or more -2.7 % +- 0.8
        assertEquals(5.8, AgeProof9001.gap9001(2_194, 3.9, 1_200, -2.7, 0.8), 1e-9)
        assertEquals(0.0, AgeProof9001.gap9001(2_194, 3.9, 851, 14.7, 3.7), 1e-9)     // a band that pays more adds nothing
        assertEquals(0.0, AgeProof9001.gap9001(AgeProof9001.MIN_LANE_N_9001 - 1L, 3.9, 1_200, -2.7, 0.8), 1e-9)   // thin lane: no claim
        assertEquals(0.0, AgeProof9001.gap9001(2_194, 3.9, AgeProof9001.MIN_BAND_N_9001 - 1L, -2.7, 0.8), 1e-9)    // thin band: no claim
        assertEquals(300.0, AgeProof9001.LABEL_CAP_PCT_9001, 1e-9)
        assertEquals(AgeProof9001.MAX_GAP_PTS_9001, AgeProof9001.gap9001(5_000, 60.0, 500, -10.0, 1.0), 1e-9)
    }

    @Test fun labelsBuildTheGapAndOnlyAdmittedFilledOnesCount() {
        AgeProof9001.resetForTest9001()
        repeat(400) { AgeProof9001.note9001("moonshot", 3.0, if (it % 2 == 0) 60.0 else -10.0, true, true) }   // young: +25
        repeat(400) { AgeProof9001.note9001("MOONSHOT", 600.0, if (it % 4 == 0) 6.0 else -6.0, true, true) }    // old: -3
        repeat(400) { AgeProof9001.note9001("MOONSHOT", 600.0, 500.0, false, true) }                              // refused: ignored
        repeat(400) { AgeProof9001.note9001("MOONSHOT", 600.0, 500.0, true, false) }                              // unfilled: ignored
        assertEquals(0.0, AgeProof9001.gapPts9001("MOONSHOT", 3.0), 1e-9)
        val old = AgeProof9001.gapPts9001("MOONSHOT", 600.0)
        assertTrue("lane +11, old band -3 +- 0.26: gap near 13.7, got $old", old > 13.0 && old < 14.0)
        assertEquals(0.0, AgeProof9001.gapPts9001("MOONSHOT", Double.NaN), 1e-9)
        assertEquals(0.0, AgeProof9001.gapPts9001("EXPRESS", 600.0), 1e-9)
        assertTrue(AgeProof9001.statusLine9001().contains("MOONSHOT[all=n800"))
        AgeProof9001.resetForTest9001()
    }

    @Test fun oneOutlierLabelCannotSetTheLaneMean() {
        AgeProof9001.resetForTest9001()
        repeat(399) { AgeProof9001.note9001("CORE", 600.0, -3.0, true, true) }
        AgeProof9001.note9001("CORE", 3.0, 80_000.0, true, true)
        repeat(100) { AgeProof9001.note9001("CORE", 600.0, -3.0, true, true) }
        assertTrue(AgeProof9001.gapPts9001("CORE", 600.0) < 1.0)
        AgeProof9001.resetForTest9001()
    }

    @Test fun theGapComesOffPooledRecordsOnlyAtTheSupportTests() {
        val setup = CandidateAdmission8136.Evidence8136(CandidateAdmission8136.KIND_SETUP, "S", 80, 9.0, 2.0)
        val base = CandidateAdmission8136.Candidate8136(mint = "M", lane = "MOONSHOT", selectedSetup = "S", regime = "", stage = "",
            priceAgeMs = 0L, missingInputs = emptyList(), expectedGrossPct = 20.0, costPct = 3.0, setups = listOf(setup), cell = setup.copy(kind = "CELL"))
        val old = base.copy(liveShortfall8290 = 1.0, ageGap9001 = 6.0)
        assertEquals(7.0, old.pooledShortfall9001, 1e-9)
        assertEquals(2.0, old.setupsProof8290.single().meanPct, 1e-9)          // 9 - 1 - 6
        assertEquals(8.0, old.cellProof8290!!.meanPct, 1e-9)                    // the cell carries its own age: shortfall only
        assertEquals(9.0, old.setups.single().meanPct, 1e-9)                    // loss tests still read the raw record
        assertTrue(CandidateAdmission8136.supported8136(base.setupsProof8290.single(), 3.0))
        assertTrue(!CandidateAdmission8136.supported8136(old.setupsProof8290.single(), 3.0))
    }

    @Test fun wired() {
        val adm = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertTrue(adm.contains("ageGap9001 = AgeProof9001.gapFor9001(ts, lane, live8290),"))
        assertTrue(adm.contains("LiveAdmissionProof8290.shift8290(c.cortexStrong8200, c.pooledShortfall9001)"))
        assertTrue(adm.contains("LiveAdmissionProof8290.shift8290(c.runnerGrab8245, c.pooledShortfall9001)"))
        assertTrue(adm.contains("LiveAdmissionProof8290.shift8290(c.marketCell8723, c.liveShortfall8290)"))   // pockets unshifted
        val edge = File("src/main/kotlin/com/lifecyclebot/engine/truth/EdgeModel8537.kt").readText()
        assertTrue(edge.contains("AgeProof9001.note9001(s.raw.lane, s.raw.ageMin, net, s.admitted, filled)"))
        assertTrue(edge.contains("AgeProof9001.note9001(r.lane, r.ageMin, d(\"net\"), o.optBoolean(\"admitted\"), o.optBoolean(\"fill\", true))"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/EntryReceipt8500.kt").readText().contains("byAge9001{"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/DiagnosisBrief8438.kt").readText().contains("AgeProof9001.statusLine9001()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 186. The proof is read at the coin's own age (9001)"))
    }
}
