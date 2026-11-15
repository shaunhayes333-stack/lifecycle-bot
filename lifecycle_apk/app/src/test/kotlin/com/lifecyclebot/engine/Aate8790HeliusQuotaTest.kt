package com.lifecyclebot.engine

import com.lifecyclebot.network.HeliusWebSocket
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A 429 is quota exhaustion, not a 2-30 second network flap. */
class Aate8790HeliusQuotaTest {
    private val ws = HeliusWebSocket("", { _, _, _, _, _, _ -> }, { _, _, _, _ -> }, {})

    @Test fun quotaRetryBacksOffButEventuallyProbesAgainAfterCreditsReturn() {
        assertEquals(60_000L, ws.quotaWaitMs8790(1, null))
        assertEquals(120_000L, ws.quotaWaitMs8790(2, null))
        assertEquals(240_000L, ws.quotaWaitMs8790(3, null))
        assertEquals(480_000L, ws.quotaWaitMs8790(4, null))
        assertEquals(900_000L, ws.quotaWaitMs8790(5, null))
        assertEquals(900_000L, ws.quotaWaitMs8790(20, null))
    }

    @Test fun retryAfterNeverShortensTheQuotaCooldown() {
        assertEquals(60_000L, ws.quotaWaitMs8790(1, 10))
        assertEquals(300_000L, ws.quotaWaitMs8790(1, 300))
        assertEquals(1_800_000L, ws.quotaWaitMs8790(1, 1_800))
        assertEquals(3_600_000L, ws.quotaWaitMs8790(1, Long.MAX_VALUE))
        assertEquals(120_000L, ws.quotaWaitMs8790(2, -1))
    }

    @Test fun providerCooldownSurvivesExternalReconnectRequests() {
        val text = File("src/main/kotlin/com/lifecyclebot/network/HeliusWebSocket.kt").readText()
        val reconnect = text.substringAfter("fun reconnectIfStale7819(reason: String)")
            .substringBefore("fun setPinnedMintsProvider7807")
        assertTrue(reconnect.contains("quotaRemainingMs8790() > 0L"))
        val failure = text.substringAfter("override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?)")
            .substringBefore("override fun onClosing")
        assertTrue(failure.contains("httpCode8790 == 429"))
        assertTrue(failure.contains("response?.header(\"Retry-After\")"))
    }
}
