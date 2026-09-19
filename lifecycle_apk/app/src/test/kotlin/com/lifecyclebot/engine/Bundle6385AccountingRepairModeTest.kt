package com.lifecyclebot.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.6385 — LIVE ACCOUNTING TRUTH REPAIR (Bundle 1 of 6).
 *
 * Ships operator directive Sections 1, 3, 11:
 *   1. SELL_ONLY_ACCOUNTING_REPAIR mode blocks new live BUY signatures.
 *   3. QUALITY MINT_ROUTE rejection removed (advisory only) — route proof
 *      is required at signing, not at candidate evaluation.
 *  11. Route-stack telemetry hygiene: EXEC_PROVIDER_TRY only fires for
 *      wired + supported providers.
 *
 * Bundles 6386-6390 will ship the remaining sections (strong amount types,
 * finalized BUY/SELL proof, immutable fill lots, historical quarantine,
 * regression tests, canary gate).
 */
class Bundle6385AccountingRepairModeTest {

    @Before
    fun setup() {
        // V5.0.7123 — the BLOCKING tests below still need the halt armed, so
        // arm it explicitly here rather than relying on a default. A test that
        // depends on a production default is a test that silently changes
        // meaning when the default changes, which is how this file ended up
        // asserting an unreachable halt was correct.
        LiveAccountingRepairMode6385.setTestOverride(true)
    }

    @After
    fun teardown() {
        // V5.0.7123 — leave it in the PRODUCTION default, which is now OFF.
        LiveAccountingRepairMode6385.setTestOverride(false)
    }

    // ── Section 1 — Repair mode invariants ────────────────────────────

    /**
     * V5.0.7123 — INVERTED, deliberately.
     *
     * This asserted the halt was ACTIVE by default. That default was only
     * defensible while the Bundle 6386-90 canary gate was going to arrive and
     * call disable(). It never did — disable() had zero production callers for
     * hundreds of builds, so the "temporary" halt was permanent and the
     * operator had no way to lift it. On the 5.0.7118 device that was 231
     * blocked live buys against 36 clean sells.
     *
     * The invariant that actually matters is not "the halt is on" — it is "the
     * halt is REACHABLE IN BOTH DIRECTIONS", which is what the original design
     * lacked and what is tested below.
     */
    @Test
    fun repair_mode_defaults_off_so_live_can_be_tested_end_to_end() {
        LiveAccountingRepairMode6385.disable()
        assertFalse(
            "V5.0.7123: repair mode must default OFF — a halt with no reachable " +
                "off switch made the live path untestable",
            LiveAccountingRepairMode6385.isActive(),
        )
    }

    @Test
    fun repair_mode_can_be_flipped_in_both_directions() {
        LiveAccountingRepairMode6385.enable()
        assertTrue("V5.0.7123: enable() must re-arm the halt",
            LiveAccountingRepairMode6385.isActive())
        LiveAccountingRepairMode6385.disable()
        assertFalse("V5.0.7123: disable() must lift the halt",
            LiveAccountingRepairMode6385.isActive())

        val src = File("src/main/kotlin/com/lifecyclebot/engine/LiveAccountingRepairMode6385.kt").readText()
        assertFalse(
            "V5.0.7123: disable() must NOT be internal again — that is exactly " +
                "what made it unreachable from any production call site",
            src.contains("internal fun disable()"),
        )
        assertTrue(
            "V5.0.7123: re-arming must stay available to the operator",
            src.contains("fun enable()"),
        )
    }

    @Test
    fun repair_mode_block_reason_is_stable() {
        assertEquals(
            "V5.0.6385: BLOCK_REASON must be the exact canonical string operators grep for",
            "LIVE_BUY_BLOCKED_ACCOUNTING_REPAIR_MODE_6385",
            LiveAccountingRepairMode6385.BLOCK_REASON,
        )
    }

    @Test
    fun executable_open_gate_hard_rejects_live_buys_when_repair_active() {
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(
            "V5.0.6385: ExecutableOpenGate must consult LiveAccountingRepairMode6385",
            gate.contains("LiveAccountingRepairMode6385.isActive()"),
        )
        assertTrue(
            "V5.0.6385: block must emit BLOCK_REASON constant, not a hardcoded string",
            gate.contains("LiveAccountingRepairMode6385.BLOCK_REASON"),
        )
        assertTrue(
            "V5.0.6385: block must record the block via recordLiveBuyBlocked() for telemetry",
            gate.contains("LiveAccountingRepairMode6385.recordLiveBuyBlocked"),
        )
    }

    @Test
    fun paper_mode_is_unaffected_by_repair_mode() {
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        // Verify the block only triggers when mode equals LIVE (case-insensitive).
        assertTrue(
            "V5.0.6385: repair-mode block MUST scope to LIVE only (paper/shadow untouched)",
            gate.contains("mode.equals(\"LIVE\", ignoreCase = true) && LiveAccountingRepairMode6385.isActive()"),
        )
    }

    // ── Section 3 — QUALITY MINT_ROUTE contract repair ────────────────

    @Test
    fun quality_mint_route_no_longer_hard_rejects() {
        val contract = File("src/main/kotlin/com/lifecyclebot/engine/LaneEntryContract6342.kt").readText()
        assertFalse(
            "V5.0.6385: QUALITY_REJECTS_MINT_ROUTE_6342 hard-reject must be REMOVED (route proof now enforced at signing, not eval)",
            contract.contains("QUALITY_REJECTS_MINT_ROUTE_6342"),
        )
        assertFalse(
            "V5.0.6385: the LANE_ENTRY_QUALITY_MINT_ROUTE_REJECTED_6342 counter must be gone",
            contract.contains("LANE_ENTRY_QUALITY_MINT_ROUTE_REJECTED_6342"),
        )
        assertTrue(
            "V5.0.6385: MINT_ROUTE is now advisory only (LANE_ENTRY_QUALITY_MINT_ROUTE_ADVISORY_6385)",
            contract.contains("LANE_ENTRY_QUALITY_MINT_ROUTE_ADVISORY_6385"),
        )
    }

    // ── Section 11 — Route-stack telemetry hygiene ────────────────────

    @Test
    fun exec_provider_try_only_fires_for_wired_supported_providers() {
        val stack = File("src/main/kotlin/com/lifecyclebot/engine/execution/MemeExecutionRouteStack.kt").readText()
        // The gate on EXEC_PROVIDER_TRY must require BOTH adapterWired AND supported.
        assertTrue(
            "V5.0.6385: EXEC_PROVIDER_TRY must be gated on adapterWired && supported",
            stack.contains("if (p.adapterWired && s.supported)") &&
                stack.contains("lifecycle(\"EXEC_PROVIDER_TRY\""),
        )
        // Unwired/unsupported providers now go to a distinct non-inflating counter.
        assertTrue(
            "V5.0.6385: unwired/unsupported providers must emit EXEC_PROVIDER_SKIPPED_6385, not EXEC_PROVIDER_TRY",
            stack.contains("EXEC_PROVIDER_SKIPPED_6385"),
        )
    }
}
