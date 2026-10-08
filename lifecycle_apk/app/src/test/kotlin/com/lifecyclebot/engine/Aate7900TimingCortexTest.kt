package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexTiming7900
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7900 — Cortex v6: waiting for the dip only on a proven record. */
class Aate7900TimingCortexTest {
    private fun stat(n: Int, mean: Double) = CortexLedger7885.Stat().also { s -> repeat(n) { s.add(mean + if (it % 2 == 0) 3.0 else -3.0, false) } }

    @Test fun aProvenDipRecordEarnsTheWait() {
        assertTrue(CortexTiming7900.waitProven(stat(50, -5.0)))
        assertFalse(CortexTiming7900.waitProven(stat(30, -5.0)))
        assertFalse(CortexTiming7900.waitProven(stat(50, -1.0)))
    }
}
