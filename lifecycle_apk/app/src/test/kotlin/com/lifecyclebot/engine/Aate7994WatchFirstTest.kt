package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7994WatchFirstTest {
    @Test fun liveMoneyFollowsTheLabels() {
        assertFalse(LiveEdgeGate7877.cellEarnsLive7994(106, -9.5))   // fresh <$10k launches: watched
        assertFalse(LiveEdgeGate7877.cellEarnsLive7994(9, 30.0))     // too few labels yet
        assertTrue(LiveEdgeGate7877.cellEarnsLive7994(11, 8.1))      // $10k-$100k young coins: live
        assertTrue(LiveEdgeGate7877.cellEarnsLive7994(10, 0.0))
        assertFalse(LiveEdgeGate7877.cellEarnsLive7994(50, Double.NaN))
        val g = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt").readText()
        val live = g.substringAfter("if (!paper) {\n            val l8059").substringBefore("val why = liveRefusalCore7970(")
        // V5.0.8201 — the watch-first refusal is still the gate's last live return (and names an admitted candidate's terminal).
        val wf = live.substringAfter("watchFirst7994(ts, lane, nowMs, auditedAdmission8076 || candidateSpecificAdmission8136 || genericNative8211, candidateVersion, admission8136)?.let {")
        assertTrue(wf.contains("observeTail7996(ts, lane, it, nowMs); return it"))
        val watch = g.indexOf("watchFirst7994(ts, lane, nowMs, auditedAdmission8076 || candidateSpecificAdmission8136 || genericNative8211, candidateVersion, admission8136)")
        assertTrue(watch in 1 until g.indexOf("val why = liveRefusalCore7970(ts, lane, paper, nowMs, candidateSpecificAdmission8136, genericNative8211, resolvedAdmission)"))
    }
}
