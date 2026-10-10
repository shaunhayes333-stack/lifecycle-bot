package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CapitalDrawdown7948
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7981DrawdownFeedsStreamsTest {

    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun ownerWithdrawalIsNotADrawdown() {
        // Equity fell 0.30 SOL with no bot P&L change: a withdrawal / manual buy.
        val flow = CapitalDrawdown7948.externalFlow7981(-0.30, 0.0, 0.0)
        assertEquals(-0.30, flow, 1e-12)
        assertTrue(CapitalDrawdown7948.isExternal7981(flow, 0.50))
        val p = CapitalDrawdown7948.rebasedPeak7981(CapitalDrawdown7948.Peak7948(0.80, 1L), flow, 0.50, 2L)
        assertEquals(0.50, p.peakSol, 1e-12)
        assertEquals(0.0, CapitalDrawdown7948.drawdownPct7948(p, 0.50, 3L), 1e-9)
    }

    @Test fun botLossesStillCount() {
        // Equity fell 0.10 and realised P&L explains all of it: not external.
        val flow = CapitalDrawdown7948.externalFlow7981(-0.10, -0.10, 0.0)
        assertFalse(CapitalDrawdown7948.isExternal7981(flow, 0.40))
        // A mark move explained by unrealised P&L is not external either.
        assertFalse(CapitalDrawdown7948.isExternal7981(CapitalDrawdown7948.externalFlow7981(0.05, 0.0, 0.05), 0.40))
        // Dust is not a flow.
        assertFalse(CapitalDrawdown7948.isExternal7981(0.002, 0.05))
    }

    @Test fun depositRaisesThePeakSoLaterLossesMeasureFromIt() {
        val p = CapitalDrawdown7948.rebasedPeak7981(CapitalDrawdown7948.Peak7948(0.10, 1L), 0.20, 0.30, 2L)
        assertEquals(0.30, p.peakSol, 1e-12)
        assertTrue(src("engine/KillSwitch.kt").contains("storedSchema7843 < 7981"))
    }

    @Test fun deadFeedsBackOff() {
        assertEquals(0L, FeedBackoff7981.waitMs7981(1))
        assertEquals(60_000L, FeedBackoff7981.waitMs7981(2))
        assertEquals(120_000L, FeedBackoff7981.waitMs7981(3))
        assertEquals(30L * 60_000L, FeedBackoff7981.waitMs7981(40))
        val t = 1_000_000L
        FeedBackoff7981.fail7981("TEST_FEED_7981", t)
        assertTrue(FeedBackoff7981.allow7981("TEST_FEED_7981", t + 1))
        FeedBackoff7981.fail7981("TEST_FEED_7981", t)
        assertFalse(FeedBackoff7981.allow7981("TEST_FEED_7981", t + 1))
        assertTrue(FeedBackoff7981.allow7981("TEST_FEED_7981", t + 60_000L))
        FeedBackoff7981.ok7981("TEST_FEED_7981")
        assertTrue(FeedBackoff7981.allow7981("TEST_FEED_7981", t + 1))
        assertTrue(src("engine/market/MemeMeta7973.kt").contains("\"PUMP_KOTH\""))
        assertTrue(src("engine/PumpCallouts7968.kt").contains("FeedBackoff7981.allow7981(\"PUMP_CALLOUTS\")"))
    }

    @Test fun logStreamsHalved() {
        assertTrue(src("network/HeliusWebSocket.kt").contains("MAX_TOKEN_SUBSCRIPTIONS_7794 = 64"))
    }
}
