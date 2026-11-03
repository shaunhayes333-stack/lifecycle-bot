package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.CloseLease
import com.lifecyclebot.engine.truth.ExitQuoteCorroboration7928 as QuoteGate
import com.lifecyclebot.engine.truth.HeldExitQuote8189
import com.lifecyclebot.engine.truth.ProtectiveExitScheduler6450 as Scheduler
import java.math.BigInteger
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Replays the 8519 failure shape: a deep mark, healthy executable holding, then a real dump. */
class Aate8521StopQuoteRevalidationTest {
    @Before fun reset() { QuoteGate.resetForTest8521(); HeldExitQuote8189.resetForTest8189() }

    @Test fun an_executable_stop_breach_is_not_vetoed_because_the_mark_overstates_the_loss() {
        assertFalse(QuoteGate.quoteRefutes8521(-82.0, -16.0, -15.0))
        assertTrue(QuoteGate.quoteRefutes8521(-82.0, -3.4, -15.0))
        assertTrue(QuoteGate.quoteRefutes8521(-25.0, -14.0, -15.0))
        var broadcasts = 0
        val allowed = QuoteGate.allowSell("breached8521", "X", "STRICT_SL_-15", -82.0, 0.05,
            nowMs = 1_000L, positionIdentity8521 = "p1|100|1", stopThresholdPct8521 = -15.0) { 0.042 }
        if (allowed) broadcasts++
        assertEquals("the first authoritative quote already breaches the stop", 1, broadcasts)
    }

    @Test fun a_healthy_quote_cannot_hold_a_later_dump_for_the_old_ten_second_cache() {
        var reads = 0
        fun quote(at: Long, out: Double) = QuoteGate.allowSell("dump8521", "X", "RAPID_CATASTROPHE_STOP", -82.0, 0.05,
            nowMs = at, positionIdentity8521 = "p1|100|1", stopThresholdPct8521 = -15.0) { reads++; out }
        assertFalse(quote(1_000L, 0.0483))
        assertFalse(quote(1_249L, 0.035))
        assertTrue("revalidate on the 250ms emergency clock", quote(1_250L, 0.035))
        assertEquals(2, reads)
    }

    @Test fun reentry_partial_basis_and_priority_each_invalidate_the_previous_quote() {
        var reads = 0
        fun q(id: String, cost: Double, reason: String, out: Double) =
            QuoteGate.allowSell("identity8521", "X", reason, -80.0, cost, nowMs = 1_000L,
                positionIdentity8521 = id, stopThresholdPct8521 = -15.0) { reads++; out }
        assertFalse(q("p1|100|1", 0.05, "RAPID_CATASTROPHE_STOP", 0.049))
        assertTrue(q("p2|100|1", 0.05, "RAPID_CATASTROPHE_STOP", 0.035))
        assertFalse(q("p2|50|2", 0.025, "RAPID_CATASTROPHE_STOP", 0.0245))
        assertTrue(q("p2|50|2", 0.04, "RAPID_CATASTROPHE_STOP", 0.0245))
        assertTrue(q("p2|50|2", 0.04, "STRICT_SL_-15", 0.0245))
        assertEquals(5, reads)
    }

    @Test fun a_missing_or_failed_optional_quote_never_strands_a_stop() {
        assertTrue(QuoteGate.allowSell("none8521", "X", "RAPID_CATASTROPHE_STOP", -60.0, 0.05) { null })
        assertTrue(QuoteGate.allowSell("failed8521", "X", "RAPID_CATASTROPHE_STOP", -60.0, 0.05) { error("provider down") })
    }

    @Test fun source_feedback_refutes_only_the_same_holding_and_an_older_bad_mark() {
        val raw = BigInteger.valueOf(100_000_000)
        val q = HeldExitQuote8189.Quote8189(0.048, 1_000L, raw, 0.048, positionId8521 = "p1", estimatedSellCostSol8521 = 0.0)
        fun v(pid: String = "p1", amount: BigInteger = raw, markAt: Long = 990L, now: Long = 1_100L) =
            StopMarkConfirm8418.feedbackVerdict8521(q, pid, amount, 0.05, markAt, now, -15.0)
        assertEquals(false, v())
        assertNull("never mask a newer dump tick", v(markAt = 1_001L))
        assertNull("a reentry cannot inherit the last position quote", v(pid = "p2"))
        assertNull("a partial changed average price and remaining basis", v(amount = raw / BigInteger.valueOf(2)))
        assertNull("feedback is not a long hold", v(now = 3_001L))
        assertNull("future-dated reads are not fresh", v(now = 999L))
        assertEquals(true, StopMarkConfirm8418.feedbackVerdict8521(q.copy(proceedsSol = 0.04), "p1", raw, 0.05, 990L, 1_100L, -15.0))
    }

