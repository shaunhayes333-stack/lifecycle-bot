package com.lifecyclebot.engine

import com.lifecyclebot.network.JupiterApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8089 — Jupiter POST 5xx (503 Service Unavailable on /swap and /execute) is a provider outage,
 * not a candidate fault: it is retried briefly, marks the builder degraded, and live buys pivot to the
 * Raydium builder instead of aborting on a sound quote.
 */
class Aate8089JupiterPostOutageTest {
    private fun src(p: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun fiveHundredsAreTransientAndRefusalsAreNot() {
        assertTrue(JupiterApi.isTransientPostFailure8089("Jupiter POST 503: Service Unavailable"))
        assertTrue(JupiterApi.isTransientPostFailure8089("Jupiter POST 502: no body"))
        assertTrue(JupiterApi.isTransientPostFailure8089("Jupiter POST timeout"))
        assertTrue(JupiterApi.isTransientPostFailure8089("Jupiter POST network error: reset"))
        assertFalse(JupiterApi.isTransientPostFailure8089("Jupiter POST 400: invalid quoteResponse"))
        assertFalse(JupiterApi.isTransientPostFailure8089("Jupiter API 401: API key required"))
        assertFalse(JupiterApi.isTransientPostFailure8089("Jupiter v6 buildSwapTx error: route not found"))
        assertFalse(JupiterApi.isTransientPostFailure8089(null))
        assertTrue(JupiterApi.isPost5xx8089(503)); assertFalse(JupiterApi.isPost5xx8089(429)); assertFalse(JupiterApi.isPost5xx8089(400))
    }

    @Test fun threeFiveHundredsInsideTheWindowDegradeTheBuilderForAMinuteOnly() {
        val t0 = 10_000_000L
        assertFalse(JupiterApi.swapBuildDegraded8089(t0))
        JupiterApi.notePost5xx8089("JUPITER_SWAP_BUILD", 503, t0)
        JupiterApi.notePost5xx8089("JUPITER_SWAP_BUILD", 503, t0 + 1_000L)
        assertFalse("two 5xx are noise", JupiterApi.swapBuildDegraded8089(t0 + 1_000L))
        JupiterApi.notePost5xx8089("JUPITER_SWAP_BUILD", 503, t0 + 2_000L)
        assertTrue("third 5xx within 90 s degrades", JupiterApi.swapBuildDegraded8089(t0 + 2_000L))
        assertTrue(JupiterApi.swapBuildDegraded8089(t0 + 2_000L + 59_000L))
        assertFalse("hold expires", JupiterApi.swapBuildDegraded8089(t0 + 2_000L + 61_000L))
    }

    @Test fun liveBuyPivotsToRaydiumOnBuilderOutageAndNeverBlocks() {
        val ex = src("engine/Executor.kt")
        val jup = src("network/JupiterApi.kt")
        val wal = src("network/SolanaWallet.kt")
        assertTrue("5xx retried per host then next host", jup.contains("if (attempt >= 1) throw lastErr   // two tries per host, then the next host / venue pivot"))
        assertTrue("5xx noted for the gauge", jup.contains("notePost5xx8089(endpoint, code)"))
        assertTrue("degraded builder → Raydium first", ex.contains("if (JupiterApi.swapBuildDegraded8089()) {"))
        assertTrue("build 5xx → Raydium, not abort", ex.contains("buildBuyTxOrRaydium8089(ts, wallet, sol, tradeKey, executionQuote7688, c, submission8397) { raydiumBuy7325 = it }"))
        assertTrue("non-transient build errors keep their true cause", ex.contains("if (!JupiterApi.isTransientPostFailure8089(e.message)) throw e"))
        assertTrue("Raydium miss keeps the Jupiter error", ex.contains("LIVE_BUY_BUILD_5XX_NO_VENUE_8089"))
        assertTrue("no second full build on a transient", ex.contains("if (JupiterApi.isTransientPostFailure8089(e.message)) throw e\n            Thread.sleep(1000)"))
        assertTrue("non-RFQ /execute 5xx → RPC self-broadcast", wal.contains("if (post5xx8089 && !isRfqRoute) {"))
        // Jupiter is still attempted when degraded (venue ordering only): the degraded branch sits before the quote ladder, not around it.
        val deg = ex.indexOf("if (JupiterApi.swapBuildDegraded8089()) {")
        val ladder = ex.indexOf("for (slip in slippageLadder) {", deg)
        assertTrue(deg in 1 until ladder)
    }
}
