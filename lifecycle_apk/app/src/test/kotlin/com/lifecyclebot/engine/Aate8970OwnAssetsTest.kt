package com.lifecyclebot.engine

import com.lifecyclebot.perps.DynamicAltTokenRegistry
import com.lifecyclebot.perps.DynamicAltTokenRegistry.DynToken
import com.lifecyclebot.perps.TokenizedAssetRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8970 — owner: meme = the Solana network's coins (+ SOL stables); crypto = established Solana majors, stables, wrapped
 * BTC / ETH and other crypto; Markets = xStocks, gold, silver, leverage. The crypto trader scans only its own assets.
 */
class Aate8970OwnAssetsTest {
    private val r = DynamicAltTokenRegistry
    private val now = System.currentTimeMillis()
    private fun sol(sym: String, cap: Double, ageMin: Double?, mint: String = "So1ana${sym}MintAddress00000000000000000") =
        DynToken(mint = mint, symbol = sym, name = sym, chainId = "solana", mcap = cap,
            pairCreatedAtMs = ageMin?.let { now - (it * 60_000.0).toLong() } ?: 0L)

    @Test fun aYoungSmallSolanaCoinIsTheMemeTraders() {
        assertFalse(r.cryptoAsset8970(sol("LAUNCH", 40_000.0, 30.0, "mGzw6zwvig3GitrCM6sGJXdRWeWz81X2vyzVH3Upump")))
        assertFalse("an hour to a day old", r.cryptoAsset8970(sol("YOUNG", 400_000.0, 300.0)))
        assertFalse("a day old but under 100k", r.cryptoAsset8970(sol("SMALL", 60_000.0, 3_000.0)))
        assertFalse("unknown age counts only through the 1M cap", r.cryptoAsset8970(sol("UNDATED", 500_000.0, null)))
    }

    @Test fun establishedSolanaMajorsStablesOtherChainsAndCuratedMajorsAreCryptos() {
        assertTrue("JUP-scale", r.cryptoAsset8970(sol("JUP", 2.0e9, null)))
        assertTrue("1M+ at any age", r.cryptoAsset8970(sol("FARTCOIN", 1.2e6, 20.0, "9BB6NFEcjBCtnNLFko2FqVQBq8HHM13kCyYcdQbgpump")))
        assertTrue("a day old and 100k+", r.cryptoAsset8970(sol("OLDER", 150_000.0, 2_000.0)))
        assertTrue(r.cryptoAsset8970(sol("USDC", 0.0, 10.0)))
        assertTrue(r.cryptoAsset8970(DynToken(mint = "0xabc", symbol = "ARB", name = "ARB", chainId = "arbitrum")))
        assertTrue(r.cryptoAsset8970(DynToken(mint = "static:BTC", symbol = "BTC", name = "BTC", isStatic = true)))
        assertTrue("a coin the desk holds is always managed", r.cryptoAsset8970(sol("LAUNCH", 40_000.0, 30.0), held = true))
    }

    @Test fun marketsAssetsAreNeverTheCryptoTraders() {
        val paxg = TokenizedAssetRegistry.mintFor("PAXG")!!
        assertFalse(r.cryptoAsset8970(DynToken(mint = paxg, symbol = "PAXG", name = "PAXG", chainId = "solana", isStatic = true, mcap = 5.0e8)))
    }

    @Test fun theMemeSidesCoinsAreNotPulledIn() {
        assertNull(r.ingestMemeWatch8789("8xegBRcvNUMYTtxno5DnPLMMHRXQg9RrUVnQwEVppump", "X", 13_000.0))
        assertNull(r.ingestMarketHunt7807("8xegBRcvNUMYTtxno5DnPLMMHRXQg9RrUVnQwEVppump", "X", "X", 0.001, 50_000.0, 500_000.0, 2.0))
    }

    @Test fun wired() {
        val reg = File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
        assertTrue(reg.contains("registry.values.filter { (cryptoMayTrade8789(it.mint) || it.isStatic) && cryptoAsset8970(it) }"))
        assertTrue(reg.contains("if (!cryptoAsset8970(t)) continue"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText().contains("&& DynamicAltTokenRegistry.cryptoAsset8970(it) }"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/DiagnosisBrief8438.kt").readText().contains("DynamicAltTokenRegistry.universeLine8970()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 155. Each trader scans its own assets (8970)"))
    }
}
