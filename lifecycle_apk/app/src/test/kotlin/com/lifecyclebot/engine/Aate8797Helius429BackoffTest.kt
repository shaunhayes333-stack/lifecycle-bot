package com.lifecyclebot.engine

import com.lifecyclebot.network.HeliusEnhancedWS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A credit refill is probed without a fixed 15-minute outage or a reconnect storm. */
class Aate8797Helius429BackoffTest {
    @Test fun exponentialProbesRecoverWithoutHammeringProvider() {
        assertEquals(60_000L, HeliusEnhancedWS.retry429DelayMs8797(1L, null))
        assertEquals(120_000L, HeliusEnhancedWS.retry429DelayMs8797(2L, null))
        assertEquals(240_000L, HeliusEnhancedWS.retry429DelayMs8797(3L, null))
        assertEquals(480_000L, HeliusEnhancedWS.retry429DelayMs8797(4L, null))
        assertEquals(900_000L, HeliusEnhancedWS.retry429DelayMs8797(5L, null))
        assertEquals(900_000L, HeliusEnhancedWS.retry429DelayMs8797(50L, null))
        assertEquals(30_000L, HeliusEnhancedWS.retry429DelayMs8797(1L, "30"))
        assertEquals(15_000L, HeliusEnhancedWS.retry429DelayMs8797(1L, "1"))
        assertEquals(900_000L, HeliusEnhancedWS.retry429DelayMs8797(2L, "9999"))
    }

    @Test fun failedHandshakeUsesOnePendingReconnectLease() {
        val src = File("src/main/kotlin/com/lifecyclebot/network/HeliusEnhancedWS.kt").readText()
        val failure = src.substringAfter("override fun onFailure(").substringBefore("override fun onClosed(")
        assertTrue(failure.contains("retry429DelayMs8797"))
        val reconnect = src.substringAfter("private fun scheduleReconnect(")
        assertTrue(reconnect.contains("reconnectPending8795.compareAndSet(false, true)"))
    }
}
