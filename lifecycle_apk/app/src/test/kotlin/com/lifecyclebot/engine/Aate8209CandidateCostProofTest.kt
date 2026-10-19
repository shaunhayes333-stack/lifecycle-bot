package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.NetEdge8030
import org.junit.Assert.*
import org.junit.Test

/**
 * 5.0.8209 — pre-admission cost is quoted at THIS candidate's proposed size (not the last sealed ticket of any lane), the quote
 * record (cost, size, breakdown) is immutable on the decision, and a materially different sealed size is re-quoted at seal.
 */
class Aate8209CandidateCostProofTest {
    @Test fun costIsQuotedAtTheProposedSizeAndRecorded() {
        val ts = TokenState(mint = "Cost8209" + System.nanoTime(), symbol = "C"); ts.lastLiquidityUsd = 25_000.0; ts.lastMcap = 90_000.0
        val small = NetEdge8030.entryCost8209(ts, "MOONSHOT", 0.02)
        val big = NetEdge8030.entryCost8209(ts, "MOONSHOT", 0.40)
        assertEquals(0.02, small.sizeSol, 1e-12); assertEquals(0.40, big.sizeSol, 1e-12)
        assertTrue(small.costPct.isFinite() && big.costPct.isFinite())
        assertNotEquals("the quote depends on the size it prices (fixed fees weigh on small tickets, impact on large ones)", small.costPct, big.costPct, 1e-9)
        assertTrue(small.breakdown.isNotBlank())
        val none = NetEdge8030.entryCost8209(ts, "MOONSHOT", Double.NaN)
        assertTrue("no proposed size: the fallback ticket is named, not hidden", none.sizeSol > 0.0)
    }

    @Test fun decisionCarriesTheQuoteItWasDecidedOn() {
        val ts = TokenState(mint = "Cost8209b" + System.nanoTime(), symbol = "C"); ts.lastLiquidityUsd = 25_000.0; ts.lastMcap = 90_000.0
        val c = CandidateAdmission8136.candidateFor8136(ts, "MOONSHOT", System.currentTimeMillis(), proposedSizeSol = 0.1234)
        assertEquals(0.1234, c.costSizeSol, 1e-12)
        assertTrue(c.costBreakdown.isNotBlank())
        val d = CandidateAdmission8136.decide8136(c)
        assertEquals(c.costPct, d.costPct, 0.0); assertEquals(0.1234, d.costSizeSol, 1e-12); assertEquals(c.costBreakdown, d.costBreakdown)
        assertTrue(d.trace.any { it.contains("costSize=0.1234SOL") })
    }

    @Test fun sealedSizeFarFromQuotedSizeIsStale() {
        assertFalse(FinalDecisionGate.admissionCostStale8209(0.10, 0.10))
        assertFalse(FinalDecisionGate.admissionCostStale8209(0.10, 0.14))
        assertTrue(FinalDecisionGate.admissionCostStale8209(0.10, 0.16))
        assertTrue(FinalDecisionGate.admissionCostStale8209(0.10, 0.05))
        assertFalse("unknown quote size cannot be stale", FinalDecisionGate.admissionCostStale8209(Double.NaN, 0.2))
    }

    @Test fun requoteUsesTheAdmittedGrossAgainstTheSealedCost() {
        // net 1% on a 3% cost = 4% gross; at 3.2% sealed cost the entry must fail NetEdge8030's multiple
        assertFalse(FinalDecisionGate.requoteClears8209(1.0, 3.0, 3.2))
        assertTrue(FinalDecisionGate.requoteClears8209(40.0, 3.0, 3.2))
        assertTrue("nothing to re-prove without numbers", FinalDecisionGate.requoteClears8209(Double.NaN, 3.0, 3.2))
    }

    @Test fun fdgPassesTheProposedSizeAndRefusesANamedRequoteFailure() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(src.contains("proposedSizeSol = proposedSizeSol,   // V5.0.8209"))
        assertTrue(src.contains("policyAllows7848 && executableSize7835 > 0.0 && sealedCostOk8209"))
        // A false `sealedCostOk8209` is an actual refusal, not a dead write to
        // the pre-final blockReason variable that the return never reads.
        assertTrue(src.contains("if (!sealedCostOk8209) blockReasonFinal = \\"ADMISSION_COST_REQUOTE_FAILED_8209\\""))
        assertTrue(src.contains("blockReason = blockReasonFinal ?: when {"))
        val adm = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertFalse("the refusal breakdown is the candidate's own quote, never only a global last-write", adm.contains("\"breakdown=\${try { NetEdge8030.lastEntryBreakdown8204() }"))
    }
}
