package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import com.lifecyclebot.engine.truth.EntryChase7961
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.7961 — selection learns the price it can execute; exits learn the ladder that captures most. */
class Aate7961SelectionAndLadderTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun chaseIsFillAgainstDecision() {
        assertEquals(12.0, EntryChase7961.chasePct7961(1.0, 1.12)!!, 1e-9)
        assertEquals(-5.0, EntryChase7961.chasePct7961(2.0, 1.9)!!, 1e-9)
        assertNull(EntryChase7961.chasePct7961(1.0, 150.0))     // unit mix-up
        assertNull(EntryChase7961.chasePct7961(0.0, 1.0))
        // Median, never credited as edge when fills come in cheaper, capped at 30.
        assertEquals(8.0, EntryChase7961.penaltyOf7961(listOf(2.0, 8.0, 40.0)), 1e-9)
        assertEquals(0.0, EntryChase7961.penaltyOf7961(listOf(-4.0, -2.0, -1.0)), 1e-9)
        assertEquals(30.0, EntryChase7961.penaltyOf7961(listOf(50.0, 60.0, 70.0)), 1e-9)
        assertEquals(0.0, EntryChase7961.penaltyOf7961(emptyList()), 1e-9)
    }

    @Test fun chaseIsWiredIntoFillsAndLabels() {
        assertTrue(src("engine/truth/EntryStrategySnapshot6450.kt").contains("EntryChase7961.onEntry7961("))
        val lab = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(lab.contains("EntryChase7961.lanePenaltyPct7961(l)"))
        assertTrue(lab.contains("fun decisionPx7961(mint: String, lane: String): Pair<Double, Long>?"))
    }

    @Test fun ladderMaximisesCaptureOnTheKeysOwnPeaks() {
        // Half pop to +30 and die, half run to +150..+530: the first rung waits for the runners.
        val peaks = (0 until 40).map { i -> if (i % 2 == 0) 150.0 + i * 20.0 else 30.0 }
        val l = ExitProfile7955.optimalLevels7961(peaks)
        assertTrue(l.isNotEmpty())
        assertTrue("first rung past the +30 pops", l[0] > 100.0)
        // All pop to +25..+55: the first rung sits inside the pop, not above it.
        val pops = (0 until 40).map { i -> 25.0 + (i % 7) * 5.0 }
        val p = ExitProfile7955.optimalLevels7961(pops)
        assertTrue(p[0] in 25.0..40.0)
        for (i in 1 until p.size) assertTrue(p[i] >= p[i - 1] * 1.4)
        assertTrue(ExitProfile7955.optimalLevels7961(emptyList()).isEmpty())
        assertTrue(ExitProfile7955.optimalLevels7961(listOf(1.0, 2.0, 5.0)).isEmpty())   // nothing reaches +15
    }

    @Test fun firedSetupsTeachTheirOwnExitKey() {
        assertTrue(ExitProfile7955.setupFired7961("LAUNCH_LADDER_PROVEN"))
        assertFalse(ExitProfile7955.setupFired7961("NONE"))
        assertFalse(ExitProfile7955.setupFired7961("NO_TRIGGER"))
        assertFalse(ExitProfile7955.setupFired7961(""))
        assertTrue(src("engine/truth/ForwardReturnLabeler7731.kt").contains("keyOnly = !o.admitted,"))
    }

    @Test fun aProvenSetupInsideTheRefuseBucketIsNotRefused() {
        val win = CortexLedger7885.Stat().also { s -> repeat(40) { i -> s.add(if (i % 4 == 0) -3.0 else 18.0, false) } }
        val thin = CortexLedger7885.Stat().also { s -> repeat(10) { s.add(20.0, false) } }
        val lose = CortexLedger7885.Stat().also { s -> repeat(60) { i -> s.add(if (i % 2 == 0) -8.0 else 2.0, false) } }
        assertTrue(Cortex7885.jointProvenPositive7961(win))
        assertFalse(Cortex7885.jointProvenPositive7961(thin))
        assertFalse(Cortex7885.jointProvenPositive7961(lose))
        assertFalse(Cortex7885.jointProvenPositive7961(null))
        assertEquals("MOONSHOT|REFUSE|LAUNCH_LADDER_PROVEN",
            Cortex7885.jointKey7961("MOONSHOT", CortexScoreboard7885.Bucket.REFUSE, "LAUNCH_LADDER_PROVEN"))
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("C3_STOOD_DOWN_SETUP_PROVEN_7961"))
    }
}
