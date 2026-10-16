package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate7417FinalDecisionGateVerifierRegressionTest {
    @Test fun preFanoutCacheLogicIsExtractedFromGiantEvaluateMethod() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        // The pre-fanout cache step and the in-FDG fanout governor call are gone;
        // evaluate() now does one cache lookup and the key/lookup logic stays in
        // helpers outside the giant evaluate method.
        val eval = s.substringAfter("fun evaluate(")
        assertTrue(eval.contains("cachedFdgVerdict(fdgCacheKey)?.let { return it }"))
        assertFalse(eval.contains("fdgCacheLane7410"))
        assertFalse(eval.contains("fdgSide7410"))
        assertTrue(s.contains("internal fun fdgCacheKey("))
        assertTrue(s.contains("private fun cachedFdgVerdict(key: String): FinalDecision?"))
        assertTrue(s.indexOf("private fun cachedFdgVerdict(") < s.indexOf("fun evaluate("))
    }
}
