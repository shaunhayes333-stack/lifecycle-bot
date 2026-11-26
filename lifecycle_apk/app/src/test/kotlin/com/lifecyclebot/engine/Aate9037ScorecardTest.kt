package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EconomicScorecard9037
import com.lifecyclebot.engine.truth.EconomicScorecard9037.Row9037
import com.lifecyclebot.engine.truth.LiveRiskPolicy7807
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.9037 — the owner's specification, section 13: the report opens with the money the wallet kept. */
class Aate9037ScorecardTest {
    @Test fun theBandsAreTheOwnersBands() {
        assertEquals(listOf("loss", "0-19", "20-49", "50-99", "100-199", "200-499", "500-999", "1000+"), EconomicScorecard9037.BANDS_9037)
        assertEquals(0, EconomicScorecard9037.band9037(-0.1))
        assertEquals(0, EconomicScorecard9037.band9037(Double.NaN))
        assertEquals(1, EconomicScorecard9037.band9037(0.0))
        assertEquals(2, EconomicScorecard9037.band9037(20.0))
        assertEquals(3, EconomicScorecard9037.band9037(99.9))
        assertEquals(4, EconomicScorecard9037.band9037(100.0))
        assertEquals(5, EconomicScorecard9037.band9037(385.0))
        assertEquals(6, EconomicScorecard9037.band9037(999.0))
        assertEquals(7, EconomicScorecard9037.band9037(1000.0))
    }

    @Test fun aCardIsTheClosesAsTheyWere() {
        val rows = listOf(
            Row9037(1L, 0.10, 385.0, 400.0, "LIVE_FINALIZED"),
            Row9037(2L, -0.01, -12.0, 3.0, "LIVE_FINALIZED"),
            Row9037(3L, -0.03, -30.0, Double.NaN, "SIGNATURE_ONLY_UNPROVED"),
            Row9037(4L, 0.02, 25.0, 200.0, "LIVE_FINALIZED"),
        )
        val c: EconomicScorecard9037.Card9037 = EconomicScorecard9037.card9037(rows)
        assertEquals(4, c.n)
        assertEquals(3, c.finalized)
        assertEquals(0.08, c.netSol, 1e-12)
        assertEquals(0.12 / 0.04, c.profitFactor, 1e-9)
        assertEquals(0.02, c.expectancySol, 1e-12)
        assertEquals((385.0 - 12.0 - 30.0 + 25.0) / 4.0, c.meanPct, 1e-9)
        assertEquals(listOf(2, 0, 1, 0, 0, 1, 0, 0), c.bands.toList())
        // runs that reached +20 %: 400 (banked 385 = 96 %) and 200 (banked 25 = 12.5 %); the median of two is their mean
        assertEquals(2, c.runs20)
        assertEquals((385.0 / 400.0 + 25.0 / 200.0) / 2.0, c.medianShareOfRun, 1e-9)
        // superseded by V5.0.9040 — the card line also prints the chain-finalised closes' own net (0.10 - 0.01 + 0.02)
        val line = EconomicScorecard9037.cardLine9037("today", c)
        assertTrue(line, line.contains("closes=4 (chain-finalised 3, net +0.1100)") && line.contains("pf=3.00") && line.contains("200-499:1"))
        assertEquals("today closes=0", EconomicScorecard9037.cardLine9037("today", EconomicScorecard9037.card9037(emptyList())))
        assertTrue(EconomicScorecard9037.card9037(listOf(Row9037(1L, 0.01, 5.0, Double.NaN, ""))).profitFactor.isInfinite())
    }

    @Test fun growthIsTradingNotDeposits() {
        assertEquals(10.0, EconomicScorecard9037.tradingGrowthPct9037(1.0, 1.1, 0.0), 1e-9)
        assertEquals("a 0.5 SOL deposit is not growth", 10.0, EconomicScorecard9037.tradingGrowthPct9037(1.0, 1.6, 0.5), 1e-9)
        assertEquals(-20.0, EconomicScorecard9037.tradingGrowthPct9037(1.0, 0.5, -0.3), 1e-9)
        assertTrue(EconomicScorecard9037.tradingGrowthPct9037(0.0, 1.0, 0.0).isNaN())
        assertEquals(86_400_000L, EconomicScorecard9037.utcDayStartMs9037(86_400_000L + 5L))
        assertEquals(3, LiveRiskPolicy7807.dayEquity9037().toList().size)
    }

    @Test fun theReportOpensWithIt() {
        val brief = File("src/main/kotlin/com/lifecyclebot/engine/DiagnosisBrief8438.kt").readText()
        val at = brief.indexOf("EconomicScorecard9037.block9037(nowMs)")
        assertTrue(at > brief.indexOf("section(sb, \"A RUN\")") && at < brief.indexOf("NetworkVerdict8894.hosts8894()"))
        val block = EconomicScorecard9037.block9037(1_795_700_000_000L)
        assertTrue(block, block.contains("money9037 equity:") && block.contains("money9037 today") && block.contains("money9037 missed runners"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## §222 — V5.0.9037: the money first"))
        assertTrue(File("../../.github/workflows/build.yml").readText().contains("--tests '*Aate9037*'"))
    }
}
