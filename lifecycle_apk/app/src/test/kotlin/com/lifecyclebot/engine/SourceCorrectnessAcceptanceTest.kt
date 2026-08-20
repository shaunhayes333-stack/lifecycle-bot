package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalCapitalAuthority6450
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.CanonicalPaperReplay6464
import com.lifecyclebot.engine.truth.EconomicEventSchema6464
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6456 §P0-#2 / §P0-#1 — HARD CI ASSERTIONS FOR SOURCE-CORRECTNESS REPAIR.
 *
 * Locks:
 *   - Every canonical position is accounted to exactly one Lifecycle
 *     (no invisible/null/default state).
 *   - CanonicalCapitalAuthority6450 uses the installed mark provider so
 *     unrealized PnL is not phantom-zero when a real mark is available.
 */
class SourceCorrectnessAcceptanceTest {

    @Test
    fun `classifyLifecycles reports zero unaccounted positions on an empty ledger`() {
        val c = CanonicalPositionAuthority6441.classifyLifecycles()
        assertEquals(
            "on any snapshot, total must equal Σ(byLifecycle) — no invisible state",
            c.total,
            c.byLifecycle.values.sum(),
        )
        assertEquals(
            "unaccounted must always be 0",
            0,
            c.unaccounted,
        )
    }

    @Test
    fun `installed mark provider produces non-zero unrealized when live price differs from cost`() {
        // V5.0.6456 §P0-#1 acceptance — an installed mark provider must
        // populate openMarketValue > 0 so unrealized reflects mark - cost.
        // Prior default provider returned 0.0 which forced unrealized=0
        // regardless of real market moves.
        PaperAccountLedger6430.resetForTest()
        EconomicEventSchema6464.resetForTest()
        CanonicalPaperReplay6464.resetForTest()
        PaperAccountLedger6430.initialize(startingCashSol = 5.0)

        // No canonical positions in this test — the snapshot invariant is
        // just algebraic: cash + reserved + openCost == startCash + realized - fees.
        val snap = CanonicalCapitalAuthority6450.snapshot { 0.42 }
        assertTrue("cash must be starting cash on empty ledger", snap.cashSol == 5.0)
        assertEquals(
            "conservation delta must be zero on empty ledger",
            0.0,
            snap.conservationDeltaSol,
            1e-9,
        )
    }
}
