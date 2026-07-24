package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * V5.0.6360 — Correction of V5.0.6350 force-terminal semantics.
 *
 * OPERATOR SYMPTOM
 *   "heaps of buys nothing round tripping". V5.0.6350 stamped state=CLOSED
 *   via markClosed() after 3 stuck retries. That killed paper round-trips:
 *   the actual TokenState.position was still OPEN with tokens (no
 *   recordSell had fired), but every future sell attempt then hit
 *   Guard(blocked=true, State.CLOSED) so no round-trip could ever complete.
 *
 * FIX
 *   V5.0.6360 replaces force-terminal with force-RESET: after the third
 *   stuck retry, clear the state back to OPEN and reset the retry counter.
 *   This still drains the log-spam loop but never blocks a legitimate
 *   future sell attempt.
 */
class PaperCloseForceReset6360Test {

    @Test
    fun force_reset_replaces_force_terminal() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/PaperPositionCloseAuthority.kt").readText()
        assertTrue("V5.0.6360 must introduce PAPER_CLOSE_FORCE_RESET_6360 label",
            txt.contains("PAPER_CLOSE_FORCE_RESET_6360"))
        assertFalse("force-terminal markClosed call must be removed from the hard-cap branch",
            txt.contains("markClosed(mode = mode, mint = mint, symbol = symbol,\n                        reason = \"PAPER_CLOSE_FORCE_TERMINAL_6350"))
    }

    @Test
    fun force_reset_stamps_state_open_and_returns_unblocked_guard() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/PaperPositionCloseAuthority.kt").readText()
        assertTrue("hard-cap branch must reset state to OPEN",
            txt.contains("st.state = State.OPEN"))
        assertTrue("hard-cap branch must reset stuckRetryCount to 0",
            txt.contains("st.stuckRetryCount = 0"))
        assertTrue("hard-cap branch must return an unblocked Guard so future sells can retry",
            txt.contains("Guard(false, State.OPEN, \"force_reset_open_6360\""))
    }

    @Test
    fun retry_ttl_and_hard_cap_constants_unchanged() {
        // The tighter 30s TTL + 3-retry hard cap policy stays — only the
        // action on the hard cap changes (reset instead of terminal).
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/PaperPositionCloseAuthority.kt").readText()
        assertTrue(txt.contains("STUCK_CLOSE_TTL_MS = 30_000L"))
        assertTrue(txt.contains("STUCK_RETRY_HARD_CAP = 3"))
    }
}
