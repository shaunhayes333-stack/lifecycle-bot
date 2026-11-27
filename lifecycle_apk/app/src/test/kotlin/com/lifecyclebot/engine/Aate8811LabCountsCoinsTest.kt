package com.lifecyclebot.engine

import com.lifecyclebot.engine.lab.StrategyLab8755
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8811 — owner: "what about building new entry setups and strategies from the data we have?" Mined offline on six days
 * (165,265 rows, 17,649 coins), nothing held on the days it was not chosen from once each coin counted once. The lab counted
 * every lane copy of a decision and every cycle's re-judgement as its own outcome; now a coin is one outcome.
 */
class Aate8811LabCountsCoinsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun row(t: Long, lane: String, mint: String, net: Double) = StrategyLab8755.Row(t, lane, "SRC", net, DoubleArray(0), mint)

    @Test fun aCoinIsOneOutcomeAtItsFirstRow() {
        val rows = listOf(row(1, "MOONSHOT", "A", 300.0), row(1, "EXPRESS", "A", 300.0), row(2, "SHITCOIN", "A", 280.0),
            row(3, "MOONSHOT", "B", -20.0), row(4, "CORE", "", -5.0), row(5, "CORE", "", -6.0))
        assertEquals("one runner counted once; mintless rows stand alone", listOf(300.0, -20.0, -5.0, -6.0), StrategyLab8755.perCoin8811(rows))
        val rec = StrategyLab8755.record8755(StrategyLab8755.perCoin8811(rows + (1..6).map { row(10L + it, "CORE", "C$it", -4.0) }))!!
        assertEquals(10, rec.n)
    }

    @Test fun theMinerAndTheGradeCountCoinsAndSearchSources() {
        val lab = src("engine/lab/StrategyLab8755.kt")
        assertTrue(lab.contains("o.optString(\"mint\", \"\"), o.optLong(\"horizonMs\")"))
        // V5.0.8831 (supersedes the copying count): the same per-coin count, on the scope's row indices (Aate8831 proves parity)
        assertTrue(lab.contains("val acc = accOver8831(disc, ix, idx) { val v = it.f[i]; v.isFinite() && v >= lo && v <= hi }"))
        assertTrue(lab.contains("Family.SCORE, Family.CELL -> record8755(perCoin8811(held.filter { matches8755(spec, it.lane, it.src, it.f) }))"))
        assertTrue(lab.contains("val scopes = lanes.map { it to \"ANY\" } + sources.map { \"ANY\" to it }"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 18. Mining entry setups from the datasets"))
    }
}
