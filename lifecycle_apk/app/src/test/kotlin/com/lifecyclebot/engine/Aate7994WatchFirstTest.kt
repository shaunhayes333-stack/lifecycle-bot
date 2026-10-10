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
        assertTrue(g.contains("if (!paper) watchFirst7994(ts, lane, nowMs)?.let { observeTail7996(ts, lane, it, nowMs); return it }"))
    }
}
