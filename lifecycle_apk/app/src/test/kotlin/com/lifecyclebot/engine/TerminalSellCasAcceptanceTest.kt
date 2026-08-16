package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PositionStateLedger6454
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.0.6454 §P0 — HARD CI ASSERTIONS FOR TERMINAL SELL CAS AT SIDE-EFFECT DOOR.
 *
 * Locks the invariants the operator called out:
 *   - blank PositionId at terminal SELL is REJECTED
 *   - duplicate reserveTerminalSell for same positionId is REJECTED
 *   - confirmTerminalSell can transition CLOSING -> CLOSED exactly once
 *   - terminalCount(positionId) never exceeds 1 under concurrent
 *     reserve+confirm races
 */
class TerminalSellCasAcceptanceTest {

    @Test
    fun `reserveTerminalSell refuses blank positionId (fail closed)`() {
        PositionStateLedger6454.resetForTest()
        val r = PositionStateLedger6454.reserveTerminalSell("", "test_blank")
        assertEquals(PositionStateLedger6454.ReserveResult.REJECTED_BLANK_ID, r)
    }

    @Test
    fun `reserveTerminalSell refuses unknown positionId (no fail-open)`() {
        PositionStateLedger6454.resetForTest()
        val r = PositionStateLedger6454.reserveTerminalSell("UNKNOWN_PID_${System.nanoTime()}", "test_unknown")
        assertEquals(PositionStateLedger6454.ReserveResult.REJECTED_UNKNOWN, r)
    }

    @Test
    fun `open position CAS transitions to CLOSING then CLOSED exactly once`() {
        PositionStateLedger6454.resetForTest()
        val pid = "TEST_PID_LIFECYCLE_${System.nanoTime()}"
        PositionStateLedger6454.onEntry(pid)
        assertEquals(PositionStateLedger6454.Lifecycle.OPEN, PositionStateLedger6454.lifecycle(pid))

        val r1 = PositionStateLedger6454.reserveTerminalSell(pid, "test_terminal")
        assertEquals(PositionStateLedger6454.ReserveResult.RESERVED, r1)
        assertEquals(PositionStateLedger6454.Lifecycle.CLOSING, PositionStateLedger6454.lifecycle(pid))

        // Duplicate reserve MUST be rejected — this is the DsXR94/2cxRDE
        // repeated-SELL guardrail.
        val r2 = PositionStateLedger6454.reserveTerminalSell(pid, "test_dup")
        assertNotEquals(PositionStateLedger6454.ReserveResult.RESERVED, r2)
        assertEquals(PositionStateLedger6454.ReserveResult.REJECTED_ALREADY_CLOSING, r2)

        // Confirm transitions to CLOSED.
        val c1 = PositionStateLedger6454.confirmTerminalSell(pid)
        assertEquals(PositionStateLedger6454.ConfirmResult.CONFIRMED, c1)
        assertEquals(PositionStateLedger6454.Lifecycle.CLOSED, PositionStateLedger6454.lifecycle(pid))
        assertEquals(1L, PositionStateLedger6454.terminalCount(pid))

        // Duplicate confirm MUST return false and NOT increment count.
        val c2 = PositionStateLedger6454.confirmTerminalSell(pid)
        assertNotEquals(PositionStateLedger6454.ConfirmResult.CONFIRMED, c2)
        assertEquals(
            "terminal count must remain 1 after duplicate confirm — operator's DsXR94 repeated SELL guardrail",
            1L,
            PositionStateLedger6454.terminalCount(pid),
        )
    }

    @Test
    fun `concurrent reserve races produce exactly one RESERVED verdict`() {
        // V5.0.6454 §P0 acceptance: 50 concurrent reserveTerminalSell for
        // the same positionId must return RESERVED exactly once.
        PositionStateLedger6454.resetForTest()
        val pid = "TEST_PID_CONCURRENT_${System.nanoTime()}"
        PositionStateLedger6454.onEntry(pid)

        val threads = 50
        val exec = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val reserved = AtomicInteger(0)

        repeat(threads) { i ->
            exec.submit {
                try {
                    start.await()
                    val r = PositionStateLedger6454.reserveTerminalSell(pid, "race_$i")
                    if (r == PositionStateLedger6454.ReserveResult.RESERVED) reserved.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue("threads must complete within 5s", done.await(5, TimeUnit.SECONDS))
        exec.shutdown()

        assertEquals(
            "exactly one thread may win the terminal-sell reservation",
            1,
            reserved.get(),
        )

        // Now confirm — again, exactly one CONFIRMED under concurrent race.
        val confirmedCount = AtomicInteger(0)
        val exec2 = Executors.newFixedThreadPool(threads)
        val start2 = CountDownLatch(1)
        val done2 = CountDownLatch(threads)
        repeat(threads) {
            exec2.submit {
                try {
                    start2.await()
                    val c = PositionStateLedger6454.confirmTerminalSell(pid)
                    if (c == PositionStateLedger6454.ConfirmResult.CONFIRMED) confirmedCount.incrementAndGet()
                } finally {
                    done2.countDown()
                }
            }
        }
        start2.countDown()
        assertTrue(done2.await(5, TimeUnit.SECONDS))
        exec2.shutdown()

        assertEquals(
            "exactly one thread may win the terminal-sell confirm",
            1,
            confirmedCount.get(),
        )
        assertEquals(
            "terminalCount must remain exactly 1 under concurrent confirms",
            1L,
            PositionStateLedger6454.terminalCount(pid),
        )
    }
}
