package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLabelHygiene8144
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import com.lifecyclebot.engine.truth.CapitalDrawdown7948
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8485 — session sizing drawdown, Cortex refusal needs separation, 10-minute grade read, wider targets, exit trigger. */
class Aate8485ExitsSizingCortexTest {
    private fun stat(n: Int, mean: Double, sd: Double = 5.0) = CortexLedger7885.Stat().apply {
        this.n = n.toDouble(); sum = mean * n; sumSq = n * (mean * mean + sd * sd)
    }

    @Test fun sizingDrawdownReadsThisSessionNotAnOldPeak() {
        // 8483: account peak 0.3827, session starts at 0.25 -> no sizing drawdown from earlier builds' losses
        assertEquals(0.0, CapitalDrawdown7948.sessionDrawdownPct(0.25, 0.25, 0.3827), 1e-9)
        // a loss this session still shrinks size
        assertEquals(20.0, CapitalDrawdown7948.sessionDrawdownPct(0.20, 0.25, 0.3827), 1e-9)
        // never measured above the account peak (rebased for withdrawals)
        assertEquals(0.0, CapitalDrawdown7948.sessionDrawdownPct(0.20, 0.25, 0.20), 1e-9)
        assertEquals(0.0, CapitalDrawdown7948.sessionDrawdownPct(0.0, 0.25, 0.3), 1e-9)
    }

    @Test fun refusalNeedsSeparationFromTheLanesOtherReads() {
        val refuse = stat(77, -8.1)
        assertFalse("no rival reads: no refusal power", CortexScoreboard7885.refuseProven(refuse, stat(3, 5.0), false, 40))
        assertFalse("lane loses everywhere alike", CortexScoreboard7885.refuseProven(refuse, stat(60, -7.0), false, 40))
        assertTrue("refused reads clearly worse", CortexScoreboard7885.refuseProven(refuse, stat(60, 4.0), false, 40))
    }

    @Test fun cortexGradesTheTenMinuteRead() {
        assertTrue(CortexLabelHygiene8144.revisionCountsFor8468("MOONSHOT", true, 9L * 60_000L))
        assertFalse(CortexLabelHygiene8144.revisionCountsFor8468("STANDARD", false, 50L * 60_000L))
        assertFalse(CortexLabelHygiene8144.revisionCountsFor8468(null, false, -1L))
    }

    @Test fun sellIntentTriggerDefaultsBlank() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/sell/SellIntent.kt").readText()
        assertTrue(src.contains("val trigger: String = \"\""))
        val coord = File("src/main/kotlin/com/lifecyclebot/engine/sell/SellFinalizationCoordinator.kt").readText()
        assertTrue(coord.contains("intent.trigger.ifBlank { intent.reason.name }"))
    }

    @Test fun winTargetsAreNotThreePercent() {
        val hold = File("src/main/kotlin/com/lifecyclebot/engine/HoldingLogicLayer.kt").readText()
        assertTrue(hold.contains("position.peakGainPct > trailArm8485"))
        val cash = File("src/main/kotlin/com/lifecyclebot/v3/scoring/CashGenerationAI.kt").readText()
        assertTrue(cash.contains("TRAIL_ARM_PCT = 12.0"))
        assertFalse(cash.contains("pnlPct > 5.0"))
        assertEquals(20.0, com.lifecyclebot.engine.truth.ScalpExit8351.TP1_PCT_8351, 0.0)
        assertEquals(40.0, com.lifecyclebot.engine.truth.ScalpExit8351.TP2_PCT_8351, 0.0)
    }

    @Test fun stopConfirmAsksTheVenueReservesBeforeFiringOnTheMark() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/StopMarkConfirm8418.kt").readText()
        val reserve = src.indexOf("reserveVerdict8485(ts, asked, nowMs, thresholdPct)")
        val expire = src.indexOf("if (awaitExpired8419(asked, nowMs))")
        assertTrue(reserve in 0 until expire)
    }
}
