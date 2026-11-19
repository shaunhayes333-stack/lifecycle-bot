package com.lifecyclebot.engine

import com.lifecyclebot.perps.DynamicAltTokenRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live observations retain the same value and clock across the crypto registry boundary. */
class Aate8870PriceClockTest {
    @Test fun metadataCannotRefreshAnOldOrUnknownPrice() {
        val now = 1_800_000_000_000L
        assertEquals(350_000L, NativeDecisionEnrollment8372.priceAge8870(now - 350_000L, now))
        assertEquals(Long.MAX_VALUE, NativeDecisionEnrollment8372.priceAge8870(0L, now))
        assertEquals(Long.MAX_VALUE, NativeDecisionEnrollment8372.priceAge8870(now + 1L, now))
        assertEquals(0L, DynamicAltTokenRegistry.priceTime8801(0L, now))
        assertEquals(now - 350_000L, DynamicAltTokenRegistry.priceTime8801(now - 350_000L, now))
        assertEquals(0L, NativeDecisionEnrollment8372.observedPriceAt(1.2, 1.2, now - 350_000L, now, 15_000L))
        assertEquals(now - 1_000L, NativeDecisionEnrollment8372.observedPriceAt(1.2, 1.2, now - 1_000L, now, 15_000L))
    }

    @Test fun fanoutObservationSurvivesAndOlderReplyCannotReplaceIt() {
        val mint = "Aate887oPriceTest11111111111111111111111111"
        val key = "solana|$mint"
        val now = System.currentTimeMillis()
        assertNotNull(DynamicAltTokenRegistry.ensureHeld8866(key, null, "CLOCK8870"))
        assertTrue(DynamicAltTokenRegistry.observeHeldMark7274(key, 0.000023, now - 2_000L))
        val first = DynamicAltTokenRegistry.heldMarkSnapshot7251(key)
        assertEquals(now - 2_000L, first.observedAtMs)
        assertEquals(0.000023, first.price, 0.0)
        assertTrue(first.freshObservation)
        assertFalse("a missing clock cannot overwrite a live observation",
            DynamicAltTokenRegistry.observeHeldMark7274(key, 0.000025))
        assertFalse("a slow HTTP response cannot roll back a newer stream observation",
            DynamicAltTokenRegistry.observeHeldMark7274(key, 0.000017, now - 90_000L))
        val after = DynamicAltTokenRegistry.heldMarkSnapshot7251(key)
        assertEquals(first, after)
    }

    @Test fun carryingAStalePriceNeverArmsAnExitAsFresh() {
        val key = "solana|Aate887oStaleTest11111111111111111111111111"
        val now = System.currentTimeMillis()
        assertNotNull(DynamicAltTokenRegistry.ensureHeld8866(key, null, "STALE8870"))
        assertTrue(DynamicAltTokenRegistry.observeHeldMark7274(key, 0.000031, now - 350_000L))
        val stale = DynamicAltTokenRegistry.heldMarkSnapshot7251(key)
        assertEquals(0.000031, stale.price, 0.0)
        assertEquals(now - 350_000L, stale.observedAtMs)
        assertFalse(stale.freshObservation)
        assertTrue(DynamicAltTokenRegistry.observeHeldMark7274(key, 0.000029, now - 1_000L))
        assertTrue(DynamicAltTokenRegistry.heldMarkSnapshot7251(key).freshObservation)
    }
}
