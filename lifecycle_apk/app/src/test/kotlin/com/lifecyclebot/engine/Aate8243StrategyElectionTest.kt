package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import com.lifecyclebot.engine.truth.StrategyElection8243
import com.lifecyclebot.engine.truth.StrategyElection8243.Verdict8243
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8243 — the strategy with the proven forward edge owns the trade; conviction only breaks ties. */
class Aate8243StrategyElectionTest {
    private fun admit(lane: String, net: Double) = Verdict8243(lane, Terminal8136.ADMIT, true, net, "IGNITION_5M", "SPECIFIC_EDGE_PROVEN_8136")
    private fun refuse(lane: String) = Verdict8243(lane, Terminal8136.REFUSE, true, Double.NaN, null, "CELL_PROVEN_LOSING_8136")
    private fun defer(lane: String) = Verdict8243(lane, Terminal8136.DEFER_DATA, false, Double.NaN, null, "DATA_INCOMPLETE_DEFER_8136")
    private fun generic(lane: String) = Verdict8243(lane, Terminal8136.ADMIT, false, 2.0, null, "GENERIC_PRIOR_CLEARS_8136")

    @Test fun zfrogTheProvenStrategyOutranksTheConvictionWinner() {
        // 8241: MOONSHOT 80 (DATA_INCOMPLETE_DEFER) owned ZFROG +129,601%; SHITCOIN 37 held ADMIT:SPECIFIC_EDGE_PROVEN.
        val scores = mapOf("MOONSHOT" to 80.0, "SHITCOIN" to 37.0)
        val verdicts = mapOf("MOONSHOT" to defer("MOONSHOT"), "SHITCOIN" to admit("SHITCOIN", 12.3))
        assertEquals("SHITCOIN", StrategyElection8243.ownerOrder8243(scores, verdicts))
        assertTrue(StrategyElection8243.electedOverConviction8243("SHITCOIN", "MOONSHOT"))
    }

    @Test fun twoProvenStrategiesTheBetterNetOfCostOwnsThenConviction() {
        val scores = mapOf("MOONSHOT" to 80.0, "SHITCOIN" to 37.0, "EXPRESS" to 60.0)
        assertEquals("SHITCOIN", StrategyElection8243.ownerOrder8243(scores,
            mapOf("MOONSHOT" to admit("MOONSHOT", 4.0), "SHITCOIN" to admit("SHITCOIN", 12.3), "EXPRESS" to defer("EXPRESS"))))
        assertEquals("MOONSHOT", StrategyElection8243.ownerOrder8243(scores,
            mapOf("MOONSHOT" to admit("MOONSHOT", 9.0), "SHITCOIN" to admit("SHITCOIN", 9.0))))
    }

    @Test fun aLaneThatWouldRefuseAtTheFdgDoesNotOwnTheVersionWhileAnotherIsUnjudged() {
        val scores = mapOf("CASHGEN" to 90.0, "QUALITY" to 40.0)
        assertEquals("QUALITY", StrategyElection8243.ownerOrder8243(scores, mapOf("CASHGEN" to refuse("CASHGEN"), "QUALITY" to null)))
        // nothing else qualified: the refusing lane still owns (its FDG refusal is the record)
        assertEquals("CASHGEN", StrategyElection8243.ownerOrder8243(mapOf("CASHGEN" to 90.0), mapOf("CASHGEN" to refuse("CASHGEN"))))
    }

    @Test fun noLaneJudgedConvictionDecidesAndAGenericAdmitIsNotAProvenStrategy() {
        val scores = mapOf("MOONSHOT" to 80.0, "SHITCOIN" to 37.0)
        assertEquals("MOONSHOT", StrategyElection8243.ownerOrder8243(scores, emptyMap()))
        assertEquals("MOONSHOT", StrategyElection8243.ownerOrder8243(scores, mapOf("SHITCOIN" to generic("SHITCOIN"))))
        assertEquals("SHITCOIN", StrategyElection8243.ownerOrder8243(mapOf("moonshot" to 80.0, "shitcoin" to 37.0),
            mapOf("SHITCOIN" to admit("SHITCOIN", 5.0)))?.uppercase())
        assertNull(StrategyElection8243.ownerOrder8243(emptyMap(), emptyMap()))
        assertEquals(0, StrategyElection8243.tier8243(admit("SHITCOIN", 1.0)))
        assertEquals(1, StrategyElection8243.tier8243(null))
        assertEquals(1, StrategyElection8243.tier8243(generic("SHITCOIN")))
        assertEquals(2, StrategyElection8243.tier8243(refuse("CASHGEN")))
        assertTrue(!StrategyElection8243.electedOverConviction8243("MOONSHOT", "MOONSHOT"))
    }

    @Test fun theBestStrategyIsTheProvenAdmitWithTheHighestNet() {
        assertEquals("SHITCOIN", StrategyElection8243.bestStrategy8243(listOf(admit("MOONSHOT", 4.0), admit("SHITCOIN", 12.3), refuse("CASHGEN"), null))?.lane)
        assertNull(StrategyElection8243.bestStrategy8243(listOf(defer("MOONSHOT"), generic("SHITCOIN"), refuse("CASHGEN"))))
    }

