package com.lifecyclebot.engine

import com.lifecyclebot.perps.CryptoAltTrader
import com.lifecyclebot.perps.PerpsDirection
import com.lifecyclebot.perps.PerpsMarket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.6654 regression coverage for DYN sentinel price/accounting corruption. */
class Aate6654CryptoDynMarkIdentityTest {

    private fun position(
        canonicalKey: String,
        markKey: String,
        entry: Double,
        current: Double,
        markAt: Long = System.currentTimeMillis(),
    ) = CryptoAltTrader.AltPosition(
        id = "ALT:test",
        market = PerpsMarket.DYN,
        dynSymbol = "REAL",
        dynName = "Real asset",
        dynMint = "real-mint",
        direction = PerpsDirection.LONG,
        isSpot = false,
        isPaper = true,
        canonicalAssetKey = canonicalKey,
        markAssetKey = markKey,
        markUpdatedAtMs = markAt,
        entryPrice = entry,
        currentPrice = current,
        sizeSol = 0.1,
        leverage = 3.0,
        takeProfitPrice = entry * 1.1,
        stopLossPrice = entry * 0.95,
        aiScore = 80,
        aiConfidence = 80,
        reasons = listOf("test"),
    )

    @Test
    fun `DYN mark from another asset cannot produce pnl`() {
        val poisoned = position(
            canonicalKey = "solana:asset-a",
            markKey = "solana:asset-b",
            entry = 0.0036,
            current = 25.14,
        )
        assertFalse(poisoned.hasTrustedMark())
        assertEquals(0.0, poisoned.getPnlPct(), 0.0)
        assertEquals(0.0, poisoned.getPnlSol(), 0.0)
    }

    @Test
    fun `fresh exact identity mark drives leverage pnl`() {
        val exact = position(
            canonicalKey = "solana:asset-a",
            markKey = "solana:asset-a",
            entry = 1.0,
            current = 2.0,
        )
        assertTrue(exact.hasTrustedMark())
        assertEquals(300.0, exact.getPnlPct(), 1e-9)
    }

    @Test
    fun `stale exact identity mark is economically neutral`() {
        val stale = position(
            canonicalKey = "solana:asset-a",
            markKey = "solana:asset-a",
            entry = 1.0,
            current = 2.0,
            markAt = System.currentTimeMillis() - 11L * 60L * 1000L,
        )
        assertFalse(stale.hasTrustedMark())
        assertEquals(0.0, stale.getPnlPct(), 0.0)
    }

    @Test
    fun `monitor and UI are bound to exact position identity`() {
        val trader = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val ui = File("src/main/kotlin/com/lifecyclebot/ui/CryptoAltActivity.kt").readText()
        val registry = File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
        assertTrue(trader.contains("CRYPTO_DYN_MARK_IDENTITY_REJECTED_6654"))
        assertTrue(trader.contains("markAssetKey = validatedMarkKey"))
        assertTrue(trader.contains("positions[id] = updated"))
        assertTrue(registry.contains("forceRefresh: Boolean = false"))
        assertTrue(ui.contains("val curStr   = fmtPrice(pos.currentPrice)"))
        assertFalse(ui.contains("tv(pos.market.symbol"))
    }
}
