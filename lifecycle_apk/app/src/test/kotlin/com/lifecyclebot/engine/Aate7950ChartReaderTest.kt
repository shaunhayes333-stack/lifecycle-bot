package com.lifecyclebot.engine

import com.lifecyclebot.engine.chart.Bar7950
import com.lifecyclebot.engine.chart.ChartLibrary7950
import com.lifecyclebot.engine.chart.ChartLibraryBuilder7950
import com.lifecyclebot.engine.chart.ChartMotif7950
import com.lifecyclebot.engine.chart.ChartReader7950
import com.lifecyclebot.engine.chart.MotifRead7950
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TreeMap

/** V5.0.7950 — charts read scale-free against a library of what similar charts did next. */
class Aate7950ChartReaderTest {
    private val rnd = java.util.Random(7)

    /** A staircase: higher lows, green-dominant, then (future) a run. */
    private fun staircase(scale: Double, volScale: Double, n: Int = 60, thenUp: Boolean = true): List<Bar7950> {
        val out = ArrayList<Bar7950>()
        var p = 1.0
        for (i in 0 until n) {
            val drift = if (i < 40 || thenUp) 0.012 else -0.02
            val wig = (rnd.nextDouble() - 0.5) * 0.006
            val o = p
            val c = p * (1.0 + drift + wig)
            val h = maxOf(o, c) * 1.004
            val l = minOf(o, c) * 0.996
            out += Bar7950(i * 60_000L, o * scale, h * scale, l * scale, c * scale, (100.0 + i) * volScale, 60.0 * volScale)
            p = c
        }
        return out
    }

    /** Lower highs bleeding down. */
    private fun bleed(n: Int = 60): List<Bar7950> {
        val out = ArrayList<Bar7950>()
        var p = 1.0
        for (i in 0 until n) {
            val o = p
            val c = p * (1.0 - 0.012 + (rnd.nextDouble() - 0.5) * 0.006)
            out += Bar7950(i * 60_000L, o, maxOf(o, c) * 1.004, minOf(o, c) * 0.996, c, 100.0, 30.0)
            p = c
        }
        return out
    }

    @Test fun fingerprintsAreScaleFree() {
        val a = staircase(1.0, 1.0)
        rnd.setSeed(7)
        val small = staircase(1.0, 1.0)
        rnd.setSeed(7)
        val big = staircase(65_000.0, 4_000.0)
        val fa = ChartMotif7950.encode(small, 30)
        val fb = ChartMotif7950.encode(big, 30)
        assertNotNull(fa); assertNotNull(fb)
        assertEquals(ChartMotif7950.DIM, fa!!.size)
        assertTrue(ChartMotif7950.dist2(fa, fb!!) < 1e-3f)
        assertNull(ChartMotif7950.encode(a, 5))
    }

    @Test fun outcomesReadTheFuture() {
        assertTrue(ChartMotif7950.outcome(staircase(1.0, 1.0), 30)!!.hitUpFirst)
        assertFalse(ChartMotif7950.outcome(bleed(), 30)!!.hitUpFirst)
        assertNull(ChartMotif7950.outcome(bleed(), 55))
    }

    @Test fun theLibraryReadsAStaircaseAsABuyAndABleedAsNot() {
        ChartLibrary7950.resetForTest()
        repeat(150) { ChartLibrary7950.ingestSeries(staircase(1.0 + it, 1.0 + it % 7), ChartLibrary7950.SRC_SOL_MEME, maxWindows = 10) }
        repeat(150) { ChartLibrary7950.ingestSeries(bleed(), ChartLibrary7950.SRC_CRYPTO, maxWindows = 10) }
        assertTrue(ChartLibrary7950.size() > 2_000)
        val up = ChartLibrary7950.query(ChartMotif7950.encode(staircase(3.0, 2.0), 30)!!)!!
        assertTrue(up.pUp > 0.8)
        assertTrue(up.lift > 0.2)
        assertTrue(up.meanDist >= 0.0 && up.meanDist.isFinite())
        val dn = ChartLibrary7950.query(ChartMotif7950.encode(bleed(), 30)!!)!!
        assertTrue(dn.pUp < 0.2)
        assertTrue(ChartReader7950.buySignal(ChartReader7950.Read(up, 40, 0.7, false, 0L)))
        assertFalse(ChartReader7950.buySignal(ChartReader7950.Read(up, 40, 0.3, false, 0L)))   // sellers dominate the tape now
        assertFalse(ChartReader7950.buySignal(ChartReader7950.Read(up, 40, 0.7, true, 0L)))    // dev sold
        assertFalse(ChartReader7950.buySignal(ChartReader7950.Read(dn, 40, 0.7, false, 0L)))
        ChartLibrary7950.resetForTest()
    }

    @Test fun exitOnTopsAndDevSells() {
        val top = MotifRead7950(40, 0.10, -0.25, 3.0, -12.0, -6.0, 1.0)
        assertEquals("TOP_MOTIF", ChartReader7950.exitSignal(ChartReader7950.Read(top, 40, 0.4, false, 0L)))
        val run = MotifRead7950(40, 0.6, 0.25, 20.0, -5.0, 8.0, 1.0)
        assertNull(ChartReader7950.exitSignal(ChartReader7950.Read(run, 40, 0.6, false, 0L)))
        assertEquals("DEV_SOLD", ChartReader7950.exitSignal(ChartReader7950.Read(run, 40, 0.6, true, 0L)))
    }

    @Test fun liveTapeFillsQuietMinutes() {
        val tape = TreeMap<Long, DoubleArray>()
        tape[0L] = doubleArrayOf(1.0, 1.2, 0.9, 1.1, 5.0, 1.0, 0.0)
        tape[3L] = doubleArrayOf(1.1, 1.3, 1.0, 1.25, 2.0, 2.0, 0.0)
        val bars = ChartReader7950.toBars(tape)
        assertEquals(4, bars.size)
        assertEquals(1.1, bars[1].c, 1e-12)
        assertEquals(0.0, bars[2].v, 1e-12)
        assertEquals(5.0, bars[0].buyV, 1e-12)
    }

    @Test fun providerCandlesParse() {
        val binance = JSONArray("""[[1700000000000,"1.0","1.2","0.9","1.1","100.0",1700000059999,"110.0",50,"60.0","66.0","0"]]""")
        val b = ChartLibraryBuilder7950.parseBinance(binance)
        assertEquals(1, b.size); assertEquals(60.0, b[0].buyV, 1e-9); assertEquals(1.1, b[0].c, 1e-12)
        val gecko = JSONObject("""{"data":{"attributes":{"ohlcv_list":[[1700000120,1.2,1.3,1.1,1.25,9.0],[1700000060,1.0,1.2,0.9,1.1,5.0]]}}}""")
        val g = ChartLibraryBuilder7950.parseGecko(gecko)
        assertEquals(2, g.size); assertEquals(1700000060000L, g[0].t); assertEquals(1.25, g[1].c, 1e-12)
    }
}
