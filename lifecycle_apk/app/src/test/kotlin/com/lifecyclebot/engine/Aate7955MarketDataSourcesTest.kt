package com.lifecyclebot.engine

import com.lifecyclebot.engine.chart.Bar7950
import com.lifecyclebot.engine.chart.ChartParsers7955
import com.lifecyclebot.engine.chart.ChartReader7950
import com.lifecyclebot.engine.chart.ChartSources7955
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TreeMap

/** V5.0.7955 — every free market-data source parses, paces, backs off and backfills. */
class Aate7955MarketDataSourcesTest {

    private fun ordered(b: List<Bar7950>) = assertTrue(b.zipWithNext().all { it.first.t < it.second.t })

    @Test fun exchangeCandlesParseOldestFirst() {
        val okx = ChartParsers7955.okx7955(JSONObject("""{"code":"0","data":[["1700000060000","2","2.5","1.9","2.4","10","20","20","1"],["1700000000000","1","2.1","0.9","2","5","10","10","1"]]}"""))
        assertEquals(2, okx.size); ordered(okx); assertEquals(2.4, okx[1].c, 1e-12); assertEquals(1700000000000L, okx[0].t)
        val bybit = ChartParsers7955.bybit7955(JSONObject("""{"retCode":0,"result":{"list":[["1700000060000","2","2.5","1.9","2.4","10","24"],["1700000000000","1","2.1","0.9","2","5","10"]]}}"""))
        assertEquals(2, bybit.size); ordered(bybit); assertEquals(10.0, bybit[1].v, 1e-12)
        val kraken = ChartParsers7955.kraken7955(JSONObject("""{"error":[],"result":{"XXBTZUSD":[[1700000000,"1.0","2.1","0.9","2.0","1.5","7.0",3],[1700000060,"2.0","2.5","1.9","2.4","2.2","3.0",2]],"last":1700000060}}"""))
        assertEquals(2, kraken.size); assertEquals(1700000000000L, kraken[0].t); assertEquals(7.0, kraken[0].v, 1e-12)
        // Coinbase rows are [time, low, high, open, close, volume].
        val cb = ChartParsers7955.coinbase7955(JSONArray("""[[1700000060,1.9,2.5,2.0,2.4,10],[1700000000,0.9,2.1,1.0,2.0,5]]"""))
        assertEquals(2, cb.size); ordered(cb); assertEquals(1.0, cb[0].o, 1e-12); assertEquals(0.9, cb[0].l, 1e-12); assertEquals(2.1, cb[0].h, 1e-12)
        // KuCoin rows are [time, open, close, high, low, volume, turnover].
        val kc = ChartParsers7955.kucoin7955(JSONObject("""{"code":"200000","data":[["1700000060","2","2.4","2.5","1.9","10","24"],["1700000000","1","2","2.1","0.9","5","10"]]}"""))
        assertEquals(2, kc.size); assertEquals(2.0, kc[0].c, 1e-12); assertEquals(2.1, kc[0].h, 1e-12)
        // Gate rows are [t, quoteVol, close, high, low, open, baseVol, closed].
        val gate = ChartParsers7955.gate7955(JSONArray("""[["1700000000","10","2","2.1","0.9","1","5","true"]]"""))
        assertEquals(1, gate.size); assertEquals(1.0, gate[0].o, 1e-12); assertEquals(2.0, gate[0].c, 1e-12); assertEquals(5.0, gate[0].v, 1e-12)
        val cc = ChartParsers7955.cryptoCompare7955(JSONObject("""{"Response":"Success","Data":{"Data":[{"time":1700000000,"high":0,"low":0,"open":0,"close":0,"volumefrom":0},{"time":1700000060,"high":2.1,"low":0.9,"open":1,"close":2,"volumefrom":4}]}}"""))
        assertEquals(1, cc.size)   // zero padding rows dropped
        val cg = ChartParsers7955.coingecko7955(JSONArray("""[[1700000000000,1,2.1,0.9,2]]"""))
        assertEquals(1, cg.size); assertEquals(0.0, cg[0].v, 1e-12)
        val pp = ChartParsers7955.paprika7955(JSONArray("""[{"time_open":"2024-01-01T00:00:00Z","open":1,"high":2.1,"low":0.9,"close":2,"volume":100}]"""))
        assertEquals(1704067200000L, pp[0].t)
    }

