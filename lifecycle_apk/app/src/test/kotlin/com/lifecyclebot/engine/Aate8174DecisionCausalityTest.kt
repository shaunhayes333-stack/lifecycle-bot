package com.lifecyclebot.engine

import com.lifecyclebot.engine.HandoffReceiverTasks8153
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8174 — decision-time Cortex truth and version-safe handoffs. */
class Aate8174DecisionCausalityTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun freshCandidateVersionCanSupersedeOldPendingHandoffWithoutTimestamp() {
        val t = 100_000L
        val h = HandoffReceiverTasks8153
        val first = h.open8153(null, "Mint8174", "MOONSHOT", "PROJECT_SNIPER", "YIELD", 1, t, t)!!
        val next = h.open8153(first, "Mint8174", "MOONSHOT", "PROJECT_SNIPER", "YIELD", 2, t + 300, t + 300)!!
        assertEquals(2L, next.version)
        assertEquals(t + 300, next.createdAtMs)
        assertFalse(h.due8153(first, "MOONSHOT", t + 300, 2))
        assertTrue(h.due8153(next, "MOONSHOT", t + 300, 2))
    }

    @Test fun newerVersionCanOpenEvenWhenOldTaskSettledInsideHoldWindow() {
        val h = HandoffReceiverTasks8153
        val first = h.open8153(null, "Mint8174", "MOONSHOT", "PROJECT_SNIPER", "YIELD", 1, 1000, 1000)!!
        val refused = h.block8153(first, "MOONSHOT", HandoffReceiverTasks8153.Block8153.JUDGEMENT, "NO_BUY", 1100)
        val second = h.open8153(refused, "Mint8174", "MOONSHOT", "PROJECT_SNIPER", "YIELD", 2, 1200, 1200)!!
        assertEquals(2L, second.version)
        assertTrue(second.pending)
    }

    @Test fun cortexNeverRecomputesMutableVotersAfterTheDecision() {
        val s = src("engine/cortex/Cortex7885.kt")
        assertTrue(s.contains("CAPTURE_VOTERS_NOT_READY_AT_DECISION_8174"))
        assertTrue(s.contains("val late8169 = lateVoterRead8169(labelLane, nowMs, a.atMs)"))
        assertTrue(s.contains("pendingCtx8144["))
        val body = s.substringAfter("private fun captureNow(").substringBefore("// ── V5.0.7920")
        assertFalse(body.contains("assess(ts, labelLane, nowMs)"))
        assertFalse(body.contains("LanePlaybook7907.classify(ts"))
        assertFalse(body.contains("LanePlaybook7907.matchedSetups8083(ts"))
    }

    @Test fun lanePlaybookSupportsFrozenEntryStrategyAndTiming() {
        val s = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(s.contains("selectedAtDecision8174: String?"))
        assertTrue(s.contains("timingAtDecision8174: String?"))
    }
}
