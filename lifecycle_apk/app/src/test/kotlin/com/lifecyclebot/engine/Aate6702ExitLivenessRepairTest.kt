package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6702 — regression locks for the paper sell accumulation incident.
 *
 * Proven failure shape:
 *  1) duplicate CLOSE_REQUESTED/CLOSING signals refreshed updatedAtMs every tick,
 *     so the 30s stuck-close retry could be starved forever;
 *  2) PendingSellQueue dropped still-open positions after an age/retry budget;
 *  3) requeue accounting incremented retryCount twice per failed attempt;
 *  4) the queue consulted LIVE terminal state even while PAPER was authoritative.
 */
class Aate6702ExitLivenessRepairTest {

    private fun paperCloseSource() = File(
        "src/main/kotlin/com/lifecyclebot/engine/PaperPositionCloseAuthority.kt"
    ).readText()

    private fun pendingSource() = File(
        "src/main/kotlin/com/lifecyclebot/engine/PendingSellQueue.kt"
    ).readText()

    @Test
    fun `duplicate paper close signals cannot refresh stuck retry clock`() {
        val src = paperCloseSource()
        assertTrue(src.contains("PAPER_CLOSE_DUPLICATE_TIMESTAMP_FROZEN_6702"))
        assertTrue(src.contains("PAPER_CLOSING_DUPLICATE_TIMESTAMP_FROZEN_6702"))
        assertTrue(src.contains("State.CLOSE_REQUESTED, State.CLOSING ->"))
        assertTrue(src.contains("State.CLOSING ->"))
        assertTrue(src.contains("STUCK_CLOSE_TTL_MS = 30_000L"))
    }

    @Test
    fun `pending sell ownership persists until terminal proof`() {
        val src = pendingSource()
        assertTrue(src.contains("PENDING_SELL_PERSISTED_BEYOND_LEGACY_LIMIT_6702"))
        assertFalse(src.contains("sell.ageMs > MAX_AGE_MS"))
        assertFalse(src.contains("sell.retryCount >= MAX_RETRIES"))
        assertFalse(src.contains("Not requeuing") && src.contains("max retries reached"))
    }

    @Test
    fun `paper queue terminal proof cannot come from live close authority`() {
        val src = pendingSource()
        assertTrue(src.contains("terminalForRuntime6702"))
        assertTrue(src.contains("RuntimeModeAuthority.isPaper()"))
        assertTrue(src.contains("PaperPositionCloseAuthority.stateOf(\"PAPER\", mint)"))
    }

    @Test
    fun `retry count advances once per attempt not twice`() {
        val src = pendingSource()
        val getBlock = src.substringAfter("fun getAndClear()").substringBefore("fun requeue")
        val requeueBlock = src.substringAfter("fun requeue").substringBefore("fun remove")
        assertTrue(getBlock.contains("retryCount = sell.retryCount + 1"))
        assertFalse(requeueBlock.contains("retryCount + 1"))
    }

    @Test
    fun `duplicate queue add preserves retry age and count`() {
        val src = pendingSource()
        assertTrue(src.contains("queuedAtMs = existing.queuedAtMs"))
        assertTrue(src.contains("retryCount = existing.retryCount"))
    }
}
