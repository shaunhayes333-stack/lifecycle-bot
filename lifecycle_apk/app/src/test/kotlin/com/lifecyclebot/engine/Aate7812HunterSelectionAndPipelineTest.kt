package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7812HunterSelectionAndPipelineTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test fun adaptiveHunterSelectionUsesOutcomeAndTransitionEvidence() {
        val s = src("engine/market/LaneHunter7297.kt")
        assertTrue(s.contains("sampleConfidence7812"))
        assertTrue(s.contains("learnedDelta7812"))
        assertTrue(s.contains("adaptiveTimingMultiplier7812"))
        assertTrue(s.contains("o.volumeAcceleration"))
        assertTrue(s.contains("o.txAcceleration"))
        assertTrue(s.contains("o.buyPressurePct"))
        assertTrue(s.contains("\"EARLY_MOMENTUM_IGNITION\""))
        assertTrue(s.contains("\"DIP_RECOVERY\""))
        assertTrue(s.contains("\"DISTRIBUTION\""))
        assertTrue(s.contains("\"EXHAUSTION\""))
        assertTrue(s.contains("commonSenseMult7797(p.lane, r) * adaptiveTimingMultiplier7812(p.lane, r)"))
    }

    @Test fun authorizerDefersUntilExactSealedFdgBuy() {
        val s = src("engine/TradeAuthorizer.kt")
        val start = s.indexOf("V5.0.7812 — only an exact immutable FDG BUY")
        val gate = s.indexOf("val finality = ExecutableOpenGate.canOpenExecutablePosition(", start)
        assertTrue(start >= 0 && gate > start)
        val pre = s.substring(start, gate)
        assertTrue(pre.contains("activeExecutionIntentForLane7809"))
        assertTrue(pre.contains("AWAIT_FDG_SEAL_7812"))
        assertTrue(pre.contains("it.fdgAllowed"))
        assertTrue(pre.contains("it.fdgVerdict.equals(\"BUY\", true)"))
        assertTrue(pre.contains("canRetry = true"))
        assertFalse(pre.contains("recordPreSizeRefusal7809("))
        assertFalse(pre.contains("markLost("))
        assertTrue(s.contains("val finalityAttemptId = sealedIntent7812.attemptId"))
    }

    @Test fun missingElectionLaneCannotBreakKotlinCompile() {
        val s = src("engine/ExecutableOpenGate.kt")
        assertTrue(s.contains("currentElection6600(mint)?.primaryLane.orEmpty()"))
    }
}
