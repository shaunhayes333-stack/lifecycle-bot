package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8045 — Solana coins the crypto brain picks reach the meme intake; the buy door reads unknown depth on chain. */
class Aate8045SolanaHandoffTest {
    @Test fun handoffAndBuyDoorDepth() {
        val ct = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        // V5.0.8714 — the hand-off now follows a refusal by the crypto desk, not a blanket ownership rule.
        assertTrue(ct.contains("if (solanaCoinRefused8714(paper7803, signal.market == PerpsMarket.DYN, signal.dynChainId))"))
        assertTrue(ct.contains("com.lifecyclebot.engine.TokenMergeQueue.enqueue("))
        val od = File("src/main/kotlin/com/lifecyclebot/engine/truth/OnChainDepth8038.kt").readText()
        assertTrue(od.contains("ParallelMarkFanout7088.curvePrices7392(listOf(ts.mint), latencyCritical8527 = true)"))
        val tm = File("src/main/kotlin/com/lifecyclebot/engine/TokenMapAuthority.kt").readText()
        assertTrue(tm.contains("((tm.liquidityUsd ?: 0.0) > 0.0 && (cached6492.liquidityUsd ?: 0.0) <= 0.0)"))
        // A swap must fit one transaction: routes are capped in accounts.
        org.junit.Assert.assertEquals(48, com.lifecyclebot.network.JupiterApi.MAX_ROUTE_ACCOUNTS_8045)
        assertTrue(File("src/main/kotlin/com/lifecyclebot/network/JupiterApi.kt").readText().contains("&maxAccounts=\$MAX_ROUTE_ACCOUNTS_8045"))
    }
}
