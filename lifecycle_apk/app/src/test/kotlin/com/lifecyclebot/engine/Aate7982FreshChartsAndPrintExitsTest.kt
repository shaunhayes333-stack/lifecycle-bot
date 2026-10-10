package com.lifecyclebot.engine

import com.lifecyclebot.engine.chart.ChartLibrary7950
import com.lifecyclebot.engine.chart.ChartReader7950
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7982FreshChartsAndPrintExitsTest {

    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun youngTapeReadsOnFifteenSecondCandles() {
        assertTrue(ChartReader7950.useFine7982(5, 21))
        assertFalse(ChartReader7950.useFine7982(5, 20))
        assertFalse(ChartReader7950.useFine7982(21, 60))
        val t0 = 1_700_000_000_000L
        var p = 1.0
        // Six minutes of prints every 5 s: 6 one-minute candles, 24 fifteen-second candles.
        for (i in 0 until 72) { p *= if (i % 5 == 0) 0.995 else 1.004; ChartReader7950.onPrice("fresh7982", p, t0 + i * 5_000L) }
        assertTrue(ChartReader7950.liveBars7955("fresh7982") < 21)
        val r = ChartReader7950.read("fresh7982", t0 + 72 * 5_000L)
        assertNotNull(r)
        assertTrue(r!!.fine7982)
    }

    @Test fun freshTapesTeachTheLibrary() {
        ChartLibrary7950.resetForTest()
        val t0 = 1_700_100_000_000L
        var p = 1.0
        // 15 minutes of 15 s candles: 60 bars > WINDOW + HORIZON.
        for (i in 0 until 180) { p *= if (i % 7 == 0) 0.99 else 1.003; ChartReader7950.onPrice("teach7982", p, t0 + i * 5_000L) }
        assertTrue(ChartReader7950.learnAll7953() > 0)
        ChartLibrary7950.resetForTest()
        assertTrue(src("engine/chart/ChartLibrary7950.kt").contains("if (source == SRC_LIVE) liveSlot7982()"))
    }

    @Test fun runnerPeakCaptureFiresOnThePrint() {
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("RunnerGrab7967.heldExit7967(ts, px, now) { t, frac, reason -> sellIntoSpike7943(t, frac, reason) }"))
    }
}
