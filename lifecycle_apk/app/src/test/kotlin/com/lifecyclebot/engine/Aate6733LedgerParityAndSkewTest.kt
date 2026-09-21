package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPaperReplay6464
import com.lifecyclebot.engine.truth.PaperLedgerDivergenceGuard6731
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.6733 — regression suite for:
 *   1. Paper-ledger divergence guard staleness fail-open
 *      (§PARITY_STALENESS_GUARD_6732).
 *   2. V3JournalRecorder skew-quarantine root-cause fix
 *      (§SKEW_QUARANTINE_RECORDEXEC_ROOT_6732) — asserted at source
 *      level because the recorder wiring depends on end-to-end
 *      dependencies not available in a pure JVM unit test.
 */
class Aate6733LedgerParityAndSkewTest {

    @Before
    fun reset() {
        CanonicalPaperReplay6464.resetForTest()
    }

    // ─── 1. Parity staleness guard ────────────────────────────────

    @Test
    fun `guard fails open when no parity has ever been computed`() {
        val v = PaperLedgerDivergenceGuard6731.evaluate()
        assertTrue("no parity ever computed must fail-open, got reason=${v.reason}", v.allow)
        assertEquals("OK_NO_PARITY", v.reason)
    }

    @Test
    fun `parity age reports Long-MAX_VALUE before any compareToLedger runs`() {
        val age = CanonicalPaperReplay6464.lastParityAgeMs()
        assertEquals(Long.MAX_VALUE, age)
    }

    // ─── 2. Source-level skew fix ─────────────────────────────────

    @Test
    fun `V3JournalRecorder passes total buy raw as cumulative sell raw for full close`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/V3JournalRecorder.kt").readText()
        assertTrue(
            "SKEW_QUARANTINE_RECORDEXEC_ROOT_6732 fix must land in V3JournalRecorder",
            src.contains("SKEW_QUARANTINE_RECORDEXEC_ROOT"),
        )
        // The identity assertion: cumulative sell raw for a full close is
        // exactly the total buy raw. If someone re-introduces the
        // fabricated `rawConsumed` divisor, this contract catches it.
        assertTrue(
            "full-close skew check must be routed on totalBuyRaw identity",
            src.contains("cumulativeSellRaw = totalBuyRaw"),
        )
    }
}
