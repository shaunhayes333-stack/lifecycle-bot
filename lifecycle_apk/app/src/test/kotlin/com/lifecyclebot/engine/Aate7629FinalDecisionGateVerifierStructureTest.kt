package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7629FinalDecisionGateVerifierStructureTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()

    @Test fun `fanout branch is extracted from giant evaluate method`() {
        val s = src()
        assertTrue(s.contains("private fun fanoutCapVerdict7629("))
        val eval = s.substringAfter("fun evaluate(")
        val preChecks = eval.substringBefore("val checks = mutableListOf<GateCheck>()")
        assertTrue(preChecks.contains("fanoutCapVerdict7629("))
        assertTrue(preChecks.contains("candidateVersion = candidateVersion7623"))
        assertFalse(preChecks.contains("val causalRoot7232"))
        assertFalse(preChecks.contains("val fanoutLane7265"))
        assertFalse(preChecks.contains("IntakeFanoutGovernor6835.allowFdgEval("))
    }

    @Test fun `extracted helper preserves fanout semantics`() {
        val s = src()
        val helper = s.substringAfter("private fun fanoutCapVerdict7629(").substringBefore("fun invalidateCandidate6734")
        assertTrue(helper.contains("IntakeFanoutGovernor6835.allowFdgEval("))
        assertTrue(helper.contains("FDG_SUPPRESSED_FANOUT_CAP_7232"))
        assertTrue(helper.contains("cap 2 FDG evals per (mint,causalRoot); this is beyond cap"))
        assertTrue(helper.contains("mode = if (config.paperMode) TradeMode.PAPER else TradeMode.LIVE"))
    }
}
