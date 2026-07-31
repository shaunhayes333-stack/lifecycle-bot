package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6402 substrate — all four new authorities in one bundle:
 *  · UniversalSlLeaseRegistry6402 (§C start/done invariant)
 *  · ProviderCircuitBreaker6402 (§D Birdeye 401 / Helius 429)
 *  · BotLoopStageTiming6402 (§A stage instrumentation)
 *  · SameMintCandidateEpoch6402 (§H same-mint queue conflation)
 *  · ExitPendingOrphanGuard6402 (§G orphaned exitPending)
 */
class Bundle6402SubstrateTest {

    @Before fun setUp() {
        UniversalSlLeaseRegistry6402.clearAllForTest()
        ProviderCircuitBreaker6402.clearAllForTest()
        BotLoopStageTiming6402.clearAllForTest()
        SameMintCandidateEpoch6402.clearAllForTest()
    }
    @After fun tearDown() {
        UniversalSlLeaseRegistry6402.clearAllForTest()
        ProviderCircuitBreaker6402.clearAllForTest()
        BotLoopStageTiming6402.clearAllForTest()
        SameMintCandidateEpoch6402.clearAllForTest()
    }

    // ─── UniversalSlLeaseRegistry6402 ────────────────────────────────

    @Test fun sl_acquire_release_cycle_leaves_registry_empty() {
        val id = UniversalSlLeaseRegistry6402.acquire()
        assertTrue(id > 0L)
        assertEquals(1, UniversalSlLeaseRegistry6402.activeLeaseCount())
        assertTrue(UniversalSlLeaseRegistry6402.release(id))
        assertEquals(0, UniversalSlLeaseRegistry6402.activeLeaseCount())
    }

    @Test fun sl_double_release_is_no_op() {
        val id = UniversalSlLeaseRegistry6402.acquire()
        assertTrue(UniversalSlLeaseRegistry6402.release(id))
        assertFalse(UniversalSlLeaseRegistry6402.release(id))
    }

    /** Directive N.2: any lease older than 10s must be reaped. */
    @Test fun sl_stale_lease_reaped_after_ttl() {
        val start = 1_000_000L
        UniversalSlLeaseRegistry6402.acquire(nowMs = start)
        // ~11s later — beyond STALE_LEASE_TTL_MS = 10s.
        val reaped = UniversalSlLeaseRegistry6402.reapStaleLeases(
            nowMs = start + UniversalSlLeaseRegistry6402.STALE_LEASE_TTL_MS + 1_000L,
        )
        assertEquals(1, reaped)
        assertEquals(0, UniversalSlLeaseRegistry6402.activeLeaseCount())
    }

    @Test fun sl_young_lease_not_reaped() {
        val start = 1_000_000L
        UniversalSlLeaseRegistry6402.acquire(nowMs = start)
        val reaped = UniversalSlLeaseRegistry6402.reapStaleLeases(nowMs = start + 3_000L)
        assertEquals(0, reaped)
        assertEquals(1, UniversalSlLeaseRegistry6402.activeLeaseCount())
    }

    // ─── ProviderCircuitBreaker6402 ─────────────────────────────────

    /** Directive §D: first Birdeye 401 → permanent circuit open. */
    @Test fun birdeye_401_opens_circuit_and_persists() {
        val p = ProviderCircuitBreaker6402.Provider.BIRDEYE
        assertFalse(ProviderCircuitBreaker6402.shouldSkip(p))
        ProviderCircuitBreaker6402.onAuthTerminal(p)
        assertTrue(ProviderCircuitBreaker6402.shouldSkip(p))
        assertTrue(ProviderCircuitBreaker6402.isAuthTerminal(p))
        assertEquals("AUTH_TERMINAL", ProviderCircuitBreaker6402.classify(p))
        // Success on same provider must NOT clear auth-terminal.
        ProviderCircuitBreaker6402.onSuccess(p)
        assertTrue(ProviderCircuitBreaker6402.isAuthTerminal(p))
        // Only explicit reset clears auth-terminal.
        ProviderCircuitBreaker6402.resetAuthTerminal(p)
        assertFalse(ProviderCircuitBreaker6402.isAuthTerminal(p))
    }

