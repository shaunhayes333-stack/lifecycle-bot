package com.lifecyclebot.engine

import com.lifecyclebot.engine.HeliusCadence8361.AlphaCandidate8361
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8361 — Helius consumers on the owner's cadence: sweep hourly, alpha 25 daily, whales big moves only. */
class Aate8361CadenceTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val hour = 60L * 60_000L

    // ── sweep ──

    @Test fun sweepHeliusSourceIsDueAtMostHourly() {
        val t0 = 1_000_000_000L
        assertTrue(HeliusCadence8361.sweepHeliusDue8361(0L, t0))
        assertFalse(HeliusCadence8361.sweepHeliusDue8361(t0, t0 + 45_000L))
        assertFalse(HeliusCadence8361.sweepHeliusDue8361(t0, t0 + 59L * 60_000L))
        assertTrue(HeliusCadence8361.sweepHeliusDue8361(t0, t0 + hour))
        assertEquals(hour, HeliusCadence8361.SWEEP_HELIUS_EVERY_MS_8361)
    }

    @Test fun keylessSweepSourcesKeepTheirCadence() {
        assertEquals(60_000L, com.lifecyclebot.engine.market.MarketSweep7297.refreshMs8124("JUP_TRENDING_5M"))
        assertEquals(60_000L, com.lifecyclebot.engine.market.MarketSweep7297.refreshMs8124("JUP_RECENT"))
        assertEquals(3L * 60_000L, com.lifecyclebot.engine.market.MarketSweep7297.refreshMs8124("JUP_TRENDING"))
        assertEquals(10L * 60_000L, com.lifecyclebot.engine.market.MarketSweep7297.refreshMs8124("JUP_TRADED_24H"))
        assertEquals(0L, com.lifecyclebot.engine.market.MarketSweep7297.refreshMs8124("RAYDIUM_VOLUME"))
        val ms = src("engine/market/MarketSweep7297.kt")
        assertTrue(ms.contains("private const val MIN_SWEEP_INTERVAL_MS = 45_000L"))
        assertTrue(ms.contains("private const val HELIUS_INTERVAL_MS = com.lifecyclebot.engine.HeliusCadence8361.SWEEP_HELIUS_EVERY_MS_8361"))
        assertTrue(ms.contains("val runHelius = com.lifecyclebot.engine.HeliusCadence8361.sweepHeliusDue8361(lastHeliusAtMs, now)"))
        // keyless-first reads, no direct paid RPC left in the sweep
        assertTrue(ms.contains("HeliusCadence8361.signatures8361(http, PUMPSWAP_AMM, HELIUS_SIGNATURES, null, keylessOnly = false"))
        assertFalse(ms.contains("mainnet.helius-rpc.com"))
    }

    // ── alpha list ──

    private fun c(w: String, closed: Int, wins: Int, mean: Double) = AlphaCandidate8361(w, closed, wins, mean, 0)

    @Test fun alphaRankingTopTwentyFiveByWinRateThenMeanThenCount() {
        val cands = (1..40).map { i -> c("W%02d".format(i), 10, i % 10 + 1, 5.0 + i) }
        val ranked = HeliusCadence8361.rankAlpha8361(cands)
        assertEquals(25, ranked.size)
        // win rate 10/10 first (i % 10 == 9: 9, 19, 29, 39), the higher mean first among them
        assertEquals(listOf("W39", "W29", "W19", "W09"), ranked.take(4))
    }

    @Test fun alphaRankingTiesAndMinimumRoundTrips() {
        val ranked = HeliusCadence8361.rankAlpha8361(listOf(
            c("B", 10, 7, 20.0), c("A", 10, 7, 20.0),     // full tie -> address order
            c("C", 20, 14, 20.0),                         // same rate + mean, more trips -> first
            c("D", 4, 4, 90.0),                           // under 5 closed trips -> out
            c("E", 10, 9, -3.0),                          // negative mean -> out
        ))
        assertEquals(listOf("C", "A", "B"), ranked)
    }

    @Test fun candidateRowClipsOutliersAndCountsWins() {
        val r = HeliusCadence8361.candidateOf8361("W", listOf(5_000.0, -50.0, Double.NaN, 10.0), 2)
        assertEquals(3, r.closed)
        assertEquals(2, r.wins)
        assertEquals((300.0 - 50.0 + 10.0) / 3.0, r.meanNetPct, 1e-9)
    }

    @Test fun dailyRebuildDueRule() {
        val t0 = 5_000_000_000L
        assertTrue(HeliusCadence8361.alphaRebuildDue8361(0L, t0))                // boot, nothing stored
        assertFalse(HeliusCadence8361.alphaRebuildDue8361(t0, t0 + 23L * hour))
        assertTrue(HeliusCadence8361.alphaRebuildDue8361(t0, t0 + 24L * hour))   // boot with a list older than 24 h
        assertEquals(24L * hour, HeliusCadence8361.ALPHA_REBUILD_MS_8361)
        assertEquals(25, HeliusCadence8361.ALPHA_MAX_8361)
        assertEquals(5, HeliusCadence8361.ALPHA_MIN_TRIPS_8361)
    }

    @Test fun oneWatchListCarriesWhalesOwnerAndAlpha() {
        val alpha = (1..30).map { "A$it" }
        val out = HeliusCadence8361.watchListOf8361(listOf("T1", "T2", "T3"), alpha, listOf("OWN", "C1"), "OWN")
        assertEquals(listOf("T1", "T2", "T3", "OWN"), out.take(4))
        assertEquals(3 + 1 + 25, out.size)
        assertFalse(out.contains("A26"))
        // a short ranked list is topped up from the existing copy list, never past 25
        val short = HeliusCadence8361.watchListOf8361(listOf("T1"), listOf("A1"), listOf("C1", "C2"), "OWN")
        assertEquals(listOf("T1", "A1", "C1", "C2"), short)
    }

    // ── whales ──

    @Test fun bigMoveThresholdIsTwentyFiveSolOrFiveThousandUsd() {
        assertTrue(HeliusCadence8361.isBigMove8361(25.0, 0.0, 0.0))
        assertFalse(HeliusCadence8361.isBigMove8361(24.9, 0.0, 0.0))           // no fresh price: SOL rule only
        assertTrue(HeliusCadence8361.isBigMove8361(20.0, 0.0, 250.0))          // $5,000 reached first
        assertFalse(HeliusCadence8361.isBigMove8361(20.0, 0.0, 200.0))         // $4,000
        assertTrue(HeliusCadence8361.isBigMove8361(0.0, 6_000.0, 0.0))         // a stablecoin leg
        assertFalse(HeliusCadence8361.isBigMove8361(Double.NaN, Double.NaN, Double.NaN))
        assertEquals(25.0, HeliusCadence8361.WHALE_BIG_SOL_8361, 0.0)
        assertEquals(5_000.0, HeliusCadence8361.WHALE_BIG_USD_8361, 0.0)
    }

    private val whale = "WhaLe1111111111111111111111111111111111111"
    private val mint = "MemeMint11111111111111111111111111111pump"

    private fun push(lamportsPre: Long, lamportsPost: Long, tokPre: Double, tokPost: Double): JSONObject {
        fun bal(amt: Double) = JSONObject().put("owner", whale).put("mint", mint).put("uiTokenAmount", JSONObject().put("uiAmount", amt))
        val meta = JSONObject().put("err", JSONObject.NULL).put("fee", 5_000L)
            .put("preBalances", JSONArray().put(lamportsPre)).put("postBalances", JSONArray().put(lamportsPost))
            .put("preTokenBalances", JSONArray().put(bal(tokPre))).put("postTokenBalances", JSONArray().put(bal(tokPost)))
        val message = JSONObject().put("accountKeys", JSONArray().put(JSONObject().put("pubkey", whale)))
        return JSONObject().put("signature", "sig1").put("transaction",
            JSONObject().put("transaction", JSONObject().put("message", message)).put("meta", meta))
    }

    @Test fun swapsAreReadFromThePushWithoutAFollowUpRead() {
        val buy = HeliusCadence8361.swapsOf8361(push(40_000_000_000L, 10_000_000_000L - 5_000L, 0.0, 1_000.0), setOf(whale), 7L)
        assertEquals(1, buy.size)
        assertTrue(buy[0].isBuy)
        assertEquals(30.0, buy[0].sol, 1e-6)
        assertEquals(7L, buy[0].tsMs)
        assertTrue(HeliusCadence8361.isBigMove8361(buy[0].sol, buy[0].stableUsd, 0.0))
        val sell = HeliusCadence8361.swapsOf8361(push(1_000_000_000L, 3_000_000_000L - 5_000L, 1_000.0, 0.0), setOf(whale), 7L)
        assertFalse(sell[0].isBuy)
        assertEquals(2.0, sell[0].sol, 1e-6)
        assertFalse(HeliusCadence8361.isBigMove8361(sell[0].sol, sell[0].stableUsd, 0.0))   // dropped
        assertTrue(HeliusCadence8361.swapsOf8361(push(1L, 1L, 0.0, 1.0), setOf("other"), 7L).isEmpty())
    }

    // ── hooks ──

    @Test fun oneSubscriptionCarriesAlphaAndWhalesAndNoPollingOnTheWatchPath() {
        val bs = src("engine/BotService.kt")
        assertTrue(bs.contains("HeliusCadence8361.watchList8361(com.lifecyclebot.perps.InsiderWalletTracker.streamPriorityAddresses7881(),"))
        assertTrue(bs.contains("HeliusEnhancedWS.updateWatchlist(HeliusCadence8361.watchList8361(insider7277, copyWallets7277))"))
        assertTrue(bs.contains("val watched7277 = HeliusCadence8361.copyWatched8361(raw7277, com.lifecyclebot.network.HeliusEnhancedWS.watchedAccounts7277())"))
        assertFalse(bs.contains("try { com.lifecyclebot.perps.InsiderWalletTracker.scanForSignals() }\n"))
        assertTrue(bs.contains("if (com.lifecyclebot.network.HeliusEnhancedWS.whaleSubLive8361()) { HeliusCadence8361.noteWhaleScanSkipped8361(); return@launch }"))
        val cad = src("engine/HeliusCadence8361.kt")
        val watchPath = cad.substringAfter("fun copyWatched8361(").substringBefore("// ── keyless JSON-RPC")
        assertFalse(watchPath.contains("getSignaturesForAddress"))
        assertFalse(watchPath.contains("signatures8361("))
        assertFalse(watchPath.contains("transaction8361("))
        assertTrue(watchPath.contains("InsiderWalletTracker.emitBigMove8361(s.wallet, s.mint, s.isBuy, s.sol, usd)"))
        val ws = src("network/HeliusEnhancedWS.kt")
        assertEquals(1, Regex("fun sendSubscribe\\(").findAll(ws).count())
        val ice = src("engine/InsiderCopyEngine.kt")
        assertTrue(ice.contains("\"BUY\", \"NEW_POSITION\", \"ACCUMULATION\", \"WHALE_BIG_BUY_8361\" ->"))
        assertTrue(ice.contains("\"SELL\", \"DISTRIBUTION\", \"WHALE_BIG_SELL_8361\" ->"))
    }

    @Test fun alphaListIsDailyAndCopyStaysBehindItsProofBar() {
        val ew = src("engine/ExpertWallets7962.kt")
        assertTrue(ew.contains("if (HeliusCadence8361.alphaRebuildDue8361(alphaAtMs8361, now8361)) rebuildAlpha8361(engine, onWatchlistChanged, now8361)"))
        assertTrue(ew.contains("if (ownerOnly8361 && (e.tier != Tier7962.OWNER || h.backfillDone)) continue"))
        assertTrue(ew.contains("keylessOnly = true, caller = HeliusCadence8361.CALLER_ALPHA_8361"))
        assertFalse(ew.contains("PROVEN_LIVE"))
        val sm = src("engine/SmartMoneyDiscovery7277.kt")
        assertTrue(sm.contains("if (!HeliusCadence8361.alphaRebuildDue8361(lastMineMs8361, now8361)) return"))
        assertFalse(sm.contains("mainnet.helius-rpc.com"))
        val proof = src("engine/truth/SignalSourceProof7291.kt")
        assertTrue(proof.contains("else -> \"PAPER_ONLY\""))
    }

    @Test fun statusLinesNameTheThreeConsumers() {
        val line = HeliusCadence8361.statusLine8361()
        assertTrue(line, line.contains("sweep8361[keylessEvery=45s heliusEvery=60m lastHelius="))
        assertTrue(line, line.contains("alpha8361[list="))
        assertTrue(line, line.contains("whales8361[watched="))
        assertTrue(HeliusCadence8361.creditsLine8361().startsWith("creditsByCaller8361{sweep="))
    }
}
