package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import com.lifecyclebot.engine.truth.ScalpExit8351
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercise the same dispatch adapter used by BotService; never supply a pre-parsed tier. */
class Aate8397RunnerExecutionTest {
    private val scalp = RunnerRungBook8153.LADDER_SCALP_EXIT_8394
    private val reason = "SCALP_TAKE_PROFIT_8351_T1"
    private val book: RunnerRungBook8153 get() = RunnerLive8153::class.java.getDeclaredField("book")
        .apply { isAccessible = true }.get(RunnerLive8153) as RunnerRungBook8153

    private fun token(now: Long) = TokenState(mint = "runner8397-${System.nanoTime()}", position = Position(
        qtyToken = 1000.0, entryPrice = 100.0, entryTime = now - 60_000L,
        tradingMode = "MOONSHOT", isPaperPosition = false))
    private fun key(ts: TokenState) = "${ts.mint}|${ts.position.entryTime}"

    @Suppress("UNCHECKED_CAST")
    private fun installScalp(ts: TokenState) {
        val states = ScalpExit8351::class.java.getDeclaredField("states").apply { isAccessible = true }
            .get(ScalpExit8351) as MutableMap<String, Any>
        val ctor = ScalpExit8351::class.java.declaredClasses.single { it.simpleName == "State8351" }
            .declaredConstructors.first { it.parameterCount == 5 }.apply { isAccessible = true }
        states[key(ts)] = ctor.newInstance(true, 9.28, 13.92, 0.0, 0.0)
    }

    @Suppress("UNCHECKED_CAST")
    private fun clean(ts: TokenState) {
        val rows = RunnerRungBook8153::class.java.getDeclaredField("book").apply { isAccessible = true }
            .get(book) as MutableMap<String, Any>
        rows.keys.removeAll { it.startsWith("${ts.mint}|") }
        val states = ScalpExit8351::class.java.getDeclaredField("states").apply { isAccessible = true }
            .get(ScalpExit8351) as MutableMap<String, Any>
        states.keys.removeAll { it.startsWith("${ts.mint}|") }
        for (name in listOf("armed", "windows8297", "firstArmed8297", "closedAt8297")) {
            val map = RunnerPlay8018::class.java.getDeclaredField(name).apply { isAccessible = true }
                .get(RunnerPlay8018) as MutableMap<String, Any>
            map.remove(ts.mint)
        }
    }

    @Test fun realScalpOfferRejectedByDispatchRetriesWithoutManualTierOrExpiry() {
        val now = System.currentTimeMillis(); val ts = token(now); installScalp(ts)
        val offers = ArrayList<String>()
        try {
            ScalpExit8351.offer8351(ts, 135.0, now) { _, _, why -> offers += why }
            assertEquals(listOf(reason), offers)
            runBlocking {
                assertFalse(RungDispatch8397.dispatch8397(ts, offers.single()) {
                    assertEquals(RunnerRungBook8153.Stage8153.TICKET_ACCEPTED, book.pending8153(key(ts))!!.stage)
                    false
                })
            }
            assertNull(book.pending8153(key(ts)))
            assertEquals(0, book.confirmedTier8153(key(ts), scalp))
            ScalpExit8351.offer8351(ts, 135.0, now + 3_000L) { _, _, why -> offers += why }
            assertEquals(listOf(reason, reason), offers)
        } finally { clean(ts) }
    }

    @Test fun thrownOrCancelledDispatchReleasesItsAttemptWithoutFabricatingAFill() {
        for (failure in listOf(IllegalStateException("route rejected"), CancellationException("stopped"))) {
            val now = System.currentTimeMillis(); val ts = token(now)
            try {
                assertTrue(RunnerLive8153.request8153(ts, scalp, 1, reason, now))
                var caught: Throwable? = null
                try { runBlocking { RungDispatch8397.dispatch8397(ts, reason) { throw failure } } }
                catch (t: Throwable) { caught = t }
                assertSame(failure, caught)
                assertNull(book.pending8153(key(ts)))
                assertEquals(0, book.confirmedTier8153(key(ts), scalp))
            } finally { clean(ts) }
        }
    }

