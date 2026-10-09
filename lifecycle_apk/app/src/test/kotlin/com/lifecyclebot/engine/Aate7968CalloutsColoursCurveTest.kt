package com.lifecyclebot.engine

import com.lifecyclebot.engine.chart.Bar7950
import com.lifecyclebot.engine.chart.CandleColors7968
import com.lifecyclebot.engine.chart.ChartParsers7955
import com.lifecyclebot.engine.chart.ChartReader7950
import com.lifecyclebot.engine.chart.MotifOutcome7950
import com.lifecyclebot.engine.truth.FreshLaunchSelector7737
import com.lifecyclebot.network.CurveTicks7968
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Aate7968CalloutsColoursCurveTest {

    // ── dev sells are not a veto ──

    @Test fun devSaleIsNotAVetoAnywhere() {
        assertNull(FreshLaunchSelector7737.structuralRefusal(3, 0.0, "FLOW_STRONG", "CONC_BROAD", 1.2))
        assertNull(ChartReader7950.exitSignal(ChartReader7950.Read(null, 40, 0.6, true, 0L)))
    }

    // ── candle colours ──

    private fun bar(i: Int, o: Double, c: Double) = Bar7950(i * 60_000L, o, maxOf(o, c), minOf(o, c), c, 1.0)

    /** A series whose colours repeat every 8 bars, priced at [scale]. */
    private fun series(scale: Double, n: Int = 60): List<Bar7950> {
        val pattern = doubleArrayOf(0.01, -0.01, 0.0, 0.01, 0.03, 0.01, -0.01, 0.02)
        var px = scale
        return (0 until n).map { i ->
            val o = px; val c = px * (1.0 + pattern[i % pattern.size]); px = c; bar(i, o, c)
        }
    }

    @Test fun lettersAndKeysIgnorePriceLevel() {
        assertEquals('G', CandleColors7968.letter7968(bar(0, 1.0, 1.05), 0.01))
        assertEquals('g', CandleColors7968.letter7968(bar(0, 1.0, 1.01), 0.01))
        assertEquals('d', CandleColors7968.letter7968(bar(0, 1.0, 1.0001), 0.01))
        assertEquals('R', CandleColors7968.letter7968(bar(0, 1.0, 0.95), 0.01))
        assertEquals('r', CandleColors7968.letter7968(bar(0, 1.0, 0.99), 0.01))
        // Same colours at $0.00001 and at $10: same keys.
        val a = CandleColors7968.keys7968(series(0.00001), 40)
        val b = CandleColors7968.keys7968(series(10.0), 40)
        assertNotNull(a)
        assertEquals(a, b)
        assertNull(CandleColors7968.keys7968(series(1.0), 2))
    }

    @Test fun provenNeedsEvidenceAndEdge() {
        assertFalse(CandleColors7968.proven7968(20, 18, 200.0, 4_000.0, 0.3))           // too few
        assertTrue(CandleColors7968.proven7968(100, 70, 800.0, 20_000.0, 0.3))           // 70% vs 30% base, +8% mean
        assertFalse(CandleColors7968.proven7968(100, 70, -300.0, 20_000.0, 0.3))         // hits but loses on the end
        assertFalse(CandleColors7968.proven7968(100, 36, 300.0, 20_000.0, 0.3))          // barely above base
    }

    @Test fun learnsASequenceThatPrecedesRunsAndReadsItLive() {
        CandleColors7968.resetForTest7968()
        val s = series(0.001)
        val win = MotifOutcome7950(true, 40f, -3f, 25f)
        val lose = MotifOutcome7950(false, 2f, -20f, -15f)
        val key = CandleColors7968.keys7968(s, 47)!!.first
        // Teach: the target key wins, every other key loses.
        repeat(12) {
            for (end in 10 until 50) {
                val k = CandleColors7968.keys7968(s, end)!!.first
                CandleColors7968.learn7968(s, end, if (k == key) win else lose)
            }
        }
        val r = CandleColors7968.read7968(s.subList(0, 48))
        assertNotNull(r)
        assertEquals(key, r!!.key)
        assertTrue(r.buy)
        val other = CandleColors7968.read7968(s.subList(0, 46))!!
        assertFalse(other.buy)
        assertTrue(CandleColors7968.statusLine7968().contains("proven="))
        CandleColors7968.resetForTest7968()
    }

    @Test fun provenColourSequenceIsABuyWithoutAMotif() {
        assertTrue(ChartReader7950.buySignal(ChartReader7950.Read(null, 40, 0.7, false, 0L, 0L, true), Double.NaN))
        assertFalse(ChartReader7950.buySignal(ChartReader7950.Read(null, 40, 0.3, false, 0L, 0L, true), Double.NaN))  // sellers own the tape
        assertFalse(ChartReader7950.buySignal(ChartReader7950.Read(null, 40, 0.7, false, 0L), Double.NaN))
    }

    // ── pump.fun callouts ──

    private val board = """
        {"callouts":[
          {"userId":"u-good","primaryWallet":"7dN2CS4yarnx236p7ik8EEWxW7bxnzb2nBZ9aL1kGfbz","wallets":["9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"],
           "totalCallouts":40,"avgMultiple":3.1,"medianMultiple":1.9,"pct2xOrMore":48,"averageTimeToPeak":900,
           "topCallouts":[{"calloutId":"c1","userId":"u-good","coinMint":"GTBxAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump","marketCap":"8500","multiple":"12.5","createdAt":"2026-10-09T10:00:00Z"}]},
          {"userId":"u-new","primaryWallet":"","wallets":[],"totalCallouts":3,"avgMultiple":9.0,"medianMultiple":5.0,"pct2xOrMore":1.0,"topCallouts":[]}
        ]}""".trimIndent()

    @Test fun parsesTheLeaderboardAndRanksCallers() {
        val (callers, calls) = PumpCallouts7968.parseLeaderboard7968(board)
        assertEquals(2, callers.size)
        val good = callers.first { it.userId == "u-good" }
        assertEquals(0.48, good.pct2x, 1e-9)
        assertEquals(2, good.wallets.size)
        assertTrue(PumpCallouts7968.provenCaller7968(good))
        assertFalse(PumpCallouts7968.provenCaller7968(callers.first { it.userId == "u-new" }))   // 3 calls is luck, not a record
        assertEquals(1, calls.size)
        assertEquals(8500.0, calls[0].marketCapUsd, 1e-9)
        assertEquals(12.5, calls[0].multiple, 1e-9)
        assertEquals("MC_LT10K", PumpCallouts7968.mcBand7968(calls[0].marketCapUsd))
        assertTrue(PumpCallouts7968.confidence7968(good) in 50..95)
    }

    @Test fun freshCallsOnlyAndTimesInAnyFormat() {
        assertEquals(1_700_000_000_000L, PumpCallouts7968.timeMs7968(1_700_000_000L))
        assertEquals(1_700_000_000_000L, PumpCallouts7968.timeMs7968(1_700_000_000_000L))
        assertEquals(1_700_000_000_000L, PumpCallouts7968.timeMs7968("1700000000000"))
        assertTrue(PumpCallouts7968.timeMs7968("2026-10-09T10:00:00Z") > 0L)
        assertEquals(0L, PumpCallouts7968.timeMs7968("yesterday"))
        assertEquals(0.5, PumpCallouts7968.frac7968(50.0), 1e-9)
        assertEquals(0.5, PumpCallouts7968.frac7968(0.5), 1e-9)
        val now = 1_700_000_000_000L
        val list = PumpCallouts7968.parseCallList7968(
            """{"callouts":[{"coinMint":"GTBxAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump","marketCap":6000,"multiple":1,"createdAt":${now - 5 * 60_000L}},
                {"coinMint":"HgBRAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump","marketCap":6000,"multiple":1,"createdAt":${now - 60 * 60_000L}},
                {"coinMint":"short","createdAt":$now}]}""", "u-good")
        assertEquals(2, list.size)
        assertTrue(PumpCallouts7968.fresh7968(list[0], now))
        assertFalse(PumpCallouts7968.fresh7968(list[1], now))
        assertTrue(PumpCallouts7968.statusLine7968().contains("callers="))
    }

    // ── free curve ticks ──

    private fun curve(vTok: Long, vSol: Long, supply: Long, complete: Boolean): ByteArray {
        val b = ByteBuffer.allocate(49).order(ByteOrder.LITTLE_ENDIAN)
        b.putLong(0L).putLong(vTok).putLong(vSol).putLong(0L).putLong(0L).putLong(supply).put((if (complete) 1 else 0).toByte())
        return b.array()
    }

    @Test fun decodesTheBondingCurveIntoAPrice() {
        // Launch reserves: 1.073B tokens (6 dp) vs 30 SOL -> ~2.8e-8 SOL per token, ~28 SOL mcap.
        val (px, mc) = CurveTicks7968.decode7968(curve(1_073_000_000_000_000L, 30_000_000_000L, 1_000_000_000_000_000L, false))!!
        assertEquals(30.0 / 1_073_000_000.0, px, 1e-15)
        assertEquals(px * 1_000_000_000.0, mc, 1e-6)
        assertNull(CurveTicks7968.decode7968(curve(1L, 1L, 1L, true)))        // graduated
        assertNull(CurveTicks7968.decode7968(ByteArray(20)))                  // short
        assertTrue(CurveTicks7968.statusLine7968().contains("ticks="))
    }

    @Test fun pumpCoinRowsCarryCreationTime() {
        val m = ChartParsers7955.pumpCreated7968(JSONArray("""[{"mint":"abc","created_timestamp":1700000000},{"mint":"def","created_timestamp":1700000000123},{"mint":"x"}]"""))
        assertEquals(1_700_000_000_000L, m["abc"])
        assertEquals(1_700_000_000_123L, m["def"])
        assertNull(m["x"])
    }
}
