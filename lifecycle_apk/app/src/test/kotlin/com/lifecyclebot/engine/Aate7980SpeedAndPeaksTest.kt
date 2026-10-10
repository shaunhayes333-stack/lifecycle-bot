package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7980SpeedAndPeaksTest {

    @Test fun fillsReturnAtConfirmedNotFinalized() {
        assertTrue(com.lifecyclebot.network.confirmedEnough7980("confirmed"))
        assertTrue(com.lifecyclebot.network.confirmedEnough7980("finalized"))
        assertFalse(com.lifecyclebot.network.confirmedEnough7980("processed"))
        assertFalse(com.lifecyclebot.network.confirmedEnough7980(""))
    }

    @Test fun peakGivebackOnlyAfterABigRun() {
        assertTrue(RunnerGrab7967.peakGiveback7980(400.0, 220.0))    // 5x peak, now 3.2x: 36% off the peak
        assertFalse(RunnerGrab7967.peakGiveback7980(400.0, 260.0))   // 3.6x: 28% off
        assertFalse(RunnerGrab7967.peakGiveback7980(150.0, 10.0))    // never ran +200%
        assertTrue(RunnerGrab7967.peakGiveback7980(1000.0, 600.0))   // 11x -> 7x
    }

    @Test fun profitCapturesLandFast() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(src.contains("val fastLand7980 = isDrainExit || com.lifecyclebot.engine.sell.CloseLease.urgentProfitCapture7965(reason)"))
        assertTrue(com.lifecyclebot.engine.sell.CloseLease.urgentProfitCapture7965("RUNNER_PEAK_CAPTURE_7980_400PK_220PCT"))
    }
}
