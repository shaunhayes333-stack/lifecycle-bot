package com.lifecyclebot.engine

import com.lifecyclebot.engine.chart.ChartLibrary7950
import com.lifecyclebot.engine.chart.ChartLibraryBuilder7950
import com.lifecyclebot.engine.chart.ChartReader7950
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7953 — memes lead the build, and every live chart teaches the library. */
class Aate7953LiveLearningTest {
    @Test fun memesLeadAndInterleave() {
        assertEquals(listOf("m1", "c1", "m2", "c2", "c3"), ChartLibraryBuilder7950.interleave7953(listOf("m1", "m2"), listOf("c1", "c2", "c3")))
    }

    @Test fun everyLiveTapeIsLearned() {
        ChartLibrary7950.resetForTest()
        val t0 = 1_700_000_000_000L
        var p = 1.0
        for (m in 0 until 60) {
            p *= 1.01
            ChartReader7950.onPrice("mintA7953", p, t0 + m * 60_000L)
            ChartReader7950.onPrice("mintA7953", p * 1.003, t0 + m * 60_000L + 30_000L)
        }
        assertTrue(ChartReader7950.learnAll7953() > 0)
        assertTrue(ChartLibrary7950.size() > 0)
        ChartLibrary7950.resetForTest()
    }
}
