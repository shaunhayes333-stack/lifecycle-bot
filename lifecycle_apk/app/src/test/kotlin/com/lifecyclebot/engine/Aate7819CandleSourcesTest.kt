package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.HeliusSwapCandles7819
import com.lifecyclebot.engine.truth.LocalCandleSynthesis7055
import com.lifecyclebot.network.SolanaOhlcvFeed6916
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.7819 CandleSources — Helius swap-derived bars, every real trade print
 * into the local binner, the tape candle no longer silencing it, and an
 * escalating GeckoTerminal backoff. MIN_BARS_7739 is unchanged.
 */
class Aate7819CandleSourcesTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private val mint = "MintAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val trader = "TraderBbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val curve = "CurveCcccccccccccccccccccccccccccccccccccccc"
    private val wsol = "So11111111111111111111111111111111111111112"
    private val t0Sec = 1_800_000_000L   // minute-aligned (1.8e12 ms / 60_000)

    private fun page(): JSONArray = JSONArray(
        """
        [
          {"signature":"s1","timestamp":${t0Sec + 5},"feePayer":"$trader",
           "tokenTransfers":[{"mint":"$mint","fromUserAccount":"$curve","toUserAccount":"$trader","tokenAmount":1000000}],
           "nativeTransfers":[{"fromUserAccount":"$trader","toUserAccount":"$curve","amount":1000000000},
                              {"fromUserAccount":"$trader","toUserAccount":"FeeDddddddddddddddddddddddddddddddddddddddd","amount":10000000}]},
          {"signature":"s2","timestamp":${t0Sec + 30},"feePayer":"$trader",
           "tokenTransfers":[{"mint":"$mint","fromUserAccount":"$trader","toUserAccount":"$curve","tokenAmount":500000},
                             {"mint":"$wsol","fromUserAccount":"$curve","toUserAccount":"$trader","tokenAmount":0.6}]},
          {"signature":"s3","timestamp":${t0Sec + 70},"feePayer":"$trader",
           "tokenTransfers":[{"mint":"$mint","fromUserAccount":"$trader","toUserAccount":"$curve","tokenAmount":100000}],
           "accountData":[{"account":"$trader","nativeBalanceChange":130000000}]},
          {"signature":"s4","timestamp":${t0Sec + 75},"feePayer":"$trader","transactionError":{"x":1},
           "tokenTransfers":[{"mint":"$mint","fromUserAccount":"$curve","toUserAccount":"$trader","tokenAmount":1000}],
           "nativeTransfers":[{"fromUserAccount":"$trader","toUserAccount":"$curve","amount":900000000}]},
          {"signature":"s5","timestamp":${t0Sec + 80},"feePayer":"$trader",
           "tokenTransfers":[{"mint":"$mint","fromUserAccount":"$curve","toUserAccount":"$trader","tokenAmount":10}],
           "nativeTransfers":[{"fromUserAccount":"$trader","toUserAccount":"$curve","amount":100000}]},
          {"signature":"s6","timestamp":${t0Sec + 200},"feePayer":"$trader",
           "events":{"swap":{"nativeInput":{"account":"$trader","amount":"2000000000"},
             "tokenOutputs":[{"mint":"$mint","rawTokenAmount":{"tokenAmount":"1000000000000","decimals":6}}]}}}
        ]
        """.trimIndent()
    )

    @Test fun parsesBuysSellsAndSkipsFailedAndDustSwaps() {
        val prints = HeliusSwapCandles7819.parseSwapPrints7819(page(), mint)
        assertEquals(4, prints.size)
        // buy: the largest SOL transfer (1.0), not the fee (0.01)
        assertEquals(1.0 / 1_000_000.0, prints[0].priceSol, 1e-15)
        assertTrue(prints[0].isBuy)
        // sell paid in WSOL
        assertEquals(0.6 / 500_000.0, prints[1].priceSol, 1e-15)
        assertFalse(prints[1].isBuy)
        // curve sell credited by lamport debit -> trader balance change
        assertEquals(0.13 / 100_000.0, prints[2].priceSol, 1e-15)
        // events.swap path with raw decimals
        assertEquals(2.0 / 1_000_000.0, prints[3].priceSol, 1e-15)
        assertEquals((t0Sec + 200) * 1000L, prints[3].tsMs)
    }

    @Test fun binsRealPrintsIntoMinuteBarsWithoutInventingEmptyMinutes() {
        val prints = HeliusSwapCandles7819.parseSwapPrints7819(page(), mint)
        val now = (t0Sec + 240) * 1000L
        val bars = HeliusSwapCandles7819.binPrints7819(prints, 100.0, now)
        assertEquals(3, bars.size)                 // minutes 0, 1, 3 — minute 2 had no swap
        val b0 = bars[0]
        assertEquals(t0Sec * 1000L, b0.ts)
        assertEquals(1.0e-4, b0.openUsd, 1e-12)
        assertEquals(1.2e-4, b0.highUsd, 1e-12)
        assertEquals(1.0e-4, b0.lowUsd, 1e-12)
        assertEquals(1.2e-4, b0.priceUsd, 1e-12)
        assertEquals(1, b0.buysH1); assertEquals(1, b0.sellsH1)
        assertFalse(b0.synthetic)
        assertTrue(HeliusSwapCandles7819.binPrints7819(prints, 0.0, now).isEmpty())
    }

    @Test fun grossMisparseIsDroppedAgainstTheMedian() {
        val p = listOf(
            HeliusSwapCandles7819.SwapPrint7819(1_000L * 60_000L, 1.0, 1.0, true),
            HeliusSwapCandles7819.SwapPrint7819(1_000L * 60_000L + 1, 1.1, 1.0, true),
            HeliusSwapCandles7819.SwapPrint7819(1_001L * 60_000L, 500.0, 1.0, true),
        )
        val bars = HeliusSwapCandles7819.binPrints7819(p, 1.0, 1_002L * 60_000L)
        assertEquals(1, bars.size)
        assertEquals(1.1, bars[0].highUsd, 1e-12)
    }

    @Test fun mergeNeverReplacesAMinuteTheHistoryHolds() {
        val ts = TokenState(mint = mint)
        val held = Candle(ts = 5L * 60_000L + 10_000L, priceUsd = 9.0, marketCap = 0.0, volumeH1 = 0.0, volume24h = 0.0)
        ts.history.addLast(held)
        val bars = listOf(
            Candle(ts = 4L * 60_000L, priceUsd = 1.0, marketCap = 0.0, volumeH1 = 1.0, volume24h = 0.0),
            Candle(ts = 5L * 60_000L, priceUsd = 2.0, marketCap = 0.0, volumeH1 = 1.0, volume24h = 0.0),
            Candle(ts = 6L * 60_000L, priceUsd = 3.0, marketCap = 0.0, volumeH1 = 1.0, volume24h = 0.0),
        )
        assertEquals(2, HeliusSwapCandles7819.mergeIntoHistory7819(ts, bars))
        assertEquals(listOf(1.0, 9.0, 3.0), ts.history.map { it.priceUsd })
        ts.candleTimeframeMinutes = 240
        assertEquals(0, HeliusSwapCandles7819.mergeIntoHistory7819(ts, bars))
    }

    @Test fun aSingleRealTradeMakesABarAndAPolledPointDoesNot() {
        LocalCandleSynthesis7055.resetForTest()
        val ts = TokenState(mint = mint)
        val m = 29_000_000L * 60_000L
        LocalCandleSynthesis7055.noteTrade7819(ts, 1.0, 0.0, m + 5_000L)
        LocalCandleSynthesis7055.noteTrade7819(ts, 1.2, 0.0, m + 65_000L)   // rolls minute m
        assertEquals(1, ts.history.size)
        assertEquals(m, ts.history.first().ts)
        LocalCandleSynthesis7055.noteTrade7819(ts, 0.5, 0.0, m + 1_000L)   // out of order: dropped
        assertEquals(1, ts.history.size)
        val line = LocalCandleSynthesis7055.statusLine7055()
        assertTrue(line, line.contains("tradePrints7819=2"))
        assertTrue(line, line.contains("tradeCandles7819=1"))
        assertTrue(line, line.contains("heliusSwaps["))
        LocalCandleSynthesis7055.resetForTest()
    }

    @Test fun tapeCandleWithTradeCountsDoesNotSilenceTheBinner() {
        val now = 1_800_000_000_000L
        val tape = Candle(ts = now - 5_000L, priceUsd = 1.0, marketCap = 0.0, volumeH1 = 2.0, volume24h = 50_000.0,
            buysH1 = 3, sellsH1 = 1, highUsd = 1.0, lowUsd = 1.0, openUsd = 1.0)
        assertFalse(LocalCandleSynthesis7055.fetchedKlineCovers7809(tape, now))
        val kline = Candle(ts = now - 30_000L, priceUsd = 1.0, marketCap = 0.0, volumeH1 = 0.0, volume24h = 900.0,
            highUsd = 1.1, lowUsd = 0.9, openUsd = 0.95)
        assertTrue(LocalCandleSynthesis7055.fetchedKlineCovers7809(kline, now))
    }

    @Test fun geckoCooldownEscalatesAndCaps() {
        assertEquals(60_000L, SolanaOhlcvFeed6916.escalatedCooldownMs7819(0L))
        assertEquals(120_000L, SolanaOhlcvFeed6916.escalatedCooldownMs7819(1L))
        assertEquals(480_000L, SolanaOhlcvFeed6916.escalatedCooldownMs7819(3L))
        assertEquals(900_000L, SolanaOhlcvFeed6916.escalatedCooldownMs7819(4L))
        assertEquals(900_000L, SolanaOhlcvFeed6916.escalatedCooldownMs7819(40L))
        val line = SolanaOhlcvFeed6916.statusLine()
        assertTrue(line, line.contains("perSource7819[gecko served="))
        assertTrue(line, line.contains("heliusSwaps served="))
    }

    @Test fun wiringContracts() {
        val plan = src("engine/truth/TradePlan7739.kt")
        assertTrue(plan.contains("private const val MIN_BARS_7739 = 5"))
        val live = plan.substringAfter("fun liveBlockReason(").substringBefore("val lp = ")
        assertTrue(live.indexOf("requestBarsIfShort7819(ts, nowMs)") in 0 until live.indexOf("if (paper) return null"))
        assertTrue(plan.contains("HeliusSwapCandles7819.request7819(ts, nowMs)"))
        val orch = src("engine/DataOrchestrator.kt")
        assertTrue(orch.contains("if (!held7787) onTradePrint7819(mint, safeSol / tokenAmt)"))
        assertTrue(orch.contains("LocalCandleSynthesis7055.noteTrade7819("))
        assertTrue(orch.contains("onHeliusTrade7773(mint, wallet, safeSol, isBuy)"))
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("orchestrator?.onTradePrint7819(mint, priceSol)"))
        val feed = src("network/SolanaOhlcvFeed6916.kt")
        assertTrue(feed.contains("if (code == 429 || consecutiveRejects.incrementAndGet() >= 3L)"))
    }
}
