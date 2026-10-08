package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CrossAssetCortex7931
import com.lifecyclebot.engine.truth.AssetClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7931 — crypto universe and Markets in the Cortex; recovered winners can be cashed. */
class Aate7931CrossAssetCortexTest {
    @Test fun crossAssetLanesAreTheirOwn() {
        assertEquals("CRYPTO_ALT", CrossAssetCortex7931.laneFor(AssetClass.CRYPTO_ALT))
        assertEquals("MKT_STOCK", CrossAssetCortex7931.laneFor(AssetClass.STOCK))
        assertEquals("MKT_FOREX", CrossAssetCortex7931.laneFor(AssetClass.FOREX))
        assertNull(CrossAssetCortex7931.laneFor(AssetClass.SOLANA_TOKEN))
        assertTrue(CrossAssetCortex7931.isCrossAssetLane("MKT_METAL@RISK_ON"))
        assertTrue(CrossAssetCortex7931.isCrossAssetLane("CRYPTO_ALT"))
        assertFalse(CrossAssetCortex7931.isCrossAssetLane("SHITCOIN"))
        assertFalse(CrossAssetCortex7931.isCrossAssetLane("GLOBAL"))
    }

    @Test fun paperAndLiveShareOneIdentity() {
        assertEquals("So1Mint", CrossAssetCortex7931.keyOf("solana|So1Mint", "ABC", null))
        assertEquals("XsMint", CrossAssetCortex7931.keyOf("AAPL", "AAPL", "XsMint"))
        assertEquals("0xabc", CrossAssetCortex7931.keyOf("ethereum|0xabc", "PEPE", null))
        assertEquals("BRENT", CrossAssetCortex7931.keyOf("BRENT", "BRENT", null))
    }

    @Test fun recoveredGraceNeverHoldsAWinnerExit() {
        assertTrue(RecoveredHoldGuard.isProfitTakingExit7931("RAPID_DRAWDOWN_FROM_PEAK_SETTLE_BYPASS_6080"))
        assertTrue(RecoveredHoldGuard.isProfitTakingExit7931("protective_peak_partial_664pct"))
        assertTrue(RecoveredHoldGuard.isProfitTakingExit7931("PEAK_CAPTURE_TRAIL_6394_peak664_now589"))
        assertTrue(RecoveredHoldGuard.isProfitTakingExit7931("SWEEP_TAKE_PROFIT_49"))
        assertFalse(RecoveredHoldGuard.isProfitTakingExit7931("STALE_FEED_EXIT"))
        assertFalse(RecoveredHoldGuard.isProfitTakingExit7931("WEAK_MOMENTUM"))
    }
}