    @Test fun memeCandlesParseWithBuySplit() {
        val bq = ChartParsers7955.bitquery7955(JSONObject("""{"data":{"Solana":{"DEXTradeByTokens":[
            {"Block":{"Timefield":"2024-06-01T00:01:00Z"},"volume":"10","buyVolume":"7","Trade":{"open":2,"high":2.5,"low":1.9,"close":2.4}},
            {"Block":{"Timefield":"2024-06-01T00:00:00Z"},"volume":"4","buyVolume":"1","Trade":{"open":1,"high":2.1,"low":0.9,"close":2}}]}}}"""))
        assertEquals(2, bq.size); ordered(bq); assertEquals(7.0, bq[1].buyV, 1e-12); assertEquals(1.0, bq[0].buyV, 1e-12)
        val cx = ChartParsers7955.codex7955(JSONObject("""{"data":{"getBars":{"t":[1700000000,1700000060],"o":[1,2],"h":[2.1,2.5],"l":[0.9,1.9],"c":[2,2.4],"volume":["5","10"],"buyVolume":["3","6"]}}}"""))
        assertEquals(2, cx.size); assertEquals(6.0, cx[1].buyV, 1e-12); assertEquals(10.0, cx[1].v, 1e-12)
        val st = ChartParsers7955.solanaTracker7955(JSONObject("""{"oclhv":[{"open":1,"close":2,"low":0.9,"high":2.1,"volume":5,"time":1700000000}]}"""))
        assertEquals(1, st.size); assertTrue(st[0].buyV.isNaN())
        val mo = ChartParsers7955.moralis7955(JSONObject("""{"result":[{"timestamp":"2024-06-01T00:01:00.000Z","open":2,"high":2.5,"low":1.9,"close":2.4,"volume":10},{"timestamp":"2024-06-01T00:00:00.000Z","open":1,"high":2.1,"low":0.9,"close":2,"volume":4}]}"""))
        assertEquals(2, mo.size); ordered(mo)
        val pf = ChartParsers7955.pumpFun7955(JSONArray("""[{"mint":"x","timestamp":1700000000,"open":0.00000003,"high":0.00000004,"low":0.00000002,"close":0.000000035,"volume":1000}]"""))
        assertEquals(1, pf.size); assertEquals(1700000000000L, pf[0].t)
        val gm = ChartParsers7955.gmgn7955(JSONObject("""{"code":0,"data":{"list":[{"time":"1700000000000","open":"1","close":"2","high":"2.1","low":"0.9","volume":"5"}]}}"""))
        assertEquals(1, gm.size)
        // A malformed candle (high below close) is dropped, never flattened.
        assertNull(ChartParsers7955.bar7955(1L, 1.0, 1.5, 0.9, 2.0, 1.0))
    }

