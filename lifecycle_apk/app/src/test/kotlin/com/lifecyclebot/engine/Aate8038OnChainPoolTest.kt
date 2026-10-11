package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.OnChainDepth8038
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8038 — the pool's depth and price read from its vaults on chain, and API numbers it contradicts refused. */
class Aate8038OnChainPoolTest {
    private fun key(seed: Int) = ByteArray(32) { (seed + it).toByte() }

    @Test fun vaultsDecodeByOwnerProgram() {
        for (owner in listOf(OnChainDepth8038.PUMP_AMM, OnChainDepth8038.RAYDIUM_V4, OnChainDepth8038.RAYDIUM_CPMM)) {
            val (a, b) = OnChainDepth8038.vaultOffsets8038(owner)!!
            val data = ByteArray(800)
            key(1).copyInto(data, a); key(77).copyInto(data, b)
            val (va, vb) = OnChainDepth8038.vaultKeys8038(owner, data)!!
            assertEquals(io.github.novacrypto.base58.Base58.base58Encode(key(1)), va)
            assertEquals(io.github.novacrypto.base58.Base58.base58Encode(key(77)), vb)
            assertNull(OnChainDepth8038.vaultKeys8038(owner, ByteArray(40)))
        }
        // An unknown program yields no reading, never a guessed one.
        assertNull(OnChainDepth8038.vaultKeys8038("SomeOtherProgram1111111111111111111111111", ByteArray(800)))
    }

    @Test fun onlyACoinAgainstSolPoolIsRead() {
        val mint = "CoinMint111111111111111111111111111111pump"
        assertEquals(42.0 to 1_000_000.0, OnChainDepth8038.sides8038(mint, OnChainDepth8038.WSOL to 42.0, mint to 1_000_000.0))
        assertEquals(42.0 to 1_000_000.0, OnChainDepth8038.sides8038(mint, mint to 1_000_000.0, OnChainDepth8038.WSOL to 42.0))
        assertNull(OnChainDepth8038.sides8038(mint, OnChainDepth8038.WSOL to 42.0, "OtherMint" to 5.0))
        assertNull(OnChainDepth8038.sides8038(mint, OnChainDepth8038.WSOL to 0.0, mint to 5.0))
        assertEquals(2.0 * 42.0 * 110.0, OnChainDepth8038.depthUsd8038(42.0, 110.0), 1e-9)
        assertEquals(42.0 / 1_000_000.0 * 110.0, OnChainDepth8038.priceUsd8038(42.0, 1_000_000.0, 110.0), 1e-12)
    }

    @Test fun contradictionsAreRefused() {
        // USDP-like: the API claims $500K of depth, the vaults hold 20 SOL (~$4.4K both sides).
        assertTrue(OnChainDepth8038.liquidityContradicted8038(500_000.0, OnChainDepth8038.depthUsd8038(20.0, 110.0)))
        assertFalse(OnChainDepth8038.liquidityContradicted8038(9_000.0, 8_471.0))
        assertFalse(OnChainDepth8038.liquidityContradicted8038(0.0, 8_471.0))
        // A drained pool behind a stale quote.
        assertTrue(OnChainDepth8038.priceCollapsed8038(1.0e-5, 0.4e-5))
        // A coin pumping faster than the API is not refused.
        assertFalse(OnChainDepth8038.priceCollapsed8038(1.0e-5, 1.6e-5))
        val exec = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(exec.contains("OnChainDepth8038.buyRefusal8038(ts)"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/LiquidityTruth8037.kt").readText().contains("chainDepthUsd8038"))
    }
}
