package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.FirstSight8026
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/** Cached permissions cannot outlive the evidence that granted them or leak to another specialist. */
class Aate8372FirstSightTicketTest {
    private val now = 1_000_000L

    private fun strong(outcome: Double) = CortexLedger7885.realisedStat8187().also { stat ->
        repeat(12) { stat.add(outcome, false) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun tickets(): ConcurrentHashMap<FirstSight8026.TicketKey8372, Long> =
        FirstSight8026::class.java.getDeclaredField("tickets").let { field ->
            field.isAccessible = true
            field.get(FirstSight8026) as ConcurrentHashMap<FirstSight8026.TicketKey8372, Long>
        }

    private fun withTicket(mint: String, lane: String, block: (TokenState) -> Unit) {
        val key = FirstSight8026.ticketKey8372(mint, lane)
        val cache = tickets()
        cache[key] = now - 1L
        try {
            block(TokenState(mint = mint, symbol = "TEST").also {
                it.lastPrice = 1.0
                it.lastPriceUpdate = now - FirstSight8026.PRICE_FRESH_MS - 1L
                it.lastPriceSource = "LOCKED_VENUE_POOL_"
            })
        } finally { cache.remove(key) }
    }

    @Test fun cachedTicketCannotOverrideRevocationSentinel() {
        withTicket("CORTEX_8372_REVOKED", "EXPRESS") { token ->
            assertFalse(FirstSight8026.grant8026(token, "EXPRESS", "SOFT_EDGE", -1.0, strong(10.0), 0.5, now))
        }
    }

    @Test fun cachedTicketCannotOverrideNewlyLosingEvidence() {
        withTicket("CORTEX_8372_LOSING", "EXPRESS") { token ->
            assertFalse(FirstSight8026.grant8026(token, "EXPRESS", "SOFT_EDGE", 0.9, strong(-10.0), 0.5, now))
        }
    }

    @Test fun cachedTicketCannotOverrideStalePriceWithOtherwisePositiveEvidence() {
        withTicket("CORTEX_8372_STALE", "EXPRESS") { token ->
            assertFalse(FirstSight8026.grant8026(token, "EXPRESS", "SOFT_EDGE", 0.9, strong(10.0), 0.5, now))
        }
    }

    @Test fun ticketIdentitySeparatesLanesWithoutChangingCaseSensitiveMintIdentity() {
        assertNotEquals(FirstSight8026.ticketKey8372("MintA", "EXPRESS"), FirstSight8026.ticketKey8372("MintA", "SHITCOIN"))
        assertNotEquals(FirstSight8026.ticketKey8372("MintA", "EXPRESS"), FirstSight8026.ticketKey8372("minta", "EXPRESS"))
        assertEquals(FirstSight8026.ticketKey8372("MintA", "EXPRESS"), FirstSight8026.ticketKey8372("MintA", " express "))
        withTicket("CORTEX_8372_LANE", "EXPRESS") { token ->
            assertFalse(tickets().containsKey(FirstSight8026.ticketKey8372(token.mint, "SHITCOIN")))
        }
    }

    @Test fun legacySizeLimiterStillOnlyLimitsAndIgnoresExpiredTickets() {
        val mint = "CORTEX_8372_SIZING"
        val key = FirstSight8026.ticketKey8372(mint, "EXPRESS")
        val cache = tickets()
        try {
            cache[key] = System.currentTimeMillis()
            assertEquals(1.0, FirstSight8026.sizeMult8026(mint, 2.0), 0.0)
            assertEquals(0.5, FirstSight8026.sizeMult8026(mint, 0.5), 0.0)
            cache[key] = 1L
            assertEquals(2.0, FirstSight8026.sizeMult8026(mint, 2.0), 0.0)
        } finally { cache.remove(key) }
    }
}
