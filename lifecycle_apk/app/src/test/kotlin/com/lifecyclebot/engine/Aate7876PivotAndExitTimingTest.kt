package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.relativeStrength7876
import com.lifecyclebot.engine.truth.ExitStageTiming7876
import com.lifecyclebot.engine.truth.LivePivotAuthority7876
import com.lifecyclebot.engine.truth.LivePivotAuthority7876.Evidence
import com.lifecyclebot.engine.truth.LivePivotAuthority7876.LaneEvidence
import com.lifecyclebot.engine.truth.StopLatencyClasses6464
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7876PivotAndExitTimingTest {
    private fun ev(liveN: Int = 0, livePp: Double = 0.0, paperN: Int = 0, paperPp: Double = 0.0,
                   labelN: Int = 0, labelMean: Double = 0.0, labelSe: Double = 0.0) =
        LaneEvidence(liveN, livePp, paperN, paperPp, labelN, labelMean, labelSe)

    @Test fun limitBreachesAreCountedAtTheFullLimit() {
        assertEquals(emptyList<String>(), KillSwitch.limitBreaches7876(-9.0, 10.0, 24.0, 25.0, 4, 5))
        assertEquals(listOf("DAILY_LOSS", "DRAWDOWN", "LOSS_STREAK"),
            KillSwitch.limitBreaches7876(-10.0, 10.0, 61.0, 25.0, 5, 5))
    }

    @Test fun pivotKeepsOnlyEvidencePositiveLanesLive() {
        assertEquals(Evidence.UNPROVEN, LivePivotAuthority7876.verdict(ev()))
        assertEquals(Evidence.PROVEN, LivePivotAuthority7876.verdict(ev(liveN = 6, livePp = 2.0)))
        // QUALITY-shaped: forward labels n=20 net +11.9% with a modest standard error.
        assertEquals(Evidence.PROVEN, LivePivotAuthority7876.verdict(ev(labelN = 20, labelMean = 11.9, labelSe = 4.0)))
        // MOONSHOT-shaped: live negative, labels negative.
        assertEquals(Evidence.NEGATIVE, LivePivotAuthority7876.verdict(ev(liveN = 11, livePp = -6.85, labelN = 65, labelMean = -14.6, labelSe = 3.0)))
        // A live loser stays live only on forward-label proof.
        assertEquals(Evidence.PROVEN, LivePivotAuthority7876.verdict(ev(liveN = 7, livePp = -3.0, labelN = 25, labelMean = 6.0, labelSe = 2.0)))
        // Paper must clear the cost margin; a noisy label mean inside one SE is not proof.
        assertEquals(Evidence.UNPROVEN, LivePivotAuthority7876.verdict(ev(paperN = 40, paperPp = 3.0)))
        assertEquals(Evidence.PROVEN, LivePivotAuthority7876.verdict(ev(paperN = 40, paperPp = 6.0)))
        assertEquals(Evidence.UNPROVEN, LivePivotAuthority7876.verdict(ev(labelN = 30, labelMean = 2.0, labelSe = 3.0)))
    }

    @Test fun paperIsNeverRefusedByThePivot() {
        assertEquals(null, LivePivotAuthority7876.liveRefusal("MOONSHOT", paper = true))
    }

    @Test fun exitStagesSeparateQueueRedispatchAndInAttemptTime() {
        val mint = "STAGE7876_MINT_A"
        val t0 = 1_000_000L
        ExitStageTiming7876.onTrigger(mint, StopLatencyClasses6464.Class.HARD_STOP, t0, nowMs = t0)
        ExitStageTiming7876.onPhase(mint, "SELL_START", t0 + 4_000L)        // queued 4 s
        ExitStageTiming7876.onPhase(mint, "SELL_QUOTE_FAIL", t0 + 10_000L)  // 6 s quote ladder
        ExitStageTiming7876.onPhase(mint, "SELL_START", t0 + 18_000L)       // 8 s until re-dispatch
        ExitStageTiming7876.onPhase(mint, "SELL_BROADCAST", t0 + 19_000L)
        ExitStageTiming7876.onPhase(mint, "SELL_CONFIRMED", t0 + 30_000L)
        val line = ExitStageTiming7876.statusLine()
        assertTrue(line, line.contains("HARD_STOP[queue=avg4000/max4000ms"))
        assertTrue(line, line.contains("redispatchGap=avg14000ms(n=1)"))
        assertTrue(line, line.contains("attempts=avg2/max2"))
        assertTrue(line, line.contains("SELL_START->SELL_QUOTE_FAIL=avg6000/max6000ms"))
        assertTrue(line, line.contains("broadcast->confirm=avg11000/max11000ms"))
    }

    @Test fun minutesOldLaunchesHaveNoHourOfRelativeStrength() {
        assertEquals(0.0, relativeStrength7876(1_400.0, 0.1, 2.0), 1e-9)
        assertEquals(1000.0, relativeStrength7876(5_000.0, 3.0, 0.0), 1e-9)
        assertEquals(8.0, relativeStrength7876(10.0, 0.0, 2.0), 1e-9) // unknown age keeps the old read
    }
}
