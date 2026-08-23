package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.OrderSizeResolver6441
import com.lifecyclebot.engine.truth.SellQtyBoundaryClamp6427
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class Repair6498LifecycleAcceptanceTest {
    @Test fun resolverNeverIncreasesBeyondRequestedOrRisk() {
        val r = OrderSizeResolver6441.resolve(
            requestedSol = 0.39114, laneName = "BLUECHIP", walletSol = 102.6278,
            paperMode = false, laneRiskCapSol = 10.0, laneMinExecutableSol = 0.05,
        )
        assertTrue(r.finalSizeSol <= r.requestedSol + 1e-9)
        assertTrue(r.finalSizeSol <= r.riskSol + 1e-9)
        assertTrue(r.finalSizeSol <= r.cashCapSol + 1e-9)
        assertTrue(r.finalSizeSol <= r.laneCapSol + 1e-9)
    }

    @Test fun rawSellBoundaryTracksAuthoritativeInventoryAndCommitsOnce() {
        SellQtyBoundaryClamp6427.resetForTest()
        val pid = "PAPER:6498:test"
        SellQtyBoundaryClamp6427.syncAuthoritativeRaw(pid, BigInteger.valueOf(100), BigInteger.valueOf(60))
        assertTrue(SellQtyBoundaryClamp6427.admitRaw(pid, BigInteger.valueOf(60), "mint", "T").allowed)
        assertTrue(SellQtyBoundaryClamp6427.commitRaw(pid, BigInteger.valueOf(60), terminal = true))
        assertFalse(SellQtyBoundaryClamp6427.admitRaw(pid, BigInteger.ONE, "mint", "T").allowed)
    }
}