    @Test fun stop_quote_is_returned_to_the_existing_held_mark_chain_without_fabricating_a_fill() {
        val raw = BigInteger.valueOf(100_000_000)
        HeldExitQuote8189.rememberStopQuote8521("feedback8521", "p1", raw, 6, 0.048, 100.0, 1_000L, 0.0003)
        val q = HeldExitQuote8189.last8189("feedback8521")!!
        assertEquals(0.048, q.priceUsd, 1e-10)
        assertEquals(0.048, q.proceedsSol!!, 1e-10)
        assertEquals("p1", q.positionId8521)
        assertEquals(raw, q.rawQty)
        HeldExitQuote8189.rememberStopQuote8521("feedback8521", "old", raw, 6, 0.01, 100.0, 900L, 0.0003)
        assertEquals("p1", HeldExitQuote8189.last8189("feedback8521")!!.positionId8521)
    }

    @Test fun invalidated_signal_releases_the_lease_and_does_not_retry_the_same_coalesced_reason() {
        val mint = "cancel8521-${System.nanoTime()}"
        val old = CloseLease.emergencyHandoff8087
        var cleanup = 0
        var handoffs = 0
        try {
            CloseLease.emergencyHandoff8087 = { _, _ -> handoffs++ }
            val l = CloseLease.acquire(mint, "X", "RAPID_CATASTROPHE_STOP")!!
            l.emergencyWaiting8087 = "RAPID_CATASTROPHE_STOP"
            assertTrue(CloseLease.invalidateSignal8521(mint, "RAPID_CATASTROPHE_STOP", false, l) { cleanup++ })
            assertFalse(CloseLease.isLeased(mint))
            assertEquals(1, cleanup)
            assertEquals(0, handoffs)
            val next = CloseLease.acquire(mint, "X", "STRICT_SL_-15")!!
            assertEquals("a fresh stop has no invented failed-route history", 1, next.closeAttemptCount)
            assertEquals(0L, next.nextEligibleMs)
        } finally { CloseLease.release(mint, "TEST_DONE"); CloseLease.emergencyHandoff8087 = old }
    }

    @Test fun a_different_real_emergency_is_handed_off_only_after_cancel_cleanup() {
        val mint = "preserve8521-${System.nanoTime()}"
        val old = CloseLease.emergencyHandoff8087
        val order = mutableListOf<String>()
        try {
            CloseLease.emergencyHandoff8087 = { _, reason -> order += reason }
            val l = CloseLease.acquire(mint, "X", "RAPID_CATASTROPHE_STOP")!!
            l.emergencyWaiting8087 = "RUG_DRAIN"
            assertTrue(CloseLease.invalidateSignal8521(mint, "RAPID_CATASTROPHE_STOP", false, l) { order += "cleanup" })
            assertEquals(listOf("cleanup", "RUG_DRAIN"), order)
        } finally { CloseLease.release(mint, "TEST_DONE"); CloseLease.emergencyHandoff8087 = old }
    }

    @Test fun another_sell_or_pending_finality_cannot_be_cancelled_by_a_quote() {
        val mint = "owned8521-${System.nanoTime()}"
        var cleanup = 0
        try {
            val l = CloseLease.acquire(mint, "X", "RAPID_CATASTROPHE_STOP")!!
            assertFalse(CloseLease.invalidateSignal8521(mint, "RAPID_CATASTROPHE_STOP", true, l) { cleanup++ })
            l.finalityPending = true
            assertFalse(CloseLease.invalidateSignal8521(mint, "RAPID_CATASTROPHE_STOP", false, l) { cleanup++ })
            assertTrue(CloseLease.isLeased(mint))
            assertEquals(0, cleanup)
        } finally { CloseLease.release(mint, "TEST_DONE") }
    }

    @Test fun typed_invalidation_reaches_cleanup_and_the_stop_origin_reads_feedback() {
        val ex = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val service = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(ex.contains("checked8521.quoteRefuted) SellResult.SIGNAL_INVALIDATED"))
        assertTrue(ex.contains("SellResult.SIGNAL_INVALIDATED -> invalidateExitSignal8521(ts, canonicalReason, lease)"))
        assertTrue(ex.contains("PendingSellQueue.removeInvalidatedSignal8521"))
        assertTrue(ex.contains("TokenLifecycleTracker.onSellSignalInvalidated8521"))
        assertTrue(ex.contains("ProtectiveExitScheduler6450.invalidateQuotedLoss8521"))
        assertTrue(ex.contains("SellExecutionLocks.releaseOwned7835(ts.mint, stamp)"))
        assertTrue(ex.contains("ExitTelemetryStamper6732.invalidateUnbroadcastSignal8521(ts.mint, ts.position.positionId)"))
        assertTrue(service.contains("StopMarkConfirm8418.cachedSellQuoteVerdict8521(ts, maxOf(catastropheThreshold, -HARD_FLOOR_STOP_PCT_CONST))"))
    }

