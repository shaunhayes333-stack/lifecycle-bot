package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AdmissionEdge8180
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Candidate8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import org.junit.Assert.*
import org.junit.Test

class Aate8521SetupAdmissionContinuityTest {
    private val lanes = listOf("BLUECHIP", "CASHGEN", "QUALITY", "CYCLIC", "SHITCOIN", "MOONSHOT",
        "TREASURY", "CORE", "EXPRESS", "DIP_HUNTER", "PROJECT_SNIPER", "MANIPULATED")
    private val supported = Evidence8136(CandidateAdmission8136.KIND_SETUP, "OBSERVED_SETUP", 60, 9.0, 1.0)
    private fun candidate(lane: String, wait: String) = Candidate8136(
        "candidate8521-$lane", lane, supported.id, live = true, candidateVersion = 8521L,
        entryWait8506 = wait, stage = "UNKNOWN", priceAgeMs = 10L,
        expectedGrossPct = 20.0, costPct = 4.5, setups = listOf(supported),
        genericScore = 80.0, genericFloor = 15.0,
    )

    @Test fun everyLaneCanEvaluateItsObservedSupportedSetupBeforeAnOptionalContextDeferral() {
        for (lane in lanes) for (wait in listOf("FLOW_UNOBSERVED", "STAGE_UNRESOLVED")) {
            val d = CandidateAdmission8136.decide8136(candidate(lane, wait))
            assertEquals("$lane $wait", Terminal8136.ADMIT, d.terminal)
            assertEquals(supported.id, d.chosenSetup)
            assertTrue(d.trace.any { it.contains("supportedSetup8521=${supported.id}") })
        }
    }

    @Test fun missingRequiredSetupInputsLeaveNoFiredEvidenceAndStillDefer() {
        for (lane in lanes) {
            val c = candidate(lane, "FLOW_UNOBSERVED")
            for (records in listOf(emptyList(), listOf(supported.copy(n = 3)), listOf(supported.copy(suspect8290 = true)))) {
                val d = CandidateAdmission8136.decide8136(c.copy(setups = records))
                assertEquals(lane, Terminal8136.DEFER_DATA, d.terminal)
                assertTrue(CandidateAdmission8136.missingOf8144(d).contains("swaps"))
            }
        }
    }

    @Test fun supportedAlternativeStillNeedsItsOwnEconomicsAndCriticalExecutionFacts() {
        val bad = supported.copy(id = "LOSING_DEFAULT", meanPct = -9.0)
        for (lane in lanes) {
            val c = candidate(lane, "FLOW_UNOBSERVED").copy(selectedSetup = bad.id,
                setups = listOf(bad, supported), expectedGrossBySetup = mapOf(supported.id to 20.0))
            val d = CandidateAdmission8136.decide8136(c)
            assertEquals(lane, Terminal8136.ADMIT, d.terminal)
            assertEquals(supported.id, d.chosenSetup)
            assertEquals(Terminal8136.DEFER_DATA, CandidateAdmission8136.decide8136(c.copy(expectedGrossBySetup = emptyMap())).terminal)
            assertEquals("EXECUTION_ECONOMICS_8136", CandidateAdmission8136.decide8136(c.copy(expectedGrossBySetup = mapOf(supported.id to 1.0))).reason)
            for (gap in listOf("quote:UNKNOWN", "price:UNKNOWN", "liquidity:UNKNOWN", "sellability:UNKNOWN", "decimals:UNKNOWN"))
                assertEquals("$lane $gap", Terminal8136.DEFER_DATA, CandidateAdmission8136.decide8136(c.copy(missingInputs = listOf(gap))).terminal)
            assertEquals("SAFETY_REFUSED_8136", CandidateAdmission8136.decide8136(c.copy(safetyRefusal = "MINT_AUTH")).reason)
            assertEquals("STRUCTURAL_RISK_8136", CandidateAdmission8136.decide8136(c.copy(structuralRisk = "RUG_PRONE")).reason)
            assertEquals(Terminal8136.DEFER_DATA, CandidateAdmission8136.decide8136(c.copy(priceAgeMs = Long.MAX_VALUE)).terminal)
        }
    }

    @Test fun admittedOptionalWaitSurvivesBothAuthorizationReadersOnlyForItsExactFrozenIdentity() {
        for (lane in lanes) for (wait in listOf("FLOW_UNOBSERVED", "STAGE_UNRESOLVED")) {
            val c = candidate(lane, wait)
            val d = CandidateAdmission8136.decide8136(c)
            AdmissionEdge8180.note8180("LIVE", c.mint, lane, c.candidateVersion, d, 10_000L)
            val proof = AdmissionEdge8180.proof8198("LIVE", c.mint, lane, c.candidateVersion, 10_100L)!!
            fun remaining(mint: String = c.mint, requestedLane: String = lane, version: Long = c.candidateVersion,
                          stage: String = c.stage, observedWait: String = wait, atMs: Long = 10_100L) =
                AdmissionEdge8180.remainingLiveWait(mint, requestedLane, version, stage, observedWait, atMs, proof)
            assertNull(remaining())
            assertNull(AdmissionEdge8180.remainingLiveWait(c.mint, lane, c.candidateVersion, c.stage, wait, 10_100L))
            assertEquals(wait, remaining(mint = "differentMint"))
            assertEquals(wait, remaining(requestedLane = "UNRELATED_LANE"))
            assertEquals(wait, remaining(version = c.candidateVersion + 1))
            assertEquals(wait, remaining(stage = "DUMPING"))
            assertEquals("RUG_PRONE", remaining(observedWait = "RUG_PRONE"))
            assertEquals(wait, remaining(atMs = 10_001L + AdmissionEdge8180.TTL_MS_8180))
        }
    }
}
