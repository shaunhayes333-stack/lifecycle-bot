package com.lifecyclebot.engine

import com.lifecyclebot.engine.chart.ColourShape8807
import com.lifecyclebot.engine.chart.OwnBars8816
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** V5.0.8816 — every witnessed print becomes a one-minute chart; each coin is taught to the setup learner once. */
class Aate8816OwnBarsTest {
    private val min = 60_000L
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Before fun setUp() { OwnBars8816.resetForTest8816(); ColourShape8807.resetForTest8807(); OwnBars8816.solUsdForTest8816 = 100.0 }
    @After fun tearDown() { OwnBars8816.solUsdForTest8816 = 0.0 }

    @Test fun printsBecomeMinuteCandlesWithFlatGaps() {
        val t = OwnBars8816.Tape(1_000L)
        assertTrue(t.add(1_000L, 1.0, 0.5)); assertTrue(t.add(1_000L, 1.2, 0.5)); assertTrue(t.add(1_000L, 0.9, 1.0))
        assertTrue(t.add(1_003L, 1.1, 2.0))                       // two quiet minutes in between
        val b = t.bars()
        assertEquals(4, b.size)
        assertEquals(listOf(1.0, 1.2, 0.9, 0.9, 2.0), listOf(b[0].o, b[0].h, b[0].l, b[0].c, b[0].v))
        assertEquals("a quiet minute is a flat candle at the last close", listOf(0.9, 0.9, 0.9, 0.9, 0.0), listOf(b[1].o, b[1].h, b[1].l, b[1].c, b[1].v))
        assertEquals(listOf(0.9, 1.1, 0.9, 1.1), listOf(b[3].o, b[3].h, b[3].l, b[3].c))
        assertEquals(1_003L * min, b[3].t)
        assertTrue("a late print for a closed minute is ignored", t.add(999L, 5.0, 1.0))
        assertEquals(4, t.bars().size)
    }

    @Test fun aQuietCoinIsHandedToTheLearnerOnceAndNeverRecordedAgain() {
        val start = 10_000L * min
        var px = 0.001
        for (i in 0 until 60) { px *= if (i % 4 == 3) 0.97 else 1.02; OwnBars8816.onPrint8816("CoinA", start + i * min, px, 0.3, start + i * min) }
        OwnBars8816.onPrint8816("ShortB", start, 0.002, 0.1, start)
        assertEquals(2, OwnBars8816.recordingForTest8816())
        OwnBars8816.sweep8816(start + 60 * min + OwnBars8816.IDLE_MS)
        assertEquals(0, OwnBars8816.recordingForTest8816())
        assertEquals("one chart scored", 1, ColourShape8807.drainTapesForTest8810())
        val line = OwnBars8816.statusLine8816()
        assertTrue(line, line.contains("handedToLearner=1 tooShort=1"))
        // the same coin printing again is not a second chart
        OwnBars8816.onPrint8816("CoinA", start + 200 * min, px, 0.3, start + 200 * min)
        assertEquals(0, OwnBars8816.recordingForTest8816())
        // and the learner refuses a second chart of a coin it was already given (the chart reader's retired tape)
        val bars = (0 until 60).map { com.lifecyclebot.engine.chart.Bar7950(it * min, 1.0 + it, 2.0 + it, 0.5 + it, 1.5 + it, 1.0) }
        ColourShape8807.learnTape8810("CoinA", bars)
        assertEquals(0, ColourShape8807.drainTapesForTest8810())
        assertTrue(ColourShape8807.statusLine8807().contains("duplicate8816=1"))
    }

    @Test fun aFullTapeIsHandedOverAtItsCap() {
        val start = 20_000L * min
        for (i in 0..OwnBars8816.MAX_BARS) OwnBars8816.onPrint8816("Long", start + i * min, 1.0 + (i % 7) * 0.01, 0.1, start + i * min)
        assertEquals(0, OwnBars8816.recordingForTest8816())
        assertEquals(1, ColourShape8807.drainTapesForTest8810())
    }

    @Test fun hashesAreStableAndDistinct() {
        assertEquals(OwnBars8816.hash8816("So11111111111111111111111111111111111111112"), OwnBars8816.hash8816("So11111111111111111111111111111111111111112"))
        assertNotEquals(OwnBars8816.hash8816("abc"), OwnBars8816.hash8816("abd"))
    }

    @Test fun everyWitnessedPrintFeedsTheRecorder() {
        val tape = src("engine/truth/TradeTape8100.kt")
        val ev = tape.substringAfter("fun launchEvidence8750(").substringBefore("return v")
        assertTrue(ev.contains("com.lifecyclebot.engine.chart.OwnBars8816.onPrint8816(mint, eventMs, priceSol, sol, recvMs)"))
        assertTrue(src("engine/chart/ColourShape8807.kt").contains("ownBars8816["))
    }
}
