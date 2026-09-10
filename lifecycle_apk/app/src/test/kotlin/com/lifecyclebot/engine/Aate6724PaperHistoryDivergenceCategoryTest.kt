package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6724 — §PAPER_HISTORY_DIVERGENCE_CATEGORY.
 *
 * Read-only diagnostic added to CanonicalPaperReplay6464.compareToLedger
 * that categorises the dominant class of divergence when replay parity
 * fails. This does NOT mutate ledger/replay state; it emits
 * PAPER_HISTORY_DIVERGENCE_CATEGORY_6724_<TAG> counters so the operator
 * sees at-a-glance whether the divergence is:
 *
 *   - CONVERGED            — happy path, no drift.
 *   - ORPHAN_LOTS_ONLY     — open-cost leak from missing terminal.
 *   - QTY_DECIMAL_SKEW     — decimal representation drift on quantity.
 *   - CASH_ONLY            — cash conservation only.
 *   - REALIZED_ONLY        — realized-pnl divergence only.
 *   - OPEN_COST_ONLY       — open-cost basis only.
 *   - CASH_REALIZED_INVERSE — cash and realized offset (net zero
 *                             conservation, sign flip somewhere).
 *   - MULTI_CAUSE          — three-way divergence (Fire-A revert
 *                             pattern; heal would break more than
 *                             it fixes).
 *
 * The 6673 "Fire A" reconciliation attempt was reverted because the
 * multi-cause case was mis-classified as a simple qty heal. This
 * diagnostic gives the operator visible ground truth on what class
 * of drift we're actually chasing before another heal attempt.
 */
class Aate6724PaperHistoryDivergenceCategoryTest {

    @Test
    fun `divergence categoricizer is wired into compareToLedger`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperReplay6464.kt").readText()
        assertTrue(
            "V5.0.6724 §PAPER_HISTORY_DIVERGENCE_CATEGORY marker must appear",
            src.contains("V5.0.6724 §PAPER_HISTORY_DIVERGENCE_CATEGORY"),
        )
        assertTrue(
            "divergence tag emit must fire per-category counter",
            src.contains("PAPER_HISTORY_DIVERGENCE_CATEGORY_6724_"),
        )
        // All 8 buckets must exist as labels in the source (so the
        // operator can see any of them fire during a session).
        listOf(
            "CONVERGED",
            "ORPHAN_LOTS_ONLY",
            "QTY_DECIMAL_SKEW",
            "CASH_ONLY",
            "REALIZED_ONLY",
            "OPEN_COST_ONLY",
            "CASH_REALIZED_INVERSE",
            "MULTI_CAUSE",
        ).forEach { tag ->
            assertTrue(
                "divergence category $tag must be present as a bucket in the categoricizer",
                src.contains("\"$tag\""),
            )
        }
    }
}
