package com.lifecyclebot.engine

import com.lifecyclebot.engine.FinalDecisionGate.FinalEconomics8395
import com.lifecyclebot.engine.FinalDecisionGate.FinalEconomicsVerdict8395
import org.junit.Assert.*
import org.junit.Test

class Aate8395FinalEconomicsTest {
    @Test fun failingQuoteIsANamedRejection() {
        val v = FinalDecisionGate.requote8395(1.0, 3.0, 3.2)
        assertEquals(FinalEconomics8395.REJECT, v.verdict)
        assertEquals("ADMISSION_COST_REQUOTE_FAILED_8209", v.reason)
    }
    @Test fun nanOrMissingRequiredQuoteDefersAndCannotExecute() {
        for (args in listOf(doubleArrayOf(Double.NaN, 3.0, 3.2), doubleArrayOf(40.0, Double.NaN, 3.2), doubleArrayOf(40.0, 3.0, Double.NaN))) {
            val v = FinalDecisionGate.requote8395(args[0], args[1], args[2])
            assertEquals(FinalEconomics8395.DEFER, v.verdict); assertFalse(v.clears)
            assertEquals("ADMISSION_COST_REQUOTE_UNAVAILABLE_8395", v.reason)
        }
        assertEquals(FinalEconomics8395.DEFER, FinalDecisionGate.requote8395(40.0, 3.0, 3.2, Double.NaN).verdict)
    }
    @Test fun validFreshClearingQuoteProceeds() {
        val v = FinalDecisionGate.requote8395(40.0, 3.0, 3.2)
        assertTrue(v.clears); assertNull(v.reason)
    }
    @Test fun economicsReasonOutranksEarlierAdvisoryReasonEverywhere() {
        val reject = FinalEconomicsVerdict8395(FinalEconomics8395.REJECT, "ADMISSION_COST_REQUOTE_FAILED_8209")
        assertEquals("ADMISSION_COST_REQUOTE_FAILED_8209", FinalDecisionGate.finalReason8395(reject, "PROBE_ONLY", "X"))
        val clear = FinalEconomicsVerdict8395(FinalEconomics8395.CLEAR, null)
        assertEquals("PROBE_ONLY", FinalDecisionGate.finalReason8395(clear, "PROBE_ONLY", "X"))
        assertEquals("X", FinalDecisionGate.finalReason8395(clear, null, "X"))
    }
    @Test fun deniedEconomicsReturnsZeroSizeAndOneAssembledTerminal() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        // superseded by V5.0.8418 — owner: evaluate() is not retouched. Its return block is the pre-8395 text again (pinned in
        // Aate8418FdgEvaluateTextTest); the zero size and the one named terminal are applied in rememberFdgVerdict from the
        // verdict sealedCostHolds8209 hands FdgGate8365. Was: econ8395 / finalReason8395 inside the return block.
        assertTrue(src.contains("com.lifecyclebot.engine.truth.FdgGate8365.noteFinalEconomics8418(ts.mint, lane, v.clears, v.reason)"))
        assertTrue(src.contains("blockReason = econ8418, blockLevel = BlockLevel.EDGE, sizeSol = 0.0)"))
        com.lifecyclebot.engine.truth.FdgGate8365.noteFinalEconomics8418("m8418", "TREASURY", false, "ADMISSION_COST_REQUOTE_UNAVAILABLE_8395", 1_000L)
        assertEquals("ADMISSION_COST_REQUOTE_UNAVAILABLE_8395", com.lifecyclebot.engine.truth.FdgGate8365.takeFinalEconomics8418("m8418", "TREASURY", 2_000L))
        assertNull("consumed once", com.lifecyclebot.engine.truth.FdgGate8365.takeFinalEconomics8418("m8418", "TREASURY", 2_000L))
        com.lifecyclebot.engine.truth.FdgGate8365.noteFinalEconomics8418("m8418", "TREASURY", false, "ADMISSION_COST_REQUOTE_FAILED_8209", 1_000L)
        assertNull("a stale verdict never names a later decision", com.lifecyclebot.engine.truth.FdgGate8365.takeFinalEconomics8418("m8418", "TREASURY", 9_000L))
        com.lifecyclebot.engine.truth.FdgGate8365.noteFinalEconomics8418("m8418", "TREASURY", false, "ADMISSION_COST_REQUOTE_FAILED_8209", 1_000L)
        com.lifecyclebot.engine.truth.FdgGate8365.noteFinalEconomics8418("m8418", "TREASURY", true, null, 1_500L)
        assertNull("a clearing re-quote clears the held refusal", com.lifecyclebot.engine.truth.FdgGate8365.takeFinalEconomics8418("m8418", "TREASURY", 2_000L))
    }
}
