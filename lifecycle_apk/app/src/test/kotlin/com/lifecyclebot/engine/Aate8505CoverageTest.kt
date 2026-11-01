package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate8505CoverageTest {
    /** Lost fades with the 60 slot: a steady 80% priced book reads ~80% after thousands of labels, not falling forever. */
    @Test
    fun lost_fades_with_the_window_so_coverage_is_recent() {
        val t = ForwardReturnLabeler7731.Tally()
        repeat(5000) { i ->
            if (i % 5 == 4) t.lost += 1 else { t.n60 += 1; t.sum60 += 1.0; t.window8014(ForwardReturnLabeler7731.CELL_WINDOW_8014) }
        }
        val share = t.n60.toDouble() / (t.n60 + t.lost)
        assertEquals(ForwardReturnLabeler7731.CELL_WINDOW_8014, t.n60)
        assertTrue("share=$share lost=${t.lost}", share in 0.75..0.85)
    }

    @Test
    fun a_window_closed_before_boot_is_downtime() {
        assertTrue(com.lifecyclebot.engine.truth.DowntimeRecovery8430.downtime8430(1_000L, 2_000L))
        assertTrue(!com.lifecyclebot.engine.truth.DowntimeRecovery8430.downtime8430(3_000L, 2_000L))
    }
}
