package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.truth.AgreementPocket8874
import com.lifecyclebot.engine.truth.ExploreLive8733
import com.lifecyclebot.engine.truth.JournalEconomicReplay6619
import com.lifecyclebot.engine.truth.PairFlow8169
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.8929 — with Helius spent and no trade tape: the agreement pocket proves again from the research rows a learning reset
 * leaves, the careful path reads the pool's own DexScreener counts when no live flow exists, a journal receipt rebuild
 * must sell exactly the open lot, a rebuilt row is never graded evidence, and an xStock quote is asked again on the
 * trade-grade client.
 */
class Aate8929TapeDownTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val other = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr"

    @Before fun setUp() = AgreementPocket8874.resetResearchForTest8929()
    @After fun tearDown() = AgreementPocket8874.resetResearchForTest8929()

    private fun row(mint: String = other, lane: String = "MOONSHOT", mcap: Double = 15_000.0, v3: Double? = 30.0,
                    pred: Double? = 12.0, net: Double = 40.0, horizon: Long = 3_600_000L) = JSONObject()
        .put("t", 1_000L).put("horizonMs", horizon).put("mint", mint).put("lane", lane).put("mcap", mcap)
        .put("v3", v3 ?: JSONObject.NULL).put("pred", pred ?: JSONObject.NULL).put("net", net)

    @Test fun theResearchRowsProveThePocketAReset() {
        assertNotNull(AgreementPocket8874.researchRow8929(row()))
        assertNull("pump.fun mint", AgreementPocket8874.researchRow8929(row(mint = "8KdKRqDWzDsrSrxo4mGA8e48KbG2JK9XgnVEFVXfpump")))
        assertNull("forecast under +10 %", AgreementPocket8874.researchRow8929(row(pred = 9.0)))
        assertNull("no forecast", AgreementPocket8874.researchRow8929(row(pred = null)))
        assertNull("over the \$20k ceiling", AgreementPocket8874.researchRow8929(row(mcap = 20_000.0)))
        assertNull("the 4 h follow-up is not the hour read", AgreementPocket8874.researchRow8929(row(horizon = 14_400_000L)))
        assertNull("a crypto lane", AgreementPocket8874.researchRow8929(row(lane = "CRYPTO_ALT")))
        // each coin once, at its worst meme-lane read within 15 minutes of its first
        AgreementPocket8874.addResearchForTest8929("a", 40.0, 0L)
        AgreementPocket8874.addResearchForTest8929("a", -20.0, 60_000L)
        AgreementPocket8874.addResearchForTest8929("a", -90.0, 16L * 60_000L)
        assertEquals(1.0, AgreementPocket8874.researchRecord8929()[0], 0.0)
        assertEquals(-20.0, AgreementPocket8874.researchRecord8929()[1], 1e-9)
        AgreementPocket8874.resetResearchForTest8929()
        for (i in 0 until 29) AgreementPocket8874.addResearchForTest8929("c$i", 30.0 + (i % 5), i * 1_000_000L)
        assertNull("29 coins: not yet", AgreementPocket8874.coinEvidenceOf8895("MOONSHOT", null, AgreementPocket8874.researchRecord8929()))
        AgreementPocket8874.addResearchForTest8929("c29", 33.0, 99_000_000L)
        assertNotNull("30 coins paying", AgreementPocket8874.coinEvidenceOf8895("MOONSHOT", null, AgreementPocket8874.researchRecord8929()))
        assertNull("not a meme lane", AgreementPocket8874.coinEvidenceOf8895("CRYPTO_ALT", null, AgreementPocket8874.researchRecord8929()))
        val s = src("engine/truth/AgreementPocket8874.kt")
        assertTrue(s.contains("coinEvidenceOf8895(l, laneRec, research) != null"))
        assertTrue(src("engine/truth/ResearchRows8877.kt").contains("AgreementPocket8874.learnLine8929(line)"))
        assertTrue("full-size admission unchanged", s.contains("else (evidenceOf8874(key, recordOf(key)) ?: coinEvidence8895(raw.lane, recordOf(key)))"))
    }

    private fun flow(bM5: Int, sM5: Int, bH1: Int, sH1: Int, chg5m: Double = 3.0, atMs: Long = 1_000_000L) =
        PairFlow8169.Flow8169(bM5, sM5, bH1, sH1, chg5m, 10.0, 20_000.0, atMs)

    @Test fun withNoTapeThePoolsOwnCountsDecide() {
        assertEquals(Pair(4.0, 1.0), ExploreLive8733.rates8929(20, 75, 60.0))
        assertEquals("a 30-minute pool: the earlier 25 minutes", Pair(4.0, 2.0), ExploreLive8733.rates8929(20, 70, 30.0))
        assertNull("under 10 minutes old: no earlier rate", ExploreLive8733.rates8929(20, 20, 9.0))
        assertNull("h1 under m5 is a misread", ExploreLive8733.rates8929(20, 10, 30.0))
        val now = 1_000_000L
        // buys 30 in 5 min vs 45 over the earlier 55: rising; buy share 75 %; price up; holders up
        val rising = flow(30, 10, 75, 30)
        assertEquals(Pair(5, 0), ExploreLive8733.pairSigns8929(rising, 60.0, 2.0))
        assertTrue(ExploreLive8733.pairFlowPasses8929(rising, 60.0, 2.0, now))
        assertFalse("holders not growing", ExploreLive8733.pairFlowPasses8929(rising, 60.0, 0.0, now))
        assertFalse("holders unread", ExploreLive8733.pairFlowPasses8929(rising, 60.0, null, now))
        assertFalse("a stale answer", ExploreLive8733.pairFlowPasses8929(rising, 60.0, 2.0, now + ExploreLive8733.PAIR_FRESH_MS_8929 + 1L))
        assertFalse("too young to read a rise", ExploreLive8733.pairFlowPasses8929(flow(30, 10, 30, 10), 6.0, 2.0, now))
        // buys falling against the earlier rate, selling heavier, price down: refused
        val fading = flow(3, 12, 120, 60, chg5m = -4.0)
        assertFalse(ExploreLive8733.pairFlowPasses8929(fading, 60.0, 2.0, now))
        assertNull("counts the pair did not carry", ExploreLive8733.pairSigns8929(flow(-1, 3, 10, 10), 60.0, 2.0))
        val s = src("engine/truth/ExploreLive8733.kt")
        assertTrue(s.contains("if (flow != null) {\n            if (!flowPasses8928(flow, holders))"))
        assertTrue(s.contains("val created = pair?.pairCreatedAtMs8417?.takeIf { it > 0L } ?: 0L"))
    }

    @Test fun aReceiptRebuildSellsExactlyTheOpenLot() {
        val q = { v: Long -> BigInteger.valueOf(v) }
        assertTrue(JournalEconomicReplay6619.receiptsMatchLot8929(listOf(q(600), q(400)), listOf(0.6, 0.4), q(1_000), 1.0))
        assertFalse("more tokens than the lot", JournalEconomicReplay6619.receiptsMatchLot8929(listOf(q(212_100)), listOf(0.0123), q(2_626), 0.0123))
        assertFalse("another lot's cost", JournalEconomicReplay6619.receiptsMatchLot8929(listOf(q(1_000)), listOf(0.98), q(1_000), 0.0123))
        assertTrue("rounding inside 0.01 %", JournalEconomicReplay6619.receiptsMatchLot8929(listOf(q(1_000)), listOf(1.00005), q(1_000), 1.0))
        assertFalse(JournalEconomicReplay6619.receiptsMatchLot8929(emptyList(), emptyList(), q(1_000), 1.0))
        assertTrue(src("engine/truth/JournalEconomicReplay6619.kt").contains("return skip(\"RECEIPT_LOT_MISMATCH_8929\")"))
        val rebuilt = Trade(side = "SELL", mode = "paper", sol = 0.218, price = 0.0275, ts = 1L, reason = "JOURNAL_REBUILT_FROM_RECEIPT_7360",
            pnlSol = 0.201, pnlPct = 1174.0, entryCostSol = 0.0171, entryPriceSnapshot = 0.0275, mint = other)
        val v = LearningPnlSanitizer.inspectTrade(rebuilt, "test", emit = false)
        assertFalse("a rebuilt row is not graded evidence", v.ok)
        assertTrue("cash still keeps it", LearningPnlSanitizer.inspectAccountingTrade8167(rebuilt, "test", emit = false).reason != "RECEIPT_REBUILD_NOT_EVIDENCE_8929")
        val x = src("xstocks/XsApi.kt")
        assertTrue(x.contains("private val tradeJupiter8929 by lazy { JupiterApi(\"\") }"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 117. The tape is down (8929)"))
    }
}
