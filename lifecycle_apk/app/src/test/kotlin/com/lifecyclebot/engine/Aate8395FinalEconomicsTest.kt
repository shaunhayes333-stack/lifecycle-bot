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
        assertTrue(src.contains("sizeSol = if (econ8395.clears) executableSize7835 else 0.0"))
        assertTrue(src.contains("blockReason = finalReason8395(econ8395, blockReasonFinal, when {"))
        assertFalse("no return can read a stale reason after the economics write", src.contains("if (!sealedCostOk8209 && blockReason == null)"))
    }
}
