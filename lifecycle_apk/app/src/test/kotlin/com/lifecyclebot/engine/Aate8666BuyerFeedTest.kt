package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.BuyerFlow8666
import com.lifecyclebot.network.HeliusWebSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Supply-side regression: actual buyer wallets, executed volume and bounded stream growth. */
class Aate8666BuyerFeedTest {
    private val now = 1_800_000L

    @Test fun executedWalletFlowRecognisesNewIndependentBuyersAndAcceleration() {
        val rows = listOf(
            BuyerFlow8666.Print(now - 28_000, "old", 1.0, true),
            BuyerFlow8666.Print(now - 23_000, "old", 0.5, false),
            BuyerFlow8666.Print(now - 21_000, "old2", 0.5, true),
            BuyerFlow8666.Print(now - 10_000, "new1", 2.0, true),
            BuyerFlow8666.Print(now - 8_000, "new2", 3.0, true),
            BuyerFlow8666.Print(now - 4_000, "new2", 2.0, true),
        )
        val flow = BuyerFlow8666.calculate(rows, now) ?: error("Expected fresh executed buyer flow")
        assertNotNull(flow)
        assertEquals(4, flow.distinctBuyers60s)
        assertEquals(2, flow.newBuyers15s)
        assertEquals(3, flow.buyTx15s)
        assertEquals(2, flow.buyTxPrev15s)
        assertEquals(1, flow.repeatBuyers60s)
        assertEquals(8.5 / 9.0 * 100.0, flow.buySharePct, 1e-9)
        assertTrue(flow.volumeAccelScore > 0.0)
        assertTrue(flow.buyersPerMin > 4.0)
    }

    @Test fun unknownWalletAndWalletlessCurvePricesDoNotInventBuyers() {
        BuyerFlow8666.clearForTest()
        BuyerFlow8666.note("mint8666", "", 1.0, true, now)
        BuyerFlow8666.note("mint8666", "buyer1", 0.0, true, now)
        assertNull(BuyerFlow8666.read("mint8666", now))
        BuyerFlow8666.note("mint8666", "buyer1", 1.0, true, now)
        assertEquals(1, BuyerFlow8666.read("mint8666", now)!!.distinctBuyers60s)
        assertTrue(BuyerFlow8666.read("mint8666", now)!!.volumeAccelScore.isNaN())
        assertNull(BuyerFlow8666.read("mint8666", now + 21_000L))
    }

    @Test fun noPreviousVolumeMeansUnknownAccelerationNotAnArtificialZero() {
        val rows = listOf(
            BuyerFlow8666.Print(now - 9_000, "a", 1.0, true),
            BuyerFlow8666.Print(now - 6_000, "b", 1.0, true),
        )
        assertTrue(BuyerFlow8666.calculate(rows, now)!!.volumeAccelScore.isNaN())
    }

    @Test fun returningBuyerIsNotInventedAsANewWallet() {
        BuyerFlow8666.clearForTest()
        BuyerFlow8666.note("returning", "alice", 1.0, true, now - 35_000L)
        BuyerFlow8666.note("returning", "alice", 2.0, true, now - 5_000L)
        BuyerFlow8666.note("returning", "bob", 1.0, true, now - 4_000L)
        val pulse = BuyerFlow8666.read("returning", now)!!
        assertEquals(2, pulse.distinctBuyers60s)
        assertEquals(1, pulse.newBuyers15s)
    }

    @Test fun heliusAdaptiveLoadShedsBeforeOomAndNeverExceedsOldCrashThreshold() {
        val ws = HeliusWebSocket("", { _, _, _, _, _, _ -> }, { _, _, _, _ -> }, {})
        assertEquals(8, ws.capForLoad8666(12, 15_000_000L, 0.30, true))
        assertEquals(8, ws.capForLoad8666(16, 1_000_000L, 0.80, true))
        assertEquals(14, ws.capForLoad8666(12, 1_000_000L, 0.20, true))
        assertEquals(16, ws.capForLoad8666(16, 1_000_000L, 0.20, true))
        assertEquals(12, ws.capForLoad8666(12, 7_000_000L, 0.30, true))
        assertFalse(ws.capForLoad8666(8, 1_000_000L, 0.30, false) > 8)
    }
}
