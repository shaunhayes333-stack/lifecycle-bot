package com.lifecyclebot.engine

import com.lifecyclebot.xstocks.XsUnderlying8930
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TreeMap

/**
 * V5.0.8930 — an xStock's panel history before its own pool (GeckoTerminal free: ~6 months) is its share's adjusted daily
 * closes on the token's level, only when the token tracks the share; and the hive is counted before anything is built on it.
 */
class Aate8930SharesAndHiveTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun bar(c: Double) = doubleArrayOf(c, c, c, c, 1_000.0)

    @Test fun theShareFillsTheDaysBeforeTheTokenOnlyWhenTheTokenTracksIt() {
        assertEquals("BRK-B", XsUnderlying8930.ticker8930("BRKB"))
        assertEquals("BRK-B", XsUnderlying8930.ticker8930("brk.b"))
        assertEquals("AAPL", XsUnderlying8930.ticker8930("AAPL"))
        val under = TreeMap<Long, DoubleArray>()
        for (d in 0L until 300L) if (d % 7 != 5L && d % 7 != 6L) under[d] = bar(100.0 + d)   // weekdays only
        val own = TreeMap<Long, DoubleArray>()
        for (d in 200L until 300L) own[d] = bar((100.0 + d) * 2.0)                            // the token at twice the share
        val (bars, why) = XsUnderlying8930.splice8930(own, under, 100L)
        assertTrue(why, why.startsWith("SPLICED_"))
        assertEquals("a weekday before the pool: the share on the token's level", 2.0 * 200.0, bars[100L]!![3], 1e-9)
        assertEquals("the stored token day is its own", own[250L]!![3], bars[250L]!![3], 0.0)
        assertTrue("a spliced day carries no volume", bars[100L]!![4].isNaN())
        // day 103 = 103 % 7 == 5: a weekend: the last close (day 102) stands
        assertEquals(2.0 * 202.0, bars[103L]!![3], 1e-9)
        assertEquals("nothing before fromDay", null, bars[99L])
        // a token that does not track its share is not spliced
        val drifting = TreeMap<Long, DoubleArray>()
        for (d in 200L until 300L) drifting[d] = bar((100.0 + d) * (if (d % 2 == 0L) 2.0 else 2.2))
        val (b2, w2) = XsUnderlying8930.splice8930(drifting, under, 100L)
        assertTrue(w2, w2.startsWith("NOT_TRACKING"))
        assertSame(drifting, b2)
        val short = TreeMap<Long, DoubleArray>().also { m -> for (d in 280L until 300L) m[d] = bar(1.0) }
        assertTrue(XsUnderlying8930.splice8930(short, under, 100L).second.startsWith("OVERLAP_"))
        assertEquals("OWN_COVERS", XsUnderlying8930.splice8930(own, under, 250L).second)
        assertEquals("NO_UNDERLYING", XsUnderlying8930.splice8930(own, TreeMap(), 100L).second)
    }

    @Test fun yahooBarsParseOnTheAdjustedClose() {
        val body = """{"chart":{"result":[{"timestamp":[86400,172800,259200],"indicators":{"quote":[{"open":[10,11,null],"high":[12,13,14],
            "low":[9,10,11],"close":[10,12,13]}],"adjclose":[{"adjclose":[5,6,6.5]}]}}]}}"""
        val b = XsUnderlying8930.parse8930(body)
        assertEquals(3, b.size)
        assertEquals(5.0, b[1L]!![3], 1e-9)
        assertEquals("open scaled by adjclose / close", 5.0, b[1L]!![0], 1e-9)
        assertTrue(b[3L]!![0].isNaN())
        assertTrue(XsUnderlying8930.parse8930("{}").isEmpty())
    }

    @Test fun theDeskGradesOnTheSplicedCopyAndTheHiveIsCounted() {
        val desk = src("xstocks/XsDesk.kt")
        assertTrue("only answered instruments with enough listed history enter the spliced panel",
            desk.contains("if (answered && h.bars.size >= MIN_LISTED_DAYS) ready.add(panelHistory8930(s, h, today))"))
        assertTrue("the stored history is never changed", desk.contains("return HlHistory(h.coin).also { it.bars.putAll(bars) }"))
        assertTrue(src("collective/CollectiveLearning.kt").contains("try { hiveCensus8930() } catch (_: Exception) {}"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("CollectiveLearning.hiveCensusLine8930()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 118. Shares before their tokens; the hive counted (8930)"))
    }
}
