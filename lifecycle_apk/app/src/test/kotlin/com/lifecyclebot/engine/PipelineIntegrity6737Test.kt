package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.truth.*
import java.math.BigDecimal
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Reproductions of the 6735 accounting/learning faults, not label-only smoke checks. */
class PipelineIntegrity6737Test {
    @Before fun reset() {
        FillLotLedger6504.setTestMemoryMode6641(true)
        CanonicalPositionAuthority6441.resetForTest()
        QuantityInvariantAuthority6500.resetForTest()
        LearningQuarantineGate6470.resetForTest()
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(20.0)
    }

    private fun sell(id: String, lane: String = "EXPRESS", pnl: Double = -0.1, mode: String = "paper") = Trade(
        side = "SELL", mode = mode, sol = 1.0 + pnl, price = 1.0 + pnl, ts = 1800000010000L,
        reason = "TEST_FINAL", pnlSol = pnl, netPnlSol = pnl, pnlPct = pnl * 100.0,
        sig = id, mint = "mint-$id", positionId = "position-$id", tradingMode = lane,
        entryPriceSnapshot = 1.0, entryCostSol = 1.0, proofState = "BALANCE_FINAL",
        entryQtyToken = 100.0, soldQtyToken = 100.0,
    )

    @Test fun same_size_time_bucket_does_not_alias_other_lane() {
        val losing = sell("loser", "EXPRESS", -0.6)
        val winning = sell("winner", "PROJECT_SNIPER", 0.8)
        assertEquals("loser", StrategyTruthLedger.clean(listOf(losing)).rows.single().sig)
        assertEquals("winner", StrategyTruthLedger.clean(listOf(winning)).rows.single().sig)
        assertEquals(80.0, StrategyTruthLedger.clean(listOf(winning)).rows.single().pnlPct, 1e-9)
    }
    @Test fun same_identity_in_other_mode_cannot_hit_paper_cache() {
        val paper = sell("mode-shared")
        StrategyTruthLedger.clean(listOf(paper))
        assertEquals("live", StrategyTruthLedger.clean(listOf(paper.copy(mode = "live"))).rows.single().mode)
    }
    @Test fun changing_only_mutable_pnl_invalidates_cached_result() {
        val row = sell("mutable-pnl")
        StrategyTruthLedger.clean(listOf(row))
        row.pnlPct = 12.0
        assertEquals(12.0, StrategyTruthLedger.clean(listOf(row)).rows.single().pnlPct, 1e-9)
    }
    @Test fun consumer_cannot_mutate_the_shared_cached_reward() {
        val row = sell("consumer-mutation")
        StrategyTruthLedger.clean(listOf(row)).rows.single().pnlPct = 999.0
        assertEquals(-10.0, StrategyTruthLedger.clean(listOf(row)).rows.single().pnlPct, 1e-9)
    }
    @Test fun new_position_quarantine_invalidates_cache_immediately() {
        val row = sell("quarantine-after-read")
        assertEquals(1, StrategyTruthLedger.clean(listOf(row)).rows.size)
        LearningQuarantineGate6470.quarantinePositionId(row.positionId, "test")
        assertTrue(StrategyTruthLedger.clean(listOf(row)).rows.isEmpty())
        LearningQuarantineGate6470.resetForTest()
        assertEquals(1, StrategyTruthLedger.clean(listOf(row)).rows.size)
    }
    @Test fun distinct_generations_of_same_mint_are_not_five_minute_deduplicated() {
        val first = sell("generation-one")
        val second = sell("generation-two").copy(mint = first.mint, ts = first.ts + 1000L)
        assertEquals(2, StrategyTruthLedger.clean(listOf(first, second)).rows.size)
    }
    @Test fun legacy_unpriced_emergency_wins_and_losses_are_both_excluded() {
        for (pnl in listOf(-0.75, 7.36)) {
            val row = sell("legacy-$pnl", pnl = pnl).copy(reason = "STALE_QUOTE_EMERGENCY_25PCT_BACKSTOP")
            assertTrue(StrategyTruthLedger.clean(listOf(row)).rows.isEmpty())
        }
    }
    @Test fun recovered_fresh_quote_exit_is_not_mistaken_for_legacy_synthetic_exit() {
        val row = sell("fresh-exit").copy(reason = "FRESH_QUOTE_EMERGENCY_EXIT_6737")
        assertEquals(1, StrategyTruthLedger.clean(listOf(row)).rows.size)
    }
    @Test fun fractional_and_one_token_holdings_remain_open() {
        for (quantity in listOf(0.000001, 0.25, 1.0, 200.0)) {
            val p = Position(qtyToken = quantity, costSol = 0.5)
            assertTrue("quantity=$quantity", p.isOpen)
            assertTrue(p.hasTokens)
        }
    }
    @Test fun invalid_quantities_are_not_open() {
        for (quantity in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse(Position(qtyToken = quantity).isOpen)
            assertFalse(Position(qtyToken = quantity).hasTokens)
        }
    }
    @Test fun paid_notional_derives_actual_tokens_not_one_synthetic_token() {
        assertEquals(BigInteger("500000000000"), PaperFillMath6737.quantityFromCost(0.05, 0.00001, 100.0, 6))
        assertEquals(BigInteger("5000000"), PaperFillMath6737.quantityFromCost(0.05, 1000.0, 100.0, 9))
    }
    @Test fun quantity_rounds_down_without_creating_purchased_units() {
        assertEquals(BigInteger("333333333"), PaperFillMath6737.quantityFromCost(0.01, 3.0, 100.0, 9))
    }
    @Test fun missing_or_nonfinite_entry_fx_creates_no_fill() {
        for (fx in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY))
            assertNull(PaperFillMath6737.quantityFromCost(0.05, 1.0, fx, 9))
    }
    @Test fun exit_uses_tokens_and_usd_conversion_not_mutable_entry_return() {
        val raw = PaperFillMath6737.quantityFromCost(0.05, 0.00001, 100.0, 6)!!
        assertEquals(0.075, PaperFillMath6737.grossProceeds(raw, 6, 0.000015, 100.0)!!, 1e-12)
        assertEquals(0.0375, PaperFillMath6737.grossProceeds(raw, 6, 0.000015, 200.0)!!, 1e-12)
    }
    @Test fun genuine_collapse_is_not_clamped_into_a_fake_fifteen_percent_loss() {
        val raw = PaperFillMath6737.quantityFromCost(0.05, 1.0, 100.0, 9)!!
        assertEquals(0.00005, PaperFillMath6737.grossProceeds(raw, 9, 0.001, 100.0)!!, 1e-12)
    }
    @Test fun missing_exit_quote_is_unknown_not_a_zero_price_sell() {
        assertNull(PaperFillMath6737.grossProceeds(BigInteger.ONE, 0, 0.0, 100.0))
        assertNull(PaperFillMath6737.grossProceeds(BigInteger.ONE, 0, 1.0, Double.NaN))
    }
    @Test fun usd_and_sol_prices_have_distinct_units() {
        assertEquals(0.0000001, PaperFillMath6737.priceSol(0.05, BigInteger("500000000000"), 6)!!, 1e-16)
    }
    @Test fun executable_minimum_cannot_override_learned_risk() {
        assertEquals(0.0, PaperFillMath6737.boundedNotional(0.00796, 0.19085, 0.05, 0.05), 0.0)
        assertEquals(0.08, PaperFillMath6737.boundedNotional(0.08, 1.0, 0.5, 0.05), 1e-9)
    }
    @Test fun cash_and_lane_caps_remain_hard_even_when_minimum_is_affordable_elsewhere() {
        assertEquals(0.0, PaperFillMath6737.boundedNotional(0.10, 0.04, 0.5, 0.05), 0.0)
        assertEquals(0.0, PaperFillMath6737.boundedNotional(0.10, 1.0, 0.04, 0.05), 0.0)
    }
    @Test fun lamport_rounding_never_raises_a_cap() {
        assertEquals(0.0, PaperFillMath6737.boundedNotional(0.0499999999, 1.0, 1.0, 0.05), 0.0)
        assertEquals(0.050000001, PaperFillMath6737.boundedNotional(0.0500000019, 1.0, 1.0, 0.05), 1e-12)
    }
    @Test fun nan_or_infinite_sizing_inputs_are_not_executable() {
        assertEquals(0.0, PaperFillMath6737.boundedNotional(Double.NaN, 1.0, 1.0, 0.05), 0.0)
        assertEquals(0.0, PaperFillMath6737.boundedNotional(0.1, 1.0, Double.POSITIVE_INFINITY, 0.05), 0.0)
    }

    private fun quote(mint: String, time: Long) = CanonicalPriceMark6522(
        mint, "pool", mint, "USD", "DEXSCREENER_PAIR_POLL", time,
        PriceUsd(BigDecimal("0.0000123")), BigDecimal("5000"), CanonicalMarkPurpose6570.EXIT_ECONOMIC,
    )
    @Test fun exit_witness_rejects_stale_missing_future_and_wrong_identity() {
        val now = 1800000010000L
        val q = quote("exact-mint", now)
        assertTrue(CanonicalPriceMarkRegistry6522.validEconomicExit6737(q, "exact-mint", now))
        assertFalse(CanonicalPriceMarkRegistry6522.validEconomicExit6737(q.copy(timestampMs = now - 30001), "exact-mint", now))
        assertFalse(CanonicalPriceMarkRegistry6522.validEconomicExit6737(q.copy(timestampMs = 0), "exact-mint", now))
        assertFalse(CanonicalPriceMarkRegistry6522.validEconomicExit6737(q.copy(timestampMs = now + 5001), "exact-mint", now))
        assertFalse(CanonicalPriceMarkRegistry6522.validEconomicExit6737(q.copy(baseMint = "other"), "exact-mint", now))
        assertFalse(CanonicalPriceMarkRegistry6522.validEconomicExit6737(q.copy(liquidityUsd = null), "exact-mint", now))
    }
    @Test fun live_coordinator_is_not_restarted_for_a_newer_request() {
        assertTrue(ExitCoordinatorHealth6737.healthy(true, 99000, 100000))
        assertFalse(ExitCoordinatorHealth6737.healthy(false, 99999, 100000))
        assertFalse(ExitCoordinatorHealth6737.healthy(true, 80000, 100000))
        assertFalse(ExitCoordinatorHealth6737.healthy(true, 0, 100000))
    }
    @Test fun throughput_finality_label_is_not_a_permanent_token_safety_ban() {
        val c = RejectTaxonomy.classify("FINALITY_EXEC_OPEN_BLOCKED_EXIT_THROUGHPUT_6727:CASH_STARVED_EXIT_THROUGHPUT_6727")
        assertEquals(RejectTaxonomy.Category.PENALTY, c.category)
        assertFalse(c.hardSafety)
        assertFalse(c.trainable)
    }
    @Test fun real_safety_rejection_is_not_weakened_by_resource_label() {
        assertTrue(RejectTaxonomy.classify("RUG_FINALITY_CASH_STARVED_EXIT_THROUGHPUT_6727").hardSafety)
        assertTrue(RejectTaxonomy.classify("ZERO_LIQUIDITY").hardSafety)
    }

    private fun funded(id: String, basis: Double, raw: BigInteger, price: Double): CanonicalPositionAuthority6441.Position {
        assertTrue(PaperAccountLedger6430.onBuy(basis, 0.0))
        assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED,
            CanonicalPositionAuthority6441.openPosition(
                "open-$id", id, id, id, "QUALITY", "test6737", basis, raw, 6, 0.0,
                paperMode = false, modeOverride = "paper", entryPriceUsd = price,
                entryPriceSource = "DEXSCREENER_PAIR_POLL", entryPoolAddress = "pool-$id", quantityScale = 6,
            ))
        return CanonicalPositionAuthority6441.getPosition(id)!!
    }
    @Test fun some_invalid_inventory_does_not_disappear_from_wallet_while_others_are_valid() {
        funded("valid6737", 1.0, BigInteger("1000000000"), 0.10)
        funded("invalid6737", 4.0, BigInteger.ONE, 0.10)
        val cap = CanonicalCapitalAuthority6450.snapshot { mint -> if (mint == "valid6737") 0.8 else 0.0 }
        assertEquals(5.0, cap.openCostBasisSol, 1e-9)
        assertEquals(4.8, cap.openMarketValueSol, 1e-9)
        assertEquals(19.8, cap.totalEquitySol, 1e-9)
        assertEquals(4.0, cap.unpricedOpenCostBasisSol, 1e-9)
        assertEquals(2, cap.fundedPositionCount)
        assertFalse(cap.valuationComplete)
        assertEquals(0.0, cap.conservationDeltaSol, 1e-9)
    }
    @Test fun legitimate_partial_compares_remaining_quantity_with_remaining_cost() {
        val original = funded("partial6737", 1.0, BigInteger("1000000000"), 0.10)
        val partial = original.copy(remainingQtyRaw = BigInteger("1000000"), soldCostBasisSol = 0.999,
            lifecycle = CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED)
        assertTrue(QuantityInvariantAuthority6500.checkCanonical6635(partial).ok)
    }
    @Test fun quarantine_does_not_release_paid_capital_or_mint_occupancy() {
        funded("quarantined6737", 1.0, BigInteger("1000000000"), 0.10)
        CanonicalPositionAuthority6441.quarantine("quarantined6737", "test")
        assertTrue(CanonicalPositionAuthority6441.hasFundedMint6737("paper", "quarantined6737"))
        assertEquals(1.0, CanonicalCapitalAuthority6450.snapshot { 0.0 }.openCostBasisSol, 1e-9)
    }
    @Test fun paper_treasury_cannot_debit_or_transfer_before_settlement() {
        assertEquals(0.0, TreasuryManager.contributeFromMemeSell(1.0, 100.0, true), 0.0)
        assertEquals(0.0, TreasuryManager.contributeFullyFromTreasuryScalp(1.0, 100.0, true), 0.0)
        assertEquals(20.0, PaperAccountLedger6430.cashSol(), 1e-9)
    }
}
