package com.lifecyclebot.perps

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8417PerpsControlsTest {
    @Suppress("UNCHECKED_CAST")
    private fun positions(): MutableMap<String, PerpsPosition> =
        PerpsTraderAI::class.java.getDeclaredField("activePositions").apply { isAccessible = true }
            .get(PerpsTraderAI) as MutableMap<String, PerpsPosition>

    private fun position() = PerpsPosition(
        id = "unconfirmed-exit-8417", market = PerpsMarket.SOL, direction = PerpsDirection.LONG,
        entryPrice = 100.0, currentPrice = 110.0, sizeSol = 0.1, sizeUsd = 10.0,
        leverage = 2.0, marginUsd = 5.0, liquidationPrice = 50.0,
        entryTime = System.currentTimeMillis(), isPaper = false, riskTier = PerpsRiskTier.SNIPER,
    )

    @Test fun rejectedLiveCloseRetainsPositionAndCannotClaimATrade() = runBlocking {
        val p = position()
        val map = positions()
        val prior = map.put(p.id, p)
        try {
            // No canonical owned position exists: actual executor must refuse before submission.
            assertNull(PerpsExecutionEngine.executeExit(p, 110.0, PerpsExitSignal.AI_EXIT))
            assertSame(p, map[p.id])
        } finally {
            map.remove(p.id)
            if (prior != null) map[p.id] = prior
        }
    }

    @Test fun closeAllCannotEraseUnconfirmedLivePositions() = runBlocking {
        val map = positions()
        val saved = map.toMap()
        val p = position()
        map.clear(); map[p.id] = p
        try {
            PerpsExecutionEngine.closeAllPositions()
            assertSame(p, map[p.id])
        } finally { map.clear(); map.putAll(saved) }
    }

    @Test fun settledLossOverridesCachedGainAndRecoveryCannotCloseTwice() = runBlocking {
        val authority = com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
        val id = "settled-loss-8417-${System.nanoTime()}"
        val p = position().copy(id = id)
        val map = positions()
        map[id] = p
        val quantity = java.math.BigInteger.valueOf(1_000_000L)
        try {
            assertEquals(com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.MutateResult.APPLIED,
                authority.openPosition("buy-$id", id, "SOL", "SOL", "PERPS", id,
                    0.1, quantity, 6, 0.002, false, entryPriceUsd = 100.0,
                    entryPriceSource = "TEST", assetClass = com.lifecyclebot.engine.truth.AssetClass.PERPS))
            assertEquals(com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.MutateResult.APPLIED,
                authority.partialSell("sell-$id", id, quantity, 0.09, 0.1, 0.001, false))
            // Chain settlement exists after a process interruption. Complete the local
            // projection without resubmitting, using actual loss rather than +20% cached mark.
            val trade = PerpsExecutionEngine.executeExit(p, 110.0, PerpsExitSignal.AI_EXIT)
            assertNotNull(trade)
            assertEquals("entry and exit fees both belong to the whole-position outcome", -13.0, trade!!.pnlPct, 1e-8)
            assertEquals(-1.3, trade.pnlUsd, 1e-8)
            assertFalse(map.containsKey(id))
            assertNull(PerpsExecutionEngine.executeExit(p, 110.0, PerpsExitSignal.AI_EXIT))
        } finally { map.remove(id) }
    }

    @Test fun operatorControlsUseTheSameExecutionOwner() {
        val code = File("src/main/kotlin/com/lifecyclebot/perps/PerpsExecutionEngine.kt").readText()
        val manual = code.substringAfter("suspend fun manualOpen(")
        assertTrue(manual.contains("return executeSignal(signal,"))
        assertTrue(manual.contains("return executeExit(position, marketData.price,"))
        assertTrue(manual.contains("executeExit(position, price,"))
        assertFalse(manual.substringBefore("fun getStats").contains("PerpsTraderAI.openPosition("))
        assertFalse(manual.substringBefore("fun getStats").contains("PerpsTraderAI.closePosition("))
    }
    @Test fun multiAssetClosesTheConfirmedSelectionThroughItsOwner() {
        val ui = File("src/main/kotlin/com/lifecyclebot/ui/MultiAssetActivity.kt").readText()
        assertFalse(ui.contains("PerpsTraderAI.closePosition("))
        val bulk = ui.substringAfter("private fun showCloseAllDialog()").substringBefore("private fun showPositionDetails")
        assertTrue(bulk.contains("val selectedTab = currentTab"))
        assertTrue(bulk.contains("val selectedPositions = getCurrentPositions().toList()"))
        assertTrue(bulk.contains("closeDisplayedPosition8417(selectedTab, position.id)"))
        assertFalse(bulk.contains("PerpsTraderAI.getActivePositions().forEach"))
    }
}
