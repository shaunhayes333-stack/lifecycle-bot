package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LanePocket8934
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8934 — each lane's own in-band cells at its designed hold, mined from the research rows (one coin once, 30+ coins,
 * both halves positive, ex-top-3 mean over a deflated margin), carried as market-cell evidence for meme lanes; the 4 h / 24 h
 * re-reads ask only Solana ids and the keyed Jupiter host first.
 */
class Aate8934LanePocketTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val other = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2h"

    @Before fun setUp() = LanePocket8934.resetForTest8934()
    @After fun tearDown() = LanePocket8934.resetForTest8934()

    @Test fun featuresAndCellsAreTheResearchRowsOwn() {
        val f = LanePocket8934.features8934(other + "r", "BASE_START", "LAUNCH_CONTINUATION", 30.0, 12.0, 25_000.0, 15_000.0)
        assertEquals(listOf("origin=OTHER", "stage=BASE_START", "setup=LAUNCH_CONTINUATION", "v3=23-35", "pred=10+", "liq=20-100k", "mcap=10-20k"), f)
        assertEquals("origin=PUMP", LanePocket8934.features8934("abcpump", "", "", Double.NaN, Double.NaN, Double.NaN, Double.NaN)[0])
        assertEquals("v3=NA", LanePocket8934.features8934("x", "", "", Double.NaN, 1.0, 1.0, 1.0)[3])
        assertEquals("7 alone and 21 pairs", 28, LanePocket8934.cellsOf8934(f).size)
        assertEquals(4L * 3_600_000L, LanePocket8934.horizon8934("BLUECHIP"))
        assertEquals("every other lane holds an hour or less", 3_600_000L, LanePocket8934.horizon8934("QUALITY"))
        assertTrue(LanePocket8934.inBand8934(80_000.0, 75_000.0, 1_000_000.0))
        assertFalse(LanePocket8934.inBand8934(11_000.0, 75_000.0, 1_000_000.0))
    }

    private fun coin(i: Int, pred: Double, net: Double, atMs: Long) =
        LanePocket8934.Coin8934(atMs, LanePocket8934.features8934("m$i", "BASE_START", "NONE", 30.0, pred, 25_000.0, 15_000.0), net)

    @Test fun aCellProvesOnlyOnCoinsBothHalvesAndTheDeflatedMargin() {
        val coins = ArrayList<LanePocket8934.Coin8934>()
        for (i in 0 until 40) coins += coin(i, 12.0, 30.0 + (i % 5), i * 3_000L)           // pays in both halves (0 .. 117 s)
        for (i in 40 until 120) coins += coin(i, -3.0, -10.0 - (i % 7), (i - 40) * 1_500L) // loses
        val cells = LanePocket8934.mine8934(coins, 60_000L)
        assertTrue(cells.any { it.conds == listOf("pred=10+") && it.n == 40 })
        assertFalse("the losing pred cell never proves", cells.any { it.conds.contains("pred=<-5") || it.conds.contains("pred=-5-0") })
        assertTrue(cells.all { it.exTop3 > it.z * it.se && it.z >= 2.0 })
        // 29 coins: not proven
        assertTrue(LanePocket8934.mine8934(coins.take(29), 45_000L).isEmpty())
        // a pocket that paid only in its first half is not proven
        val firstHalfOnly = (0 until 40).map { coin(it, 12.0, if (it < 20) 60.0 else -20.0, it * 1_000L) }
        assertFalse(LanePocket8934.mine8934(firstHalfOnly, 20_000L).any { it.conds == listOf("pred=10+") })
    }

    @Test fun aRowIsFiledOnlyAtItsLanesHoldAndInsideItsBand() {
        fun row(lane: String, h: Long, mcap: Double, mint: String) = JSONObject().put("t", 1L).put("horizonMs", h).put("mint", mint)
            .put("lane", lane).put("stage", "BASE_START").put("setup", "NONE").put("v3", 30.0).put("pred", 12.0).put("liq", 25_000.0)
            .put("mcap", mcap).put("net", 20.0)
        LanePocket8934.fileForTest8934(row("QUALITY", 3_600_000L, 11_000.0, "a"))        // under QUALITY's band
        LanePocket8934.fileForTest8934(row("QUALITY", 3_600_000L, 200_000.0, "b"))       // in band
        LanePocket8934.fileForTest8934(row("QUALITY", 3_600_000L, 200_000.0, "b"))       // the same coin again
        LanePocket8934.fileForTest8934(row("BLUECHIP", 3_600_000L, 3_000_000.0, "c"))    // BLUECHIP reads at 4 h, not 1 h
        LanePocket8934.fileForTest8934(row("BLUECHIP", 14_400_000L, 3_000_000.0, "c"))
        LanePocket8934.fileForTest8934(row("LPSTATE8725_PENDING", 3_600_000L, 15_000.0, "d"))
        val line = LanePocket8934.statusLine8934()
        assertTrue(line, line.contains("coins{BLUECHIP=1,QUALITY=1}"))
    }

    @Test fun theLanePocketIsWiredAndTheReReadsAreFixed() {
        val adm = src("engine/truth/CandidateAdmission8136.kt")
        val providers = adm.substringAfter("marketCell8723 = selectMarketEvidence8961(", "").substringBefore("regimeCold8546 =")
        val base = providers.indexOf("BasePocket8904.evidence8904(ts, lane, nowMs)")
        val lane = providers.indexOf("LanePocket8934.evidence8934(ts, lane, edge8874?.predPct, nowMs)")
        assertTrue("the own-lane pocket follows the base pocket and precedes the LLM bands",
            base >= 0 && lane > base && lane < providers.indexOf("LlmBands8875.evidence8875("))
        assertTrue(src("engine/truth/LaneAuthority8577.kt").contains("LanePocket8934.KIND_8934,"))
        val rows = src("engine/truth/ResearchRows8877.kt")
        val sanitize = rows.indexOf("val safeLine = try { ResearchEvidence9043.annotate9043(line) }")
        val file = rows.indexOf("LanePocket8934.learnLine8934(safeLine)")
        assertTrue("the lane pocket receives the shared sanitized row after its provenance is recorded", sanitize >= 0 && file > sanitize)
        assertFalse("the raw row must not bypass quality handling", rows.contains("LanePocket8934.learnLine8934(line)"))
        val f = src("engine/truth/ResearchFollow8884.kt")
        assertTrue(f.contains("JupiterPriceIds8418.ids8418(mints, Int.MAX_VALUE)"))
        assertTrue(f.contains("read8934(\"https://api.jup.ag/price/v3?ids=\" + ids.joinToString(\",\"), \"jupiter_price_keyed\", key)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 122. Each lane its own pocket (8934)"))
    }
}
