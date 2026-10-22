package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7485PumpUnsupportedDemandCoalescingTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/network/PumpFunWS.kt").readText()

    @Test fun unsupported_trade_demand_is_state_not_repeated_subscription_work() {
        val s = src()
        val fn = s.substringAfter("fun syncTradeSubscriptions7278").substringBefore("fun start(")
        assertTrue(fn.contains("unsupportedTradeDemand7485.add(mint)"))
        assertTrue(fn.contains("unsupportedTradeDemand7485.removeIf"))
        assertTrue(fn.contains("return"))
        // superseded by V5.0.8307: parked PumpSwap mints are filtered out of the diff before the frame is built
        assertTrue(fn.indexOf("if (!tradeStreamKeyed7284())") < fn.indexOf("val add = admitForSubscribe8307(wanted - tradeSubscriptions7278).toSet()"))
    }

    @Test fun capability_recovery_restores_normal_subscription_path() {
        val s = src()
        assertTrue(s.contains("unsupportedTradeDemand7485.clear()"))
        assertTrue(s.contains("val add = admitForSubscribe8307(wanted - tradeSubscriptions7278).toSet()"))  // superseded by V5.0.8307
        assertTrue(s.contains("subscribeTokenTrade"))
    }

    @Test fun free_stream_and_fallback_capabilities_remain() {
        val s = src()
        assertTrue(s.contains("subscribeNewToken"))
        assertTrue(s.contains("subscribeMigration"))
        assertTrue(s.contains("PUMP_TOKEN_TRADE_FALLBACK_ACTIVE"))
    }
}
