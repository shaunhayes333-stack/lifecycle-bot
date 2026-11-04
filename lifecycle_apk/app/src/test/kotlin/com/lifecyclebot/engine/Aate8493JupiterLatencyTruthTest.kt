package com.lifecyclebot.engine

import com.lifecyclebot.network.JupiterLatency
import com.lifecyclebot.network.JupiterLatency.Kind8493
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8493 — the bot's own skips and Jupiter's fast "no route" answers never mark Jupiter slow. */
class Aate8493JupiterLatencyTruthTest {
    @Test fun localSkipsAndNoRouteAreNotJupiterFailures() {
        assertEquals(Kind8493.LOCAL_SKIP, JupiterLatency.kindOf8493(false, "Jupiter observation quote yielded after 429 (quota reserved for trades)"))
        assertEquals(Kind8493.LOCAL_SKIP, JupiterLatency.kindOf8493(false, "Jupiter GET skipped: jupiter_quote in backoff lockout"))
        assertEquals(Kind8493.ANSWERED, JupiterLatency.kindOf8493(false, "Jupiter v2 quote error: COULD_NOT_FIND_ANY_ROUTE"))
        assertEquals(Kind8493.ANSWERED, JupiterLatency.kindOf8493(false, "Jupiter v6 quote returned outAmount=0"))
        assertEquals(Kind8493.FAILED, JupiterLatency.kindOf8493(false, "timeout"))
        assertEquals(Kind8493.ANSWERED, JupiterLatency.kindOf8493(true, null))
        assertFalse("our own yield is not a Jupiter 429", JupiterLatency.realRateLimit8493("Jupiter observation quote yielded after 429 (quota reserved for trades)"))
        assertTrue(JupiterLatency.realRateLimit8493("HTTP 429 Too Many Requests"))
    }
}

/** V5.0.8496 — trade quotes reach the wire through another caller's backoff; observation quotes do not time routing. */
class Aate8496TradeQuoteThroughLockoutTest {
    @Test fun tradeQuotesAreNotShedAndObservationsDoNotTimeJupiter() {
        val jup = java.io.File("src/main/kotlin/com/lifecyclebot/network/JupiterApi.kt").readText()
        assertTrue(jup.contains("if (quoteLockedOut && observationOnly7397 && !ExitHttpScope7314.active())"))
        assertTrue(jup.contains("if (!observationOnly7397) reqBuilder.header(HostCircuitInterceptor.TRADE_HEADER_8496, \"1\")"))
        assertTrue(jup.contains("if (!observationOnly7397) try { JupiterLatency.record(System.currentTimeMillis() - t0, true) }"))
        val hci = java.io.File("src/main/kotlin/com/lifecyclebot/network/HostCircuitInterceptor.kt").readText()
        assertTrue(hci.contains("tradeQuote8527(req)"))
    }
}
