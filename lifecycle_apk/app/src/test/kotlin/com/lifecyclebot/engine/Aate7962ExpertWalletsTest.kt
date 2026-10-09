package com.lifecyclebot.engine

import com.lifecyclebot.engine.ExpertWallets7962.Leg7962
import com.lifecyclebot.engine.chart.Bar7950
import com.lifecyclebot.engine.chart.ChartLibrary7950
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.7962 — expert wallets: round trips, cells, the promotion rule, leaderboard parsing and the wiring. */
class Aate7962ExpertWalletsTest {
    private val m = "So1aMemeMint1111111111111111111111111111pump"

    private fun leg(sig: String, t: Long, buy: Boolean, tok: Double, sol: Double, mint: String = m) = Leg7962(sig, t, mint, buy, tok, sol)

    @Test fun roundTripsFromSyntheticSwapLegs() {
        val legs = listOf(
            leg("s0", 500L, false, 100.0, 0.5),                     // sell of a bag bought before the window: ignored
            leg("b1", 1_000L, true, 1_000.0, 1.0),
            leg("b2", 2_000L, true, 500.0, 1.0),
            leg("x1", 3_000L, false, 750.0, 3.0),
            leg("x2", 4_000L, false, 740.0, 3.0),                   // 10 tokens left = 0.7% of the bag: dust, closed
            leg("b3", 9_000L, true, 100.0, 0.2),                    // a second trip still open
            leg("o1", 1_500L, true, 10.0, 0.1, mint = "Other111111111111111111111111111111111pump"),
            leg("o2", 2_500L, false, 20.0, 0.3, mint = "Other111111111111111111111111111111111pump"), // oversell: pro rata
        )
        val trips = ExpertWallets7962.reconstructRoundTrips7962(legs)
        val main = trips.filter { it.mint == m }
        assertEquals(2, main.size)
        val t = main[0]
        assertTrue(t.closed)
        assertEquals(1_000L, t.entryMs)
        assertEquals(4_000L, t.exitMs)
        assertEquals(3_000L, t.holdMs)
        assertEquals(2, t.buys)
        assertEquals(2, t.sells)
        assertEquals(2.0, t.costSol, 1e-9)
        assertEquals(6.0, t.proceedsSol, 1e-9)
        assertEquals(200.0, t.realizedPct, 1e-9)
        assertEquals(0.001, t.entryPriceSol, 1e-12)
        assertFalse(main[1].closed)
        val o = trips.first { it.mint != m }
        assertTrue(o.closed)
        assertEquals(0.15, o.proceedsSol, 1e-9)                    // half of the 20-token sell was the tracked bag
        assertEquals(50.0, o.realizedPct, 1e-9)
    }

    @Test fun walletLegsParsedFromHeliusEnhancedTransactions() {
        val w = "Wa11et11111111111111111111111111111111111111"
        val buy = JSONObject().put("signature", "sigBuy").put("timestamp", 1_700_000_000L).put("feePayer", w)
            .put("tokenTransfers", JSONArray().put(JSONObject().put("mint", m).put("toUserAccount", w).put("fromUserAccount", "pool").put("tokenAmount", 1_000_000.0)))
            .put("events", JSONObject().put("swap", JSONObject().put("nativeInput", JSONObject().put("account", w).put("amount", "500000000"))))
        val sell = JSONObject().put("signature", "sigSell").put("timestamp", 1_700_000_600L).put("feePayer", w)
            .put("tokenTransfers", JSONArray().put(JSONObject().put("mint", m).put("fromUserAccount", w).put("toUserAccount", "pool").put("tokenAmount", 1_000_000.0)))
            .put("nativeTransfers", JSONArray().put(JSONObject().put("fromUserAccount", "pool").put("toUserAccount", w).put("amount", 1_500_000_000L)))
        val tokenForToken = JSONObject().put("signature", "sigT2T").put("timestamp", 1_700_000_100L)
            .put("tokenTransfers", JSONArray()
                .put(JSONObject().put("mint", m).put("toUserAccount", w).put("tokenAmount", 5.0))
                .put(JSONObject().put("mint", "Other111111111111111111111111111111111pump").put("fromUserAccount", w).put("tokenAmount", 5.0)))
        val failed = JSONObject(buy.toString()).put("signature", "sigErr").put("transactionError", JSONObject().put("x", 1))
        val legs = ExpertWallets7962.parseWalletLegs7962(JSONArray().put(buy).put(sell).put(tokenForToken).put(failed), w)
        assertEquals(2, legs.size)
        assertTrue(legs[0].isBuy)
        assertEquals(0.5, legs[0].sol, 1e-9)
        assertEquals(1_700_000_000_000L, legs[0].tsMs)
        assertFalse(legs[1].isBuy)
        assertEquals(1.5, legs[1].sol, 1e-9)
        val trip = ExpertWallets7962.reconstructRoundTrips7962(legs).single()
        assertEquals(200.0, trip.realizedPct, 1e-9)
        assertEquals(600_000L, trip.holdMs)
    }

