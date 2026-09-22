package com.lifecyclebot.engine.truth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7243EconomicAuthorityConvergenceTest {
    @Test
    fun mark_suppression_is_stateful_and_repair_releases_it() {
        MarkIdentityExecutionGate7230.clearForTest()
        val mint = "Mint7243State1111111111111111111111111"
        val v = MarkIdentityExecutionGate7230.evaluate(
            mint = mint,
            poolOrVenueKey = "DEXSCREENER_PAIR",
            markIdentityBroken = true,
            corroboratedByIndependentSource = false,
            exitReasonOrContext = "test",
        )
        assertTrue(v.verdict == MarkIdentityExecutionGate7230.Verdict.SUPPRESSED)
        assertTrue(MarkIdentityExecutionGate7230.isExecutionSuppressed7243(mint))
        assertTrue(MarkIdentityExecutionGate7230.markRepairedUsable7243(mint))
        assertFalse(MarkIdentityExecutionGate7230.isExecutionSuppressed7243(mint))
    }

    @Test
    fun source_wiring_converges_economic_entry_learning_and_fanout_authority() {
        val root = java.io.File("src/main/kotlin/com/lifecyclebot/engine")
        val partial = java.io.File(root, "truth/CanonicalPaperPartialOperation6510.kt").readText()
        val repair = java.io.File(root, "truth/MarkIdentityRepairAuthority7236.kt").readText()
        val pnl = java.io.File(root, "OpenPnlSanity.kt").readText()
        val learning = java.io.File(root, "CanonicalLearning.kt").readText()
        val fdg = java.io.File(root, "FinalDecisionGate.kt").readText()
        val router = java.io.File(root, "AgenticStyleRouter.kt").readText()

        assertTrue(partial.contains("PAPER_PARTIAL_BLOCKED_UNTRUSTED_MARK_7243"))
        assertTrue(repair.contains("markRepairedUsable7243"))
        assertTrue(pnl.contains("MARK_IDENTITY_SUPPRESSED_7243"))
        assertTrue(pnl.contains("suppressMint7243"))
        assertTrue(learning.split("CANONICAL_OUTCOME_MARK_SUPPRESSED_7243").size - 1 >= 2)
        assertTrue(fdg.contains("ts.lastV3Score?.toDouble() ?: candidate.entryScore"))
        assertFalse(fdg.contains("maxOf(candidate.entryScore, laneScore)"))
        assertTrue(router.contains("IntakeFanoutGovernor6835.allowLaneEval"))
    }
}
