package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate7419FinalDecisionGateRuntimeVerifierTest {
    @Test fun finalDecisionGateIsRestoredToKnownGood7409Shape() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertFalse(s.contains("preFanoutCachedVerdict7417"))
        assertFalse(s.contains("FDG_PRE_FANOUT_CACHE_HIT_7417"))
        assertFalse(s.contains("fdgCacheLane7410"))
        assertFalse(s.contains("fdgSide7410"))
        assertTrue(s.contains("IntakeFanoutGovernor6835.allowFdgEval"))
    }
}
