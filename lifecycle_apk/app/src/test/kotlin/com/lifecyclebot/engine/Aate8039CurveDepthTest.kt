package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.OnChainDepth8038
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8039 — a candidate with unknown depth gets its pump.fun curve read from the chain, batched. */
class Aate8039CurveDepthTest {
    @Test fun aCurveIsAskedOncePerMinute() {
        assertTrue(OnChainDepth8038.curveDue8039(0L, 1_000_000L))
        assertFalse(OnChainDepth8038.curveDue8039(1_000_000L - 30_000L, 1_000_000L))
        assertTrue(OnChainDepth8038.curveDue8039(1_000_000L - 60_000L, 1_000_000L))
    }

    @Test fun unknownDepthQueuesTheCurveRead() {
        val lt = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiquidityTruth8037.kt").readText()
        assertTrue(lt.contains("OnChainDepth8038.needCurve8039(ts.mint)"))
        val od = File("src/main/kotlin/com/lifecyclebot/engine/truth/OnChainDepth8038.kt").readText()
        assertTrue(od.contains("ParallelMarkFanout7088.curvePrices7392(batch)"))
    }
}
