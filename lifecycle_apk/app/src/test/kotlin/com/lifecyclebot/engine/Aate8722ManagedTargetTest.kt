package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EdgeModel8537
import com.lifecyclebot.engine.truth.PocketLedger8610
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8722 — the edge model learns what the lane banks (not the log return) and reads the three structure pairs. */
class Aate8722ManagedTargetTest {
    private fun raw(ageMin: Double, liq: Double) = EdgeModel8537.Raw("SHITCOIN", "PUMP_PORTAL", liq, 50_000.0, ageMin, 10.0, 0.8,
        10.0, 60.0, 1.0, 5.0, 50.0, 20.0, "", 50.0, 4.0)

    @Test fun theTargetIsTheBankedOutcomeNotTheLogReturn() {
        val loss = EdgeModel8537.managedTarget8722("SHITCOIN", -90.0, 4.0)
        assertTrue("a wipe-out costs what the stop loses, not its log: $loss", loss >= -100.0 && loss > EdgeModel8537.target8567(-90.0) + 100.0)
        assertEquals("a runner is kept to the pocket cap", PocketLedger8610.managed8610(2_000.0), EdgeModel8537.managedTarget8722("SHITCOIN", 2_000.0, 4.0), 1e-9)
        assertEquals(3.0, EdgeModel8537.managedTarget8722("SHITCOIN", 3.0, 4.0), 1e-9)
        val o = EdgeModel8537.Oos()
        repeat(4) { o.add(1.5, 10.0) }
        assertEquals("a +1.5 % prediction is a pick as it stands", 4, o.selN)
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/EdgeModel8537.kt").readText()
        assertTrue(src.contains("val y = managedTarget8722(s.raw.lane, netPct, s.raw.costPct)"))
        assertTrue(src.contains("internal const val MODEL_EPOCH_8642 = 8722"))
    }

    @Test fun aPairThatPaysOnlyTogetherIsLearned() {
        EdgeModel8537.resetForTest8537()
        try {
            repeat(400) {
                EdgeModel8537.learnRawForTest8537(raw(3.0, 20_000.0), 20.0)     // young coin, deep pool: pays
                EdgeModel8537.learnRawForTest8537(raw(3.0, 2_000.0), -6.0)      // young, thin
                EdgeModel8537.learnRawForTest8537(raw(60.0, 20_000.0), -6.0)    // old, deep
                EdgeModel8537.learnRawForTest8537(raw(60.0, 2_000.0), 0.0)      // old, thin: flat
            }
            val (both, _) = EdgeModel8537.predictRawForTest8537(raw(3.0, 20_000.0))
            val (neither, _) = EdgeModel8537.predictRawForTest8537(raw(60.0, 2_000.0))
            // single-feature bins alone put these at +12 and -8
            assertTrue("the pair is read as a pair: $both", both > 16.0)
            assertTrue("and its absence is not charged for it: $neither", neither > -4.0)
            val x = EdgeModel8537.vectorOf(raw(3.0, 20_000.0))
            assertEquals(EdgeModel8537.DIM, x.size)
            assertTrue(EdgeModel8537.DIM > 200)
        } finally { EdgeModel8537.resetForTest8537() }
    }
}