    @Test fun appliedReceiptAdvancesOnlyItsLadderAndSurvivesRestore() {
        val now = System.currentTimeMillis(); val ts = token(now)
        try {
            assertTrue(RunnerLive8153.request8153(ts, scalp, 1, reason, now))
            runBlocking { assertTrue(RungDispatch8397.dispatch8397(ts, reason) { true }) }
            assertEquals(1, book.confirmedTier8153(key(ts), scalp))
            assertEquals(0, book.confirmedTier8153(key(ts), RunnerRungBook8153.LADDER_RUNNER_8153))
            assertFalse(RunnerLive8153.request8153(ts, scalp, 1, reason, now + 3_000L))
            val restored = RunnerRungBook8153(); restored.restore8153(book.encode8153())
            assertEquals(1, restored.confirmedTier8153(key(ts), scalp))
        } finally { clean(ts) }
    }

    @Test fun delayedRejectionCannotReleaseANewerRequestWithTheSameReason() {
        val now = System.currentTimeMillis(); val ts = token(now)
        try {
            assertTrue(RunnerLive8153.request8153(ts, scalp, 1, reason, now))
            runBlocking {
                RungDispatch8397.dispatch8397(ts, reason) {
                    book.revert8153(key(ts), RunnerRungBook8153.Revert8153.SUPERSEDED, now + 1L)
                    assertTrue(RunnerLive8153.request8153(ts, scalp, 1, reason, now + 2L))
                    false
                }
            }
            assertEquals(now + 2L, book.pending8153(key(ts))!!.atMs)
            assertEquals(RunnerRungBook8153.Stage8153.REQUESTED, book.pending8153(key(ts))!!.stage)
        } finally { clean(ts) }
    }

    @Test fun unrelatedReceiptCannotAdvanceOrRearmAnotherLaddersPendingRequest() {
        val now = System.currentTimeMillis(); val ts = token(now)
        try {
            assertTrue(RunnerLive8153.request8153(ts, RunnerRungBook8153.LADDER_RUNNER_8153, 1, "SPIKE_CAPTURE_7943_T1", now))
            runBlocking { assertFalse(RungDispatch8397.dispatch8397(ts, reason) { fail("unowned rung submitted"); false }) }
            val p = book.pending8153(key(ts))!!
            assertEquals("SPIKE_CAPTURE_7943_T1", p.reason)
            assertEquals(RunnerRungBook8153.Stage8153.REQUESTED, p.stage)
        } finally { clean(ts) }
    }

    @Test fun upstreamArbiterAcceptanceStillAllowsItsQueuedSellToExecute() {
        val now = System.currentTimeMillis(); val ts = token(now)
        try {
            assertTrue(RunnerLive8153.request8153(ts, scalp, 1, reason, now))
            val offer = RungDispatch8397.capture8397(ts, reason)
            SpikeCapture7943.dispatched8153(ts, reason)
            assertEquals(RunnerRungBook8153.Stage8153.TICKET_ACCEPTED, book.pending8153(key(ts))!!.stage)
            var calls = 0
            runBlocking { assertTrue(RungDispatch8397.dispatch8397(ts, reason, offer) { calls++; true }) }
            assertEquals(1, calls)
            assertEquals(1, book.confirmedTier8153(key(ts), scalp))
        } finally { clean(ts) }
    }

    @Test fun confirmedInitialScalpSliceDoesNotAlsoConsumeTheRunnersFirstTake() {
        val now = System.currentTimeMillis(); val ts = token(now)
        val initialScalp = RunnerRungBook8153.LADDER_SCALP_8153
        val spike = "SPIKE_CAPTURE_7943_T1"
        try {
            assertTrue(RunnerLive8153.request8153(ts, initialScalp, 1, spike, now))
            RunnerLive8153.bookSlice8153(ts, spike, "slice8397-${ts.mint}", java.math.BigInteger.ONE, 0.02, 0.01)
            assertNull(book.pending8153(key(ts)))
            SpikeCapture7943.onConfirmedPartial8081(ts, spike, java.math.BigInteger.ONE, alreadyCommitted8397 = true)
            assertEquals(1, book.confirmedTier8153(key(ts), initialScalp))
            assertEquals(0, book.confirmedTier8153(key(ts), RunnerRungBook8153.LADDER_RUNNER_8153))
        } finally { clean(ts) }
    }

