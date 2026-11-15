package com.lifecyclebot.engine

import com.lifecyclebot.desks.DeskJournal8802
import com.lifecyclebot.hyperliquid.HlBrain
import com.lifecyclebot.network.PythAuth8802
import com.lifecyclebot.perps.PerpsMarket
import com.lifecyclebot.xstocks.XsApi
import com.lifecyclebot.xstocks.XsDesk
import com.lifecyclebot.xstocks.XsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * V5.0.8802 — owner, on GPT's audit of 8800: retiring the stock trader whole (8797) was too broad; xStocks are real Solana
 * tokens. They trade again on their own desk — long or flat, 1x, priced and costed by executable quotes of the token itself,
 * its own history, 24/7 — and the fake short / fake leverage of the old route are refused. Both desks keep a trade book.
 * Pyth's Hermes needs an API key since 26 Aug 2026: every call carries the owner's key, none storms without one.
 */
class Aate8802XStocksDeskTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theTradeBookKeepsAverageCostRoundTripsAndFees() {
        val b = DeskJournal8802.Book()
        fun f(q: Double, px: Double, fee: Double = 0.0) = DeskJournal8802.apply(b, DeskJournal8802.Fill(0L, "X", "PAPER", "AAPL", q, px, fee))
        assertEquals(0.0, f(10.0, 100.0, 1.0), 1e-9)
        assertEquals(40.0, f(-4.0, 110.0), 1e-9)                       // 4 sold 10 over cost
        assertEquals(-60.0, f(-6.0, 90.0), 1e-9)                       // the rest 10 under
        assertEquals(1, b.trips); assertEquals(0, b.won)               // -20 and a dollar of fees: a losing trip
        assertTrue(b.lots.isEmpty())
        f(5.0, 100.0)
        assertEquals(100.0, f(-8.0, 120.0), 1e-9)                      // closes 5 long (+100) and opens 3 short at 120
        assertEquals(2, b.trips); assertEquals(1, b.won)
        assertEquals(-3.0, b.lots.getValue("AAPL").qty, 1e-12)
        assertEquals(30.0, f(3.0, 110.0), 1e-9)                        // the short covers 10 under
        assertEquals(3, b.trips); assertEquals(2, b.won)
        assertEquals(110.0, b.realisedUsd, 1e-9); assertEquals(1.0, b.feesUsd, 1e-9)
        val fill = DeskJournal8802.Fill(5L, "HYPERLIQUID", "LIVE", "BTC", -0.01, 95_000.0, 0.43, "DAILY", "oid=7")
        assertEquals(fill, DeskJournal8802.fromLine(DeskJournal8802.toLine(fill)))
        assertNull(DeskJournal8802.fromLine("not json"))
    }

    @Test fun spotIsGradedAsItCanBeHeldLongOrFlat() {
        val w = HlBrain.longOnly8802(arrayOf(doubleArrayOf(-0.5, 0.3, 0.2), doubleArrayOf(-0.5, -0.5, 0.0)))
        assertEquals(0.0, w[0][0], 0.0); assertEquals(0.6, w[0][1], 1e-12); assertEquals(0.4, w[0][2], 1e-12)
        assertTrue(w[1].all { it == 0.0 })
        val r = Random(8802)
        val days = 300; val n = 4
        val close = Array(n) { c -> DoubleArray(days).also { a -> var p = 100.0; for (d in 0 until days) { p *= 1.0 + (if (c == 0) 0.004 else -0.002) + r.nextDouble(-0.01, 0.01); a[d] = p } } }
        fun nan() = Array(n) { DoubleArray(days) { Double.NaN } }
        val panel = HlBrain.Panel(List(n) { "xs:S$it" }, 0L, close, Array(n) { DoubleArray(days) { 1e6 } }, nan(), nan(), nan(), DoubleArray(n) { 0.006 })
        val ev = HlBrain.evaluate(panel, 1.0, longOnly = true)
        assertTrue("a spot book never holds a negative weight", ev.targets.all { it >= 0.0 })
        assertTrue(ev.leverage <= 1.0)
        assertTrue(src("xstocks/XsDesk.kt").contains("HlBrain.evaluate(panel, 1.0, longOnly = true)"))
    }

    @Test fun pricesAndHistoryComeFromTheTokenItself() {
        val pools = XsApi.parsePools("""{"data":[{"attributes":{"address":"P1","reserve_in_usd":"1200.5","volume_usd":{"h24":"10"}}},
            {"attributes":{"address":"P2","reserve_in_usd":"98000","volume_usd":{"h24":"5000"}}},{"attributes":{"address":"","reserve_in_usd":"5"}}]}""")
        assertEquals(listOf("P2", "P1"), pools.map { it.address })
        val day = 20_400L
        val bars = XsApi.parseDailyBars("""{"data":{"attributes":{"ohlcv_list":[[${day * 86_400},101,103,99,102,5000],[${(day - 1) * 86_400},100,101,98,100.5,4000],[0,1,1,1,1,1]]}}}""")
        assertEquals(2, bars.size)
        assertEquals(102.0, bars.getValue(day)[3], 0.0)
        // Token-2022 Scaled UI: the multiplier in force (a dividend folded in), 1 without the extension
        fun mint(ts: Long) = org.json.JSONObject("""{"data":{"parsed":{"info":{"decimals":8,"extensions":[{"extension":"scaledUiAmountConfig",
            "state":{"multiplier":"1.0","newMultiplier":"1.02","newMultiplierEffectiveTimestamp":$ts}}]}}}}""")
        assertEquals(1.02, XsApi.parseMint(mint(1_000L), 2_000_000L)!!.multiplier, 1e-12)
        assertEquals(1.0, XsApi.parseMint(mint(9_999_999_999L), 2_000_000L)!!.multiplier, 1e-12)
        val plain = XsApi.parseMint(org.json.JSONObject("""{"data":{"parsed":{"info":{"decimals":8}}}}"""), 0L)!!
        assertEquals(8, plain.decimals); assertEquals(1.0, plain.multiplier, 0.0)
        // one SOL buys one token, which sells back for 0.98 SOL: ask $73, bid $71.54, 1 % a side
        val q = XsApi.RoundTrip(1_000_000_000L, 100_000_000L, 980_000_000L, 1_000L)
        assertEquals(73.0, q.askUsd(8, 73.0), 1e-9); assertEquals(71.54, q.bidUsd(8, 73.0), 1e-9); assertEquals(0.01, q.costSide, 1e-12)
        // the desk's cost: the measured half round trip plus the 0.5 % app fee; unmeasured or stale costs more
        assertEquals(0.015, XsDesk.costSideOf(q, 2_000L), 1e-12)
        assertEquals(0.025, XsDesk.costSideOf(null, 2_000L), 1e-12)
        assertEquals(0.025, XsDesk.costSideOf(q, 1_000L + 25L * 3_600_000L), 1e-12)
        assertEquals(72.27, XsDesk.markOf(q, 8, 73.0, 60.0, 2_000L), 1e-9)       // the executable mid
        assertEquals(60.0, XsDesk.markOf(q, 8, 73.0, 60.0, 1_000L + 4L * 3_600_000L), 0.0)   // stale: the last close
        assertTrue(XsDesk.markOf(null, null, null, null, 0L).isNaN())
    }

    @Test fun theDeskIsLongOrFlatSizedByItsBudgetAndStopped() {
        val u = XsDesk.universeOf(PerpsMarket.values().toList()) { when (it) { "AAPL" -> "m1"; "TSLA" -> "m2"; "SOL" -> "m3"; else -> null } }
        assertEquals(setOf("AAPL", "TSLA"), u.map { it.symbol }.toSet())      // SOL is crypto: not an xStock
        val sizes = XsDesk.openSizes(mapOf("AAPL" to 0.6, "TSLA" to 0.4, "NVDA" to 0.05), 0.5, 0.5, setOf("TSLA"))
        assertEquals(mapOf("AAPL" to 0.15), sizes)                             // held TSLA is not reopened; NVDA's 0.0125 SOL is too small
        assertEquals("LIVE_OFF", XsDesk.liveGate(XsSettings.liveRefusal(XsSettings.Config(false, 1.0, 6.0, 20.0), true), true, false))
        assertEquals("NO_WALLET", XsSettings.liveRefusal(XsSettings.Config(true, 1.0, 6.0, 20.0), false))
        assertEquals("BRAIN_NOT_ARMED", XsDesk.liveGate(null, false, false))
        assertEquals("STOP_HIT_SWITCH_LIVE_OFF_AND_ON", XsDesk.liveGate(null, true, true))
        assertNull(XsDesk.liveGate(null, true, false))
        assertEquals("DAILY_STOP", XsDesk.stopHit(0.93, 1.0, 1.0, 6.0, 20.0))
        assertEquals("DRAWDOWN_STOP", XsDesk.stopHit(0.79, 0.8, 1.0, 6.0, 20.0))
        assertNull(XsDesk.stopHit(0.97, 1.0, 1.0, 6.0, 20.0))
        val desk = src("xstocks/XsDesk.kt")
        assertTrue(desk.contains("MarketsLiveExecutor.executeLiveTradeProof6486(id, s.market, PerpsDirection.LONG, sizeSol, 1.0, ask, LANE)"))
        assertTrue(desk.contains("MarketsLiveExecutor.closeLivePositionProof6486(p.positionId, s.market, PerpsDirection.LONG,"))
        assertFalse("the desk never asks for a short", desk.contains("PerpsDirection.SHORT"))
    }

    @Test fun theOldRoutesFakeShortAndLeverageAreRefused() {
        val ex = src("perps/MarketsLiveExecutor.kt")
        assertTrue(ex.contains("TOKENIZED_SPOT_CANNOT_SHORT_8802") && ex.contains("TOKENIZED_SPOT_HAS_NO_LEVERAGE_8802") && ex.contains("SPOT_CANNOT_SHORT_8802"))
        assertFalse("no SOL -> USDC 'short' remains", ex.contains("MARKETS_SHORT_BRIDGE"))
        assertFalse("no spot-scale leverage remains", ex.contains("effectiveSpotSizeSol"))
        assertTrue(ex.contains("val walletRaw = tokenData.raw"))
        val retired = src("perps/RetiredMarkets8797.kt")
        assertTrue(retired.contains("xStocks spot desk XsDesk 8802"))
        assertTrue(src("engine/BotService.kt").contains("com.lifecyclebot.xstocks.XsDesk.ensureStarted(applicationContext)"))
    }

    @Test fun bothDesksKeepATradeBookAndReportIt() {
        assertTrue(src("hyperliquid/HlExecutor.kt").contains("com.lifecyclebot.desks.DeskJournal8802.record("))
        assertTrue(src("hyperliquid/HlDesk.kt").contains("\"HYPERLIQUID\", \"PAPER\", coin, delta / m, m,"))
        val report = src("engine/PipelineHealthCollector.kt")
        assertTrue(report.contains("com.lifecyclebot.xstocks.XsDesk.statusLine()") && report.contains("com.lifecyclebot.desks.DeskJournal8802.statusLine()")
            && report.contains("com.lifecyclebot.network.PythAuth8802.statusLine()"))
        val ui = src("ui/HyperliquidActivity.kt")
        assertTrue(ui.contains("XsDesk.sellAllNow()") && ui.contains("PythAuth8802.save(this, k)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 12. The desks (8802)"))
    }

    @Test fun everyHermesCallCarriesTheKeyAndNoneStormsWithoutOne() {
        assertTrue(PythAuth8802.mayCall(true, 0L, 1L))
        assertFalse(PythAuth8802.mayCall(false, 1_000_000L, 1_000_000L + 60_000L))
        assertTrue(PythAuth8802.mayCall(false, 1_000_000L, 1_000_000L + 30L * 60_000L))
        val oracle = src("perps/PythOracle.kt")
        assertEquals(2, Regex("PythAuth8802\\.apply\\(it\\)").findAll(oracle).count())
        assertEquals(2, Regex("PythAuth8802\\.shouldCall\\(\\)").findAll(oracle).count())
        val stream = src("network/PythHermesStream.kt")
        assertTrue(stream.contains("if (!PythAuth8802.hasKey())") && stream.contains(".let { PythAuth8802.apply(it) }"))
        assertTrue(src("network/LiveReadinessChecker.kt").contains("return httpPing(url, pyth8802 = true)"))
    }
}
