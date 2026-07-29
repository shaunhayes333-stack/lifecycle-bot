package com.lifecyclebot.engine.truth

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6386 MIGRATION — verifies the substrate is WIRED into the legacy
 * call sites, not just present as unused scaffolding.
 *
 * The prior operator directive was explicit: "Remove CanonicalBuyFillRegistry
 * from realised PnL, sold-quantity attribution, stop basis and learning
 * authority." This test guards the migration progress via CI so future edits
 * cannot re-introduce mint-keyed lot replacement.
 */
class Bundle6386MigrationTest {

    @Test
    fun canonical_buy_fill_registry_dual_writes_to_substrate() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/CanonicalBuyFillRegistry.kt").readText()
        assertTrue(
            "V5.0.6386: CanonicalBuyFillRegistry.record must dual-write into FillLotLedger6386",
            src.contains("FillLotLedger6386.openLot(lot)"),
        )
        assertTrue(
            "V5.0.6386: dual-write must construct MintDecimals.Known with buy signature as proofSignature",
            src.contains("com.lifecyclebot.engine.truth.MintDecimals.Known") &&
                src.contains("proofSignature = fill.buySignature"),
        )
        assertTrue(
            "V5.0.6386: dual-write must construct FillLot6386 keyed by (wallet, mint, sig)",
            src.contains("confirmedBuySignature = fill.buySignature"),
        )
        assertTrue(
            "V5.0.6386: FILL_LOT_LEDGER_6386_DUPLICATE_SIG_REJECTED must be caught (no top-up merges)",
            src.contains("FILL_LOT_LEDGER_6386_DUPLICATE_SIG_REJECTED"),
        )
    }

    @Test
    fun legacy_reads_are_audited_with_counter() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/CanonicalBuyFillRegistry.kt").readText()
        assertTrue(
            "V5.0.6386: every CanonicalBuyFillRegistry.get() call must be counted (migration debt visibility)",
            src.contains("LEGACY_CANONICAL_BUY_FILL_READ_6386"),
        )
    }

    @Test
    fun executor_realized_pnl_prefers_substrate_over_legacy() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(
            "V5.0.6386: Executor realized-PnL path must consult FillLotLedger6386.aggregatedFillForMint",
            src.contains("FillLotLedger6386.aggregatedFillForMint"),
        )
        assertTrue(
            "V5.0.6386: substrate-first read must emit REALIZED_PNL_COST_BASIS_SOURCE_6386_SUBSTRATE counter",
            src.contains("REALIZED_PNL_COST_BASIS_SOURCE_6386_SUBSTRATE"),
        )
        assertTrue(
            "V5.0.6386: legacy-fallback path must emit REALIZED_PNL_COST_BASIS_SOURCE_6386_LEGACY_FALLBACK counter",
            src.contains("REALIZED_PNL_COST_BASIS_SOURCE_6386_LEGACY_FALLBACK"),
        )
    }

    @Test
    fun aggregated_fill_helper_exists_for_legacy_migration() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/FillLotLedger6386.kt").readText()
        assertTrue(
            "V5.0.6386: FillLotLedger6386.aggregatedFillForMint helper must exist for migrating legacy callers",
            src.contains("fun aggregatedFillForMint"),
        )
        assertTrue(
            "V5.0.6386: aggregated view returns volume-weighted entry SOL/token, not a simple average",
            src.contains("vwEntrySolPerToken"),
        )
    }

    @Test
    fun startup_coverage_gauge_exists_on_route_stack() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/execution/MemeExecutionRouteStack.kt").readText()
        assertTrue(
            "V5.0.6386: MemeExecutionRouteStack.emitStartupCoverageGauge6386 must exist (directive Section 11: 'Emit unwired adapter coverage once at startup as a gauge')",
            src.contains("emitStartupCoverageGauge6386"),
        )
        assertTrue(
            "V5.0.6386: startup gauge must be idempotent (startupCoverageEmitted flag)",
            src.contains("startupCoverageEmitted"),
        )
    }
}
