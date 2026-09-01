package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * V5.0.6630 §CRITICAL_AUTHORITY_RECOVERY coverage.
 *
 * Operator directive Feb 2026 identified three source-level defects
 * that survived V5.0.6629:
 *   §A ExitCoordinator consumer was dying after startup — 60x
 *      EXIT_COORDINATOR_REQUESTED with 0 sweeps started.
 *   §B PAPER_CAPITAL_AUTHORITY_SYNCED_6448 used coerceAtLeast(0.0)
 *      which manufactured 0.0 from a negative-cash accounting
 *      invariant violation, hiding the defect and starving the
 *      sizing resolver with NO_WALLET.
 *   §F The V5.0.6629 hero snapshot read from JournalEconomicReplay
 *      which the operator explicitly forbade as a balance authority
 *      ("hero money comes from the canonical PaperCapitalSnapshot").
 *
 * This test suite only verifies source-authority of the three
 * repairs; runtime behaviour is validated on the operator's PAPER
 * acceptance run.
 */
class Aate6630CriticalAuthorityRecoveryCoverageTest {

    @Test
    fun aate6630_hero_snapshot_reads_from_canonical_capital_authority_not_journal_replay() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/PaperEconomicSnapshot6629.kt"
        ).readText()
        assertTrue("V5.0.6630 §F: hero snapshot must read from PaperCapitalAuthority6577, not journal replay",
            src.contains("PaperCapitalAuthority6577.snapshot()") &&
                src.contains("PAPER_CAPITAL_AUTHORITY_6577_6630"))
        // The journal MUST remain as a diagnostic divergence probe, not the source.
        assertTrue("V5.0.6630 §F: journal must be diagnostic only (drift label present)",
            src.contains("PAPER_ECONOMIC_SNAPSHOT_DIVERGENCE_6629") &&
                src.contains("hero_uses_canonical_journal_is_diagnostic_only_6630"))
    }

    @Test
    fun aate6630_paper_capital_sync_never_writes_zero_from_negative_cash() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/BotService.kt"
        ).readText()
        assertTrue("V5.0.6630 §B: negative-cash path must be diagnostic and skip the projection write",
            src.contains("PAPER_CAPITAL_AUTHORITY_NEGATIVE_CASH_HELD_6630") &&
                src.contains("do_not_write_zero_to_projections"))
        // The old coerceAtLeast(0.0) call MUST be gone from syncPaperCapitalAuthority6448.
        val idx = src.indexOf("fun syncPaperCapitalAuthority6448")
        assertTrue("V5.0.6630 §B: syncPaperCapitalAuthority6448 must still exist", idx >= 0)
        val fnRegion = src.substring(idx, minOf(idx + 4000, src.length))
        assertTrue("V5.0.6630 §B: the old coerceAtLeast(0.0) manufacturing 0.0 must be removed",
            !fnRegion.contains("PaperCapitalAuthority6577.cashSol().coerceAtLeast(0.0)"))
    }

    @Test
    fun aate6630_exit_coordinator_self_heal_wired_source_authority() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/BotService.kt"
        ).readText()
        assertTrue("V5.0.6630 §A: requestExitSweeps must self-heal a dead consumer",
            src.contains("EXIT_COORDINATOR_CONSUMER_MISSING_RECOVERED_6630") &&
                src.contains("staleConsumer6630") &&
                src.contains("exitSweepCoordinatorJob?.isActive != true"))
    }
}
