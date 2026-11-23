package com.lifecyclebot.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8972 — a non-pump.fun pool is charged the pool fee its own quote names, not a flat 0.25 % a side. */
class Aate8972PoolFeeTest {
    private val SOL = "So11111111111111111111111111111111111111112"
    private val JUP = "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN"
    private fun hop(inMint: String, outMint: String, inA: String, outA: String, fee: String, feeMint: String, nested: Boolean = true, percent: Int = 100): JSONObject {
        val info = JSONObject().put("inputMint", inMint).put("outputMint", outMint).put("inAmount", inA).put("outAmount", outA)
            .put("feeAmount", fee).put("feeMint", feeMint).put("ammKey", "pool")
        return if (nested) JSONObject().put("swapInfo", info).put("percent", percent) else info.put("percent", percent)
    }

    @Test fun aSingleHopsFeeIsItsShareOfTheHop() {
        // 0.04 % taken in the input mint
        val f = RoundTrip8418.poolFeePct8972(JSONArray().put(hop(SOL, JUP, "100000000", "5000", "40000", SOL)))!!
        assertEquals(0.04, f, 1e-9)
        // taken in the output mint: fee / (out + fee)
        val g = RoundTrip8418.poolFeePct8972(JSONArray().put(hop(SOL, JUP, "100000000", "9970", "30", JUP, nested = false)))!!
        assertEquals(0.3, g, 1e-9)
    }

    @Test fun aChainAddsItsHopsAndAnythingAmbiguousFallsBackToTheModel() {
        val two = JSONArray().put(hop(SOL, "USDC", "100000000", "750000", "10000", SOL)).put(hop("USDC", JUP, "750000", "1000", "75", "USDC"))
        assertEquals(0.01 + 0.01, RoundTrip8418.poolFeePct8972(two)!!, 1e-9)
        assertNull("a split route", RoundTrip8418.poolFeePct8972(JSONArray().put(hop(SOL, JUP, "1", "1", "0", SOL, percent = 60))))
        assertNull("an unpriced fee mint", RoundTrip8418.poolFeePct8972(JSONArray().put(hop(SOL, JUP, "100", "100", "1", "OTHER"))))
        assertNull(RoundTrip8418.poolFeePct8972(null))
        assertNull(RoundTrip8418.poolFeePct8972(JSONArray()))
        assertEquals("a zero-fee report is floored", RoundTrip8418.POOL_FEE_FLOOR_PCT_8972,
            RoundTrip8418.poolFeePct8972(JSONArray().put(hop(SOL, JUP, "100", "100", "0", SOL)))!!, 1e-12)
    }

    @Test fun aQuoteFilesItsFeeAndTheCostReadsItForANonPumpPoolOnly() {
        val mint = "Fee8972MintAddress1111111111111111111111111"
        val q = JSONObject().put("inAmount", "100000000").put("outAmount", "5000")
            .put("routePlan", JSONArray().put(hop(SOL, mint, "100000000", "5000", "50000", SOL)))
        RoundTrip8418.noteQuote8418(SOL, mint, q, 1_000L)
        assertEquals(0.05, RoundTrip8418.quotedPoolFeePct8972(mint, 2_000L)!!, 1e-9)
        assertNull("six hours later the model returns", RoundTrip8418.quotedPoolFeePct8972(mint, 1_000L + RoundTrip8418.POOL_FEE_TTL_MS_8972 + 1))
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/NetEdge8030.kt").readText()
        assertTrue(src.contains("PaperVenueCost7287.venue(mint, liquidityUsd) != PaperVenueCost7287.Venue.AMM) null"))
        assertTrue(src.contains("val venuePct = quoted8972 ?: venueModel8972"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/Costs8369.kt").readText().contains("NetEdge8030.venueFeeLine8972()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 157. The pool fee is the quote's (8972)"))
    }
}
