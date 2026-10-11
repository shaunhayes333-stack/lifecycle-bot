package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8071 — a real move the price held is credited on the lane's ladder; a one-print spike is not. */
class Aate8071HeldMovesCountTest {
    private fun run(marks: List<Pair<Long, Double>>): Double {
        val w = ArrayDeque<DoubleArray>()
        var held = 0.0
        for ((t, g) in marks) held = ForwardReturnLabeler7731.heldPeakStep8071(w, t, g, held)
        return held
    }

    @Test fun aOnePrintSpikeIsNotHeld() {
        // +276% for one print at 12 s, back to +2% by 15 s (foff).
        val held = run(listOf(0L to 0.0, 5_000L to 3.0, 12_000L to 276.0, 15_000L to 2.0, 25_000L to 1.0, 40_000L to 0.0))
        assertTrue(held < 5.0)
    }

    @Test fun aRealRunIsHeld() {
        // Runs to +300% and sits there for a minute.
        val marks = (0..20).map { i -> (i * 5_000L) to if (i < 4) i * 25.0 else 300.0 + (i % 3) }
        assertTrue(run(marks) >= 300.0)
    }

    @Test fun lifecycleLadderCreditsTheHeldLevel() {
        // Held +150% (half sold at 2x), back to +10% at the read: half at +100% less slip, half at +10%.
        val g = ForwardReturnLabeler7731.heldCredit8071(150.0, 10.0, LifecycleHold8065.ladder8071())
        assertTrue(g > 45.0)   // 0.5 x (+100% less 5% slip) + 0.5 x +10% = +50%
        // A held level below the first rung credits nothing beyond the read.
        assertEquals(10.0, ForwardReturnLabeler7731.heldCredit8071(60.0, 10.0, LifecycleHold8065.ladder8071()), 1e-9)
    }
}