    @Test fun cellLiftUsesTheForwardLabelTaxonomy() {
        assertEquals("MC_LT10K|AGE_LT15M", ExpertWallets7962.cellKey7962(8_000.0, 5L * 60_000L))
        assertEquals("MC_10K_100K|AGE_15M_2H", ExpertWallets7962.cellKey7962(40_000.0, 30L * 60_000L))
        assertEquals("MC_UNKNOWN|AGE_UNKNOWN", ExpertWallets7962.cellKey7962(0.0, -1L))
        assertEquals(0.0, ExpertWallets7962.cellLiftOf7962(0, 0.0), 1e-12)
        // 10 entries averaging +50%: shrunk by n/(n+10) to +25%.
        assertEquals(25.0, ExpertWallets7962.cellLiftOf7962(10, 500.0), 1e-9)
        assertTrue(ExpertWallets7962.cellLiftOf7962(40, 2_000.0) > ExpertWallets7962.cellLiftOf7962(10, 500.0))
        // No evidence in a cell reads 0, never a guess.
        assertEquals(0.0, ExpertWallets7962.expertCellLift7962(12_345_678.0, 99L * 3_600_000L), 1e-12)
    }

    @Test fun promotionIsEvidenceBasedAndDemotesItself() {
        // 5.0.7958 COPY: labeled n19 mean +57.9% pf 5.8 — one label short of the statistical floor.
        assertFalse(ExpertWallets7962.labeledPromotes7962(19, 0.579, 1.0, 5.8))
        assertTrue(ExpertWallets7962.labeledPromotes7962(20, 0.579, 1.0, 5.8))
        // mean - SE must clear the label-to-live gap (10%): 0.12 - 0.5/sqrt(20) = 0.008.
        assertFalse(ExpertWallets7962.labeledPromotes7962(20, 0.12, 0.5, 3.0))
        assertTrue(ExpertWallets7962.labeledPromotes7962(80, 0.20, 0.5, 3.0))
        // Profit factor floor 1.5.
        assertFalse(ExpertWallets7962.labeledPromotes7962(80, 0.20, 0.5, 1.4))
        // Unknown dispersion never promotes.
        assertFalse(ExpertWallets7962.labeledPromotes7962(80, 0.20, Double.NaN, 3.0))
        // The same source demotes when its record fades (same n, mean collapses).
        assertFalse(ExpertWallets7962.labeledPromotes7962(80, 0.05, 0.5, 3.0))
        // Sample sd: {0.1, 0.3} -> 0.1414.
        assertEquals(0.141421356, ExpertWallets7962.sdOf7962(2, 0.4, 0.1), 1e-6)
        assertTrue(ExpertWallets7962.sdOf7962(1, 0.4, 0.16).isNaN())
    }

