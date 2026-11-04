package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.engine.truth.DowntimeRecovery8430
import com.lifecyclebot.network.OhlcvRequestQueue8401
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Exercise the real recovery admission/completion path without a network or Android clock. */
class Aate8527DowntimeRecoveryTest {
    private val boot = 1_800_000_000_000L
    private val expires = boot + DowntimeRecovery8430.RECOVERY_WINDOW_MS_8430
    private val target = boot - 600_000L
    private val from = target - 60_000L
    private val deadline = target + 120_000L
    private fun candle() = Candle(ts = target - 60_000L, priceUsd = 0.0025,
        marketCap = 0.0, volumeH1 = 0.0, volume24h = 0.0)
    private fun read(mint: String = "MintA", now: Long = boot + 1_000L) =
        DowntimeRecovery8430.candleMark8430(mint, target, from, deadline, boot, now)
    private fun complete(state: OhlcvRequestQueue8401.State, now: Long = boot + 2_000L, rows: List<Candle> = emptyList()) =
        DowntimeRecovery8430.completeFetch8527("MintA", OhlcvRequestQueue8401.Answer(state, rows), now)

    @Before fun reset() { DowntimeRecovery8430.resetForTest8430() }
    @After fun after() { DowntimeRecovery8430.resetForTest8430() }

    @Test fun localBudgetRefusalRequeuesInsteadOfCachingAnEmptySourceAnswer() {
        assertTrue(read().waiting)
        assertEquals("MintA", DowntimeRecovery8430.takeQueued8527(boot + 1_000L))
        complete(OhlcvRequestQueue8401.State.WAIT_BUDGET)
        assertTrue(read(now = boot + 3_000L).waiting)
        assertTrue(DowntimeRecovery8430.statusLine8430().contains("empty=0 "))
        assertEquals("MintA", DowntimeRecovery8430.takeQueued8527(boot + 4_000L))
        complete(OhlcvRequestQueue8401.State.ANSWERED_BARS, boot + 5_000L, listOf(candle()))
        val result = read(now = boot + 6_000L)
        assertFalse(result.waiting)
        assertEquals(0.0025, result.mark!!.usd, 0.0)
        assertEquals(target, result.mark!!.observedAtMs) // provider CLOSE, never receipt/restart time
        assertEquals(DowntimeRecovery8430.SOURCE_8430, result.mark!!.source)
    }

    @Test fun localWaitsDoNotSpendTheProviderFailureBudget() {
        assertTrue(read().waiting)
        repeat(8) { i ->
            assertEquals("MintA", DowntimeRecovery8430.takeQueued8527(boot + 2_000L + i))
            complete(OhlcvRequestQueue8401.State.WAIT_BUDGET, boot + 2_001L + i)
        }
        assertTrue(read(now = boot + 10_000L).waiting)
        assertTrue(DowntimeRecovery8430.statusLine8430().contains("providerFail8527=0 "))
        assertEquals("MintA", DowntimeRecovery8430.takeQueued8527(boot + 11_000L))
        complete(OhlcvRequestQueue8401.State.ANSWERED_BARS, boot + 12_000L, listOf(candle()))
        assertNotNull(read(now = boot + 13_000L).mark)
    }

    @Test fun providerFailuresRetryBoundedlyWithoutBecomingEmptyPriceEvidence() {
        assertTrue(read().waiting)
        repeat(3) { i ->
            assertEquals("MintA", DowntimeRecovery8430.takeQueued8527(boot + 2_000L + i))
            complete(OhlcvRequestQueue8401.State.PROVIDER_FAILURE, boot + 2_001L + i)
            assertEquals(i < 2, read(now = boot + 3_000L + i).waiting)
        }
        assertNull(read(now = boot + 10_000L).mark)
        assertTrue(DowntimeRecovery8430.statusLine8430().contains("empty=0 "))
        assertTrue(DowntimeRecovery8430.statusLine8430().contains("gaveUp=1 "))
        assertTrue(DowntimeRecovery8430.statusLine8430().contains("providerFail8527=3 "))
    }

    @Test fun genuineEmptyAnswerIsTerminalAndNotRetried() {
        assertTrue(read().waiting)
        assertEquals("MintA", DowntimeRecovery8430.takeQueued8527(boot + 1_000L))
        complete(OhlcvRequestQueue8401.State.ANSWERED_EMPTY)
        assertFalse(read(now = boot + 3_000L).waiting)
        assertNull(read(now = boot + 3_000L).mark)
        assertNull(DowntimeRecovery8430.takeQueued8527(boot + 4_000L))
        assertTrue(DowntimeRecovery8430.statusLine8430().contains("empty=1 "))
    }