    @Test fun an_old_quote_cannot_cancel_or_reschedule_a_new_lease_for_the_same_mint() {
        val mint = "reentry-lease8521-${System.nanoTime()}"
        var cleanup = 0
        try {
            val old = CloseLease.acquire(mint, "X", "RAPID_CATASTROPHE_STOP")!!
            CloseLease.release(mint, "OLD_FINALITY_RECONCILED")
            val current = CloseLease.acquire(mint, "X", "RAPID_CATASTROPHE_STOP")!!
            assertFalse(CloseLease.invalidateSignal8521(mint, "RAPID_CATASTROPHE_STOP", false, old) { cleanup++ })
            assertEquals(0L, CloseLease.recordPreRouteWait8288(mint, old))
            assertTrue(CloseLease.isLeased(mint))
            assertTrue("old completion must not turn off the new worker", current.inFlight)
            assertEquals(0, current.preRouteWaits8288)
            assertEquals(0L, current.nextEligibleMs)
            assertEquals(0, cleanup)
            assertTrue(CloseLease.invalidateSignal8521(mint, "RAPID_CATASTROPHE_STOP", false, current) { cleanup++ })
            assertEquals(1, cleanup)
        } finally { CloseLease.release(mint, "TEST_DONE") }
    }

    @Test fun cleanup_releases_the_monitor_but_reserves_its_generation_until_handoff() {
        val mint = "cleanup-race8521-${System.nanoTime()}"
        val otherMint = "$mint-other"
        val entered = CountDownLatch(1)
        val contenderDone = CountDownLatch(1)
        val order = ConcurrentLinkedQueue<String>()
        val previousHandoff = CloseLease.emergencyHandoff8087
        val workers = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "lease-cleanup-test8521").apply { isDaemon = true }
        }
        try {
            CloseLease.emergencyHandoff8087 = { requestedMint, reason ->
                if (requestedMint == mint) order.add(reason)
            }
            val lease = CloseLease.acquire(mint, "X", "RAPID_CATASTROPHE_STOP")!!
            val cleanup = workers.submit(Callable {
                CloseLease.invalidateSignal8521(mint, "RAPID_CATASTROPHE_STOP", false, lease) {
                    entered.countDown()
                    // A tracker holding its own monitor must be able to reconcile a
                    // lease while cleanup waits for that tracker. No sleep/race lottery.
                    assertTrue("CloseLease monitor is held during external cleanup", contenderDone.await(5, TimeUnit.SECONDS))
                    assertTrue("handoff must follow cleanup", order.none { it == "RUG_DRAIN" })
                    order.add("cleanup")
                }
            })
            val contender = workers.submit(Callable {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                try {
                    assertNotNull(CloseLease.acquire(otherMint, "Y", "TAKE_PROFIT"))
                    CloseLease.release(otherMint, "OTHER_DONE")
                    CloseLease.release(mint, "CONCURRENT_CANONICAL_TERMINAL")
                    assertTrue("terminal release is deferred until cleanup ends", CloseLease.isLeased(mint))
                    assertEquals(0L, CloseLease.recordPreRouteWait8288(mint, lease))
                    val attempts = lease.closeAttemptCount
                    assertEquals(attempts, CloseLease.recordRetry(mint, "STALE_WORKER_RETRY"))
                    assertTrue(lease.inFlight)
                    // Neither the TTL reader nor residue scan may expose a new owner.
                    lease.acquiredMs = System.currentTimeMillis() - CloseLease.LEASE_TTL_MS - 1L
                    assertTrue(CloseLease.isLeased(mint))
                    CloseLease.activeBlockingLeaseCount()
                    assertTrue(CloseLease.isLeased(mint))
                    assertNull(CloseLease.acquire(mint, "X", "RUG_DRAIN"))
                    assertTrue(order.isEmpty())
                    order.add("reconciled")
                } finally { contenderDone.countDown() }
            })
            contender.get(7, TimeUnit.SECONDS)
            assertTrue(cleanup.get(7, TimeUnit.SECONDS))
            assertEquals(listOf("reconciled", "cleanup", "RUG_DRAIN"), order.toList())
            assertFalse(CloseLease.isLeased(mint))
            val next = CloseLease.acquire(mint, "X", "RUG_DRAIN")!!
            assertNotSame(lease, next)
        } finally {
            contenderDone.countDown()
            workers.shutdownNow()
            workers.awaitTermination(7, TimeUnit.SECONDS)
            CloseLease.release(mint, "TEST_DONE")
            CloseLease.release(otherMint, "TEST_DONE")
            CloseLease.emergencyHandoff8087 = previousHandoff
        }
    }

    @Test fun cancellation_prioritizes_new_emergencies_without_the_disproved_original_reason() {
        val mint = "new-emergency8521-${System.nanoTime()}"
        val previousHandoff = CloseLease.emergencyHandoff8087
        val handoffs = mutableListOf<String>()
        try {
            CloseLease.emergencyHandoff8087 = { m, reason -> if (m == mint) handoffs += reason }
            val lease = CloseLease.acquire(mint, "X", "RAPID_CATASTROPHE_STOP")!!
            assertTrue(CloseLease.invalidateSignal8521(mint, "RAPID_CATASTROPHE_STOP", false, lease) {
                assertNull(CloseLease.acquire(mint, "X", "RUG_DRAIN"))
                assertEquals("RUG_DRAIN", lease.emergencyWaiting8087)
                assertNull(CloseLease.acquire(mint, "X", "STRICT_SL_-15"))
                assertEquals("new lower-priority signals cannot displace the queued rug exit", "RUG_DRAIN", lease.emergencyWaiting8087)
                CloseLease.raiseIntent(mint, "WALLET_DRAIN", 100)
                assertEquals("WALLET_DRAIN", lease.emergencyWaiting8087)
                assertNull(CloseLease.acquire(mint, "X", "RUG_DRAIN"))
                assertEquals("WALLET_DRAIN", lease.emergencyWaiting8087)
                assertTrue(handoffs.isEmpty())
            })
            assertEquals(listOf("WALLET_DRAIN"), handoffs)
        } finally {
            CloseLease.release(mint, "TEST_DONE")
            CloseLease.emergencyHandoff8087 = previousHandoff
        }
    }

    @Test fun cleanup_failure_and_late_finality_cannot_strand_or_cancel_a_reserved_lease() {
        val mint = "cleanup-failure8521-${System.nanoTime()}"
        val previousHandoff = CloseLease.emergencyHandoff8087
        try {
            CloseLease.emergencyHandoff8087 = null
            val lease = CloseLease.acquire(mint, "X", "RAPID_CATASTROPHE_STOP")!!
            assertFalse(CloseLease.invalidateSignal8521(mint, "RAPID_CATASTROPHE_STOP", false, lease) {
                throw IllegalStateException("simulated tracker persistence failure")
            })
            assertTrue(CloseLease.isLeased(mint))
            assertTrue(lease.inFlight)
            assertTrue("reservation clears so the exact owner can schedule its retry",
                CloseLease.recordPreRouteWait8288(mint, lease) > 0L)
            CloseLease.release(mint, "TEST_RETRY_DONE")
            val finality = CloseLease.acquire(mint, "X", "RAPID_CATASTROPHE_STOP")!!
            assertFalse(CloseLease.invalidateSignal8521(mint, "RAPID_CATASTROPHE_STOP", false, finality) {
                CloseLease.recordRetry(mint, "SELL_FINALITY_PENDING_RETRY")
            })
            assertTrue(finality.finalityPending)
            assertTrue(CloseLease.isLeased(mint))
            CloseLease.release(mint, "FINALITY_CONFIRMED")
            val terminal = CloseLease.acquire(mint, "X", "RAPID_CATASTROPHE_STOP")!!
            assertFalse(CloseLease.invalidateSignal8521(mint, "RAPID_CATASTROPHE_STOP", false, terminal) {
                CloseLease.release(mint, "CONCURRENT_TERMINAL")
                throw IllegalStateException("simulated cleanup failure after terminal proof")
            })
            assertFalse("deferred terminal release still runs after a thrown callback", CloseLease.isLeased(mint))
            assertNotNull(CloseLease.acquire(mint, "X", "MANUAL"))
        } finally {
            CloseLease.release(mint, "TEST_DONE")
            CloseLease.emergencyHandoff8087 = previousHandoff
        }
    }

    @Test fun net_execution_fees_cannot_hide_a_breached_small_ticket_stop() {
        assertTrue(QuoteGate.allowSell("net8521", "X", "STRICT_SL_-15", -80.0, 0.05,
            positionIdentity8521 = "p1|100|1", stopThresholdPct8521 = -15.0, sellCostSol8521 = 0.0015) { 0.0435 })
        assertTrue(QuoteGate.allowSell("unknown-fee8521", "X", "STRICT_SL_-15", -80.0, 0.05,
            stopThresholdPct8521 = -15.0, sellCostSol8521 = Double.NaN) { 0.049 })
        val raw = BigInteger.valueOf(100)
        val q = HeldExitQuote8189.Quote8189(0.0435, 1_000L, raw, 0.0435,
            positionId8521 = "p1", estimatedSellCostSol8521 = 0.0015)
        assertEquals(true, StopMarkConfirm8418.feedbackVerdict8521(q, "p1", raw, 0.05, 990L, 1_100L, -15.0))
        assertNull(StopMarkConfirm8418.feedbackVerdict8521(q.copy(estimatedSellCostSol8521 = null), "p1", raw, 0.05, 990L, 1_100L, -15.0))
    }

    @Test fun no_unsubmitted_or_failed_sell_may_retire_specialist_ownership() {
        for (r in Executor.SellResult.values()) {
            assertEquals(r in setOf(Executor.SellResult.CONFIRMED, Executor.SellResult.PAPER_CONFIRMED, Executor.SellResult.ALREADY_CLOSED), r.settled8521())
        }
    }

    @Test fun a_quote_refuted_risk_clock_latch_rearms_without_replaying_its_old_retry_history() {
        val pid = "riskclock8521-${System.nanoTime()}"
        val raw = BigInteger.valueOf(100)
        val old = Scheduler.latchTrigger(pid, Scheduler.TriggerKind.CATASTROPHE, 0.1, thresholdPrice8521 = 0.85)
        val q = HeldExitQuote8189.Quote8189(0.98, old.triggerTimestamp + 1, raw, 0.049,
            positionId8521 = pid, estimatedSellCostSol8521 = 0.001)
        assertFalse("a plain mark cannot undo a protective stop", Scheduler.attemptUntrigger(pid, "mark recovered"))
        assertTrue(Scheduler.invalidateQuotedLoss8521(pid, raw, q, old.triggerTimestamp, old.triggerTimestamp + 2))
        assertFalse(Scheduler.isTriggered(pid))
        assertFalse(Scheduler.shouldDispatch7176(pid))
        val next = Scheduler.latchTrigger(pid, Scheduler.TriggerKind.STOP_LOSS, 0.7, thresholdPrice8521 = 0.85)
        assertEquals(Scheduler.TriggerKind.STOP_LOSS, next.kind)
        assertTrue(Scheduler.isTriggered(pid))
        assertFalse("the real new stop receives its first dispatch, not accumulated retry debt", Scheduler.shouldDispatch7176(pid))
    }

    @Test fun quote_cleanup_preserves_a_still_breached_or_newer_independent_latch() {
        val pid = "preserve-risk8521-${System.nanoTime()}"
        val raw = BigInteger.valueOf(100)
        val latch = Scheduler.latchTrigger(pid, Scheduler.TriggerKind.STOP_LOSS, 0.1, thresholdPrice8521 = 0.95)
        val q = HeldExitQuote8189.Quote8189(0.98, latch.triggerTimestamp + 1, raw, 0.049,
            positionId8521 = pid, estimatedSellCostSol8521 = 0.003)
        // Gross price has recovered but executable NET still breaches this independent floor.
        assertEquals(0.92, Scheduler.lossMark8521(q, pid, raw, latch.triggerTimestamp, latch.triggerTimestamp + 2)!!, 1e-10)
        assertFalse(Scheduler.invalidateQuotedLoss8521(pid, raw, q, latch.triggerTimestamp, latch.triggerTimestamp + 2))
        val older = q.copy(observedAtMs = latch.triggerTimestamp - 1, estimatedSellCostSol8521 = 0.0)
        assertFalse(Scheduler.invalidateQuotedLoss8521(pid, raw, older, latch.triggerTimestamp - 2, latch.triggerTimestamp + 2))
        assertNull("new price tick requires new confirmation", Scheduler.lossMark8521(q, pid, raw, q.observedAtMs + 1, q.observedAtMs + 2))
        assertNull(Scheduler.lossMark8521(q, pid, raw / BigInteger.valueOf(2), latch.triggerTimestamp, latch.triggerTimestamp + 2))
        assertNull(Scheduler.lossMark8521(q, "reentry", raw, latch.triggerTimestamp, latch.triggerTimestamp + 2))
        assertTrue(Scheduler.isTriggered(pid))
    }
}
