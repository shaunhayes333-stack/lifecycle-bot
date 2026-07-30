package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.6387 — LIVE TRUTH, HOT EXIT AND NET-EDGE AUTHORITY (directive P0-P12).
 *
 * Ships every priority module from the directive:
 *   P0  LiveExitOnlyMode6387          — umbrella authority + 9 conditions
 *   P4  ReconcilerZeroProof6387       — 3-of-2-endpoints proof, minContextSlot
 *   P5  PriceIntegrityAuthority6387   — 4-tier source ranking + zero-price = UNKNOWN
 *   P6  HotExitSupervisorContract6387 — heartbeat + missed-3-forces-exit-only
 *   P8  FeeAwareExecution6387         — gross edge ≥ 2× cost + net ≥ 3%
 *   P9  PartialExitStateMachine6387   — monotonic NONE→FIRST→SECOND→RUNNER
 */
class Bundle6387LiveTruthExitAuthorityTest {

    @Before fun setup() {
        LiveExitOnlyMode6387.setTestOverride(null)   // start clean
        PartialExitStateMachine6387.clearAllForTest()
        HotExitSupervisorContract6387.resetForTest()
    }
    @After fun teardown() {
        LiveExitOnlyMode6387.setTestOverride("STARTUP_DEFAULT")
        PartialExitStateMachine6387.clearAllForTest()
        HotExitSupervisorContract6387.resetForTest()
    }

    // ── P0 ───────────────────────────────────────────────────────────

    @Test fun exit_only_fires_when_hot_exit_inactive() {
        val r = LiveExitOnlyMode6387.evaluate(LiveExitOnlyMode6387.Conditions(
            hotExitJobActive = false, botLoopP95Ms = 100L, hasConfirmedBuyWithZeroSpend = false,
            estVsConfirmedQtyDeviationPct = 0.0, currentPriceZeroOrStaleOnStop = false,
            canonicalPositionsExceedProvenWallet = false, uniqueCloseCounterMismatch = false,
            canonicalHasUnprovenOrQuarantinedLot = false, ownerMintDeltaUnresolved = false,
        ))
        assertEquals("HOT_EXIT_JOB_INACTIVE", r)
    }
    @Test fun exit_only_fires_on_bot_loop_gt_10s() {
        val r = LiveExitOnlyMode6387.evaluate(LiveExitOnlyMode6387.Conditions(
            true, 10_500L, false, 0.0, false, false, false, false, false))
        assertTrue(r?.startsWith("BOT_LOOP_P95_GT_10S") == true)
    }
    @Test fun exit_only_fires_on_qty_deviation_gt_5pct() {
        val r = LiveExitOnlyMode6387.evaluate(LiveExitOnlyMode6387.Conditions(
            true, 100L, false, 6.0, false, false, false, false, false))
        assertTrue(r?.startsWith("QTY_DEVIATION_GT_5PCT") == true)
    }
    @Test fun exit_only_passes_when_all_conditions_healthy() {
        val r = LiveExitOnlyMode6387.evaluate(LiveExitOnlyMode6387.Conditions(
            true, 100L, false, 0.0, false, false, false, false, false))
        assertNull(r)
    }
    @Test fun executable_open_gate_wires_live_exit_only() {
        val g = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(g.contains("LiveExitOnlyMode6387.isActive()"))
        assertTrue(g.contains("LIVE_EXIT_ONLY_BUY_BLOCKED_6387"))
    }

    // ── P4 ───────────────────────────────────────────────────────────

    @Test fun zero_proof_requires_3_samples_across_2_endpoints() {
        val state1 = ReconcilerZeroProof6387.State(
            samples = listOf(
                ReconcilerZeroProof6387.ProofSample("rpc-A", 100L, true, false),
                ReconcilerZeroProof6387.ProofSample("rpc-A", 101L, true, false),
                ReconcilerZeroProof6387.ProofSample("rpc-A", 102L, true, false),
            ),
            minContextSlot = 100L,
        )
        assertFalse("3 samples on ONE endpoint must not confirm", ReconcilerZeroProof6387.isConfirmedZero(state1))
        val state2 = state1.copy(samples = state1.samples.dropLast(1) +
            ReconcilerZeroProof6387.ProofSample("rpc-B", 102L, true, false))
        assertTrue("3 samples across 2 endpoints must confirm", ReconcilerZeroProof6387.isConfirmedZero(state2))
    }
    @Test fun zero_proof_stale_context_resets() {
        val sample = ReconcilerZeroProof6387.ProofSample("rpc-A", 50L, true, false)
        assertTrue(ReconcilerZeroProof6387.requiresReset(sample, minContextSlot = 100L))
    }
    @Test fun unattributed_close_reason_string_stable() {
        assertEquals("CLOSED_BALANCE_ZERO_UNATTRIBUTED", ReconcilerZeroProof6387.UNATTRIBUTED_CLOSE_REASON)
    }

    // ── P5 ───────────────────────────────────────────────────────────

