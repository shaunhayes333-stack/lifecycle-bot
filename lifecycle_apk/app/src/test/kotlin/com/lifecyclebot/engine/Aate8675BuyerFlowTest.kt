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
    @Test fun buyOnlyRunAcrossBothWindowsIsSustainedDemand8681() {
        BuyerFlow8666.note("mint", "bob", 1.0, true, now - 20_000, "b", now)
        BuyerFlow8666.note("mint", "carol", 1.0, true, now - 18_000, "c", now)
        BuyerFlow8666.note("mint", "dave", 1.0, true, now - 5_000, "d", now)
        BuyerFlow8666.note("mint", "erin", 1.0, true, now - 3_000, "e", now)
        assertTrue(BuyerFlow8666.read("mint", now)!!.sustainedDemand)
    }
    @Test fun oneWhaleOrNoBaselineIsNotSustainedDemand8681() {
        BuyerFlow8666.note("mint", "dave", 5.0, true, now - 5_000, "d", now)
        BuyerFlow8666.note("mint", "erin", 0.1, true, now - 3_000, "e", now)
        assertFalse(BuyerFlow8666.read("mint", now)!!.sustainedDemand)
        BuyerFlow8666.clearForTest()
        BuyerFlow8666.note("mint", "bob", 1.0, true, now - 20_000, "b", now)
        BuyerFlow8666.note("mint", "dave", 1.0, true, now - 5_000, "d", now)
        BuyerFlow8666.note("mint", "erin", 1.0, true, now - 3_000, "e", now)
        assertFalse(BuyerFlow8666.read("mint", now)!!.sustainedDemand)
    }
    @Test fun staleAndWalletlessObservationsNeverManufactureDemand() {
        BuyerFlow8666.note("mint", "alice", 1.0, true, now - 61_000, "old", now)
        BuyerFlow8666.note("mint", "", 1.0, true, now, "missing", now)
        assertNull(BuyerFlow8666.read("mint", now))
        BuyerFlow8666.note("mint", "alice", 1.0, true, now, "missing", now)
        assertEquals(1, BuyerFlow8666.read("mint", now)!!.newBuyers15s)
    }
}