    /** Directive §D: Helius 429 → one shared backoff, not per-mint. */
    @Test fun helius_429_activates_shared_backoff() {
        val p = ProviderCircuitBreaker6402.Provider.HELIUS
        val t = 1_000_000L
        ProviderCircuitBreaker6402.onRateLimited(p, nowMs = t)
        assertTrue(ProviderCircuitBreaker6402.shouldSkip(p, nowMs = t + 1_000L))
        // Base backoff is 5s min; after 10s we must be past it.
        assertFalse(ProviderCircuitBreaker6402.shouldSkip(p,
            nowMs = t + ProviderCircuitBreaker6402.RATE_LIMIT_MAX_BACKOFF_MS + 1_000L))
    }

    @Test fun rate_limit_backoff_grows_exponentially() {
        val p = ProviderCircuitBreaker6402.Provider.HELIUS
        val t = 1_000_000L
        ProviderCircuitBreaker6402.onRateLimited(p, nowMs = t)
        val after1 = ProviderCircuitBreaker6402.isRateLimited(p, nowMs = t + 3_000L)
        assertTrue("first 429 backoff must extend past 3s", after1)
        ProviderCircuitBreaker6402.onRateLimited(p, nowMs = t)
        ProviderCircuitBreaker6402.onRateLimited(p, nowMs = t)
        assertTrue("third consecutive 429 must extend well past 10s",
            ProviderCircuitBreaker6402.isRateLimited(p, nowMs = t + 10_000L))
    }

    @Test fun rate_limit_respects_retry_after_hint() {
        val p = ProviderCircuitBreaker6402.Provider.HELIUS
        val t = 1_000_000L
        ProviderCircuitBreaker6402.onRateLimited(p, retryAfterMs = 20_000L, nowMs = t)
        // Retry-After = 20s must extend beyond the base 5s backoff.
        assertTrue(ProviderCircuitBreaker6402.isRateLimited(p, nowMs = t + 15_000L))
    }

    @Test fun success_clears_transient_state_but_not_auth() {
        val p = ProviderCircuitBreaker6402.Provider.HELIUS
        val t = 1_000_000L
        ProviderCircuitBreaker6402.onRateLimited(p, nowMs = t)
        ProviderCircuitBreaker6402.onSuccess(p)
        assertFalse(ProviderCircuitBreaker6402.isRateLimited(p, nowMs = t + 100L))
    }

    // ─── BotLoopStageTiming6402 ─────────────────────────────────────

    @Test fun stage_time_runs_body_and_records_stats() {
        val cid = BotLoopStageTiming6402.newCycleId()
        val result = BotLoopStageTiming6402.time(cid, BotLoopStageTiming6402.Stage.SCANNER_DRAIN) {
            "ok"
        }
        assertEquals("ok", result)
        val stats = BotLoopStageTiming6402.stats(BotLoopStageTiming6402.Stage.SCANNER_DRAIN)
        assertEquals(1L, stats.count)
    }

    @Test fun stage_time_records_exception_but_re_throws() {
        val cid = BotLoopStageTiming6402.newCycleId()
        try {
            BotLoopStageTiming6402.time(cid, BotLoopStageTiming6402.Stage.EXIT_SWEEP) {
                throw IllegalStateException("boom")
            }
            @Suppress("UNREACHABLE_CODE")
            org.junit.Assert.fail("must re-throw the caller exception")
        } catch (t: IllegalStateException) {
            assertEquals("boom", t.message)
        }
        // The DONE emission must still have happened (exception path).
        val stats = BotLoopStageTiming6402.stats(BotLoopStageTiming6402.Stage.EXIT_SWEEP)
        assertEquals(1L, stats.count)
    }

    @Test fun cycle_ids_are_strictly_monotonic() {
        val a = BotLoopStageTiming6402.newCycleId()
        val b = BotLoopStageTiming6402.newCycleId()
        assertTrue(b > a)
    }

    // ─── SameMintCandidateEpoch6402 ─────────────────────────────────

