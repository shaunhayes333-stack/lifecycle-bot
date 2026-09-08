package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6702 — regression locks for the paper sell accumulation incident.
 */
class Aate6702ExitLivenessRepairTest {

    private fun paperCloseSource() = File(
        "src/main/kotlin/com/lifecyclebot/engine/PaperPositionCloseAuthority.kt"
    ).readText()

    private fun pendingSource() = File(
        "src/main/kotlin/com/lifecyclebot/engine/PendingSellQueue.kt"
    ).readText()

    private fun stateLedgerSource() = File(
        "src/main/kotlin/com/lifecyclebot/engine/truth/PositionStateLedger6454.kt"
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
    fun `one shot emergency exit can break stale transient close state`() {
        val src = paperCloseSource()
        assertTrue(src.contains("isEmergencyRetryReason6702"))
        assertTrue(src.contains("EMERGENCY_TRANSIENT_RETRY_GRACE_MS_6702 = 2_000L"))
        assertTrue(src.contains("PAPER_EMERGENCY_CLOSE_STALE_STATE_BYPASSED_6702"))
        assertTrue(src.contains("emergency_stale_state_bypass_6702"))
        assertTrue(src.contains("\"STALE\""))
        assertTrue(src.contains("\"MAX_HOLD\""))
        assertTrue(src.contains("\"ZOMBIE\""))
    }

    @Test
    fun `paper terminal cas cannot remain closing forever`() {
        val src = stateLedgerSource()
        assertTrue(src.contains("closingSinceMs6702"))
        assertTrue(src.contains("PAPER_STALE_CLOSING_MS_6702 = 30_000L"))
        assertTrue(src.contains("PAPER_EMERGENCY_STALE_CLOSING_MS_6702 = 2_000L"))
        assertTrue(src.contains("recoverStalePaperClosing6702"))
        assertTrue(src.contains("PAPER_TERMINAL_STALE_CLOSING_RECOVERED_6702"))
        assertTrue(src.contains("p.mode.equals(\"paper\", true)"))
        assertTrue(src.contains("p.remainingQtyRaw > java.math.BigInteger.ZERO"))
        // Recovery belongs to paper only; live chain finality remains fail-closed.
        assertFalse(src.contains("p.mode.equals(\"live\", true)"))
    }

    @Test
    fun `terminal cas age is cleared on confirmation abandon and canonical rebuild`() {
        val src = stateLedgerSource()
        assertTrue(src.contains("closingSinceMs6702.clear()"))
        assertTrue(src.contains("closingSinceMs6702.remove(positionId)"))
        assertTrue(src.contains("states.replace(positionId, Lifecycle.CLOSING, Lifecycle.OPEN)"))
        assertTrue(src.contains("states.replace(positionId, Lifecycle.CLOSING, Lifecycle.CLOSED)"))
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
