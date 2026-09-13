package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6751 — Operator 6750 diagnostic: legacy forensic/audit
 * disagreement with the canonical ledger.
 *
 *   > "Canonical correctness balances exactly: replay has cashΔ=0,
 *   >  realizedΔ=0, openCostΔ≈0, qtyMismatch=0. Yet the older
 *   >  forensic reconciler reports wallet 20.376 vs expected
 *   >  ≤15.263, a 5.113 SOL mismatch ... 2,692 PAPER_LEDGER_VS_
 *   >  JOURNAL_DIVERGENCE events. Not completely harmless because
 *   >  six execution attempts were still blocked by
 *   >  PAPER_LEDGER_DIVERGENCE_6731."
 *
 * Fix: JournalEconomicReplay6619's whole-history divergence check
 * now consults CanonicalPaperReplay6464.lastParity() before emitting
 * the divergence label. When the canonical same-revision replay
 * reports all deltas within tolerance AND no revision race, the
 * historical divergence is superseded and does NOT drive telemetry
 * / execution authority. A dedicated SUPERSEDED_BY_CANONICAL label
 * measures how often the guard defers.
 */
class Aate6751LegacyDivergenceSupersededByCanonicalTest {

    @Test
    fun `journal replay defers to canonical parity when canonical reports clean`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/JournalEconomicReplay6619.kt").readText()
        assertTrue(
            "replay MUST consult CanonicalPaperReplay6464.lastParity() before emitting divergence",
            src.contains("CanonicalPaperReplay6464.lastParity()") &&
                src.contains("canonicalSupersedes6751"),
        )
        assertTrue(
            "the supersede predicate MUST require no revisionRaceObserved AND all deltas within tolerance",
            src.contains("!p.revisionRaceObserved") &&
                src.contains("kotlin.math.abs(p.cashDelta) <= 0.001") &&
                src.contains("kotlin.math.abs(p.realizedDelta) <= 0.001") &&
                src.contains("kotlin.math.abs(p.openCostDelta) <= 0.01"),
        )
        assertTrue(
            "divergence emission MUST be conditional on !canonicalSupersedes6751",
            src.contains("kotlin.math.abs(delta) > 0.001 && !canonicalSupersedes6751"),
        )
        assertTrue(
            "supersede branch MUST emit dedicated label so the deferral rate is observable",
            src.contains("PAPER_LEDGER_VS_JOURNAL_DIVERGENCE_SUPERSEDED_BY_CANONICAL_6751"),
        )
        // Order matters: legacy divergence label emission must be gated behind
        // the canonicalSupersedes6751 check, not before it.
        val legacyIdx = src.lastIndexOf("labelInc(\"PAPER_LEDGER_VS_JOURNAL_DIVERGENCE_6619\"")
        val gateIdx = src.indexOf("canonicalSupersedes6751 = ")
        assertTrue("supersede predicate MUST be computed BEFORE the legacy divergence label emit",
            gateIdx > 0 && legacyIdx > 0 && gateIdx < legacyIdx)
    }
}
