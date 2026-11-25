package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9026 — the same question is answered once: the ranked pick is kept for a second on unchanged inputs, the coin's stage
 * is read once per option build, the finalized closes are projected once per bus revision, and a stored setup's tokens are kept.
 */
class Aate9026SameQuestionOnceTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aPickIsKeptOnlyWhileItsInputsAndBookAreUnchangedAndForASecond() {
        val pb = LanePlaybook7907
        assertEquals(1_000L, pb.PICK_TTL_MS_9026)
        assertTrue(pb.pickFresh9026(atMs = 10_000L, key = 7L, rev = 3L, nowMs = 10_000L, nowKey = 7L, nowRev = 3L))
        assertTrue(pb.pickFresh9026(10_000L, 7L, 3L, 11_000L, 7L, 3L))
        assertFalse("older than a second", pb.pickFresh9026(10_000L, 7L, 3L, 11_001L, 7L, 3L))
        assertFalse("the coin's inputs moved", pb.pickFresh9026(10_000L, 7L, 3L, 10_200L, 8L, 3L))
        assertFalse("a label was graded: the book moved", pb.pickFresh9026(10_000L, 7L, 3L, 10_200L, 7L, 4L))
        assertFalse("a clock that went backwards is not a hit", pb.pickFresh9026(10_000L, 7L, 3L, 9_999L, 7L, 3L))
        assertTrue(pb.pickMemoLine9026().startsWith("pickMemo9026[hits="))
        val s = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(s.contains("val p = rankedPick9026(ts, laneRaw, nowMs) ?: return null"))
        assertTrue("the explicit-frame pick the admission asks for is untouched", src("engine/truth/CandidateAdmission8136.kt").contains("pb.strategyPick8153(ts, lane, nowMs, features8535)"))
        assertTrue("one stage read per option build", s.contains("timingAdjustment8528(lane, id, band, stage9026)"))
        assertTrue(s.contains("val stage9026 by lazy(LazyThreadSafetyMode.NONE)"))
    }

    @Test fun theFinalizedClosesAreProjectedOncePerRevision() {
        val bus = CanonicalFinalizedTradeBus6464
        fun n(tag: String) = Regex("$tag=(\\d+)").find(bus.terminalCacheLine9026())!!.groupValues[1].toLong()
        val a = bus.terminalEnvelopes7863("live")
        val reused = n("reused")
        val b = bus.terminalEnvelopes7863("LIVE")
        assertSame("the same revision and mode: the same projection", a, b)
        assertEquals(reused + 1L, n("reused"))
        // "since" applied after the projection is the same list the old per-call build gave
        val since = (a.getOrNull(a.size / 2)?.atMs ?: 1L)
        assertEquals(a.filter { it.atMs >= since }, bus.terminalEnvelopes7863("live", since))
        assertEquals(a, bus.terminalEnvelopes7863("live", 0L))
        val s = src("engine/truth/CanonicalFinalizedTradeBus6464.kt")
        assertTrue(s.contains("terminals9026[key]?.takeIf { it.revision == revision }?.list"))
        assertTrue("a test reset drops the projection with the revision", s.contains("canonicalProjectionCache7497.set(null); terminals9026.clear()"))
    }

    @Test fun aStoredSetupsTokensAreKeptAndReadTheSame() {
        val g = SemanticPatternGraph
        val text = "LAUNCH_LADDER_PROVEN Micro-Cap ignition x #pump ab"
        val once = g.tokenSetUncached9026(text)
        assertEquals(setOf("launch_ladder_proven", "micro-cap", "ignition", "#pump"), once)
        val kept = g.nodeTokens9026(text)
        assertEquals(once, kept)
        assertSame("the second read is the kept set", kept, g.nodeTokens9026(text))
        assertEquals(emptySet<String>(), g.nodeTokens9026(""))
        val s = src("engine/SemanticPatternGraph.kt")
        assertTrue(s.contains("private val TOKEN_SPLIT_9026 = Regex("))
        assertTrue(s.contains("val nodeTokens = nodeTokens9026(node.setup)"))
        assertTrue(s.contains("nodeTokens.any { it in dnaTokens9026 }"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("LanePlaybook7907.pickMemoLine9026()"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("CanonicalFinalizedTradeBus6464.terminalCacheLine9026()"))
    }
}