    @Test fun sameTimestampAndReasonCannotBorrowAReplacementLaddersRequest() {
        val now = System.currentTimeMillis(); val ts = token(now)
        val spike = "SPIKE_CAPTURE_7943_T1"
        try {
            assertTrue(RunnerLive8153.request8153(ts, RunnerRungBook8153.LADDER_SCALP_8153, 1, spike, now))
            val offer = RungDispatch8397.capture8397(ts, spike)
            book.revert8153(key(ts), RunnerRungBook8153.Revert8153.SUPERSEDED, now)
            assertTrue(RunnerLive8153.request8153(ts, RunnerRungBook8153.LADDER_RUNNER_8153, 1, spike, now))
            runBlocking { assertFalse(RungDispatch8397.dispatch8397(ts, spike, offer) { fail("replacement ladder sold"); true }) }
            assertEquals(RunnerRungBook8153.LADDER_RUNNER_8153, book.pending8153(key(ts))!!.ladder)
        } finally { clean(ts) }
    }

    @Test fun queuedOldPositionOrSupersededRequestNeverReachesTheSellDoor() {
        val now = System.currentTimeMillis(); val ts = token(now)
        try {
            assertTrue(RunnerLive8153.request8153(ts, scalp, 1, reason, now))
            val offered = RungDispatch8397.capture8397(ts, reason)
            book.revert8153(key(ts), RunnerRungBook8153.Revert8153.SUPERSEDED, now + 1L)
            assertTrue(RunnerLive8153.request8153(ts, scalp, 1, reason, now + 2L))
            runBlocking { assertFalse(RungDispatch8397.dispatch8397(ts, reason, offered) { fail("superseded request submitted"); true }) }
            assertEquals(now + 2L, book.pending8153(key(ts))!!.atMs)
            val oldPosition = RungDispatch8397.capture8397(ts, reason)
            ts.position = ts.position.copy(entryTime = now + 10L)
            runBlocking { assertFalse(RungDispatch8397.dispatch8397(ts, reason, oldPosition) { fail("replacement position sold"); true }) }
        } finally { clean(ts) }
    }

    @Test fun refusedCapacityDoesNotPublishARunnerTicket() {
        val now = System.currentTimeMillis(); val ts = token(now)
        try {
            assertFalse(RunnerPlay8018.issueTicket8397(ts.mint, now, 2))
            assertFalse(RunnerPlay8018.ticketActive8018(ts.mint, now))
            assertTrue(RunnerPlay8018.issueTicket8397(ts.mint, now + 1L, 0))
            assertTrue(RunnerPlay8018.ticketActive8018(ts.mint, now + 1L))
        } finally { clean(ts) }
    }

    @Test fun canonicalChaseHonoursTheRestoredTicketAndStillRefusesAnExpiredUnprovenRun() {
        val now = System.currentTimeMillis(); val ts = token(now)
        ts.lastPriceChange5m = 20.0
        try {
            assertTrue(RunnerPlay8018.issueTicket8397(ts.mint, now, 0))
            // superseded by V5.0.8418 — a re-entry ticket is no chase exemption (DIRECTIVE_LEDGER #10, 8337 FIX 6b); was assertNull.
            assertEquals("CHASING_SPIKE_8065", LiveEdgeGate7877.chaseRefusal8397(ts, "MOONSHOT", 200.0, 0.98, now))
            assertEquals("CHASING_SPIKE_8065", LiveEdgeGate7877.chaseRefusal8397(ts, "MOONSHOT", 200.0, 0.98, now + 180_001L))
            assertNull(LiveEdgeGate7877.chaseRefusal8397(ts, "MOONSHOT", 20.0, 0.80, now + 180_001L))
        } finally { clean(ts) }
    }
}
