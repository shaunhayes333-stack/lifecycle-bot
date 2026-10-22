package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LaneCheatSheet8323
import com.lifecyclebot.engine.cortex.LaneCheatSheet8323.Record8323
import com.lifecyclebot.engine.cortex.LaneCheatSheet8323.Verdict8323
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Candidate8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8325 §FAVOUR_IS_LOCAL — 5.0.8323 live went 2 / 12 with the cheat sheets favouring setups whose own records lost:
 * SHITCOIN CROWD_FORMING n2 -10.1 (live1 -17.3), RS_LEADER n30 -1.2 (live1 -3.9); EXPRESS IGNITION_5M n52 +1.9 and
 * BASE_BREAKOUT_FLOW n20 +0.9 below cost. FAVOUR now needs this install's own executable record (20+, mean - SE above
 * max(prior, 0) + cost) and no losing live close, on every lane; the hive ranks and priors, never favours.
 */
class Aate8325FavourTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    /** A merged record that reads well while the local one does not (the 8324 hive path). */
    private fun hiveHeavy(id: String, localN: Double, localMean: Double, localSe: Double, liveN: Double = 0.0, liveMean: Double = Double.NaN) =
        Record8323(id, 0.5, 40.0, 25.0, 3.0, 0.1, liveN = liveN, liveMeanPct = liveMean,
            hiveN = 824.0, hiveMeanPct = 40.0, hiveSePct = 6.0, localN = localN, localMeanPct = localMean, localSePct = localSe)

    @Test fun theOwnersFavouredLosersAreNoLongerFavouredOnAnyLane() {
        // CROWD_FORMING: 2 local labels at -10.1, one live close at -17.3
        val crowd = hiveHeavy("CROWD_FORMING", 2.0, -10.1, 6.0, liveN = 1.0, liveMean = -17.3)
        // RS_LEADER: 30 local labels at -1.2, one live close at -3.9
        val rs = hiveHeavy("RS_LEADER", 30.0, -1.2, 1.0, liveN = 1.0, liveMean = -3.9)
        // IGNITION_5M n52 +1.9 +/- 1.5 and BASE_BREAKOUT_FLOW n20 +0.9: below max(prior, 0) + cost by one SE
        val ign = Record8323("IGNITION_5M", 0.5, 52.0, 1.9, 1.5, localN = 52.0, localMeanPct = 1.9, localSePct = 1.5)
        val bbf = Record8323("BASE_BREAKOUT_FLOW", 0.5, 20.0, 0.9, 2.0, localN = 20.0, localMeanPct = 0.9, localSePct = 2.0)
        for (prev in Verdict8323.values()) {
            for (r in listOf(crowd, rs, ign, bbf)) {
                assertNotEquals("${r.setup} from $prev", Verdict8323.FAVOUR, LaneCheatSheet8323.judge8323(r, prev))
                assertFalse(LaneCheatSheet8323.localFavour8325(r))
            }
        }
        // every lane's sheet applies the same rule (the sheet is lane-generic)
        for (lane in LaneCheatSheet8323.LANES_8323) {
            val id = "TEST_FAVOUR_8325_$lane"
            val s = LaneCheatSheet8323.applyRecords8323(lane, listOf(crowd.copy(setup = id)))
            assertFalse(lane, s.favour.contains(id))
        }
    }

    @Test fun theHiveCanNeverPutASetupIntoFavourByItself() {
        // strong network record, local record thin or contradicting: never FAVOUR
        assertNotEquals(Verdict8323.FAVOUR, LaneCheatSheet8323.judge8323(hiveHeavy("H", 5.0, 12.0, 4.0), Verdict8323.NEUTRAL))
        assertNotEquals(Verdict8323.FAVOUR, LaneCheatSheet8323.judge8323(hiveHeavy("H", 19.0, 30.0, 3.0), Verdict8323.NEUTRAL))
        assertNotEquals(Verdict8323.FAVOUR, LaneCheatSheet8323.judge8323(hiveHeavy("H", 60.0, -4.0, 1.0), Verdict8323.NEUTRAL))
        // the hysteresis keeps favour only on the LOCAL record above cost
        assertNotEquals(Verdict8323.FAVOUR, LaneCheatSheet8323.judge8323(hiveHeavy("H", 60.0, -0.5, 1.0), Verdict8323.FAVOUR))
        // the local record earns it: 20+ labels, mean - SE above max(prior, 0) + cost, live not losing
        val good = hiveHeavy("H", 40.0, 9.0, 2.0)
        assertEquals(Verdict8323.FAVOUR, LaneCheatSheet8323.judge8323(good, Verdict8323.NEUTRAL))
        assertEquals("cost counts", Verdict8323.NEUTRAL, LaneCheatSheet8323.judge8323(good, Verdict8323.NEUTRAL, costPct = 8.0))
        assertEquals("one losing live close", Verdict8323.NEUTRAL,
            LaneCheatSheet8323.judge8323(good.copy(liveN = 1.0, liveMeanPct = -5.0), Verdict8323.NEUTRAL))
        assertNotEquals(Verdict8323.FAVOUR, LaneCheatSheet8323.judge8323(good.copy(liveN = 1.0, liveMeanPct = -5.0), Verdict8323.FAVOUR))
        assertTrue(LaneCheatSheet8323.liveAnyLosing8325(good.copy(liveN = 1.0, liveMeanPct = -0.1)))
    }

    @Test fun theSheetRecordsCarryTheLocalExecutableRecord() {
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(pb.contains("val localExec8325 = local8324?.takeIf { it.n > 0.0 }?.let { localExecutable8325(lane, s.id, it) }"))
        assertTrue(pb.contains("localSePct = localExec8325?.takeIf { it.n > 1.0 }"))
        assertTrue(pb.contains("Cortex7885.executableCopy8187(local, Cortex7885.executableOffset8187(lane)"))
        val sheet = src("engine/cortex/LaneCheatSheet8323.kt")
        assertTrue(sheet.contains("if (favourEarned8323(r, costPct)) return Verdict8323.FAVOUR"))
        assertFalse(sheet.contains("|| hiveFavour8324(r, costPct)) return Verdict8323.FAVOUR"))
    }

    private fun cand(lane: String, setups: List<Evidence8136>, sheet: Map<String, String>) = Candidate8136(
        mint = "MINT8325fav", lane = lane, selectedSetup = setups.first().id, regime = "TRENDING", stage = "BASE_START",
        priceAgeMs = 2_000L, expectedGrossPct = 40.0, costPct = 6.0, downsidePct = 15.0,
        expectedGrossBySetup = setups.associate { it.id to 40.0 }, setups = setups, genericScore = 40.0, genericFloor = 30.0,
        sheet8323 = sheet,
    )

    @Test fun admissionReadsAFavourOnlyOnTwentyLabels() {
        for (lane in LaneCheatSheet8323.LANES_8323) {
            val thin = Evidence8136(CandidateAdmission8136.KIND_SETUP, "FAV", 12, 10.0, 3.0)
            val c = cand(lane, listOf(thin), mapOf("FAV" to CandidateAdmission8136.SHEET_FAVOUR_8323))
            assertNull(lane, CandidateAdmission8136.sheetFavour8323(c, c.setupsProof8290, c.setupTimingsProof8290))
            val ok = Evidence8136(CandidateAdmission8136.KIND_SETUP, "FAV", 22, 10.0, 3.0)
            val c2 = cand(lane, listOf(ok), mapOf("FAV" to CandidateAdmission8136.SHEET_FAVOUR_8323))
            assertEquals(lane, "FAV", CandidateAdmission8136.sheetFavour8323(c2, c2.setupsProof8290, c2.setupTimingsProof8290)?.id)
        }
    }
}
