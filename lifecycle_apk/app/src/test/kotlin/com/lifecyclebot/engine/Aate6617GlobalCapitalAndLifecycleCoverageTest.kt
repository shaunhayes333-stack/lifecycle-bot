package com.lifecyclebot.engine

import org.junit.Test
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * V5.0.6617 — GLOBAL CAPITAL ARBITRATION +
 *             POSITION LIFECYCLE FORMALIZATION
 *
 * Operator directives (verbatim, Feb 2026):
 *
 *   [A] Global capital across trading domains. Every lane must read the
 *       SAME spendable cash figure from the same authority. Lanes may
 *       SHAPE their proposals but must never fabricate a lane-local
 *       wallet balance that diverges from the shared account.
 *
 *   [Lifecycle] Formalise the DISCOVERED → CLOSED → LEARNED →
 *       REENTRY_ELIGIBLE transition so canonicalClosedDelta,
 *       closeLedgerClosedDelta and terminalSellPublishedDelta agree.
 */
class Aate6617GlobalCapitalAndLifecycleCoverageTest {

    @After
    fun tearDown() {
        com.lifecyclebot.engine.truth.GlobalCapitalArbitration6617.resetForTest()
        com.lifecyclebot.engine.truth.PositionLifecycleFormalization6617.resetForTest()
    }

