package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Candidate8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8136 — one candidate-level admission record: the generic V3 score is a prior, candidate-specific evidence
 * decides, hard safety and loss patterns are never overridden, DATA_INCOMPLETE defers (bounded).
 */
class Aate8136AdmissionRecordTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    /** A clean, freshly priced candidate whose economics clear 2x cost (gross 30%, cost 6%). */
    private fun base(
        selected: String? = "PRE_IGNITION_BASE",
        setups: List<Evidence8136> = emptyList(),
        cell: Evidence8136? = null,
        timing: Evidence8136? = null,
        score: Double = 12.0,
        floor: Double = 30.0,
    ) = Candidate8136(
        mint = "MINT8136pump", lane = "SHITCOIN", selectedSetup = selected, regime = "TRENDING", stage = "BASE_START",
        priceAgeMs = 5_000L, expectedGrossPct = 30.0, costPct = 6.0, downsidePct = 15.0,
        expectedGrossBySetup = setups.associate { it.id to 30.0 }, setups = setups, cell = cell, timing = timing, genericScore = score, genericFloor = floor,
    )

    private fun setup(id: String, n: Int, mean: Double, se: Double, run: Double = 0.0) =
        Evidence8136(CandidateAdmission8136.KIND_SETUP, id, n, mean, se, run)

    @Test fun provenSpecificEdgeIsNotRefusedByALowGenericScore() {
        // the selected setup: 40 graded labels, +9% mean, SE 3% -> lower bound +6% net of cost; generic V3 score 12 < floor 30
        val d = CandidateAdmission8136.decide8136(base(setups = listOf(setup("PRE_IGNITION_BASE", 40, 9.0, 3.0))))
        assertEquals(Terminal8136.ADMIT, d.terminal)
        assertEquals("SPECIFIC_EDGE_PROVEN_8136", d.reason)
        assertTrue(d.specific)
        assertEquals("PRE_IGNITION_BASE", d.chosenSetup)
        assertEquals(24.0, d.expectedNetPct, 1e-9)
        // the generic floor helper is cleared by the record (no second, contradictory floor)
        assertEquals(true, CandidateAdmission8136.floorVerdict8136(d))
        assertTrue(d.trace.any { it.startsWith("edge=SETUP:PRE_IGNITION_BASE n40") })
    }

    @Test fun losingSetupWithAHighScoreAndRunnerLabelIsRefused() {
        // 40 labels, -8% mean, SE 2% (upper -6%), a 30% runner rate and a generic score of 95: still refused.
        val d = CandidateAdmission8136.decide8136(base(setups = listOf(setup("PRE_IGNITION_BASE", 40, -8.0, 2.0, run = 0.30)), score = 95.0))
        assertEquals(Terminal8136.REFUSE, d.terminal)
        assertEquals("SETUP_RECORD_LOSING_8136", d.reason)
        assertTrue(d.specific)
        // the record holds the floor: no lane-wide bypass may clear it
        assertEquals(false, CandidateAdmission8136.floorVerdict8136(d))
    }

    @Test fun thinPositiveEvidenceDoesNotBypassTheGenericScore() {
        // 12 labels at +20% mean: positive but thin -> never admitted on the score bypass
        val thin = base(cell = Evidence8136(CandidateAdmission8136.KIND_CELL, "PUMP|SHITCOIN|MC_10K", 12, 20.0, 4.0))
        val d = CandidateAdmission8136.decide8136(thin)
        assertEquals(Terminal8136.REFUSE, d.terminal)
        assertEquals("GENERIC_SCORE_FLOOR_8136", d.reason)
        assertFalse(d.specific)
        assertNull(CandidateAdmission8136.floorVerdict8136(d))   // the floor's own rules decide, the record does not clear it
        assertTrue(d.trace.any { it.startsWith("thinPositive=CELL") })
        // the same thin evidence with a generic score above the floor is admitted on the prior, not as specific edge
        val prior = CandidateAdmission8136.decide8136(thin.copy(genericScore = 41.0))
        assertEquals("GENERIC_PRIOR_CLEARS_8136", prior.reason)
        assertFalse(prior.specific)
        // and an unknown generic score with no supported edge is not an admission
        assertEquals("NO_SUPPORTED_EDGE_8136", CandidateAdmission8136.decide8136(thin.copy(genericScore = Double.NaN)).reason)
        assertFalse(CandidateAdmission8136.supported8136(thin.cell, 6.0))
        assertTrue(CandidateAdmission8136.thinPositive8136(thin.cell))
    }

    @Test fun sparseDataDefersAndTheDeferralIsBounded() {
        val sparse = base(selected = null).copy(missingInputs = listOf("shape:UNKNOWN", "chg:DEFAULT_UNOBSERVED"))
        val d = CandidateAdmission8136.decide8136(sparse)
        assertEquals(Terminal8136.DEFER_DATA, d.terminal)
        assertEquals("DATA_INCOMPLETE_DEFER_8136", d.reason)
        assertNull(CandidateAdmission8136.floorVerdict8136(d))
        assertTrue(d.trace.any { it.contains("missing=shape:UNKNOWN,chg:DEFAULT_UNOBSERVED") })
        // a stale price and unknown economics defer too; neither is a fabricated reading
        assertEquals(Terminal8136.DEFER_DATA, CandidateAdmission8136.decide8136(base().copy(priceAgeMs = 10L * 60_000L)).terminal)
        assertEquals(Terminal8136.DEFER_DATA, CandidateAdmission8136.decide8136(base().copy(priceAgeMs = Long.MAX_VALUE)).terminal)
        assertEquals(Terminal8136.DEFER_DATA, CandidateAdmission8136.decide8136(base().copy(costPct = Double.NaN)).terminal)
        // bounded re-evaluation: the window counts re-evaluations, closes after the budget or the window, and resets later
        var s = CandidateAdmission8136.nextDefer8136(null, 1_000L)
        assertTrue(CandidateAdmission8136.deferOpen8136(s, 1_000L))
        repeat(CandidateAdmission8136.DEFER_MAX_8136) { s = CandidateAdmission8136.nextDefer8136(s, 2_000L) }
        assertFalse(CandidateAdmission8136.deferOpen8136(s, 2_000L))                       // budget spent
        val young = CandidateAdmission8136.nextDefer8136(null, 0L)
        assertFalse(CandidateAdmission8136.deferOpen8136(young, CandidateAdmission8136.DEFER_WINDOW_MS_8136 + 1L))   // window spent
        val reset = CandidateAdmission8136.nextDefer8136(s, 1_000L + CandidateAdmission8136.DEFER_RESET_MS_8136)
        assertEquals(1, reset.count)                                                       // not permanent
        assertTrue(CandidateAdmission8136.deferOpen8136(reset, 1_000L + CandidateAdmission8136.DEFER_RESET_MS_8136))
        // the ledger files a data gap as DEFER_DATA, never as a verdict on the token
        assertEquals(Terminal8136.DEFER_DATA, CandidateAdmission8136.terminalOf8136("DEFER", "DATA_INCOMPLETE_DEFER_8136"))
        assertEquals(Terminal8136.DEFER_DATA, CandidateAdmission8136.terminalOf8136("WAIT", "PLAN_TOO_FEW_BARS"))
        assertEquals(Terminal8136.REFUSE, CandidateAdmission8136.terminalOf8136("DEFER", "LIVE_PROVEN_SETUP_ONLY_8059"))
        assertEquals(Terminal8136.ADMIT, CandidateAdmission8136.terminalOf8136("ADMIT", "ADMIT"))
    }

    @Test fun adverseTimingRefusesUnlessTheCoinsOwnCellPays() {
        val timing = Evidence8136(CandidateAdmission8136.KIND_TIMING, "PRE_IGNITION_BASE@PEAK", 15, -6.0, 2.0)
        val d = CandidateAdmission8136.decide8136(base(setups = listOf(setup("PRE_IGNITION_BASE", 40, 9.0, 3.0)), timing = timing))
        assertEquals(Terminal8136.REFUSE, d.terminal)
        assertEquals("TIMING_ADVERSE_8136", d.reason)
        // the token's own cell proven to pay has already paid for its timing
        val cell = Evidence8136(CandidateAdmission8136.KIND_CELL, "C", 50, 12.0, 4.0)
        val ok = CandidateAdmission8136.decide8136(base(setups = listOf(setup("PRE_IGNITION_BASE", 40, 9.0, 3.0)), timing = timing, cell = cell))
        assertEquals(Terminal8136.ADMIT, ok.terminal)
        // a timing record on too few labels is not a verdict
        assertFalse(CandidateAdmission8136.losing8136(timing.copy(n = 8)))
    }

    @Test fun hotLaunchDefersOnStaleOrMissingTapeAndAdmitsOnItsOwnProvenCell() {
        val cell = Evidence8136(CandidateAdmission8136.KIND_CELL, "PUMP|SHITCOIN|MC_LT10K|AGE_LT15M", 60, 18.0, 6.0, runnerRate = 0.2)
        val hot = base(selected = null, cell = cell, score = 9.0).copy(hotLaunch = true, priceAgeMs = 90_000L)
        // 90 s is fresh for an established coin but stale for a launch
        assertEquals(Terminal8136.DEFER_DATA, CandidateAdmission8136.decide8136(hot).terminal)
        assertFalse(CandidateAdmission8136.priceStale8136(90_000L, hotLaunch = false))
        // fresh: the launch's own proven cell admits it although the V3 meme score reads 9
        val fresh = CandidateAdmission8136.decide8136(hot.copy(priceAgeMs = 10_000L, missingInputs = listOf("shape:UNKNOWN")))
        assertEquals(Terminal8136.ADMIT, fresh.terminal)
        assertEquals("SPECIFIC_EDGE_PROVEN_8136", fresh.reason)
        // structural selling risk on the launch is refused whatever the cell says
        val structural = CandidateAdmission8136.decide8136(hot.copy(priceAgeMs = 10_000L, structuralRisk = "DEV_SELLING"))
        assertEquals("STRUCTURAL_RISK_8136", structural.reason)
        // a runner tail on 100+ labels supports a positive point estimate; on 60 labels it does not
        assertTrue(CandidateAdmission8136.supported8136(Evidence8136("CELL", "T", 120, 4.0, 9.0, runnerRate = 0.15), 6.0))
        assertFalse(CandidateAdmission8136.supported8136(Evidence8136("CELL", "T", 60, 4.0, 9.0, runnerRate = 0.15), 6.0))
    }

    @Test fun aSafeAlternateSetupCarriesTheCandidateOverALosingSelectedOne() {
        val losing = setup("LAUNCH_CONTINUATION", 25, -23.6, 5.0)
        val alt = setup("ACCELERATING_TAPE", 61, 23.1, 6.0, run = 0.18)
        val d = CandidateAdmission8136.decide8136(base(selected = "LAUNCH_CONTINUATION", setups = listOf(losing, alt)))
        assertEquals(Terminal8136.ADMIT, d.terminal)
        assertEquals("ACCELERATING_TAPE", d.chosenSetup)
        assertTrue(d.trace.any { it.startsWith("altSetup=SETUP:ACCELERATING_TAPE") })
        // without the alternate the losing selected setup refuses
        assertEquals("SETUP_RECORD_LOSING_8136",
            CandidateAdmission8136.decide8136(base(selected = "LAUNCH_CONTINUATION", setups = listOf(losing))).reason)
        // gross evidence (not net of cost) must clear the cost itself
        assertFalse(CandidateAdmission8136.supported8136(alt.copy(meanPct = 10.0, sePct = 5.0, netOfCost = false), 6.0))
        assertTrue(CandidateAdmission8136.supported8136(alt.copy(meanPct = 10.0, sePct = 2.0, netOfCost = false), 6.0))
    }

    @Test fun hostileMintIsAlwaysRefused() {
        val best = base(setups = listOf(setup("PRE_IGNITION_BASE", 200, 30.0, 2.0, run = 0.5)),
            cell = Evidence8136(CandidateAdmission8136.KIND_CELL, "C", 300, 25.0, 2.0), score = 99.0, floor = 0.0)
        for (hostile in listOf("HARD_BLOCK:TOKEN2022_PERMANENT_DELEGATE", "HARD_BLOCK:FREEZE_AUTHORITY", "HARD_BLOCK:HONEYPOT")) {
            val d = CandidateAdmission8136.decide8136(best.copy(safetyRefusal = hostile))
            assertEquals(Terminal8136.REFUSE, d.terminal)
            assertEquals("SAFETY_REFUSED_8136", d.reason)
            assertEquals(false, CandidateAdmission8136.floorVerdict8136(d))
        }
        // bad execution economics (expected move under 2x the round trip) is refused too
        val econ = CandidateAdmission8136.decide8136(best.copy(expectedGrossPct = 8.0, costPct = 6.0))
        assertEquals("EXECUTION_ECONOMICS_8136", econ.reason)
        // V5.0.8240 — a cell proven losing is a lane-wide prior: a SUPPORTED fired setup is the token-specific strategy and
        // carries it (8241 runner ledger: MONPAD +30584% / KimWhale +2962% MISSED on CELL_PROVEN_LOSING with a supported setup).
        val cellCarried = CandidateAdmission8136.decide8136(best.copy(cell = Evidence8136("CELL", "C", 80, -9.0, 2.0)))
        assertEquals(Terminal8136.ADMIT, cellCarried.terminal)
        assertTrue(cellCarried.trace.any { it.contains("carried by supported setup") })
        // without a supported setup the cell prior still refuses
        val cellLosing = CandidateAdmission8136.decide8136(best.copy(setups = emptyList(), cell = Evidence8136("CELL", "C", 80, -9.0, 2.0)))
        assertEquals("CELL_PROVEN_LOSING_8136", cellLosing.reason)
    }

    @Test fun theLedgerKeyIsBoundedAndTheRecordIsWiredIntoTheFloorTheLiveGateAndThePlan() {
        assertEquals("LIVE|REFUSE:PLAYBOOK_SETUP_PROVEN_LOSING_7907",
            CandidateAdmission8136.ledgerKey8136("LIVE", Terminal8136.REFUSE, "PLAYBOOK_SETUP_PROVEN_LOSING_7907_MOONSHOT_LAUNCH_CONTINUATION"))
        assertEquals("LIVE|REFUSE:EDGE_7877_CELL_EDGE_BELOW_MARGIN",
            CandidateAdmission8136.ledgerKey8136("LIVE", Terminal8136.REFUSE, "EDGE_7877_CELL_EDGE_BELOW_MARGIN_-2.1PCT_MOONSHOT"))
        assertEquals("PLAN|DEFER_DATA:PLAN_TOO_FEW_BARS",
            CandidateAdmission8136.ledgerKey8136("PLAN", Terminal8136.DEFER_DATA, "PLAN_TOO_FEW_BARS"))
        val fdg = src("engine/FinalDecisionGate.kt")
        val helper = fdg.substringAfter("private fun playbookClearsFloor7937(").substringBefore("private fun cortexPaperChoice7915(")
        assertTrue(helper.indexOf("floorFromAdmission8136(ts, lane, waitFloor, candidateVersion, candidateAdmission)?.let { return it }") in 0 until helper.indexOf("ladderProven7932(ts)"))
        assertTrue(fdg.contains("recordLaneAdmission7932(ts, lane, waitFloor.coerceIn(0.0, 100.0), false, candidateVersion)"))
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(gate.indexOf("dataDefer8136(ts, l8059, setup8083, nowMs)") in 1 until gate.indexOf("return \"LIVE_PROVEN_SETUP_ONLY_8059\""))
        assertTrue(gate.contains("CandidateAdmission8136.noteVerdict8136(\"LIVE\", verdict.name, reason)"))
        val plan = src("engine/truth/TradePlan7739.kt")
        assertTrue(plan.contains("if (LiveEdgeGate7877.chartAdmits7950(ts, lane, nowMs)) return chartPlanRefusal8136(ts, lane, paper, nowMs, candidateVersion, candidateAdmission)"))
        // superseded by V5.0.8317: the local-tape minimum follows the coin's age (LocalTapeBars8128.minMinutes8317).
        assertTrue(plan.contains("LocalTapeBars8128.fromPrints8128(TradeTape8100.prints8128(mint, nowMs, WINDOW_MS_7739), solUsd,"))
        assertTrue(plan.contains("com.lifecyclebot.engine.chart.LocalTapeBars8128.minMinutes8317(ageMin))"))
        assertTrue(src("engine/truth/CanonicalEntryFloor7266.kt").contains("CandidateAdmission8136.statusLine8136()"))
        assertTrue(CandidateAdmission8136.statusLine8136().startsWith("admission8136[ADMIT="))
    }
}
