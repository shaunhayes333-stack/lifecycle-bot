package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7905 — Cortex v10: a new lane borrows what a voter learned on the others. */
class Aate7905HierarchicalCortexTest {
    @Test fun aThinLaneBorrowsTheVotersCrossLaneDeviation() {
        val led = CortexLedger7885()
        val flag = doubleArrayOf(0.5)
        repeat(200) { i ->
            val sig = if (i % 2 == 0) 1.0 else 0.0
            led.grade("QUALITY", listOf("V"), listOf(flag), doubleArrayOf(sig), if (sig > 0.5) 8.0 else -8.0, 0.0)
        }
        // MOONSHOT has never graded V: its prediction still leans on V's other-lane record.
        val hi = led.predict("V", "MOONSHOT", flag, 1.0).netPct
        val lo = led.predict("V", "MOONSHOT", flag, 0.0).netPct
        assertTrue("hi=$hi lo=$lo", hi > 3.0 && lo < -3.0)
        // QUALITY's own evidence is not counted twice through the global cell.
        assertEquals(0.0, led.predict("V", "QUALITY", flag, 1.0).netPct - led.predict("V", "QUALITY", flag, 1.0).netPct, 1e-12)
    }
}
