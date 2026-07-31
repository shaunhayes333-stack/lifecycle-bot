package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Bundle6401StartupExitOnlyLatchTest {

    @Before fun setUp() {
        StartupExitOnlyLatch6401.clearAllForTest()
        LiveExitOnlyMode6387.setTestOverride("STARTUP_DEFAULT")
    }
    @After fun tearDown() {
        StartupExitOnlyLatch6401.clearAllForTest()
        LiveExitOnlyMode6387.setTestOverride("STARTUP_DEFAULT")
    }

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

    @Test fun check_and_clear_no_op_when_reason_is_not_startup_default() {
        // Real runtime exit-only reason must be preserved by the auto-clear.
        LiveExitOnlyMode6387.setTestOverride("HOT_EXIT_MISSED_3_HEARTBEATS")
        try {
            val cleared = StartupExitOnlyLatch6401.checkAndClearStartupDefault()
            // Latch clears locally (reason isn't STARTUP_DEFAULT so latch is n/a),
            // but the runtime authority must remain engaged.
            assertTrue(cleared)
            assertEquals("HOT_EXIT_MISSED_3_HEARTBEATS", LiveExitOnlyMode6387.activeReason())
        } finally {
            LiveExitOnlyMode6387.setTestOverride("STARTUP_DEFAULT")
        }
    }

    @Test fun check_and_clear_disengages_startup_default_after_repair_ms() {
        LiveExitOnlyMode6387.setTestOverride("STARTUP_DEFAULT")
        val cleared = StartupExitOnlyLatch6401.checkAndClearStartupDefault(
            nowMs = System.currentTimeMillis() + StartupExitOnlyLatch6401.REPAIR_MS + 1_000L)
        assertTrue(cleared)
        // LiveExitOnlyMode6387 must be disengaged so downstream buy checks
        // no longer report STARTUP_DEFAULT.
        assertEquals(null, LiveExitOnlyMode6387.activeReason())
    }

    @Test fun blocking_pillars_are_named() {
        val list = blocking().blockingPillars()
        assertTrue(list.contains("sell_reconciler_first_tick"))
    }
}