    @Test
    fun aate6617_global_capital_arbiter_exists_with_required_api() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/GlobalCapitalArbitration6617.kt"
        ).readText()
        assertTrue(
            "V5.0.6617: GlobalCapitalArbitration6617 object must exist",
            src.contains("object GlobalCapitalArbitration6617")
        )
        assertTrue(
            "V5.0.6617: arbiter must expose availableForLane + recordSpecialistProposal6617 + recordLaneLocalBypass6617",
            src.contains("fun availableForLane(") &&
                src.contains("fun recordSpecialistProposal6617(") &&
                src.contains("fun recordLaneLocalBypass6617(")
        )
        assertTrue(
            "V5.0.6617: paper mode must route through PaperCapitalAuthority6577.availableCashSol()",
            src.contains("PaperCapitalAuthority6577.availableCashSol()")
        )
        assertTrue(
            "V5.0.6617: every request must be counted via LANE_CAPITAL_REQUEST_6617_<LANE>",
            src.contains("LANE_CAPITAL_REQUEST_6617_")
        )
    }

    @Test
    fun aate6617_paper_lanes_no_longer_read_botservice_paperWalletSol() {
        val commodities = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CommoditiesTrader.kt").readText()
        val metals = java.io.File("src/main/kotlin/com/lifecyclebot/perps/MetalsTrader.kt").readText()
        val forex = java.io.File("src/main/kotlin/com/lifecyclebot/perps/ForexTrader.kt").readText()
        val stocks = java.io.File("src/main/kotlin/com/lifecyclebot/perps/TokenizedStockTrader.kt").readText()
        val perps = java.io.File("src/main/kotlin/com/lifecyclebot/perps/PerpsTraderAI.kt").readText()

        // Every lane's getBalance/getEffectiveBalance is now routed via
        // GlobalCapitalArbitration6617.availableForLane in paper mode.
        val laneMap = mapOf(
            "COMMODITIES" to commodities,
            "METALS" to metals,
            "FOREX" to forex,
            "STOCKS" to stocks,
            "PERPS" to perps,
        )
        laneMap.forEach { (lane, src) ->
            assertTrue(
                "V5.0.6617: $lane trader must route paper balance through GlobalCapitalArbitration6617.availableForLane(\"$lane\", paperMode = true)",
                src.contains("GlobalCapitalArbitration6617.availableForLane(\"$lane\", paperMode = true)")
            )
        }
    }

    @Test
    fun aate6617_paper_mode_returns_canonical_cash() {
        val arb = com.lifecyclebot.engine.truth.GlobalCapitalArbitration6617
        // Force a known paper-account state so the arbiter's paper read
        // is deterministic. Reset first so the invariant test isn't
        // polluted by a prior test's state.
        com.lifecyclebot.engine.truth.PaperAccountLedger6430.resetForTest()
        com.lifecyclebot.engine.truth.PaperAccountLedger6430.initialize(3.0)
        val v = arb.availableForLane("TEST", paperMode = true)
        assertEquals("V5.0.6617: paper arbiter must return canonical cash", 3.0, v, 1e-9)
        // Live mode returns caller-supplied wallet.
        val vl = arb.availableForLane("TEST", paperMode = false, liveWalletSol = 7.5)
        assertEquals("V5.0.6617: live arbiter must return caller wallet SOL", 7.5, vl, 1e-9)
    }

    @Test
    fun aate6617_lifecycle_formalization_exists_with_required_api() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/PositionLifecycleFormalization6617.kt"
        ).readText()
        assertTrue(
            "V5.0.6617: PositionLifecycleFormalization6617 object must exist",
            src.contains("object PositionLifecycleFormalization6617")
        )
        assertTrue(
            "V5.0.6617: formalization must expose the four operator stages",
            src.contains("enum class Stage") &&
                src.contains("DISCOVERED") && src.contains("CLOSED") &&
                src.contains("LEARNED") && src.contains("REENTRY_ELIGIBLE")
        )
        assertTrue(
            "V5.0.6617: formalization must expose markDiscovered / markClosed / markLearned / markReentryEligible",
            src.contains("fun markDiscovered(") &&
                src.contains("fun markClosed(") &&
                src.contains("fun markLearned(") &&
                src.contains("fun markReentryEligible(")
        )
        assertTrue(
            "V5.0.6617: reconcileClosureDeltas6617 must emit three delta counters the operator can grep",
            src.contains("fun reconcileClosureDeltas6617()") &&
                src.contains("CANONICAL_CLOSED_DELTA_6617") &&
                src.contains("CLOSE_LEDGER_CLOSED_DELTA_6617") &&
                src.contains("TERMINAL_SELL_PUBLISHED_DELTA_6617")
        )
    }

    @Test
    fun aate6617_stage_transitions_advance_monotonically() {
        val life = com.lifecyclebot.engine.truth.PositionLifecycleFormalization6617
        val pid = "pos-6617-test"
        life.markDiscovered(pid, mint = "MintA", symbol = "AAA", lane = "SHITCOIN")
        val r1 = life.current(pid)!!
        assertEquals(com.lifecyclebot.engine.truth.PositionLifecycleFormalization6617.Stage.DISCOVERED, r1.currentStage)
        assertTrue("V5.0.6617: DISCOVERED must stamp discoveredAtMs", r1.discoveredAtMs > 0L)

        // Small sleep so timestamps differ; not required for correctness
        // but keeps the record realistic.
        Thread.sleep(2L)
        life.markClosed(pid)
        val r2 = life.current(pid)!!
        assertEquals(com.lifecyclebot.engine.truth.PositionLifecycleFormalization6617.Stage.CLOSED, r2.currentStage)
        assertTrue("V5.0.6617: closedAtMs must be >= discoveredAtMs", r2.closedAtMs >= r2.discoveredAtMs)

        Thread.sleep(2L)
        life.markLearned(pid)
        val r3 = life.current(pid)!!
        assertEquals(com.lifecyclebot.engine.truth.PositionLifecycleFormalization6617.Stage.LEARNED, r3.currentStage)

        Thread.sleep(2L)
        life.markReentryEligible(pid)
        val r4 = life.current(pid)!!
        assertEquals(com.lifecyclebot.engine.truth.PositionLifecycleFormalization6617.Stage.REENTRY_ELIGIBLE, r4.currentStage)
    }

    @Test
    fun aate6617_reconciler_reports_zero_deltas_on_empty_state() {
        val life = com.lifecyclebot.engine.truth.PositionLifecycleFormalization6617
        val (a, b, c) = life.reconcileClosureDeltas6617()
        assertEquals("V5.0.6617: empty state must have canonicalClosedDelta = 0", 0L, a)
        assertEquals("V5.0.6617: empty state must have closeLedgerClosedDelta = 0", 0L, b)
        assertEquals("V5.0.6617: empty state must have terminalSellPublishedDelta = 0", 0L, c)
    }

    @Test
    fun aate6617_psl6454_mirrors_discovered_and_closed_to_formalization() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/PositionStateLedger6454.kt"
        ).readText()
        assertTrue(
            "V5.0.6617: PositionStateLedger6454.onEntry must mirror DISCOVERED into the formalization",
            src.contains("PositionLifecycleFormalization6617.markDiscovered(")
        )
        assertTrue(
            "V5.0.6617: PositionStateLedger6454.confirmTerminalSell must mirror CLOSED into the formalization",
            src.contains("PositionLifecycleFormalization6617.markClosed(positionId)")
        )
    }

    @Test
    fun aate6617_learning_pathway_stamps_learned() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/AateDecisionEnvelope6512.kt"
        ).readText()
        assertTrue(
            "V5.0.6617: AateDecisionEnvelope6512.onFinalized must stamp LEARNED at end of the learner fanout",
            src.contains("PositionLifecycleFormalization6617.markLearned(env.positionId)")
        )
    }

    @Test
    fun aate6617_bot_service_reconciles_deltas_on_health_tick() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/BotService.kt"
        ).readText()
        assertTrue(
            "V5.0.6617: BotService must call reconcileClosureDeltas6617 on the shared 12-loop health tick",
            src.contains("PositionLifecycleFormalization6617.reconcileClosureDeltas6617()")
        )
    }
}
