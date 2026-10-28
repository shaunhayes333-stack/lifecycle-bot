package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Candidate8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import com.lifecyclebot.engine.truth.NetEdge8030
import org.junit.Assert.*
import org.junit.Test

class Aate8417StrategyEconomicsContinuityTest {
    private fun admission(lane: String, gross: Double = 18.0) = CandidateAdmission8136.decide8136(
        Candidate8136(mint = "continuity8417", lane = lane, selectedSetup = "RANGE_LOW_BOUNCE",
            expectedGrossPct = gross, costPct = 4.0, costSizeSol = 0.08,
            expectedGrossBySetup = mapOf("RANGE_LOW_BOUNCE" to gross),
            setups = listOf(Evidence8136("SETUP", "RANGE_LOW_BOUNCE", 60, 14.0, 2.0)),
            genericScore = 70.0, genericFloor = 40.0,
        ))

    @Test fun bothScalpDesksRetainTheirOwnSupportedStrategyAndQuotedSize() {
        for (lane in listOf("CASHGEN", "TREASURY")) {
            val decision = admission(lane)
            assertTrue(decision.compact, decision.admitted)
            assertTrue(decision.compact, decision.basis.startsWith("SETUP:RANGE_LOW_BOUNCE"))
            assertTrue("history alone cannot qualify", NetEdge8030.scalpMoveTooSmall8054(2.0, 10.0))
            assertFalse("own setup predicts 18 at its quoted cost 4", NetEdge8030.scalpMoveTooSmall8054(2.0, 10.0, decision))
        }
    }

    @Test fun supportedSetupStillMustClearTheScalpCostMultiple() {
        val decision = admission("CASHGEN", gross = 9.0)
        assertTrue(decision.compact, decision.admitted)
        assertTrue(NetEdge8030.scalpMoveTooSmall8054(2.0, 4.0, decision))
    }

    @Test fun genericOtherSetupMissingQuoteAndRefusedEvidenceCannotSupplyTheMove() {
        val good = admission("TREASURY")
        for (bad in listOf(
            good.copy(specific = false), good.copy(basis = "CELL:profitable n60 +14.0±2.0"),
            good.copy(chosenSetup = "OTHER"), good.copy(costSizeSol = Double.NaN),
            good.copy(terminal = CandidateAdmission8136.Terminal8136.REFUSE),
        )) assertTrue(bad.compact, NetEdge8030.scalpMoveTooSmall8054(2.0, 10.0, bad))
    }
}
