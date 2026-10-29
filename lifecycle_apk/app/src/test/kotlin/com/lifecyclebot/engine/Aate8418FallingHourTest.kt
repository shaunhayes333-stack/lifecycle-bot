package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Candidate8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import com.lifecyclebot.engine.truth.FallingHourCohort8418
import com.lifecyclebot.engine.truth.NetEdge8030
import com.lifecyclebot.engine.truth.StrategyMove8153
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.8418 — 8418 phone run: FALLING_HOUR_UNPROVEN_8418 refused 403 MOONSHOT candidates on no evidence. The rule now
 * refuses only when the lane's own forward-labelled falling-hour record is proven losing after cost; otherwise it defers
 * until the hour turns, and never fires on hour direction alone inside a fresh launch's window.
 */
class Aate8418FallingHourTest {
    @Before fun setUp() { FallingHourCohort8418.resetForTests8418() }
    @After fun tearDown() { FallingHourCohort8418.resetForTests8418() }

    private fun cand(proof: Evidence8136?) = Candidate8136(
        mint = "fallingHour8418" + (proof?.n ?: 0), lane = "MOONSHOT", selectedSetup = "IGNITION_5M",
        expectedGrossPct = Double.NaN, costPct = 3.4, costSizeSol = 0.03, moveWait8418 = NetEdge8030.MOVE_WAIT_FALLING_8418,
        expectedGrossBySetup = mapOf("IGNITION_5M" to Double.NaN), genericScore = 70.0, genericFloor = 40.0,
        fallingHourProof8418 = proof,
    )

    private fun ev(n: Int, mean: Double, se: Double, run: Double = 0.02) =
        Evidence8136(FallingHourCohort8418.KIND_8418, "MOONSHOT", n, mean, se, runnerRate = run)

    @Test fun noEvidenceDefersUntilTheHourTurns() {
        val d = CandidateAdmission8136.decide8136(cand(null))
        assertEquals(d.compact, Terminal8136.DEFER_DATA, d.terminal)
        assertEquals(CandidateAdmission8136.FALLING_HOUR_DEFER_8418, d.reason)
        val missing = CandidateAdmission8136.missingOf8144(d)
        assertTrue(missing.toString(), CandidateAdmission8136.FALLING_HOUR_WAIT_8418 in missing)
        // economics stays binding: a confirmed run cannot pass a falling hour as an optional gap
        assertTrue(missing.any { CandidateAdmission8136.criticalMissing8170(it) })
        assertEquals(FdgReceipt8397.Outcome.DEFER_DATA, FdgReceipt8397.outcome(false, d.reason))
    }

    @Test fun aThinOrRunnerRichOrBreakEvenRecordStillDefers() {
        assertEquals(Terminal8136.DEFER_DATA, CandidateAdmission8136.decide8136(cand(ev(12, -9.0, 1.0))).terminal)      // thin
        assertEquals(Terminal8136.DEFER_DATA, CandidateAdmission8136.decide8136(cand(ev(80, -1.0, 1.5))).terminal)      // mean+se >= 0
        assertEquals(Terminal8136.DEFER_DATA, CandidateAdmission8136.decide8136(cand(ev(80, -6.0, 1.0, 0.15))).terminal) // runner tail
    }

    @Test fun aProvenLosingRecordRefusesByName() {
        val d = CandidateAdmission8136.decide8136(cand(ev(60, -7.7, 1.2)))
        assertEquals(d.compact, Terminal8136.REFUSE, d.terminal)
        assertEquals(CandidateAdmission8136.FALLING_HOUR_PROVEN_LOSING_8418, d.reason)
        assertTrue(d.specific)
    }

    @Test fun provenLosingNeedsNetOfCostAndUnsuspectEvidence() {
        assertTrue(FallingHourCohort8418.provenLosing8418(ev(30, -3.0, 1.0)))
        assertFalse(FallingHourCohort8418.provenLosing8418(ev(29, -30.0, 1.0)))
        assertFalse(FallingHourCohort8418.provenLosing8418(ev(30, -3.0, 1.0).copy(netOfCost = false)))
        assertFalse(FallingHourCohort8418.provenLosing8418(ev(30, -3.0, 1.0).copy(suspect8290 = true)))
        assertFalse(FallingHourCohort8418.provenLosing8418(null))
    }

    @Test fun theCohortIsTheRulesOwnPopulation() {
        assertTrue(FallingHourCohort8418.qualifies8418("MOONSHOT", -12.0, 300.0, 0, Double.NaN, Double.NaN))
        assertFalse("rising hour", FallingHourCohort8418.qualifies8418("MOONSHOT", 4.0, 300.0, 0, Double.NaN, Double.NaN))
        assertFalse("not a momentum lane", FallingHourCohort8418.qualifies8418("DIP_HUNTER", -12.0, 300.0, 0, Double.NaN, Double.NaN))
        assertFalse("inside the launch window", FallingHourCohort8418.qualifies8418("MOONSHOT", -12.0, 20.0, 0, Double.NaN, Double.NaN))
        assertFalse("supported strategy", FallingHourCohort8418.qualifies8418("MOONSHOT", -12.0, 300.0, 45, 24.0, 4.0))
        assertTrue("unknown age is not proof of a launch", FallingHourCohort8418.qualifies8418("MOONSHOT", -12.0, null, 0, Double.NaN, Double.NaN))
    }

    @Test fun gradedLabelsBuildTheLaneRecord() {
        val s = FallingHourCohort8418.Stat8418()
        repeat(40) { s.add(-6.0 + (it % 2), 5.0) }
        FallingHourCohort8418.seedForTest8418("moonshot", s)
        val e = FallingHourCohort8418.evidence8418("MOONSHOT")!!
        assertEquals(40, e.n)
        assertEquals(-5.5, e.meanPct, 1e-9)
        assertTrue(e.sePct > 0.0 && e.sePct < 1.0)
        assertEquals(0.0, e.runnerRate, 1e-9)
        assertTrue(FallingHourCohort8418.provenLosing8418(e))
        assertNull(FallingHourCohort8418.evidence8418("SHITCOIN"))
    }

    @Test fun aFreshLaunchIsNeverJudgedOnItsHourDirection() {
        val launch = StrategyMove8153.expected8153(45.0, 36.0, true, null,
            0, Double.NaN, Double.NaN, 3.4, true,
            signedHourPct = -31.0, momentumEntry = true, inLaunchWindow8418 = true)
        assertFalse(launch.basis == "FALLING_HOUR_UNPROVEN")
        assertNull(NetEdge8030.moveWait8418(launch, 3.4))
        assertTrue(FallingHourCohort8418.inLaunchWindow8418(20.0))
        assertFalse(FallingHourCohort8418.inLaunchWindow8418(null))
        assertFalse(FallingHourCohort8418.inLaunchWindow8418(90.0))
        // outside the window the falling hour still produces the named wait
        val older = StrategyMove8153.expected8153(45.0, 36.0, true, null,
            0, Double.NaN, Double.NaN, 3.4, false,
            signedHourPct = -31.0, momentumEntry = true, inLaunchWindow8418 = false)
        assertEquals(NetEdge8030.MOVE_WAIT_FALLING_8418, NetEdge8030.moveWait8418(older, 3.4))
    }
}
