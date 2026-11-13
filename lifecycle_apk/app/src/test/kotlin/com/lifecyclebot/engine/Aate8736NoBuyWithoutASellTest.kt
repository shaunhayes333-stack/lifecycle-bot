package com.lifecyclebot.engine

import com.lifecyclebot.network.JupiterApi
import com.lifecyclebot.network.SwapQuote
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8736 — no Jupiter buy is signed unless its exact tokens quote back to SOL now; the explore path is off. */
class Aate8736NoBuyWithoutASellTest {
    private val mint = "Sell8736AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private fun q(inMint: String, outMint: String, inAmt: Long, outAmt: Long) = SwapQuote(
        raw = JSONObject(), outAmount = outAmt, priceImpactPct = 0.1, inputMint = inMint, outputMint = outMint,
        inAmount = inAmt, router = "metis", isRfqRoute = false, receivedAtMs8521 = 0L)

    @Test fun theRoundTripMustBeQuotedAndHoldHalf() {
        val buy = q(JupiterApi.SOL_MINT, mint, 40_000_000L, 90_000_000_000L)
        assertNull(BuyRouteSafety8522.sellableRefusal8736(buy, q(mint, JupiterApi.SOL_MINT, 90_000_000_000L, 37_000_000L)))
        assertEquals("BUY_SELL_UNPROVEN_NO_SELL_ROUTE_8736", BuyRouteSafety8522.sellableRefusal8736(buy, null)?.first)
        assertEquals("BUY_SELL_UNPROVEN_ZERO_OUT_8736",
            BuyRouteSafety8522.sellableRefusal8736(buy, q(mint, JupiterApi.SOL_MINT, 1L, 0L))?.first)
        assertEquals("BUY_ROUND_TRIP_LOSES_TOO_MUCH_8736",
            BuyRouteSafety8522.sellableRefusal8736(buy, q(mint, JupiterApi.SOL_MINT, 1L, 19_000_000L))?.first)
        assertEquals("BUY_SELL_UNPROVEN_NO_BUY_QUOTE_8736", BuyRouteSafety8522.sellableRefusal8736(null, null)?.first)
    }

    @Test fun everyJupiterBuyProvesTheSellAndExploreIsOff() {
        val ex = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val sign = ex.indexOf("BuyRouteSafety8522.requireAllowed(ts, BuyRouteSafety8522.jupiter(tx.executionQuote8521), \"JUPITER_FINAL_SIGN\")")
        val sell = ex.indexOf("BuyRouteSafety8522.requireSellable8736(ts, tx.executionQuote8521,")
        val send = ex.indexOf("wallet.signSendAndConfirm(tx.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey, tx.isRfqRoute, tx.senderCompatible)")
        assertTrue(sign in 0 until sell && sell < send)
        val explore = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExploreLive8733.kt").readText()
        assertTrue(explore.contains("private const val ENABLED_8736 = false") && explore.contains("if (!ENABLED_8736) return false"))
    }
}
