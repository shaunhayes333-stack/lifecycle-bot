package com.lifecyclebot.engine

import com.lifecyclebot.engine.chart.Bar7950
import com.lifecyclebot.engine.chart.ChartSources7955
import com.lifecyclebot.engine.chart.ColourShape8807
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8807 — owner, with twenty of his charts and the candlestick cheat sheets: the green and red candles "repeat into a
 * consecutive pattern which can then be used against the shape"; "look at the top 50 performing sol coins of the last 12
 * months". The colour learner that existed read colours alone and was never traded. This one reads runs against the
 * shape, the named candles and the named chart shapes, scores each by what a trade made on charts it was not chosen from,
 * and trades none of them until its own paper record is positive.
 */
class Aate8807ColourShapeTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun bar(i: Int, o: Double, c: Double, h: Double = maxOf(o, c), l: Double = minOf(o, c), v: Double = 1.0) = Bar7950(i * 60_000L, o, h, l, c, v)
    private fun fromCloses(cs: List<Double>) = cs.mapIndexed { i, c -> bar(i, if (i == 0) c else cs[i - 1], c) }

    /** 22 small alternating candles, three reds, five greens into the high. */
    private fun runUp(scale: Double): List<Bar7950> {
        val out = ArrayList<Bar7950>()
        var p = 100.0
        fun add(d: Double) { out += bar(out.size, p * scale, (p + d) * scale); p += d }
        repeat(11) { add(1.0); add(-1.0) }
        repeat(3) { add(-2.0) }
        repeat(5) { add(2.0) }
        return out
    }

    @Test fun theRunsAreReadAgainstTheShapeAtAnyPrice() {
        val bars = runUp(1.0)
        assertEquals(ColourShape8807.LOOK, bars.size)
        assertEquals("g1r4g4:G|HU-" to "r4g4|HU", ColourShape8807.keys8807(bars, bars.size - 1))
        assertEquals("the same chart at a millionth of the price", "g1r4g4:G|HU-", ColourShape8807.keys8807(runUp(1e-6), 29)!!.first)
        assertNull("too few candles is no read", ColourShape8807.keys8807(bars, 20))
        assertTrue(ColourShape8807.allKeys8807(bars, 29).containsAll(listOf("g1r4g4:G|HU-", "r4g4|HU")))
    }

    @Test fun theNamedCandlesAreReadWithTheirWicks() {
        val flat = bar(0, 10.0, 10.0)
        val hammer = ColourShape8807.candles8807(listOf(flat, flat, bar(2, 10.0, 10.2, h = 10.25, l = 9.0)), 2, 0.5)
        assertTrue(hammer.contains("HAMMER")); assertFalse(hammer.contains("SHOOTING_STAR"))
        val engulf = ColourShape8807.candles8807(listOf(flat, bar(1, 10.0, 9.5), bar(2, 9.4, 10.3)), 2, 0.5)
        assertTrue(engulf.contains("ENGULF_BULL")); assertFalse(engulf.contains("ENGULF_BEAR"))
        assertTrue(ColourShape8807.candles8807(listOf(bar(0, 10.0, 10.4), bar(1, 10.4, 10.8), bar(2, 10.8, 11.3)), 2, 0.5).contains("THREE_SOLDIERS"))
    }

    @Test fun aDoubleBottomIsNamedAtTheCandleThatBreaksItsNeckline() {
        val closes = listOf(10.0, 9.5, 9.0, 8.5, 8.0, 7.5, 7.0, 6.5, 6.0, 6.25, 6.5, 6.75, 7.0, 7.25, 7.5, 7.75, 8.0,
            7.75, 7.5, 7.25, 7.0, 6.75, 6.5, 6.25, 6.1, 6.4, 6.7, 7.0, 7.3, 7.6, 7.9, 8.2)
        val bars = fromCloses(closes)
        val piv: List<ColourShape8807.Pivot8807> = ColourShape8807.pivots8807(bars, 0, bars.size - 1)
        assertEquals(listOf(8 to false, 16 to true, 24 to false), piv.map { it.i to it.high })
        assertTrue(ColourShape8807.shapes8807(bars, 31).contains("DOUBLE_BOTTOM"))
        assertFalse("under the neckline it is not broken yet", ColourShape8807.shapes8807(bars, 30).contains("DOUBLE_BOTTOM"))
        assertTrue(ColourShape8807.allKeys8807(bars, 31).contains("P:DOUBLE_BOTTOM"))
        assertTrue("the mirror image is a double top", ColourShape8807.shapes8807(fromCloses(closes.map { 16.0 - it }), 31).contains("DOUBLE_TOP"))
    }

    @Test fun aPatternIsScoredByWhatATradeMadeAndProvenOnlyInBothHalves() {
        val flat = List(30) { bar(it, 1.0, 1.0) }
        assertEquals(-23.5, ColourShape8807.outcome8807(flat + bar(30, 1.0, 0.79) + List(10) { bar(31 + it, 0.79, 0.79) }, 29)!!, 1e-9)
        assertEquals("ten flat candles is the stale exit", 1.5, ColourShape8807.outcome8807(flat + List(11) { bar(30 + it, 1.05, 1.05) }, 29)!!, 1e-9)
        assertNull("no future, no outcome", ColourShape8807.outcome8807(flat + List(5) { bar(30 + it, 1.0, 1.0) }, 29))
        fun half(n: Int, lo: Double, hi: Double) = ColourShape8807.Half(n, n / 2 * (lo + hi), n / 2 * (lo * lo + hi * hi))
        assertTrue(ColourShape8807.proven8807(half(12, 8.0, 12.0), half(12, 8.0, 12.0)))
        assertFalse("one half is too few charts", ColourShape8807.proven8807(half(12, 8.0, 12.0), half(10, 8.0, 12.0)))
        assertFalse("one half loses", ColourShape8807.proven8807(half(12, 8.0, 12.0), half(12, -12.0, -8.0)))
        assertFalse("inside two standard errors of zero", ColourShape8807.proven8807(half(12, -30.0, 34.0), half(12, 8.0, 12.0)))
    }

    @Test fun onlyMinuteChartsTeachAndNothingTradesUnproven() {
        ColourShape8807.resetForTest8807()
        val minute = runUp(1.0) + List(30) { bar(30 + it, 104.0, 104.0) }
        assertEquals(60_000L, ColourShape8807.spacingMs8807(minute))
        assertTrue(ColourShape8807.learnSeries8807("G8807|solana|poolA", minute) > 0)
        val daily = minute.mapIndexed { i, b -> Bar7950(i * 86_400_000L, b.o, b.h, b.l, b.c, b.v) }
        assertEquals("a daily chart is not read", 0, ColourShape8807.learnSeries8807("G|solana|poolB", daily))
        ColourShape8807.onBars8807("MintA", minute, 60L * 60_000L)
        ColourShape8807.onPrice8807("MintA", 104.0, 61L * 60_000L)
        assertNull("one chart proves nothing: no evidence", ColourShape8807.evidence8807("MintA", 61L * 60_000L))
        val line = ColourShape8807.statusLine8807()
        assertTrue(line, line.contains("charts=1") && line.contains("live SHADOW") && line.contains("armedFires=0"))
    }

    @Test fun theYearsBestSolanaCoinsAreDownloadedFirst() {
        val rows = JSONArray("""[{"name":"Slow","price_change_percentage_1y_in_currency":40.0},{"name":"Runner","price_change_percentage_1y_in_currency":9000.5},
            {"name":"NoHistory"},{"name":"Mid","price_change_percentage_1y_in_currency":310.0}]""")
        assertEquals(listOf("Runner", "Mid"), ColourShape8807.topByYear8807(rows, 2))
        val rr = ChartSources7955.RoundRobin7955<String>()
        rr.add("g-sol-new", "new pool"); rr.add("g-top-1y", "runner"); rr.prefer8807("g-top-1y")
        assertEquals("runner", rr.poll()); assertEquals("new pool", rr.poll())
        val sources = src("engine/chart/ChartSources7955.kt")
        assertTrue(sources.contains("category=solana-meme-coins") && sources.contains("price_change_percentage=1y"))
        assertTrue(sources.contains("""val gen = if (network == "solana" && tf == "minute" && agg <= 5) "G8807" else "G""""))
        assertTrue(sources.contains("ColourShape8807.learnSeries8807(job.key.substringBeforeLast('|'), out.bars)"))
        assertTrue(src("engine/chart/ChartReader7950.kt").contains("if (!fine) ColourShape8807.onBars8807(mint, bars, nowMs)"))
        assertTrue(src("engine/truth/CandidateAdmission8136.kt").contains("ColourShape8807.evidence8807(ts.mint, nowMs)"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("ColourShape8807.statusLine8807()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 14. Candle colours, named candles and chart shapes, graded as trades (8807)"))
    }
}
