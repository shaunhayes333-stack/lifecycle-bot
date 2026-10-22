package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.LiveAdmissionProof8290
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8322 — the three remaining win caps under the owner's 8072 directive: the scanner source brain's +200 % cap, the
 * launch tape's ingestion clamp, and the labeler cell OUTLIER_SUSPECT veto at the live doors (a flag, not a veto, when the
 * cell's tail is real: 3+ local runners).
 */
class Aate8322TailCapsTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private fun sums(xs: List<Double>): Pair<Double, Double> = xs.sum() to xs.sumOf { it * it }

    @Test fun aCellWithARealTailReadsRawAndIsNotSuspect() {
        val xs = List(40) { if (it % 8 == 0) 1_400.0 else -12.0 }
        val (sum, sumSq) = sums(xs)
        val wins = xs.count { it > 0.0 }
        val r = ForwardReturnLabeler7731.tailRead8322("CELL|SHITCOIN|MC_LT10K", 40, sum, sumSq, wins, 5)
        assertFalse(r.suspect)
        assertEquals(sum / 40.0, r.mean, 1e-9)
        val variance = (sumSq / 40.0 - (sum / 40.0) * (sum / 40.0)) * 40.0 / 39.0
        assertEquals(kotlin.math.sqrt(variance / 40.0), r.stderr, 1e-9)
        // The live proof's outlier veto no longer fires; its realised shortfall still binds.
        assertTrue(LiveAdmissionProof8290.survives8290(40.0, r.mean, r.stderr, r.suspect, 0.0))
        assertFalse(LiveAdmissionProof8290.survives8290(40.0, r.mean, r.stderr, r.suspect, r.mean))
        assertEquals(3, ForwardReturnLabeler7731.TAIL_RUNNERS_8322)
    }

    @Test fun aCellRestingOnOneImplausibleLabelKeepsTheBoundAndTheVeto() {
        val xs = List(39) { -12.0 } + listOf(30_000.0)
        val (sum, sumSq) = sums(xs)
        val r = ForwardReturnLabeler7731.tailRead8322("CELL|SHITCOIN|MC_LT10K", 40, sum, sumSq, 1, 1)
        assertTrue(r.suspect)
        assertEquals(1_000.0 / 40.0, r.mean, 1e-9)
        assertFalse(LiveAdmissionProof8290.survives8290(40.0, r.mean, r.stderr, r.suspect, 0.0))
        // Hive runners do not make a tail real: the cell read passes the LOCAL runner count.
        val frl = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(frl.contains("val robust = tailRead8322(key, n, sum, sumSq, wins, t.runner60)"))
        assertTrue(frl.contains("tailRead8322(key, n, t.sum60, t.sumSq60, t.win60, t.runner60)"))
    }

    @Test fun launchTapeAndSourceBrainStoreRaw() {
        val tape = src("engine/market/LaunchTape7921.kt")
        assertFalse(tape.contains("coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.LABEL_CAP_8187)"))
        assertTrue(tape.contains("coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX)  // V5.0.8322"))
        val cx = src("engine/cortex/Cortex7885.kt")
        assertTrue(cx.contains("ScannerSourceBrain.recordOutcome(p.source, netPct.coerceAtLeast(-100.0))"))
        assertFalse(cx.contains("recordOutcome(p.source, netPct.coerceIn(-100.0, 200.0))"))
    }
}
