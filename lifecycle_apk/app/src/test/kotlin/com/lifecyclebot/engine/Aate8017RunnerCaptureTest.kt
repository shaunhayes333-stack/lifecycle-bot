package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.RunnerCapture8017
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8017RunnerCaptureTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun paperCloseCannotReplaceALiveRunnerOutcomeOrSupplyItsEntry() {
        val r = RunnerCapture8017.Run("mint", "SYM", "MOONSHOT", 1.0, 1000L, "")
        fun close(mode: String, ret: Double, at: Long) = com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope(
            tradeId = mode, positionId = mode, atMs = at, realizedPnlSol = 0.0, realizedReturnPct = ret,
            mint = "mint", lane = "MOONSHOT", mode = mode, holdingTimeMs = 1000L)
        r.holding("paper", false, 1000L, 9.0, 9.1)
        r.closed(close("live", -3.0, 3000L))
        r.closed(close("paper", 500.0, 4000L))
        assertTrue(r.live)
        assertEquals(-3.0, r.bankedPct, 0.0)
        assertEquals(0.0, r.entryPx, 0.0)
    }

    @Test fun entryIsMeasuredAgainstTheStartMark() {
        assertEquals(100.0, RunnerCapture8017.entryVsStart8017(2.0, 1.0), 1e-9)
        assertEquals(-50.0, RunnerCapture8017.entryVsStart8017(0.5, 1.0), 1e-9)
        assertTrue(RunnerCapture8017.entryVsStart8017(0.0, 1.0).isNaN())
    }

    @Test fun bankedShareIsOfTheRunFromTheBotsOwnEntry() {
        // entered at start, coin went 9x (+800%), banked +200% -> a quarter of the run
        assertEquals(0.25, RunnerCapture8017.bankedShare8017(200.0, 800.0, 0.0), 1e-9)
        // entered at 2x the start, peak 9x -> 3.5x available (+350%), banked +175% -> half
        assertEquals(0.5, RunnerCapture8017.bankedShare8017(175.0, 800.0, 100.0), 1e-9)
        assertTrue(RunnerCapture8017.bankedShare8017(Double.NaN, 800.0, 0.0).isNaN())
    }

    @Test fun hookedIntoTheLabelerTheCloseBusAndTheDiag() {
        assertTrue(src("engine/truth/ForwardReturnLabeler7731.kt").contains("RunnerCapture8017.onRunnerPeak8017(o.mint, o.symbol, o.lane, o.entryPrice, gross"))
        assertTrue(src("engine/truth/CanonicalFinalizedTradeBus6464.kt").contains("RunnerCapture8017.onClose8017(env)"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("RunnerCapture8017.statusLine()"))
    }
}
