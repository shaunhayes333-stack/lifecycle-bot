package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8512 — a SOL/USD observation newer than the caller's clock read is fresh; older than 5 minutes is not. */
class Aate8512SolRateFreshnessTest {
    private val now = 1_800_000_000_000L

    @Test fun anObservationNewerThanTheBatchClockIsFresh() {
        assertTrue(WalletManager.solPriceFresh8512(now, now + 400L))
        assertTrue(WalletManager.solPriceFresh8512(now, now + 16_000L))
    }

    @Test fun ordinaryAgesStillReadAsBefore() {
        assertTrue(WalletManager.solPriceFresh8512(now, now))
        assertTrue(WalletManager.solPriceFresh8512(now, now - 299_000L))
        assertFalse(WalletManager.solPriceFresh8512(now, now - 301_000L))
    }

    @Test fun unknownAndImplausibleFutureStampsAreNotFresh() {
        assertFalse(WalletManager.solPriceFresh8512(now, 0L))
        assertFalse(WalletManager.solPriceFresh8512(now, now + 61_000L))
    }
}
