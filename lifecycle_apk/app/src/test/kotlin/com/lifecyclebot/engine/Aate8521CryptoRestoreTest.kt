package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AssetClass
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.perps.CryptoAltTrader
import com.lifecyclebot.perps.DynamicAltTokenRegistry
import com.lifecyclebot.perps.PerpsDirection
import com.lifecyclebot.perps.PerpsMarket
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class Aate8521CryptoRestoreTest {
    private val at = 1_000_000L
    private fun canonical(lane: String = "CRYPTO_LEV") = CanonicalPositionAuthority6441.Position(
        "crypto8521", "paper", "cg:exact-8521", "EXACT8521", lane, "run8521", 100L,
        2.0, BigInteger("15000000"), BigInteger("20000000"), 0.5, 0.1, 0.6, 0.0, 6,
        lifecycle = CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED,
        lastMutationMs = at, quarantineReason = "", entryPriceUsd = 8.0, assetClass = AssetClass.CRYPTO_ALT,
    )

    private fun native() = CryptoAltTrader.AltPosition(
        id = "crypto8521", market = PerpsMarket.DYN, dynSymbol = "EXACT8521", dynMint = "cg:exact-8521",
        direction = PerpsDirection.SHORT, isSpot = false, isPaper = true, canonicalAssetKey = "cg:exact-8521",
        markAssetKey = "cg:exact-8521", markUpdatedAtMs = at - 60_000L,
        entryPrice = 7.0, currentPrice = 6.0, sizeSol = 2.0, leverage = 3.0,
        takeProfitPrice = 5.0, stopLossPrice = 9.0, aiScore = 70, aiConfidence = 80,
        reasons = listOf("ORIGINAL_SHORT"), openTime = 101L, highestPnlPct = 42.0, flashPositionKey = "exact-flash-key",
    )

    @Test fun exactRecoveryPreservesSideLeverageAndClockButUsesCanonicalRemainingBasis() {
        val cp = canonical()
        val restored = CryptoAltTrader.restoreCanonicalPosition8521(cp, native())!!
        assertEquals(cp.positionId, restored.id)
        assertEquals(PerpsDirection.SHORT, restored.direction)
        assertFalse(restored.isSpot)
        assertEquals(3.0, restored.leverage, 0.0)
        assertEquals(1.5, restored.sizeSol, 0.0)
        assertEquals(8.0, restored.entryPrice, 0.0)
        assertEquals(100L, restored.openTime)
        assertEquals(at - 60_000L, restored.markUpdatedAtMs)
        assertEquals(42.0, restored.highestPnlPct, 0.0)
        assertEquals("exact-flash-key", restored.flashPositionKey)
        assertEquals(listOf("ORIGINAL_SHORT"), restored.reasons)
    }

    @Test fun anotherLotModeOrAssetCannotSupplyExecutionMetadataAndMissingLeverageIsDeferred() {
        val cp = canonical()
        for (p in listOf(native().copy(id = "other-lot"), native().copy(isPaper = false),
            native().copy(canonicalAssetKey = "cg:other"), native().copy(leverage = Double.NaN),
            native().copy(direction = PerpsDirection.LONG, isSpot = true, leverage = 1.0))) {
            assertNull(CryptoAltTrader.restoreCanonicalPosition8521(cp, p))
        }
        assertNull(CryptoAltTrader.restoreCanonicalPosition8521(cp, null))
        assertNull(CryptoAltTrader.restoreCanonicalPosition8521(cp.copy(lane = "UNKNOWN"), null))
        assertNull(CryptoAltTrader.restoreCanonicalPosition8521(cp.copy(lifecycle = CanonicalPositionAuthority6441.Lifecycle.CLOSED), native()))
        assertNull(CryptoAltTrader.restoreCanonicalPosition8521(cp.copy(soldCostBasisSol = 2.0), native()))
    }

    @Test fun canonicalSpotRecoveryPreservesObservedClockAndDoesNotInventAMark() {
        val cp = canonical("CRYPTO_ALT")
        val mark = DynamicAltTokenRegistry.HeldMarkRefresh7251(6.0, cp.mint, at - 60_000L, false)
        val restored = CryptoAltTrader.restoreCanonicalPosition8521(cp, null, mark)!!
        assertTrue(restored.isSpot)
        assertEquals(PerpsDirection.LONG, restored.direction)
        assertEquals(1.0, restored.leverage, 0.0)
        assertEquals(1.5, restored.sizeSol, 0.0)
        assertEquals(mark.observedAtMs, restored.markUpdatedAtMs)
        assertEquals(6.0, restored.currentPrice, 0.0)
        assertFalse(restored.markUpdatedAtMs in at - 30_000L..at)
        for (unobserved in listOf(null, mark.copy(canonicalIdentity = "cg:wrong"), mark.copy(observedAtMs = 0L))) {
            val missing = CryptoAltTrader.restoreCanonicalPosition8521(cp, null, unobserved)!!
            assertEquals(0L, missing.markUpdatedAtMs)
            assertEquals(0.0, missing.currentPrice, 0.0)
            assertFalse(missing.hasTrustedMark(at))
        }
        // A dynamic token named SOL is still its exact asset, never the enum SOL quote by symbol collision.
        val collision = CryptoAltTrader.restoreCanonicalPosition8521(cp.copy(symbol = "SOL"), null, mark)!!
        assertEquals(PerpsMarket.DYN, collision.market)
        assertEquals(cp.mint, collision.canonicalAssetKey)
    }
}
