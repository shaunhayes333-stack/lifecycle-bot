package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8072 — no win caps on the learning books; runner tickets fund the unproven 1000x hunt inside a 10% budget. */
class Aate8072NoWinCapsRunnerTicketsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aRunnerSetupIsValuedAtItsRealSize() {
        // 10% of coins run 10x (+900%), 90% lose 20%: the real mean is +72%, the old +200% clip read it +2%.
        val st = CortexLedger7885.Stat()
        repeat(9) { st.add((-20.0).coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX), false) }
        st.add(900.0.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX), true)
        assertEquals(72.0, st.mean(), 1e-9)
        assertTrue(src("engine/truth/ForwardReturnLabeler7731.kt").contains("NET_CEILING_PCT_7738 = Double.POSITIVE_INFINITY"))
        assertTrue(src("engine/ForwardOutcomeModel.kt").contains("LABEL_GAIN_CEILING_PCT_7738 = Double.POSITIVE_INFINITY"))
    }

    @Test fun ticketGroupAndBudget() {
        assertTrue(RunnerTicket8073.inGroup8073("PUMPSWAP", 40_000.0))
        assertFalse(RunnerTicket8073.inGroup8073("CURVE", 40_000.0))      // on the curve: -41% book
        assertFalse(RunnerTicket8073.inGroup8073("PUMPSWAP", 5_000.0))
        assertFalse(RunnerTicket8073.inGroup8073("PUMPSWAP", 400_000.0))
        assertTrue(RunnerTicket8073.fits8073(0.0, 0.03, 0.4))                // 0.03 of a 0.04 budget
        assertFalse(RunnerTicket8073.fits8073(0.03, 0.03, 0.4))              // a second would pass 10%
        assertFalse(RunnerTicket8073.fits8073(0.0, 0.03, 0.0))
    }

    @Test fun ticketsAreWiredAndHeld() {
        val g = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(g.contains("RunnerTicket8073.admits8073(ts, nowMs)"))
        assertTrue(g.contains("RunnerTicket8073.isTicket8073(ts.mint, nowMs)"))
        assertTrue(src("engine/LifecycleHold8065.kt").contains("RunnerTicket8073.isTicket8073(ts.mint)"))
    }
}
