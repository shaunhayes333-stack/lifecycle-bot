package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Bundle6401StartupExitOnlyLatchTest {

    @Before fun setUp() { StartupExitOnlyLatch6401.clearAllForTest() }
    @After fun tearDown() { StartupExitOnlyLatch6401.clearAllForTest() }

    private fun allMet() = StartupExitOnlyLatch6401.Pillars(
        governorNotHold = true, scannerQueueInit = true,
        fillLotLedgerInit = true, canonicalBuyRegistryInit = true,
        sellReconcilerFirstTick = true, walletSnapshotComplete = true,
        noGlobalAccountingQuarantine = true,
    )

    private fun blocking() = StartupExitOnlyLatch6401.Pillars(
        governorNotHold = true, scannerQueueInit = true,
        fillLotLedgerInit = true, canonicalBuyRegistryInit = true,
        sellReconcilerFirstTick = false, // ← blocking
        walletSnapshotComplete = true,
        noGlobalAccountingQuarantine = true,
    )

    @Test fun latch_starts_active() {
        assertTrue(StartupExitOnlyLatch6401.isActive())
    }

    @Test fun latch_clears_when_all_pillars_met() {
        val cleared = StartupExitOnlyLatch6401.evaluateAndMaybeClear(allMet())
        assertTrue(cleared)
        assertFalse(StartupExitOnlyLatch6401.isActive())
    }

    @Test fun latch_stays_active_while_pillars_unmet() {
        val cleared = StartupExitOnlyLatch6401.evaluateAndMaybeClear(blocking())
        assertFalse(cleared)
        assertTrue(StartupExitOnlyLatch6401.isActive())
    }

    @Test fun latch_repairs_after_stale_15s_even_if_pillars_unmet() {
        StartupExitOnlyLatch6401.evaluateAndMaybeClear(blocking(), nowMs = 1_000L) // seed startedAt via reset already done
        // Simulate 20s passing. `startedAt` is set at reset time — hard to
        // control across time in a unit test, so we instead call with nowMs
        // far past REPAIR_MS from any reasonable epoch value.
        val cleared = StartupExitOnlyLatch6401.evaluateAndMaybeClear(
            blocking(), nowMs = System.currentTimeMillis() + StartupExitOnlyLatch6401.REPAIR_MS + 1_000L)
        assertTrue("stale latch must self-repair after 15s", cleared)
        assertFalse(StartupExitOnlyLatch6401.isActive())
    }

    @Test fun deferral_classification_is_requeue_not_terminal_fail() {
        val reason = StartupExitOnlyLatch6401.classifyDeferral()
        assertEquals("BUY_DEFERRED_STARTUP", reason)
        assertEquals(1L, StartupExitOnlyLatch6401.requeuedDeferrals.get())
    }

    @Test fun successful_live_buy_locks_latch_cleared() {
        StartupExitOnlyLatch6401.onLiveBuySuccess()
        assertFalse(StartupExitOnlyLatch6401.isActive())
        // Even blocking pillars after this must NOT reactivate the latch.
        StartupExitOnlyLatch6401.evaluateAndMaybeClear(blocking())
        assertFalse("latch cannot reactivate after live buy this generation",
            StartupExitOnlyLatch6401.isActive())
    }

    @Test fun blocking_pillars_are_named() {
        val list = blocking().blockingPillars()
        assertTrue(list.contains("sell_reconciler_first_tick"))
    }
}
