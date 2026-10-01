package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7678CoreBluechipSourceContractTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun coreIsExecutableEnsembleNotGenericTrunk() {
        val brain = src("engine/SpecialistBrainBridge7542.kt")
        assertTrue(brain.contains("val ensemble=a!=null&&b!=null"))
        assertTrue(brain.contains("CORE_GENERALIST_NO_SPECIALIST_FIT"))
        assertTrue(brain.contains("CORE_YIELD_CLEAR_SPECIALIST_OWNER"))
        val p = src("engine/ExecutionAuthorityPolicy6533.kt")
        val trunk = p.substringAfter("private val trunk =").substringBefore("\n")
        assertFalse(trunk.contains("CORE"))
    }

    @Test fun corePrimarySpineRequiresSameAttemptMarkAndPositiveSize() {
        val b = src("engine/BotService.kt")
        val block = b.substringAfter("val postAuthIntent7467")
            .substringBefore("val specialistFdgAllowed6614")
        assertTrue(block.contains("authResult.isExecutable()"))
        assertTrue(block.contains("it.attemptId == authResult.attemptId"))
        assertTrue(block.contains("it.resolvedSize.isFinite() && it.resolvedSize > 0.0"))
        assertTrue(block.contains("it.executableMarkTimestampMs6613 > 0L"))
        assertTrue(b.contains("PRIMARY_SPINE_POST_AUTH_PROOF_INCOMPLETE_7467"))
    }

    @Test fun bluechipUsesSealedAttemptAndOnlyEmitsSizeTicketAfterAuthorization() {
        val b = src("engine/BotService.kt")
        val pre = b.substringAfter("val blueChipCandidateVersion7466")
            .substringBefore("val canExecute = blueChipAuth6494.isExecutable()")
        assertTrue(pre.contains("ExecutableOpenGate.activeExecutionIntent6519("))
        assertTrue(pre.contains("BLUECHIP_SEALED_INTENT_REUSED_FOR_AUTH_7466"))
        val auth = b.substringAfter("val blueChipAuth6494 = TradeAuthorizer.authorize(")
            .substringBefore("val canExecute = blueChipAuth6494.isExecutable()")
        val guard = auth.indexOf("if (blueChipAuth6494.isExecutable()")
        assertTrue(auth.indexOf("recordDeskStage(\"BLUECHIP\", \"SIZED_EXECUTABLE\"") > guard)
        assertTrue(auth.indexOf("recordDeskStage(\"BLUECHIP\", \"TICKET\"") > guard)
    }

    @Test fun oldSourceTasksAreClosedButRuntimeProofStaysOpen() {
        val a = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertFalse(a.contains("- [ ] Trace BLUECHIP mark -> canonical sizing bridge"))
        assertFalse(a.contains("- [ ] Preserve CORE as ensemble/coordinator"))
        assertTrue(a.contains("Runtime acceptance: fresh BLUECHIP mark-ready attempts"))
        assertTrue(a.contains("runtime proof pending"))
    }
}