    @Test fun theElectionTheSpikeRuleAndTheLaunchPriorCarryTheContract() {
        val coord = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LaneExecutionCoordinator.kt").readText()
        assertTrue(coord.contains("StrategyElection8243.ownerOrder8243(scores, verdicts) ?: convictionWinner"))
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt").readText()
        // Superseded by V5.0.8290/8298: a specific ADMIT stands the spike-chase rule down only on the lane's earned realised
        // record (RegressionRestore8298); otherwise the chase names the admitted terminal.
        assertTrue(gate.contains("val admittedJudged8201 = candidateSpecificAdmission8136 && RegressionRestore8298.specificStandDownEarned8298(l8059, nowMs)"))
        assertTrue(gate.contains("admittedTerminal8201(ts, lane, \"CHASING_SPIKE_8065\", candidateVersion)"))
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(fdg.contains("raw == \"FRESH_LAUNCH_SETUP_STOPS_FIRST_7737\" && admitted8243"))
        assertTrue(fdg.contains("freshLaunchBlock7737(ts, candidate, specialistLane, laneName, config.paperMode, mode, candidateAdmission8136)"))
    }

    // V5.0.8244 — a council, not a veto chain. V5.0.8279 §SELECT_LIKE_8114 — each brain's refusal binds again on trunk.
    @Test fun oneDissentIsAdvisoryOnlyAUnanimousCouncilRefusesAndTheCellPriorIsNotReadTwice() {
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(fdg.contains("val dissent = listOfNotNull(manual, plan, council)"))
        assertTrue(fdg.contains("return dissent.first()"))
        assertFalse(fdg.contains("FDG_COUNCIL_DISSENT_ADVISORY_8244"))
        assertTrue(fdg.contains("FDG_COUNCIL_DISSENT_REFUSES_8279"))
        assertTrue(fdg.contains("cellProofBlock7731(ts, candidate, specialistLane, laneName, config.paperMode, mode, candidateAdmission8136)"))
        assertTrue(fdg.contains("CELL_PROOF_STOOD_DOWN_FOR_ADMITTED_STRATEGY_8244_"))
        // a 429 is a per-minute quota: one minute first, doubling, half an hour at most
        assertEquals(60_000L, com.lifecyclebot.engine.chart.ChartSources7955.backoffMs7955(429, 0, false))
        assertEquals(240_000L, com.lifecyclebot.engine.chart.ChartSources7955.backoffMs7955(429, 2, false))
    }

    // V5.0.8245 — the runner capture is admission evidence, not a late bypass.
    private fun grabCandidate(grab: com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136?, cell: com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136? = null, missing: List<String> = listOf("shape:UNKNOWN", "tape:UNKNOWN")) =
        com.lifecyclebot.engine.truth.CandidateAdmission8136.Candidate8136(
            mint = "m8245", lane = "MOONSHOT", selectedSetup = null, stage = "FRESH_LAUNCH", priceAgeMs = 2_000L,
            expectedGrossPct = Double.NaN, costPct = 8.0, setups = emptyList(), cell = cell, missingInputs = missing, runnerGrab8245 = grab,
        )

    @Test fun aFormingRunnerInAProvenCellIsJudgedOnItNotDeferredOnAnOptionalInput() {
        val A = com.lifecyclebot.engine.truth.CandidateAdmission8136
        val grab = com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136(A.KIND_RUNNER_GRAB_8245, "PUMP_PORTAL_WS|MOONSHOT|MC_10K_100K|AGE_LT15M", 25, 45.0, 10.0)
        assertTrue(A.grabSupported8245(grab))
        assertTrue(!A.grabSupported8245(com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136(A.KIND_RUNNER_GRAB_8245, "x", 19, 45.0, 10.0)))
        assertTrue(!A.grabSupported8245(com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136(A.KIND_RUNNER_GRAB_8245, "x", 40, 25.0, 10.0)))
        // no setup fired, chart shape unobserved: without the grab this is DATA_INCOMPLETE_DEFER (ZFROG); with it, ADMIT on the grab
        assertEquals("DATA_INCOMPLETE_DEFER_8136", A.decide8136(grabCandidate(null)).reason)
        val d = A.decide8136(grabCandidate(grab))
        assertEquals("SPECIFIC_EDGE_PROVEN_8136", d.reason)
        assertTrue(d.trace.any { it.contains("RUNNER_GRAB:") })
        // the coin's own losing cell is never carried by the grab; a critical missing input still defers
        val losingCell = com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136(A.KIND_CELL, "PUMP_PORTAL_WS|MOONSHOT|MC_LT10K", 40, -9.0, 2.0)
        assertTrue(A.decide8136(grabCandidate(grab, losingCell)).reason != "SPECIFIC_EDGE_PROVEN_8136")
        assertEquals("DATA_INCOMPLETE_DEFER_8136", A.decide8136(grabCandidate(grab, missing = listOf("liquidity:UNKNOWN"))).reason)
    }
}
