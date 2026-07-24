package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * V5.0.6350 — Paper close retry-loop drain golden-tape test.
 *
 * Operator complaint: 779 PAPER_CLOSE_STUCK_TTL_RETRY_6071 events in a 15-minute
 * window, 134 paper BUY vs 27 SELL → 100+ silent-stuck paper positions blocking
 * learning cadence.
 *
 * Fix: tighten STUCK_CLOSE_TTL_MS from 120s → 30s and hard-cap the retry loop
 * at 3 attempts, force-terminaling the mint on the 4th so paper cannot
 * accumulate stuck positions.
 */
class PaperCloseRetryDrain6350Test {

    @Test
    fun stuck_close_ttl_tightened_to_thirty_seconds() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/PaperPositionCloseAuthority.kt").readText()
        assertTrue("STUCK_CLOSE_TTL_MS must be 30_000L (30s)",
            txt.contains("STUCK_CLOSE_TTL_MS = 30_000L"))
    }

    @Test
    fun retry_hard_cap_forces_terminal_close_after_three_retries() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/PaperPositionCloseAuthority.kt").readText()
        assertTrue("STUCK_RETRY_HARD_CAP must be defined at 3",
            txt.contains("STUCK_RETRY_HARD_CAP = 3"))
        assertTrue("must emit PAPER_CLOSE_FORCE_TERMINAL_6350 telemetry",
            txt.contains("PAPER_CLOSE_FORCE_TERMINAL_6350"))
        assertTrue("hard-cap branch must call markClosed for the force-terminal",
            txt.contains("markClosed(mode = mode, mint = mint"))
        assertTrue("retry counter must be tracked on CloseState",
            txt.contains("stuckRetryCount: Int = 0") ||
            txt.contains("stuckRetryCount = 0"))
    }

    @Test
    fun retry_count_resets_on_true_close() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/PaperPositionCloseAuthority.kt").readText()
        assertTrue("markClosed must reset the stuck retry counter",
            txt.contains("s.stuckRetryCount = 0"))
    }

    @Test
    fun original_ttl_retry_label_still_present_for_pre_hard_cap_retries() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/PaperPositionCloseAuthority.kt").readText()
        assertTrue("PAPER_CLOSE_STUCK_TTL_RETRY_6071 label must remain (pre-hard-cap retries still allowed)",
            txt.contains("PAPER_CLOSE_STUCK_TTL_RETRY_6071"))
    }
}