    @Test fun price_source_ranking_prefers_executable_quote() {
        assertEquals(PriceIntegrityAuthority6387.Source.EXECUTABLE_QUOTE,
            PriceIntegrityAuthority6387.rankSources(true, true, true, true))
        assertEquals(PriceIntegrityAuthority6387.Source.WS_PAIR,
            PriceIntegrityAuthority6387.rankSources(false, true, true, true))
        assertEquals(PriceIntegrityAuthority6387.Source.LAST_MARK,
            PriceIntegrityAuthority6387.rankSources(false, false, false, true))
        assertEquals(PriceIntegrityAuthority6387.Source.NONE,
            PriceIntegrityAuthority6387.rankSources(false, false, false, false))
    }
    @Test fun zero_price_never_becomes_100pct_loss() {
        assertEquals("PRICE_UNKNOWN",
            PriceIntegrityAuthority6387.classifyForStop(0.0, isStale = false, hasExecutableQuote = false))
    }
    @Test fun stale_without_quote_holds_under_observation() {
        assertEquals("STALE_HOLD_UNDER_OBSERVATION",
            PriceIntegrityAuthority6387.classifyForStop(1.0, isStale = true, hasExecutableQuote = false))
    }

    // ── P6 ───────────────────────────────────────────────────────────

    @Test fun hot_exit_missed_heartbeats_force_exit_only() {
        // Prime with a heartbeat, then never beat again — 3 evaluations later, exit-only.
        HotExitSupervisorContract6387.heartbeat()
        val futureMs = System.currentTimeMillis() + 100_000L
        HotExitSupervisorContract6387.evaluateHealth(futureMs)
        HotExitSupervisorContract6387.evaluateHealth(futureMs)
        HotExitSupervisorContract6387.evaluateHealth(futureMs)
        assertTrue(LiveExitOnlyMode6387.isActive())
        assertTrue(LiveExitOnlyMode6387.activeReason()?.startsWith("HOT_EXIT_MISSED_") == true)
    }

    // ── P8 ───────────────────────────────────────────────────────────

    @Test fun fee_gate_rejects_gross_edge_below_2x_cost() {
        val r = FeeAwareExecution6387.evaluate(FeeAwareExecution6387.Inputs(
            estimatedBuyFeeSol = 0.0002, estimatedSellFeeSol = 0.0002,
            priorityFeesSol = 0.0001, tipsSol = 0.0001,
            expectedPriceImpactSol = 0.0001, expectedSlippageSol = 0.0001, routeFeesSol = 0.0001,
            expectedGrossEdgeSol = 0.001,  // < 2 * roundTripCost (~0.0018)
            expectedNetEdgePct = 5.0, positionSizeSol = 0.1,
        ))
        assertFalse(r.allowed)
        assertTrue(r.reason.contains("GROSS_EDGE_LT_2X_COST"))
    }
    @Test fun fee_gate_rejects_net_edge_below_3pct() {
        val r = FeeAwareExecution6387.evaluate(FeeAwareExecution6387.Inputs(
            estimatedBuyFeeSol = 0.0001, estimatedSellFeeSol = 0.0001,
            priorityFeesSol = 0.0, tipsSol = 0.0,
            expectedPriceImpactSol = 0.0, expectedSlippageSol = 0.0, routeFeesSol = 0.0,
            expectedGrossEdgeSol = 0.01, expectedNetEdgePct = 2.0, positionSizeSol = 0.1,
        ))
        assertFalse(r.allowed); assertTrue(r.reason.contains("NET_EDGE_LT_3PCT"))
    }
    @Test fun fee_gate_rejects_fixed_fee_exceeding_half_pct_of_position() {
        val r = FeeAwareExecution6387.evaluate(FeeAwareExecution6387.Inputs(
            estimatedBuyFeeSol = 0.0003, estimatedSellFeeSol = 0.0003,
            priorityFeesSol = 0.0, tipsSol = 0.0,
            expectedPriceImpactSol = 0.0, expectedSlippageSol = 0.0, routeFeesSol = 0.0,
            expectedGrossEdgeSol = 0.01, expectedNetEdgePct = 10.0,
            positionSizeSol = 0.05,   // 0.5% cap = 0.00025 < 0.0003 fee
        ))
        assertFalse(r.allowed); assertTrue(r.reason.contains("FEE_EXCEEDS_HALF_PCT_OF_POSITION"))
    }

    // ── P9 ───────────────────────────────────────────────────────────

    @Test fun partial_exit_ladder_is_monotonic() {
        assertTrue(PartialExitStateMachine6387.tryAdvance("p1", PartialExitStateMachine6387.Index.FIRST))
        assertFalse("skipping SECOND to RUNNER must fail",
            PartialExitStateMachine6387.tryAdvance("p1", PartialExitStateMachine6387.Index.RUNNER))
        assertTrue(PartialExitStateMachine6387.tryAdvance("p1", PartialExitStateMachine6387.Index.SECOND))
        assertTrue(PartialExitStateMachine6387.tryAdvance("p1", PartialExitStateMachine6387.Index.RUNNER))
        assertFalse("RUNNER is terminal — no re-fire",
            PartialExitStateMachine6387.tryAdvance("p1", PartialExitStateMachine6387.Index.RUNNER))
    }
    @Test fun completed_partial_index_never_fires_again() {
        assertTrue(PartialExitStateMachine6387.tryAdvance("p2", PartialExitStateMachine6387.Index.FIRST))
        assertFalse("FIRST already completed must not re-fire",
            PartialExitStateMachine6387.tryAdvance("p2", PartialExitStateMachine6387.Index.FIRST))
    }
}
