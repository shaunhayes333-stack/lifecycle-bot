package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7927 — only terminal wallet rows give up their token account rent. */
class Aate7927RentReclaimTest {
    @Test fun activeRowsAreProtected() {
        assertTrue(RentReclaimer7927.activeTrackerStatus(HostWalletTokenTracker.PositionStatus.BUY_PENDING.priority, dustIgnored = false))
        assertTrue(RentReclaimer7927.activeTrackerStatus(HostWalletTokenTracker.PositionStatus.SELL_VERIFYING.priority, dustIgnored = false))
        assertFalse(RentReclaimer7927.activeTrackerStatus(HostWalletTokenTracker.PositionStatus.SOLD_CONFIRMED.priority, dustIgnored = false))
        assertFalse(RentReclaimer7927.activeTrackerStatus(HostWalletTokenTracker.PositionStatus.CLOSED.priority, dustIgnored = false))
        assertFalse(RentReclaimer7927.activeTrackerStatus(1, dustIgnored = true))
    }
}
