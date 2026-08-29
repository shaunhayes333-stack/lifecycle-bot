package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalMarkPurpose6570
import com.lifecyclebot.engine.truth.CanonicalPriceMark6522
import com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522
import com.lifecyclebot.engine.truth.OrderSizeResolver6441
import com.lifecyclebot.engine.truth.PriceUsd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class Aate6600SpecialistAuthorityRestorationTest {
    private fun source(path: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun project_sniper_cannot_own_from_toolkit_map_insertion_order() {
        val bot = source("engine/BotService.kt")
        assertTrue(bot.contains("forced ?: styleLanes.firstOrNull()"))
        assertTrue(!bot.contains("strongestDesk6599"))
        assertTrue(bot.contains("boundedRescue6600"))
        assertTrue(bot.contains("currentElection6600"))
    }

    @Test fun paper_first_mark_uses_existing_observation_registry() {
        val mint = "6600_MARK_${System.nanoTime()}"
        assertTrue(CanonicalPriceMarkRegistry6522.publish(CanonicalPriceMark6522(
            mint = mint, pairId = "MINT_ROUTE:$mint", baseMint = mint, quoteMint = "USD",
            source = "PUMP_FUN_NEW", timestampMs = System.currentTimeMillis(),
            priceUsd = PriceUsd(BigDecimal("0.0000123")), liquidityUsd = BigDecimal("5000"),
            purpose = CanonicalMarkPurpose6570.OBSERVATION_SCORING,
        )))
        assertNotNull(CanonicalPriceMarkRegistry6522.get(mint, CanonicalMarkPurpose6570.OBSERVATION_SCORING))
        assertTrue(source("engine/Executor.kt").contains("PAPER_ENTRY_OBSERVATION_MARK_BOOTSTRAPPED_6600"))
    }

    @Test fun approved_subminimum_order_promotes_once_when_hard_caps_fund_minimum() {
        val r = OrderSizeResolver6441.resolve(
            requestedSol = 0.00154, laneName = "EXPRESS", walletSol = 1.0,
            paperMode = false, laneRiskCapSol = 0.50, laneMinExecutableSol = 0.05,
        )
        assertTrue(r.executable)
        assertEquals(0.05, r.finalSizeSol, 1e-9)
        assertEquals("OK_MIN_PROMOTED_6600", r.reason)
    }

    @Test fun canonical_position_heals_projection_and_legacy_history_cannot_veto_sell() {
        val exec = source("engine/Executor.kt")
        assertTrue(exec.contains("SELL_CANONICAL_PROJECTION_HEALED_6600"))
        assertTrue(exec.contains("canonicalTerminalPosition6492.remainingQtyRaw"))
        assertTrue(!exec.contains("val canonicalBuy = TradeHistoryStore.getTradesSnapshot()"))
        assertTrue(exec.contains("recordDeskStage(canonicalTerminalPosition6492.lane, \"SELL_CONFIRMED\""))
    }

    @Test fun scheduler_trigger_is_delivered_to_existing_request_sell_return_path() {
        val exec = source("engine/Executor.kt")
        assertTrue(exec.contains("val canonicalExitTrigger6600 ="))
        assertTrue(exec.contains("return \"PROTECTIVE_EXIT_${'$'}{canonicalExitTrigger6600.name}_6450\""))
        assertTrue(exec.contains("PROTECTIVE_EXIT_DELIVERED_TO_REQUEST_SELL_6600_"))
    }

    @Test fun causal_funnel_and_dynamic_shared_capital_are_visible() {
        val toolkit = source("engine/ToolkitSignalSheet.kt")
        val report = source("engine/PipelineHealthCollector.kt")
        assertTrue(toolkit.contains("===== MEME SPECIALIST CAUSAL FUNNEL ====="))
        listOf("ownerLaneChangedAfterSelection", "crossLaneExecutionRewrite", "telemetryOnlySuppression", "missingExecutableMarkWithValidSource", "specialistLearningMissing", "sellCanonicalLookupFailure").forEach { assertTrue(toolkit.contains(it)) }
        assertTrue(toolkit.contains("PAPER_CAPITAL_AUTHORITY_6577+LANE_EXPECTANCY+OPPORTUNITY_PRESSURE"))
        assertTrue(!toolkit.contains("targetAllocation=UNPROVEN_SOURCE"))
        assertTrue(report.contains("specialistCausalFunnel6600()"))
    }
}
