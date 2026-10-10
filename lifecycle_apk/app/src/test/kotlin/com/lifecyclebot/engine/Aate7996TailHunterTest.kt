package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.TailHunter7996
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7996TailHunterTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun loserStopsAtTheGap() {
        val l = TailHunter7996.Ladder7996(1.0)
        l.onPrice(0.95); l.onPrice(0.70)                 // gapped through the -15% stop
        assertTrue(l.done)
        assertEquals(-33.0, l.resultPct(), 1e-9)         // -30% fill + 3% cost
    }

    @Test fun bumStyleRunnerPaysTheBook() {
        // BUM: 329x. Half off at 2x, 35% of the rest at 5x and 11x, the rest out 35% below the peak.
        val l = TailHunter7996.Ladder7996(1.0)
        for (x in listOf(1.5, 2.0, 5.0, 11.0, 50.0, 200.0, 329.0, 300.0, 210.0)) l.onPrice(x)
        assertTrue(l.done)
        assertTrue(l.peakX >= 329.0)
        // 0.5x2 + 0.175x5 + 0.11375x11 + 0.21125x210 = 47.5x the stake: one ticket pays ~145 losing ones.
        assertTrue(l.resultPct() > 4_500.0)
    }

    @Test fun wiredIntoGatesFeedAndSizing() {
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("TailHunter7996.ticket7996(ts, l, nowMs)"))
        assertTrue(src("engine/chart/ChartReader7950.kt").contains("TailHunter7996.onPrice7996(mint, priceUsd, atMs)"))
        assertTrue(src("engine/truth/TraderSizingBridge6444.kt").contains("TailHunter7996.sizeMult7996(mintForSeal,"))
        assertTrue(src("engine/SpikeCapture7943.kt").contains("TailHunter7996.goodMayhemTail7996(ts, nowMs)"))
        assertTrue(src("engine/truth/LearningResetSweep7781.kt").contains("\"tail_hunter_7996.txt\""))
    }
}
