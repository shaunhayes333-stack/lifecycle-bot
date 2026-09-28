package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate7417FinalDecisionGateVerifierRegressionTest {
    @Test fun preFanoutCacheLogicIsExtractedFromGiantEvaluateMethod() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        val eval = s.substringAfter("fun evaluate(")
        val preFanout = eval.substringBefore("IntakeFanoutGovernor6835.allowFdgEval")
        assertTrue(preFanout.contains("preFanoutCachedVerdict7417"))
        assertFalse(preFanout.contains("fdgCacheLane7410"))
        assertFalse(preFanout.contains("fdgSide7410"))
        assertTrue(s.contains("private fun preFanoutCachedVerdict7417"))
        assertTrue(s.contains("FDG_PRE_FANOUT_CACHE_HIT_7417"))
    }
}