    @Test fun leaderboardParsingIsTolerantAndFailSoft() {
        val a = "Trader1111111111111111111111111111111111111"
        val b = "Trader2222222222222222222222222222222222222"
        val pump = JSONArray().put(JSONObject().put("user", a).put("realized_pnl", 42.0)).put(JSONObject().put("user", "bad").put("realized_pnl", 9.0)).toString()
        assertEquals(listOf(a to 42.0), ExpertWallets7962.parseLeaderboard7962(pump, 150.0))
        val gmgn = JSONObject().put("code", 0).put("data", JSONObject().put("rank", JSONArray()
            .put(JSONObject().put("wallet_address", b).put("realized_profit_7d", 15_000.0).put("winrate_7d", 0.62).put("txs_7d", 300))
            .put(JSONObject().put("wallet_address", a).put("realized_profit_7d", 90_000.0).put("winrate_7d", 0.2))      // low win rate
            .put(JSONObject().put("wallet_address", "Bot33333333333333333333333333333333333333").put("realized_profit_7d", 9e6).put("txs_7d", 50_000)))).toString()
        val rows = ExpertWallets7962.parseLeaderboard7962(gmgn, 150.0)
        assertEquals(1, rows.size)
        assertEquals(b, rows[0].first)
        assertEquals(100.0, rows[0].second, 1e-9)
        assertTrue(ExpertWallets7962.parseLeaderboard7962("<!doctype html>", 150.0).isEmpty())
        assertTrue(ExpertWallets7962.parseLeaderboard7962("", 150.0).isEmpty())
        assertEquals(2, ExpertWallets7962.LEADERBOARD_URLS_7962.size)
    }

    @Test fun peakAndTradeOutcomeFromBars() {
        val bars = (0 until 30).map { i -> Bar7950(i * 60_000L, 1.0, if (i == 25) 3.0 else 1.1, 0.9, 1.0, 10.0) }
        val pk = ExpertWallets7962.peakFromBars7962(bars, 20, 21 * 60_000L, 28 * 60_000L)!!
        assertEquals(200.0, pk.first, 1e-9)
        assertEquals(4L * 60_000L + 30_000L, pk.second)
        assertNull(ExpertWallets7962.peakFromBars7962(bars, 20, 21 * 60_000L, 60 * 60_000L))   // bars stop before the exit
        val o = ExpertWallets7962.tripOutcome7962(45.0, 120.0)
        assertTrue(o.hitUpFirst)
        assertEquals(120f, o.maxUpPct, 1e-6f)
        assertEquals(45f, o.endPct, 1e-6f)
        assertFalse(ExpertWallets7962.tripOutcome7962(-30.0, Double.NaN).hitUpFirst)
    }

    @Test fun expertMotifsCountInTheMemeFamily() {
        ChartLibrary7950.resetForTest()
        assertEquals(4, ChartLibrary7950.SRC_EXPERT)
        val f = FloatArray(com.lifecyclebot.engine.chart.ChartMotif7950.DIM) { 0.1f }
        ChartLibrary7950.add(f, ExpertWallets7962.tripOutcome7962(50.0, 80.0), ChartLibrary7950.SRC_EXPERT)
        assertTrue(ChartLibrary7950.statusLine().contains("expert=1"))
        ChartLibrary7950.resetForTest()
    }

    @Test fun ownerBuysArmExpertEntryAndLeadTheWatchList() {
        val owner = ExpertWallets7962.OWNER_WALLET_7962
        assertEquals(listOf(owner, "a", "b"), ExpertWallets7962.prioritise7962(listOf("a", owner, "b")))
        val now = 10_000_000L
        val mint = "ExpertBought111111111111111111111111111pump"
        assertFalse(ExpertWallets7962.expertEntryLive7962(mint, now))
        ExpertWallets7962.onTrackedBuy7962(mint, owner, now)
        assertTrue(ExpertWallets7962.expertEntryLive7962(mint, now + 60_000L))
        assertFalse(ExpertWallets7962.expertEntryLive7962(mint, now + 16L * 60_000L))
        ExpertWallets7962.onTrackedBuy7962("Untracked1111111111111111111111111111pump", "nobody", now)
        assertFalse(ExpertWallets7962.expertEntryLive7962("Untracked1111111111111111111111111111pump", now))
        ExpertWallets7962.noteUntrackedTape7962()
        ExpertWallets7962.noteLeaderboard7962(ExpertWallets7962.LEADERBOARD_URLS_7962[1], 7)
        val line = ExpertWallets7962.statusLine7962()
        assertTrue(line.startsWith("wallets="))
        for (k in listOf("histories=", "roundTrips=", "ownerTrades=", "motifs=", "topCells=", "copyTier=", "leaderboard=gmgn.ai:7")) assertTrue(k, line.contains(k))
    }