    @Test fun listingsAndDiscoveryParse() {
        val bin = ChartParsers7955.rankObjects7955(JSONArray("""[{"symbol":"ETHUSDT","quoteVolume":"5"},{"symbol":"BTCUSDT","quoteVolume":"9"},{"symbol":"BTCEUR","quoteVolume":"99"}]"""), "symbol", "quoteVolume", "USDT")
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), bin.map { it.id }); assertEquals("BTC", bin[0].base)
        assertEquals("BTC-USDT", ChartParsers7955.okxTickers7955(JSONObject("""{"data":[{"instId":"BTC-USDT","volCcy24h":"1"}]}"""))[0].id)
        assertEquals("BTC", ChartParsers7955.krakenTickers7955(JSONObject("""{"result":{"XXBTZUSD":{"c":["60000","1"],"v":["1","2"]},"XXBTZEUR":{"c":["1"],"v":["1","1"]}}}"""))[0].base)
        assertEquals("BTC", ChartParsers7955.baseOf7955("BTC_USDT")); assertEquals("XRP", ChartParsers7955.baseOf7955("XRPUSDT")); assertEquals("ETH", ChartParsers7955.baseOf7955("XETHZUSD"))
        assertEquals(1, ChartParsers7955.coinbaseProducts7955(JSONArray("""[{"id":"BTC-USD","base_currency":"BTC","quote_currency":"USD","status":"online"},{"id":"BTC-EUR","quote_currency":"EUR"}]""")).size)
        assertEquals(listOf("solana" to "So1"), ChartParsers7955.dexTokens7955(JSONArray("""[{"chainId":"solana","tokenAddress":"So1"},{"chainId":"tron","tokenAddress":"T1"}]""")))
        val pairs = ChartParsers7955.dexPairs7955(JSONObject("""{"pairs":[{"chainId":"solana","pairAddress":"P1","baseToken":{"address":"A"},"liquidity":{"usd":10}},{"chainId":"solana","pairAddress":"P2","baseToken":{"address":"A"},"liquidity":{"usd":50}},{"chainId":"base","pairAddress":"P3","baseToken":{"address":"B"}}]}"""))
        assertEquals(listOf(ChartParsers7955.Pool7955("solana", "P2"), ChartParsers7955.Pool7955("base", "P3")), pairs)
        assertEquals(listOf("pool1"), ChartParsers7955.geckoPools7955(JSONObject("""{"data":[{"attributes":{"address":"pool1"}}]}""")))
        assertEquals(listOf("R1"), ChartParsers7955.raydiumPools7955(JSONObject("""{"success":true,"data":{"count":1,"data":[{"id":"R1"}]}}""")))
        assertEquals(listOf("M1"), ChartParsers7955.meteoraPools7955(JSONObject("""{"pairs":[{"address":"M1"}],"total":1}""")))
        val pump = ChartParsers7955.pumpCoins7955(JSONObject("""{"mint":"mintK","pump_swap_pool":null,"raydium_pool":"RaydiumPool1111111111111111111111111"}"""))
        assertEquals(listOf("mintK" to "RaydiumPool1111111111111111111111111"), pump)
        assertEquals(listOf("g1"), ChartParsers7955.gmgnRank7955(JSONObject("""{"code":0,"data":{"rank":[{"address":"g1"}]}}""")))
        assertEquals("BTC", ChartParsers7955.cmcListings7955(JSONObject("""{"data":[{"symbol":"BTC","quote":{"USD":{"volume_24h":1}}}]}"""))[0].base)
        assertEquals(listOf("cp1"), ChartParsers7955.cmcDexPairs7955(JSONObject("""{"data":[{"contract_address":"cp1"}]}""")))
        assertEquals("ETH", ChartParsers7955.cryptoCompareTop7955(JSONObject("""{"Data":[{"CoinInfo":{"Name":"ETH"}}]}"""))[0].id)
        assertEquals(listOf("t1"), ChartParsers7955.moralisTokens7955(JSONObject("""{"result":[{"tokenAddress":"t1"}]}""")))
        assertEquals("deep", ChartParsers7955.moralisBestPair7955(JSONObject("""{"pairs":[{"pairAddress":"thin","liquidityUsd":1},{"pairAddress":"deep","liquidityUsd":9}]}""")))
        assertEquals(listOf("m1"), ChartParsers7955.solanaTrackerTrending7955(JSONArray("""[{"token":{"mint":"m1"}}]""")))
        assertEquals("bitcoin", ChartParsers7955.coingeckoMarkets7955(JSONArray("""[{"id":"bitcoin","symbol":"btc"}]"""))[0].id)
        assertEquals("btc-bitcoin", ChartParsers7955.paprikaTickers7955(JSONArray("""[{"id":"eth-ethereum","symbol":"ETH","rank":2},{"id":"btc-bitcoin","symbol":"BTC","rank":1}]"""))[0].id)
    }

    @Test fun backoffChallengeAndFairness() {
        assertEquals(6L * 3_600_000L, ChartSources7955.backoffMs7955(403, 0, false))
        assertEquals(6L * 3_600_000L, ChartSources7955.backoffMs7955(200, 0, true))
        assertEquals(10L * 60_000L, ChartSources7955.backoffMs7955(429, 0, false))
        assertEquals(2L * 3_600_000L, ChartSources7955.backoffMs7955(429, 6, false))
        assertEquals(0L, ChartSources7955.backoffMs7955(404, 0, false))
        assertTrue(ChartSources7955.backoffMs7955(-1, 0, false) in 1L..30L * 60_000L)
        assertTrue(ChartParsers7955.isChallenge7955(403, "<html><title>Just a moment...</title>"))
        assertTrue(ChartParsers7955.isChallenge7955(200, "<!DOCTYPE html><html>"))
        assertFalse(ChartParsers7955.isChallenge7955(200, "{\"data\":[]}"))
        assertEquals(2, ChartSources7955.pickNext7955(booleanArrayOf(true, false, true), 1))
        assertEquals(0, ChartSources7955.pickNext7955(booleanArrayOf(true, false, false), 1))
        assertEquals(-1, ChartSources7955.pickNext7955(booleanArrayOf(false, false), 0))
        assertTrue(ChartSources7955.familyAllowed7955(false, 99_999, 0, false))
        assertFalse(ChartSources7955.familyAllowed7955(true, 10_000, 1_000, false))
        assertTrue(ChartSources7955.familyAllowed7955(true, 10_000, 1_000, true))
        val rr = ChartSources7955.RoundRobin7955<String>()
        rr.add("dex", "d1"); rr.add("dex", "d2"); rr.add("gecko", "g1")
        assertEquals("d1", rr.peek())
        assertEquals(listOf("d1", "g1", "d2"), List(3) { rr.poll() })
        assertNull(rr.poll()); assertEquals(0, rr.size())
    }

    @Test fun backfillSeedsTheReaderTape() {
        val mint = "Seed7955Mint1111111111111111111111111111111"
        val now = 1_700_000_000_000L
        val bars = (0 until 25).map { i ->
            val t = now - (25 - i) * 60_000L
            Bar7950(t, 1.0 + i * 0.01, 1.02 + i * 0.01, 0.99 + i * 0.01, 1.01 + i * 0.01, 3.0, if (i % 2 == 0) 2.0 else Double.NaN)
        }
        assertEquals(25, ChartReader7950.seedBars7955(mint, bars, now))
        assertEquals(0, ChartReader7950.seedBars7955(mint, bars, now))   // live minutes are never overwritten
        assertTrue(ChartReader7950.liveBars7955(mint) >= 21)
        // A seeded bar without a split keeps its volume but no fabricated buy share.
        val tape = TreeMap<Long, DoubleArray>()
        tape[0L] = doubleArrayOf(1.0, 1.1, 0.9, 1.0, 0.0, 0.0, 0.0, 4.0)
        tape[1L] = doubleArrayOf(1.0, 1.1, 0.9, 1.0, 3.0, 1.0, 0.0, 0.0)
        val b = ChartReader7950.toBars(tape)
        assertEquals(4.0, b[0].v, 1e-12); assertTrue(b[0].buyV.isNaN()); assertEquals(3.0, b[1].buyV, 1e-12)
        // Backfill order: held first, then candidates; short, Solana, not tried recently.
        val held = "Hexd7955Mint11111111111111111111111111111111"
        val cand = "Cand7955Mint11111111111111111111111111111111"
        assertEquals(held, ChartSources7955.nextBackfill7955(listOf(held), listOf(cand), { 0 }, { 0L }, now))
        assertEquals(cand, ChartSources7955.nextBackfill7955(listOf(held), listOf(cand), { if (it == held) 30 else 0 }, { 0L }, now))
        assertNull(ChartSources7955.nextBackfill7955(listOf(held), listOf("AAPL"), { 0 }, { if (it == held) now else 0L }, now))
        val sol = ChartSources7955.usdBarsToSol7955(listOf(Bar7950(1L, 1.0, 1.0, 1.0, 1.0, 150.0, 75.0)), 150.0)
        assertEquals(1.0, sol[0].v, 1e-12); assertEquals(0.5, sol[0].buyV, 1e-12)
        assertEquals(0.0, ChartSources7955.usdBarsToSol7955(listOf(Bar7950(1L, 1.0, 1.0, 1.0, 1.0, 150.0)), 0.0)[0].v, 1e-12)
    }
}
