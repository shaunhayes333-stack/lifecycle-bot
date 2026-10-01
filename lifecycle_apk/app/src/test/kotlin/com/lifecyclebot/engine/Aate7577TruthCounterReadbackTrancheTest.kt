package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7577TruthCounterReadbackTrancheTest {
    private fun src(rel: String) =
        File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test
    fun `truth tranche consists of pure readbacks`() {
        assertTrue(src("engine/truth/CanonicalSettlement6389.kt").contains("fun auditCount(): Long ="))
        assertTrue(src("engine/truth/CanonicalSettlement6389.kt").contains("fun learningExcludedCount(): Long ="))
        assertTrue(src("engine/truth/SafetyHolds6387.kt").contains("fun cleanCycleCount(): Int ="))
        assertTrue(src("engine/truth/CanonicalOutcomeClassifier6576.kt").contains("fun divergenceCount(): Long ="))
        assertTrue(src("engine/truth/CapitalConservationTracer6469.kt").contains("fun violationCount(): Long ="))
        assertTrue(src("engine/truth/CounterParityLedger6399.kt").contains("fun fdgCount("))
        assertTrue(src("engine/truth/ExecutionIntent6386.kt").contains("fun outstandingCount(): Int ="))
        assertTrue(src("engine/truth/SellOnlyHoldRepair6391.kt").contains("fun openMintCount(): Int ="))
        assertTrue(src("engine/truth/ReconciliationCoordinator6387.kt").contains("fun activeJobsCount(): Int ="))
        assertTrue(src("engine/truth/RootCauseFreshnessAuthority6496.kt").contains("fun lifetimeCount("))
        assertTrue(src("engine/truth/SameMintCandidateEpoch6402.kt").contains("fun trackedMintCount(): Int ="))
        assertTrue(src("engine/truth/SpecialistContributorMerge6612.kt").contains("fun mergeCount(): Long ="))
    }

    @Test
    fun `audit updates wider remaining count`() {
        val audit = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(audit.contains("V5.0.7577 — F_DEAD engine/truth counter-readback tranche (12 rows)"))
        assertTrue(audit.contains("74 / 1,458"))
        assertTrue(audit.contains("1,384 remain"))
    }
}
