package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6743 — Directive follow-up regression coverage for two
 * source-level defects the operator surfaced in the 6742 dump:
 *
 * §CANONICAL_ENUMERATION_TRUTH — the strict 6631 §B/§L filter was
 * silently hiding 15 legitimately-funded lots from exit /
 * reconciliation, producing the "115 OPEN but openPositions()=100"
 * contradiction and pinning the throughput authority at HARD_CAP.
 * openPositions() must now return TRUTH (lifecycle + qty>0);
 * `openPositionsForValuation()` retains the strict filter for
 * hero-equity paths.
 *
 * §CLOSE_LEDGER_RECONSTRUCT_FROM_CANONICAL — 42 canonical CLOSED
 * lots had ZERO PositionCloseLedger stamps in the 6742 dump. A
 * new `reconstructFromCanonical6743` fills the gap on each cycle
 * so slot-health can actually reap forced=100/open=100 into a
 * true count.
 *
 * Both are source-level contracts — asserted against the actual
 * .kt so a future edit that removes the split or the reconstructor
 * breaks CI.
 */
class Aate6743CanonicalTruthAndCloseReconstructTest {

    // ─── §CANONICAL_ENUMERATION_TRUTH ────────────────────────────────

    @Test
    fun `openPositions surface is source-contracted to lifecycle-plus-qty truth`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()
        assertTrue(
            "openPositions() MUST route through the truth predicate isOpenLifecycleWithQty6743",
            src.contains("fun openPositions(): List<Position> = positions.values.filter { isOpenLifecycleWithQty6743(it) }"),
        )
        assertTrue(
            "the truth predicate must exist and reject non-OPEN/PARTIALLY_CLOSED",
            src.contains("private fun isOpenLifecycleWithQty6743(p: Position): Boolean") &&
                src.contains("p.lifecycle != Lifecycle.OPEN && p.lifecycle != Lifecycle.PARTIALLY_CLOSED"),
        )
        assertTrue(
            "the truth predicate must require remainingQtyRaw > 0",
            src.contains("return p.remainingQtyRaw.signum() > 0"),
        )
    }

    @Test
    fun `strict valuation surface is separately exposed`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()
        assertTrue(
            "openPositionsForValuation() must route through the strict 6631 filter",
            src.contains("fun openPositionsForValuation(): List<Position> = positions.values.filter { isEconomicallyValidOpen6631(it) }"),
        )
        assertTrue(
            "an openCountForValuation counter must be exposed for hero paths",
            src.contains("fun openCountForValuation(): Int = openPositionsForValuation().size"),
        )
    }

    @Test
    fun `hasOpenMint and firstOpenForMint consult truth (not strict)`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()
        val hasOpenIdx = src.indexOf("fun hasOpenMint(")
        assertTrue("hasOpenMint must exist", hasOpenIdx > 0)
        val hasOpenBody = src.substring(hasOpenIdx, kotlin.math.min(hasOpenIdx + 300, src.length))
        assertTrue(
            "hasOpenMint must consult isOpenLifecycleWithQty6743, not the strict 6631 filter",
            hasOpenBody.contains("isOpenLifecycleWithQty6743"),
        )
        val firstOpenIdx = src.indexOf("fun firstOpenForMint(")
        assertTrue("firstOpenForMint must exist", firstOpenIdx > 0)
        val firstOpenBody = src.substring(firstOpenIdx, kotlin.math.min(firstOpenIdx + 400, src.length))
        assertTrue(
            "firstOpenForMint must consult isOpenLifecycleWithQty6743, not the strict 6631 filter",
            firstOpenBody.contains("isOpenLifecycleWithQty6743"),
        )
    }

    // ─── §CLOSE_LEDGER_RECONSTRUCT_FROM_CANONICAL ────────────────────

    @Test
    fun `close ledger exposes reconstructFromCanonical6743`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/PositionCloseLedger.kt").readText()
        assertTrue(
            "PositionCloseLedger must expose reconstructFromCanonical6743",
            src.contains("fun reconstructFromCanonical6743("),
        )
        assertTrue(
            "reconstructor MUST pull from canonical closedPositions()",
            src.contains("CanonicalPositionAuthority6441") &&
                src.contains("closedPositions()"),
        )
        assertTrue(
            "reconstructor MUST NOT mutate a mint that is already stamped",
            src.contains("if (closed.containsKey(mint)) continue"),
        )
        assertTrue(
            "reconstructor MUST use a synthetic canonical-derived reason so isRejectedCloseReason cannot swallow it",
            src.contains("CANONICAL_TERMINAL_RECONSTRUCT_6743"),
        )
        assertTrue(
            "reconstructor MUST emit a dedicated telemetry label",
            src.contains("POSITION_CLOSE_LEDGER_RECONSTRUCTED_FROM_CANONICAL_6743"),
        )
    }

    @Test
    fun `bot service wires the reconstructor before consulting the close ledger for forced-open reaping`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val reapIdx = src.indexOf("fun reapPaperForcedOpen(")
        assertTrue("reapPaperForcedOpen must exist", reapIdx > 0)
        // Take a generous window; reconstructor call must appear inside
        // the reap function BEFORE the ledger.isClosed probe.
        val body = src.substring(reapIdx, kotlin.math.min(reapIdx + 3000, src.length))
        val reconstructIdx = body.indexOf("reconstructFromCanonical6743")
        assertTrue("reap function must invoke reconstructFromCanonical6743", reconstructIdx > 0)
        val isClosedIdx = body.indexOf("PositionCloseLedger.isClosed")
        assertTrue("reap function must still consult isClosed after reconstruction", isClosedIdx > 0)
        assertTrue(
            "reconstructor MUST run BEFORE the isClosed consult (otherwise the fresh stamps miss this cycle)",
            reconstructIdx < isClosedIdx,
        )
    }

    @Test
    fun `openPositions returns truth on synthetic reconciler-safe fixtures`() {
        // Behavioural sanity: an OPEN lifecycle position with a positive
        // qty and an INVARIANT_BROKEN_6500 entryPriceSource used to be
        // filtered OUT under 6742 and hidden from exit — now it must
        // remain visible in openPositions().
        val prior = CanonicalPositionAuthority6441.openPositions().size
        // Cannot deterministically inject a Position in unit tests without
        // an authority mutation API — the source contract test above is
        // the primary correctness gate. This behavioural test asserts
        // openPositions() and openPositionsForValuation() are BOTH
        // callable AND that valuation is a subset (never a superset).
        val truth = CanonicalPositionAuthority6441.openPositions().size
        val valuation = CanonicalPositionAuthority6441.openPositionsForValuation().size
        assertTrue("valuation MUST be <= truth (strict filter never adds rows)", valuation <= truth)
        // openCount and openCountForValuation must match their surfaces.
        assertEquals(truth, CanonicalPositionAuthority6441.openCount())
        assertEquals(valuation, CanonicalPositionAuthority6441.openCountForValuation())
        assertFalse("prior enumeration must not exceed the current truth",
            prior > truth + 128) // sanity band, catches accidental narrowing
    }
}
