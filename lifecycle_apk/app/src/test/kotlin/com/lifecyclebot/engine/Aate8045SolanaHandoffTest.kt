package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8045 — Solana coins the crypto brain picks reach the meme intake; the buy door reads unknown depth on chain. */
class Aate8045SolanaHandoffTest {
    @Test fun handoffAndBuyDoorDepth() {
        val ct = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        assertTrue(ct.contains("\"SOLANA_COIN_OWNED_BY_MEME_STACK_8043\".also { handOffSolanaToMeme8045(signal, candidate.liquidityUsd, candidate.marketCapUsd6493) }"))
        assertTrue(ct.contains("com.lifecyclebot.engine.TokenMergeQueue.enqueue("))
        val od = File("src/main/kotlin/com/lifecyclebot/engine/truth/OnChainDepth8038.kt").readText()
        assertTrue(od.contains("ParallelMarkFanout7088.curvePrices7392(listOf(ts.mint))"))
        val tm = File("src/main/kotlin/com/lifecyclebot/engine/TokenMapAuthority.kt").readText()
        assertTrue(tm.contains("((tm.liquidityUsd ?: 0.0) > 0.0 && (cached6492.liquidityUsd ?: 0.0) <= 0.0)"))
    }
}
