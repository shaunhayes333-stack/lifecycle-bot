package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9034 — the 26 Nov 23:38 report: hotFrames9031 costMs=2415 with a 10 s cap on its wait, so the sampler ran for a fifth
 * of the time in a runtime that was already stalled. And the one test that failed in the 9032 and 9033 runs.
 */
class Aate9034SamplerCapTest {
    @Test fun theSamplerKeepsItsFivePercentWhenASampleIsSlow() {
        assertEquals(HotFrames9031.PERIOD_MS_9031, HotFrames9031.gapAfter9031(0L))
        assertEquals(2_000L, HotFrames9031.gapAfter9031(100L))
        assertEquals("the 23:38 sample: 2.4 s of work, then 48 s of wait", 48_300L, HotFrames9031.gapAfter9031(2_415L))
        assertEquals(HotFrames9031.GAP_CAP_MS_9034, HotFrames9031.gapAfter9031(60_000L))
        // under the cap, a sample is never more than 1 part in 21 of the sampler's time
        for (cost in listOf(30L, 500L, 2_415L, 6_000L)) {
            val gap = HotFrames9031.gapAfter9031(cost)
            assertTrue("cost $cost gap $gap", cost.toDouble() / (cost + gap) <= 1.0 / 21.0 + 1e-9)
        }
    }

    @Test fun theBuildIsRecorded() {
        val t = File("src/test/kotlin/com/lifecyclebot/engine/Aate9032PumpLpAndPoolsTest.kt").readText()
        assertTrue("the 9032 test notes a frame at a real (positive) time", t.contains("noteFrame7951(seen, 34.0, 1_000_000.0, now - 8L * 60L * 60_000L)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## §219 — V5.0.9034: the sampler keeps its 5 %"))
        assertTrue(File("../../.github/workflows/build.yml").readText().contains("--tests '*Aate9034*'"))
    }
}
