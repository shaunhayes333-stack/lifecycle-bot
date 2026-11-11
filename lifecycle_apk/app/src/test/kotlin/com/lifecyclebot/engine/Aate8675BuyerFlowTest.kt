package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.BuyerFlow8666
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Aate8675BuyerFlowTest {
    private val now = 1_800_000L
    @Before fun reset() = BuyerFlow8666.clearForTest()
    @Test fun multipleProvidersCannotMultiplyOneSignedSwap() {
        repeat(3) { BuyerFlow8666.note("mint", "alice", 2.0, true, now, "same", now) }
        val f = BuyerFlow8666.read("mint", now)!!
        assertEquals(1, f.newBuyers15s)
        assertEquals(1.0, f.txPerMin, 0.0)
        assertEquals(2.0, f.netSol60s, 0.0)
    }
    @Test fun outOfOrderBuyerBelongsToItsOriginalTimeWindow() {
        BuyerFlow8666.note("mint", "alice", 1.0, true, now - 1_000, "newer", now)
        BuyerFlow8666.note("mint", "alice", 1.0, true, now - 25_000, "older", now)
        val f = BuyerFlow8666.read("mint", now)!!
        assertEquals(0, f.newBuyers15s)
        assertEquals(1, f.repeatBuyers60s)
        assertEquals(1, f.buyTxPrev15s)
    }
    @Test fun staleAndWalletlessObservationsNeverManufactureDemand() {
        BuyerFlow8666.note("mint", "alice", 1.0, true, now - 61_000, "old", now)
        BuyerFlow8666.note("mint", "", 1.0, true, now, "missing", now)
        assertNull(BuyerFlow8666.read("mint", now))
        BuyerFlow8666.note("mint", "alice", 1.0, true, now, "missing", now)
        assertEquals(1, BuyerFlow8666.read("mint", now)!!.newBuyers15s)
    }
}