    @Test fun expertEntryIsAPlaybookSetupOnMemeLanes() {
        val nan = Double.NaN
        fun f(expert: Boolean) = LanePlaybook7907.F(nan, nan, nan, nan, nan, nan, nan, nan, nan, nan, nan, nan, "", "", expertEntry7962 = expert)
        assertTrue(LanePlaybook7907.matches("SHITCOIN", f(true))!!.any { it.id == "EXPERT_ENTRY" })
        assertFalse(LanePlaybook7907.matches("SHITCOIN", f(false))!!.any { it.id == "EXPERT_ENTRY" })
        assertFalse(LanePlaybook7907.matches("BLUECHIP", f(true))!!.any { it.id == "EXPERT_ENTRY" })
        assertTrue(LanePlaybook7907.menuIds("MOONSHOT").contains("EXPERT_ENTRY"))
    }

    @Test fun wiring() {
        fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
        val cte = src("engine/CopyTradeEngine.kt")
        assertTrue(cte.contains("ExpertWallets7962.noteUntrackedTape7962()"))
        assertTrue(cte.indexOf("ExpertWallets7962.noteUntrackedTape7962()") < cte.indexOf("SmartMoneyBridgeHealth7422.detected()"))
        assertTrue(cte.contains("ExpertWallets7962.onTrackedBuy7962(mint, buyerWallet, now)"))
        assertTrue(cte.contains("ExpertWallets7962.parseLeaderboard7962(body, solUsd)"))
        assertTrue(src("engine/SmartMoneyDiscovery7277.kt").contains("ExpertWallets7962.start7962(scope, heliusKey, copyEngine, onWatchlistChanged)"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("Expert wallets (§7962): "))
        assertTrue(src("network/HeliusEnhancedWS.kt").contains("ExpertWallets7962.prioritise7962(requested)"))
        val bs = src("engine/BotService.kt")
        assertTrue(bs.contains("|| copyProven7291 || copyRouteLive7962())"))
        assertTrue(bs.contains("SignalSourceProof7291.liveEligible7962(com.lifecyclebot.engine.truth.SignalSourceProof7291.Source.COPY)"))
        assertTrue(bs.contains("val copyProven7291 = copyLiveProven7962()"))
        // The copy perps side trade stays on settled live proof only.
        assertTrue(src("engine/BotService.kt").contains("if (!c.paperMode && c.heliusApiKey.isNotBlank() && copyProven7291)"))
        val sp = src("engine/truth/SignalSourceProof7291.kt")
        assertTrue(sp.contains("fun liveEligible7962(source: Source): Boolean"))
        assertTrue(sp.contains("if (t.n >= 10 && t.mean() < 0.0) return false"))
        val lib = src("engine/chart/ChartLibrary7950.kt")
        assertTrue(lib.contains("bySrc[SRC_LIVE] + bySrc[SRC_EXPERT]"))
        assertTrue(lib.contains("coerceIn(0, SRC_MAX_7962)"))
        val ew = src("engine/ExpertWallets7962.kt")
        assertTrue(ew.contains("HeliusCreditEconomy7881.Consumer.SMART_MONEY_DISCOVERY, PAGE_CREDITS"))
        assertTrue(ew.contains("ExitProfile7955.onLabel7955(\"EXPERT\", tier.name"))
        assertTrue(ew.contains("ChartLibrary7950.add(f, o, ChartLibrary7950.SRC_EXPERT)"))
    }
}
