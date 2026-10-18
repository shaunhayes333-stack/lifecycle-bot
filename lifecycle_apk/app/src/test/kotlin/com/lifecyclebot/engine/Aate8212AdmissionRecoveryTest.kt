package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.NetEdge8030
import org.junit.Assert.*
import org.junit.Test

/** 8212: behavioural coverage of the exact three dominant device choke wiring defects. */
class Aate8212AdmissionRecoveryTest {
    @Test fun unknownStageIsNotNegativeProofButKnownMeasuredLosingStageStillRefuses() {
        val loss = CandidateAdmission8136.Evidence8136("STAGE", "UNKNOWN", 40, -20.0, 1.0)
        val base = CandidateAdmission8136.Candidate8136(
            mint="stage8212",lane="SHITCOIN",selectedSetup="IGNITION_5M",
            stage="UNKNOWN",priceAgeMs=500L,expectedGrossPct=25.0,costPct=5.0,
            genericScore=80.0,genericFloor=15.0,
            lossEpochAware8169=true,stageLoss8169=loss)
        val unknown = CandidateAdmission8136.decide8136(base)
        assertEquals(CandidateAdmission8136.Terminal8136.ADMIT,unknown.terminal)
        assertEquals("GENERIC_PRIOR_CLEARS_8136",unknown.reason)
        val known = CandidateAdmission8136.decide8136(base.copy(stage="FRESH_LAUNCH",
            stageLoss8169=loss.copy(id="FRESH_LAUNCH")))
        assertEquals(CandidateAdmission8136.Terminal8136.REFUSE,known.terminal)
        assertEquals("STAGE_PROVEN_LOSING_8136",known.reason)
        assertNull(ForwardReturnLabeler7731.stageStatFor7928("SHITCOIN","UNKNOWN"))
        assertNull(ForwardReturnLabeler7731.freshStageStatFor8169("SHITCOIN","UNKNOWN"))
    }

    @Test fun quoteUsesAffordableRoutableFloorButNeverPromisesAnUnaffordableSize() {
        val normalized = NetEdge8030.admissionQuoteSize8212(0.0100, 0.3690, 104.03)
        assertTrue("subroute quote must be costed on a actually routable size", normalized > 0.027)
        assertTrue(normalized < 0.04)
        assertEquals(0.05, NetEdge8030.admissionQuoteSize8212(0.05, 0.3690, 104.03), 0.0)
        assertEquals(0.01, NetEdge8030.admissionQuoteSize8212(0.01, 0.006, 104.03), 0.0)
        assertEquals(0.01, NetEdge8030.admissionQuoteSize8212(0.01, 0.369, Double.NaN), 0.0)
    }

    @Test fun sniperRecognisesItsOwnNonfirstHunterClaimAndOnlyExactHuntSources() {
        val s = SniperScope8190
        assertTrue(s.ownHunterEvidence8212(linkedSetOf("SHITCOIN", "MOONSHOT","PROJECT_SNIPER"), ""))
        assertTrue(s.ownHunterEvidence8212(emptySet(), "PUMP_PORTAL_WS,SCANNER_DIRECT_MARKET_HUNT_PROJECT_SNIPE"))
        assertFalse(s.ownHunterEvidence8212(linkedSetOf("SHITCOIN","MOONSHOT"),"PUMP_FUN_NEW"))
        assertFalse(s.directSniperSource8212("MARKET_HUNT_OTHER,SCOUT_PROJECT_SNIPE_FAKE"))
        assertFalse(s.ownHunterPool8190(false,true))
        assertTrue(s.ownHunterPool8190(true,true))
    }
}
