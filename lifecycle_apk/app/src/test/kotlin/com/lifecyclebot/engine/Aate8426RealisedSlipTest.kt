package com.lifecyclebot.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.8426 — realised slippage is the fill against the EXECUTED quote (the one the swap was built from), never the latest
 * probe quote of the mint; a protective sell's slippage is the stop's, never an entry's cost. 8422: buy=-/n2 sell=3.82%/n5
 * unmatched=24 -> slipRt=4.32% -> used=9.88% on a 0.03 SOL ticket.
 */
class Aate8426RealisedSlipTest {
    private val sol = "So11111111111111111111111111111111111111112"
    private val mint = "M8426".padEnd(44, 'm')
    private val t0 = 1_800_000_000_000L

    @Before fun reset() { RealisedSlip8418.resetForTest8418() }
    @After fun back() { RealisedSlip8418.resetForTest8418() }

    private fun probe(side: String, inA: Long, outA: Long, at: Long) {
        val j = org.json.JSONObject().put("inAmount", inA.toString()).put("outAmount", outA.toString()).put("priceImpactPct", "0.3")
        if (side == "BUY") RealisedSlip8418.noteQuote8418(sol, mint, j, at) else RealisedSlip8418.noteQuote8418(mint, sol, j, at)
    }

    @Test fun aBuyFillIsMeasuredAgainstTheExecutedQuoteNotTheLatestProbe() {
        probe("BUY", 100_000_000L, 1_000_000L, t0)                               // a 0.1 SOL mark probe
        RealisedSlip8418.noteExecuted8426(sol, mint, 30_000_000L, 300_000L, 0.4, t0 + 1_000L)   // the 0.03 SOL swap
        probe("BUY", 100_000_000L, 990_000L, t0 + 2_000L)                        // another probe before the fill verifies
        RealisedSlip8418.noteBuyFill8418(mint, BigInteger.valueOf(297_000L), 30_000_000L + 25_000L, 5_000L, t0 + 12_000L)
        val line = RealisedSlip8418.statusLine8418()
        assertTrue(line, line.contains("buy=-%/n1"))          // one sample (median needs five), not unmatched
        assertTrue(line, line.contains("unmatched=0"))
        // the pure arithmetic of that fill: 1 % worse than the executed quote
        assertEquals(1.0, RealisedSlip8418.buySlipPct8418(BigInteger.valueOf(30_000_000L), BigInteger.valueOf(300_000L), 30_000_000L + 20_000L, BigInteger.valueOf(297_000L))!!, 1e-9)
        // the old match (latest probe, 0.1 SOL in) would have refused the 0.03 SOL spend as another quote
        assertNull(RealisedSlip8418.buySlipPct8418(BigInteger.valueOf(100_000_000L), BigInteger.valueOf(990_000L), 30_000_000L + 20_000L, BigInteger.valueOf(297_000L)))
    }

    @Test fun aFillWithNoExecutedQuoteIsUnmatchedNeverGuessed() {
        probe("SELL", 1_000_000L, 50_000_000L, t0)
        RealisedSlip8418.noteSellFill8418(mint, BigInteger.valueOf(1_000_000L), 48_000_000L, 5_000L, 25_000L, t0 + 5_000L)
        val line = RealisedSlip8418.statusLine8418()
        assertTrue(line, line.contains("sell=-%/n0") && line.contains("unmatched=1"))
    }

    @Test fun aProtectiveSellIsMeasuredApartAndNeverPricesAnEntry() {
        repeat(5) { i ->
            RealisedSlip8418.noteExecuted8426(mint, sol, 1_000_000L, 50_000_000L, 0.5, t0 + i * 1_000L)
            // a stop sent with the protective tip, filled 3.67 % under its own quote
            RealisedSlip8418.noteSellFill8418(mint, BigInteger.valueOf(1_000_000L), 48_000_000L, 5_000L, 160_000L, t0 + i * 1_000L + 500L)
        }
        assertTrue(RealisedSlip8418.protectiveSell8426(160_000L))
        assertFalse(RealisedSlip8418.protectiveSell8426(CostTrim8151.ORDINARY_SENDER_TIP_LAMPORTS_8151))
        assertNull("five stop fills measure no ORDINARY sell slippage", RealisedSlip8418.medianPct8418("SELL"))
        val stop = RealisedSlip8418.stopMedianPct8426()
        assertTrue(stop != null && stop > 3.0 && stop < 4.5)
        // the entry's round-trip slippage stays the allowance on both sides: 1.0 %, not 4.32 %
        assertEquals(1.0, RoundTrip8418.slipRoundTripPctLive8418(), 1e-9)
        assertEquals("the 8422 arithmetic that priced entries at 9.88 %", 4.32, RoundTrip8418.slipRoundTripPct8418(null, 3.82), 1e-9)
        // ordinary sells do measure
        repeat(5) { i ->
            RealisedSlip8418.noteExecuted8426(mint, sol, 1_000_000L, 50_000_000L, 0.5, t0 + 100_000L + i * 1_000L)
            RealisedSlip8418.noteSellFill8418(mint, BigInteger.valueOf(1_000_000L), 49_500_000L, 5_000L, 25_000L, t0 + 100_000L + i * 1_000L + 500L)
        }
        val ordinary = RealisedSlip8418.medianPct8418("SELL")!!
        assertTrue(ordinary > 0.8 && ordinary < 1.1)
        assertEquals(0.5 + ordinary, RoundTrip8418.slipRoundTripPctLive8418(), 1e-9)
    }

    @Test fun wiring() {
        val jup = File("src/main/kotlin/com/lifecyclebot/network/JupiterApi.kt").readText()
        assertTrue(jup.contains("RealisedSlip8418.noteExecuted8426(quote.inputMint, quote.outputMint, quote.inAmount, quote.outAmount, quote.priceImpactPct)"))
        assertTrue("recorded before either build path (Ultra or Metis)", jup.indexOf("noteExecuted8426(") < jup.indexOf("if (quote.isUltra) {"))
        val rt = File("src/main/kotlin/com/lifecyclebot/engine/RoundTrip8418.kt").readText()
        assertFalse(rt.contains("val q = quotes[\"\$mint|BUY\"]?.takeIf { nowMs - it.atMs in 0L..QUOTE_MATCH_MS_8418 }"))
        assertFalse(rt.contains("val q = quotes[\"\$mint|SELL\"]?.takeIf { nowMs - it.atMs in 0L..QUOTE_MATCH_MS_8418 }"))
        assertTrue(rt.contains("record8418(false, slip, protective = protectiveSell8426(tipLamports))"))
        assertTrue(rt.contains("read(\"buy\", buys); read(\"sell8426\", sells); read(\"stop8426\", stops8426)"))
        val tv = File("src/main/kotlin/com/lifecyclebot/engine/TradeVerifier.kt").readText()
        assertTrue(tv.contains("RealisedSlip8418.noteSellFill8418(mint, rawConsumed, solReceived, parsed.feeLamports, CostMeter8151.lastSellTipLamports8418())"))
    }
}
