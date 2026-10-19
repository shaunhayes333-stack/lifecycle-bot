package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.NetEdge8030
import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import org.junit.Assert.*
import org.junit.Test

/** 8211: the admission is measured and terminal BEFORE it clears a redundant proven-only prior. */
class Aate8211AdmissionDataRepairTest {
    private fun decision(reason: String, net: Double, cost: Double = 4.0, setup: String? = "IGNITION_5M",
                         specific: Boolean = false, terminal: CandidateAdmission8136.Terminal8136 = CandidateAdmission8136.Terminal8136.ADMIT) =
        CandidateAdmission8136.Decision8136(terminal, reason, specific, setup, net, emptyList(),
            basis = "GENERIC_PRIOR:IGNITION_5M", costPct = cost, costSizeSol = 0.04)

    @Test fun economicGenericNativeAdmissionClearsOnlyTheRedundantPrior() {
        assertTrue(LiveEdgeGate7877.genericNativeClearsProvenOnly8211(decision("GENERIC_PRIOR_CLEARS_8136", 8.0)))
        assertFalse(LiveEdgeGate7877.genericNativeClearsProvenOnly8211(decision("GENERIC_PRIOR_CLEARS_8136", 0.1)))
        assertFalse(LiveEdgeGate7877.genericNativeClearsProvenOnly8211(decision("GENERIC_PRIOR_CLEARS_8136", 8.0, setup = null)))
        assertFalse(LiveEdgeGate7877.genericNativeClearsProvenOnly8211(decision("GENERIC_PRIOR_CLEARS_8136", Double.NaN)))
        assertFalse(LiveEdgeGate7877.genericNativeClearsProvenOnly8211(decision("GENERIC_PRIOR_CLEARS_8136", 8.0, cost = Double.NaN)))
        assertFalse(LiveEdgeGate7877.genericNativeClearsProvenOnly8211(decision("SPECIFIC_EDGE_PROVEN_8136", 8.0, specific = true)))
        assertFalse(LiveEdgeGate7877.genericNativeClearsProvenOnly8211(decision("GENERIC_PRIOR_CLEARS_8136", 8.0,
            terminal = CandidateAdmission8136.Terminal8136.REFUSE)))
        assertFalse(LiveEdgeGate7877.genericNativeClearsProvenOnly8211(null))
    }

    @Test fun mappingRoutesMissingCoreAndEconomicEvidenceToRealFetches() {
        val input = DataDeferFetch8180.inputsFor8180(listOf("core:UNKNOWN", "economics", "price", "tape:NO_PRINTS"))
        assertTrue(input.contains("shape:HISTORY_SPARSE"))
        assertTrue(input.any { it.startsWith("chg:") })
        assertTrue(input.any { it.startsWith("liq:") })
        assertTrue(DataDeferFetch8180.streamInputNeeded8211(input))
        assertFalse(DataDeferFetch8180.streamInputNeeded8211(listOf("liq:REFRESH")))
        assertFalse(DataDeferFetch8180.streamInputNeeded8211(emptyList()))
    }
    @Test fun executableProofGapsQueueRealAuthorityReadersRatherThanBecomingEconomicRefusals() {
        val f = DataDeferFetch8180
        assertTrue(f.missingExecutableProof8236(listOf("route:UNKNOWN")))
        assertTrue(f.missingExecutableProof8236(listOf("sellability:UNKNOWN")))
        assertTrue(f.missingExecutableProof8236(listOf("decimals:UNKNOWN")))
        assertTrue(f.missingExecutableProof8236(listOf("token_map:UNKNOWN")))
        assertTrue(f.missingExecutableProof8236(listOf("quote:UNKNOWN")))
        assertFalse(f.missingExecutableProof8236(listOf("shape:UNKNOWN", "bp:NO_PRINTS")))
        val source = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RunnerCapture8180.kt").readText()
        assertTrue(source.contains("AuthorityProofPrefetch8136.prefetchIfLive8136(ts.mint)"))
        assertTrue(source.contains("OnChainDepth8038.needDepth8047(ts, nowMs)"))
    }

}