    @Test fun queuedAndInFlightMembershipCannotOverrideTheBootExpiry() {
        assertTrue(read().waiting)
        assertTrue(read("MintB").waiting)
        assertEquals("MintA", DowntimeRecovery8430.takeQueued8527(boot + 1_000L))
        assertFalse(read(now = expires + 1L).waiting)
        assertFalse(read("MintB", expires + 1L).waiting)
        assertFalse(read("MintB", expires + 2L).waiting)
        assertTrue(DowntimeRecovery8430.statusLine8430().contains("gaveUp=2 "))
        assertTrue(DowntimeRecovery8430.statusLine8430().contains("queued=0 "))
        complete(OhlcvRequestQueue8401.State.WAIT_BUDGET, expires + 3L)
        assertTrue(DowntimeRecovery8430.statusLine8430().contains("queued=0 "))
    }

    @Test fun expiryRunsEvenWithoutAProviderSlotAndDoesNotDiscardAnAlreadyStartedRealAnswer() {
        assertTrue(read().waiting)
        assertTrue(read("MintB").waiting)
        assertEquals("MintA", DowntimeRecovery8430.takeQueued8527(expires - 1L))
        DowntimeRecovery8430.expireQueued8527(expires + 1L)
        assertFalse(read("MintB", expires + 2L).waiting)
        // The boot bound limits waiting/retries, not the validity of a source-timed answer already on the wire.
        complete(OhlcvRequestQueue8401.State.ANSWERED_BARS, expires + 2L, listOf(candle()))
        val result = read(now = expires + 3L)
        assertNotNull(result.mark)
        assertEquals(target, result.mark!!.observedAtMs)
        assertNull(DowntimeRecovery8430.takeQueued8527(expires + 4L))
    }

    @Test fun retriesGoBehindOtherMintsAndDoNotConsumeAnotherUniqueMintSlot() {
        assertTrue(read().waiting)
        assertTrue(read("MintB").waiting)
        assertEquals("MintA", DowntimeRecovery8430.takeQueued8527(boot + 1_000L))
        complete(OhlcvRequestQueue8401.State.WAIT_BUDGET)
        assertEquals("MintB", DowntimeRecovery8430.takeQueued8527(boot + 3_000L))
        DowntimeRecovery8430.completeFetch8527("MintB",
            OhlcvRequestQueue8401.Answer(OhlcvRequestQueue8401.State.ANSWERED_EMPTY), boot + 4_000L)
        assertEquals("MintA", DowntimeRecovery8430.takeQueued8527(boot + 5_000L))
    }

    @Test fun inconsistentAnswerDoesNotCacheFalseAbsence() {
        assertTrue(read().waiting)
        assertEquals("MintA", DowntimeRecovery8430.takeQueued8527(boot + 1_000L))
        complete(OhlcvRequestQueue8401.State.ANSWERED_BARS) // no bars despite tag
        assertTrue(read(now = boot + 3_000L).waiting)
        assertTrue(DowntimeRecovery8430.statusLine8430().contains("empty=0 "))
    }

    @Test fun cancellationRemainsLocalAndDoesNotSpendProviderFailureAttempts() {
        assertTrue(read().waiting)
        assertEquals("MintA", DowntimeRecovery8430.takeQueued8527(boot + 1_000L))
        val cancelled = DowntimeRecovery8430.fetchOwned8527 { throw kotlinx.coroutines.CancellationException("cancel") }
        assertEquals(OhlcvRequestQueue8401.State.DEADLINE, cancelled.state)
        DowntimeRecovery8430.completeFetch8527("MintA", cancelled, boot + 2_000L)
        assertTrue(read(now = boot + 3_000L).waiting)
        assertTrue(DowntimeRecovery8430.statusLine8430().contains("providerFail8527=0 "))
        val wasInterrupted = Thread.interrupted()
        try {
            val interrupted = DowntimeRecovery8430.fetchOwned8527 { throw InterruptedException("interrupted") }
            assertEquals(OhlcvRequestQueue8401.State.DEADLINE, interrupted.state)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    @Test fun productionUsesTypedSourceAndPreservesTimeAndProofGates() {
        val recovery = File("src/main/kotlin/com/lifecyclebot/engine/truth/DowntimeRecovery8430.kt").readText()
        val provider = File("src/main/kotlin/com/lifecyclebot/network/SolanaOhlcvFeed6916.kt").readText()
        assertTrue(recovery.contains("SolanaOhlcvFeed6916.fetchAnswer8527"))
        assertFalse(recovery.contains("SolanaOhlcvFeed6916.fetchCandles6916("))
        assertTrue(provider.contains("fetch = { fetchAnswer8527(mint, timeframe, limit, poolHint) }"))
        assertTrue(recovery.contains("!c.synthetic && t in fromMs..toMs"))
        assertTrue(recovery.indexOf("nowMs - bootMs > RECOVERY_WINDOW_MS_8430") <
            recovery.indexOf("if (mint == inFlight8430 || queue.contains(mint))"))
    }
}
