package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.LaneCheatSheet8323
import com.lifecyclebot.engine.cortex.LaneCheatSheet8323.Record8323
import com.lifecyclebot.engine.cortex.LaneCheatSheet8323.Verdict8323
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Candidate8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8325 — what let the 8323 losers in, undone on every lane:
 *   - the 8324 missed-runner credit no longer lifts a losing record past a live loss test (8136 setup / timing / cell /
 *     stage, the PICKED_SETUP / TIMING doors, the sheet's AVOID);
 *   - a LIVE admit needs the lane's own live closes not losing, the lane's own labels positive, and — when the admitting
 *     read is a cohort / cell / runner-grab (tail or lane-wide) read — the chosen setup's own record positive past cost;
 *   - a veto no longer retires on a raw tail read alone.
 */
class Aate8325LiveLossTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private val lanes = listOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED", "QUALITY", "BLUECHIP",
        "DIP_HUNTER", "TREASURY", "CASHGEN", "CYCLIC", "CORE")

    private fun ev(id: String, n: Int, mean: Double, se: Double, run: Double = 0.0, kind: String = CandidateAdmission8136.KIND_SETUP) =
        Evidence8136(kind, id, n, mean, se, run)

    private fun cand(lane: String, sel: Evidence8136, rate: Double = Double.NaN, prem: Double = Double.NaN, live: Boolean = false,
                     cortex: Evidence8136? = null, laneR: DoubleArray? = null, laneLabel: Evidence8136? = null, cell: Evidence8136? = null) =
        Candidate8136(
            mint = "MINT8325loss", lane = lane, selectedSetup = sel.id, regime = "TRENDING", stage = "BASE_START",
            priceAgeMs = 2_000L, expectedGrossPct = 40.0, costPct = 6.0, downsidePct = 15.0,
            expectedGrossBySetup = mapOf(sel.id to 40.0), setups = listOf(sel), genericScore = 40.0, genericFloor = 30.0,
            tailLaneRate8324 = rate, tailPremium8324 = prem, live = live, cortexStrong8200 = cortex,
            laneRealised8325 = laneR, laneLabel8325 = laneLabel, cell = cell,
        )

    @Test fun theRunnerCreditNoLongerBypassesALossTestOnAnyLane() {
        val thin = ev("LAUNCH", 14, -20.0, 2.0, run = 0.0)
        for (lane in lanes) {
            val d = CandidateAdmission8136.decide8136(cand(lane, thin, 0.05, 780.0, live = true))
            assertEquals(lane, "SETUP_RECORD_LOSING_8136", d.reason)
            assertEquals(lane, Terminal8136.REFUSE, d.terminal)
        }
        // the sheet AVOIDs on the record's own labels whatever the lane's pond
        val r = Record8323("LAUNCH", 0.5, 14.0, -20.0, 2.0, 0.0)
        assertEquals(Verdict8323.AVOID, LaneCheatSheet8323.judge8323(r, Verdict8323.NEUTRAL, 0.0, 0.05, 780.0))
        // the live doors and the 8136 loss tests read no tail credit
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        assertFalse(pb.contains("st.mean() + tailCredit8324(lane, st)"))
        val adm = src("engine/truth/CandidateAdmission8136.kt")
        val decide = adm.substringAfter("fun decide8136(c: Candidate8136, doorBinds8393:").substringBefore("internal fun bestSetup8153(")
        assertFalse(decide.contains("tailCredited8324("))
        // the sheet's clear-loss read carries no missed-runner credit either
        val sheet = src("engine/cortex/LaneCheatSheet8323.kt")
        val clear = sheet.substringAfter("private fun clearLoss8323(").substringBefore("fun judge8323(")
        assertFalse(clear.contains("missedRunnerPts8324"))
    }

    @Test fun aCortexStrongCohortAdmitsALiveBuyOnlyWithThePositiveSetupRecordOrTheAdvisoryBand() {
        val cortex = ev("STRONG@X", 60, 30.0, 4.0, kind = CandidateAdmission8136.KIND_CORTEX_8200)
        val losingSetup = ev("S", 14, -1.0, 4.0)       // a thin one-SE loser: advisory on its own, not a clear loss
        val positiveSetup = ev("S", 14, 4.0, 4.0)
        for (lane in lanes) {
            // paper: unchanged (the cohort read supports)
            assertTrue(lane, CandidateAdmission8136.decide8136(cand(lane, losingSetup, cortex = cortex)).admitted)
            // superseded by V5.0.8328 — the thin one-SE live loss stays advisory under the 8316 directive; the cohort read supports it
            assertTrue(lane, CandidateAdmission8136.decide8136(cand(lane, losingSetup, live = true, cortex = cortex)).admitted)
            assertTrue(lane, CandidateAdmission8136.decide8136(cand(lane, positiveSetup, live = true, cortex = cortex)).admitted)
        }
    }

    @Test fun aLiveAdmitNeedsTheLanesOwnEvidencePositive() {
        val proven = ev("P", 60, 20.0, 3.0)
        for (lane in lanes) {
            assertTrue(lane, CandidateAdmission8136.decide8136(cand(lane, proven, live = true)).admitted)
            // the lane's own live closes losing (3+, mean < 0)
            val lr = CandidateAdmission8136.decide8136(cand(lane, proven, live = true, laneR = doubleArrayOf(7.0, -9.0)))
            assertEquals(lane, CandidateAdmission8136.LIVE_LANE_LOSING_8325, lr.reason)
            // the lane's own labels not positive after cost
            val ll = CandidateAdmission8136.decide8136(cand(lane, proven, live = true, laneLabel = ev(lane, 200, -3.0, 1.0, kind = "LANE")))
            assertEquals(lane, CandidateAdmission8136.LIVE_LANE_LABELS_8325, ll.reason)
            // positive lane labels and a lane not losing: admitted
            assertTrue(lane, CandidateAdmission8136.decide8136(cand(lane, proven, live = true, laneR = doubleArrayOf(4.0, 6.0),
                laneLabel = ev(lane, 200, 12.0, 4.0, kind = "LANE"))).admitted)
            // paper never reads the lane fields
            assertTrue(lane, CandidateAdmission8136.decide8136(cand(lane, proven, laneR = doubleArrayOf(7.0, -9.0))).admitted)
        }
        val c = cand("SHITCOIN", proven, live = true)
        assertNull(CandidateAdmission8136.liveLocalGuard8325(c, proven, proven))
        assertNull(CandidateAdmission8136.liveLocalGuard8325(c, ev("cell", 60, 20.0, 2.0, kind = CandidateAdmission8136.KIND_CELL), null))  // superseded by V5.0.8328
        val adm = src("engine/truth/CandidateAdmission8136.kt")
        assertTrue(adm.contains("laneRealised8325 = if (live8290) (try { com.lifecyclebot.engine.cortex.Cortex7885.laneRealised8295(lane) }"))
        assertTrue(adm.contains("laneLabel8325 = if (live8290) laneLabel8325(lane) else null,"))
    }

    @Test fun aVetoNoLongerRetiresOnARawTailAlone() {
        val st = CortexLedger7885.labelStat8187()
        repeat(44) { st.add(-15.0, false) }
        repeat(6) { st.add(2_500.0, true, witnessed = true) }
        assertTrue(st.tailReal8322())
        assertFalse(Cortex7885.vetoRobustProof8185(st))
        assertFalse(Cortex7885.vetoProvenWinners8006(st))
    }
}
