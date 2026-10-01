package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7679FinalizedAttributionSourceContractTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun finalizedRepairRequiresDurableProofAndNeverInventsHistory() {
        val r = src("engine/truth/FinalizedLearningReconciler7423.kt")
        assertTrue(r.contains("repairable7544 -> Reason.BUS_PUBLISH_FAILED"))
        assertTrue(r.contains("terminal7544 != null -> Reason.DURABLE_TERMINAL_UNREPAIRABLE"))
        assertTrue(r.contains("p.positionId.isNotBlank() -> Reason.HISTORICAL_NO_DURABLE_FINALITY"))
        assertTrue(r.contains("filter { it.reason == Reason.BUS_PUBLISH_FAILED }"))
        assertTrue(r.contains("DURABLE_TERMINAL_NO_ENTRY_SNAPSHOT_7521"))
    }

    @Test fun duplicateTerminalCanOnlyRedriveMissingCanonicalProjection() {
        val s = src("engine/truth/CanonicalTradeFinalizedBus6450.kt")
        assertTrue(s.contains("val richDuplicate7473 = prior != null"))
        assertTrue(s.contains("CANONICAL_FINALITY_DUPLICATE_REDRIVE_7473"))
        assertTrue(s.contains("if (economicInvalid6495 == null && !richDuplicate7473)"))
        assertTrue(s.contains("CanonicalFinalizedTradeBus6464.publish(env)"))
    }

    @Test fun exactStrategyIdentityBindsAtOpenAndInvalidTacticDoesNotFallback() {
        val a = src("engine/truth/AateDecisionEnvelope6512.kt")
        val bind = a.substringAfter("fun attachPosition(").substringBefore("fun onFinalized(")
        assertTrue(bind.contains("StrategyHypothesisEngine.bindExecutedPosition7428("))
        assertTrue(bind.contains("candidateVersion = e.context.candidateVersion"))
        assertTrue(bind.contains("lane = e.context.primaryStrategy"))
        val t = src("engine/learning/TacticSwitcher.kt")
        val invalid = t.substringAfter("TACTIC_ENTRY_ATTRIBUTION_INVALID_6568")
        assertTrue(invalid.contains("TACTIC_INVALID_ENTRY_FORENSIC_ONLY_7456"))
        assertTrue(invalid.contains("return"))
    }

    @Test fun sourceItemsClosedWhileFreshRuntimeMissAcceptanceStaysOpen() {
        val a = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertFalse(a.contains("- [ ] Repair only provable durable finalized-bus publication gaps"))
        assertFalse(a.contains("- [ ] Exact entry identity must bind at open"))
        assertTrue(a.contains("Runtime acceptance: reduce hypothesis/variant bind/outcome misses"))
        assertTrue(a.contains("Runtime acceptance: fresh opens materially increase"))
    }
}