    @Test fun same_mint_open_is_suppressed_and_counter_increments() {
        val mint = "MINT1"
        val v1 = SameMintCandidateEpoch6402.shouldSuppress(mint, sameMintAlreadyOpen = true)
        val v2 = SameMintCandidateEpoch6402.shouldSuppress(mint, sameMintAlreadyOpen = true)
        assertTrue(v1); assertTrue(v2)
        assertEquals(2L, SameMintCandidateEpoch6402.totalSuppressed())
    }

    @Test fun different_mint_not_suppressed() {
        SameMintCandidateEpoch6402.shouldSuppress("MINT1", sameMintAlreadyOpen = true)
        assertFalse(SameMintCandidateEpoch6402.shouldSuppress("MINT2", sameMintAlreadyOpen = false))
    }

    @Test fun state_change_admits_next_candidate() {
        val mint = "MINT1"
        SameMintCandidateEpoch6402.shouldSuppress(mint, sameMintAlreadyOpen = true)
        SameMintCandidateEpoch6402.onStateChange(mint, reason = "position_closed")
        assertFalse(SameMintCandidateEpoch6402.shouldSuppress(mint, sameMintAlreadyOpen = false))
    }

    @Test fun cooldown_window_still_suppresses_after_close() {
        val mint = "MINT1"
        val t = 1_000_000L
        SameMintCandidateEpoch6402.shouldSuppress(mint, sameMintAlreadyOpen = true, nowMs = t)
        // Position closed WITHOUT a state-change notification —
        // caller still hits the mint in the cooldown window.
        assertTrue(SameMintCandidateEpoch6402.shouldSuppress(mint, sameMintAlreadyOpen = false, nowMs = t + 500L))
        // Beyond the cooldown, the caller is admitted.
        val past = t + SameMintCandidateEpoch6402.SUPPRESSION_COOLDOWN_MS + 100L
        assertFalse(SameMintCandidateEpoch6402.shouldSuppress(mint, sameMintAlreadyOpen = false, nowMs = past))
    }

    // ─── ExitPendingOrphanGuard6402 ─────────────────────────────────

    @Test fun exitpending_false_returns_not_pending() {
        val v = ExitPendingOrphanGuard6402.classify(
            exitPending = false, sellIntentId = null,
            intentTimestampMs = null, intentAliveInExecutor = false,
        )
        assertEquals(ExitPendingOrphanGuard6402.Verdict.NotPending, v)
    }

    @Test fun exitpending_true_without_intent_is_orphaned() {
        val v = ExitPendingOrphanGuard6402.classify(
            exitPending = true, sellIntentId = null,
            intentTimestampMs = null, intentAliveInExecutor = false,
        )
        assertTrue(v is ExitPendingOrphanGuard6402.Verdict.Orphaned)
    }

    @Test fun exitpending_with_intent_not_in_executor_is_orphaned() {
        val v = ExitPendingOrphanGuard6402.classify(
            exitPending = true, sellIntentId = "intent_abc",
            intentTimestampMs = 1000L, intentAliveInExecutor = false,
        )
        assertTrue(v is ExitPendingOrphanGuard6402.Verdict.Orphaned)
    }

    @Test fun stale_intent_returns_stale_intent_verdict() {
        val now = 1_000_000L
        val v = ExitPendingOrphanGuard6402.classify(
            exitPending = true, sellIntentId = "intent_abc",
            intentTimestampMs = now - ExitPendingOrphanGuard6402.STALE_INTENT_MS - 1_000L,
            intentAliveInExecutor = true,
            nowMs = now,
        )
        assertTrue(v is ExitPendingOrphanGuard6402.Verdict.StaleIntent)
    }

    @Test fun healthy_pending_when_intent_recent_and_alive() {
        val now = 1_000_000L
        val v = ExitPendingOrphanGuard6402.classify(
            exitPending = true, sellIntentId = "intent_abc",
            intentTimestampMs = now - 500L,   // very recent
            intentAliveInExecutor = true,
            nowMs = now,
        )
        assertEquals(ExitPendingOrphanGuard6402.Verdict.HealthyPending, v)
    }
}
