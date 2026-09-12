package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import com.lifecyclebot.perps.DexTokenQuote6738
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.io.File

/** Regression tests exercise production policies; no networks, wallets or funded orders. */
class Aate6738ExitRecoveryTest {
    @Before fun reset() {
        PaperExitEvidence6738.resetForTest()
        CanonicalPriceMarkRegistry6522.resetForTest()
        EconomicEventSchema6464.resetForTest()
        CanonicalPaperReplay6464.resetForTest()
    }
    private fun mint() = "Retry6738${System.nanoTime()}"
    private val now get() = System.currentTimeMillis()

    @Test fun failure_is_blocked_until_retry_deadline_including_boundary() {
        val m = mint(); val at = now
        PaperPositionCloseAuthority.markFailed(mint = m, reason = "QUOTE_MISSING", nowMs = at)
        for (elapsed in listOf(0L, 1L, 500L, 19_999L)) {
            val g = PaperPositionCloseAuthority.preSellGuard(mint = m, nowMs = at + elapsed)
            assertTrue(g.blocked)
            assertEquals(Executor.SellResult.FAILED_RETRYABLE, PaperSellOutcome6738.blocked(g))
        }
        assertFalse(PaperPositionCloseAuthority.preSellGuard(mint = m, nowMs = at + 20_000).blocked)
    }
    @Test fun repeated_failure_observations_do_not_extend_backoff() {
        val m = mint(); val at = now
        PaperPositionCloseAuthority.markFailed(mint = m, nowMs = at)
        PaperPositionCloseAuthority.markFailed(mint = m, nowMs = at + 19_000)
        assertFalse(PaperPositionCloseAuthority.preSellGuard(mint = m, nowMs = at + 20_000).blocked)
    }
    @Test fun next_attempt_gets_its_own_failure_deadline() {
        val m = mint(); val at = now
        PaperPositionCloseAuthority.markFailed(mint = m, nowMs = at - 20_000)
        assertFalse(PaperPositionCloseAuthority.preSellGuard(mint = m, nowMs = at).blocked)
        val id = PaperPositionCloseAuthority.markCloseRequested(mint = m)
        PaperPositionCloseAuthority.markFailed(mint = m, nowMs = at)
        val g = PaperPositionCloseAuthority.preSellGuard(mint = m, nowMs = at + 19_999)
        assertTrue(g.blocked); assertEquals(id, g.closeId)
    }
    @Test fun late_failure_cannot_reopen_terminal_state() {
        val m = mint()
        PaperPositionCloseAuthority.markClosed(mint = m, reason = "CONFIRMED_TEST")
        PaperPositionCloseAuthority.markFailed(mint = m, reason = "LATE_CALLBACK")
        val g = PaperPositionCloseAuthority.preSellGuard(mint = m)
        assertEquals(PaperPositionCloseAuthority.State.CLOSED, g.state)
        assertEquals(Executor.SellResult.ALREADY_CLOSED, PaperSellOutcome6738.blocked(g))
    }
    @Test fun no_transient_guard_state_is_reported_as_closed() {
        PaperPositionCloseAuthority.State.values().filter { it != PaperPositionCloseAuthority.State.CLOSED }.forEach {
            assertEquals(Executor.SellResult.FAILED_RETRYABLE,
                PaperSellOutcome6738.blocked(PaperPositionCloseAuthority.Guard(true, it, "WAIT")))
        }
    }
    @Test fun all_nonterminal_executor_outcomes_retain_specialist_inventory() {
        val terminal = setOf(Executor.SellResult.CONFIRMED, Executor.SellResult.PAPER_CONFIRMED, Executor.SellResult.ALREADY_CLOSED)
        Executor.SellResult.values().forEach { assertEquals(it in terminal, PaperSellOutcome6738.isTerminal(it)) }
    }
    private fun quote(base: String = "MintA", chain: String = "solana", price: Double = 0.1234,
                      liq: Double = 12_345.0, quoteMint: String = "USDC") =
        DexTokenQuote6738(chain, base, quoteMint, "PoolA", "raydium", price, liq, 100_000.0, now)
    @Test fun higher_liquidity_wrong_base_token_cannot_supply_our_price() {
        val right = quote()
        assertEquals(right, DexTokenQuote6738.select("MintA", "solana", listOf(
            quote(base = "OTHER", price = 400.0, liq = 1e9, quoteMint = "MintA"), right)))
    }
    @Test fun wrong_chain_and_case_changed_solana_address_are_rejected() {
        assertNull(DexTokenQuote6738.select("MintA", "solana", listOf(quote(chain = "ethereum"))))
        assertNull(DexTokenQuote6738.select("minta", "solana", listOf(quote())))
    }
    @Test fun ambiguous_cross_chain_address_is_not_an_unqualified_quote() {
        assertNull(DexTokenQuote6738.select("MintA", null, listOf(quote(), quote(chain = "base"))))
    }
    @Test fun finite_price_and_complete_identity_are_required() {
        assertNull(DexTokenQuote6738.select("MintA", "solana", listOf(quote(price = Double.NaN))))
        assertNull(DexTokenQuote6738.select("MintA", "solana", listOf(quote(liq = Double.POSITIVE_INFINITY))))
        assertNull(DexTokenQuote6738.select("MintA", "solana", listOf(quote(quoteMint = ""))))
    }
    @Test fun observed_fx_can_be_below_fifty_dollars_without_a_fabricated_floor() {
        val at = now
        assertTrue(PaperExitEvidence6738.observeSolUsd(35.0, "COINGECKO_SOL_USD", at, at))
        assertEquals(35.0, PaperExitEvidence6738.freshFx(at)!!.usdPerSol, 0.0)
    }
    @Test fun display_cache_usdt_stale_and_future_fx_are_not_dollar_execution_evidence() {
        val at = now
        assertFalse(PaperExitEvidence6738.observeSolUsd(140.0, "DISPLAY_CACHE", at, at))
        assertFalse(PaperExitEvidence6738.observeSolUsd(140.0, "BINANCE_SOL_USDT", at, at))
        assertFalse(PaperExitEvidence6738.observeSolUsd(140.0, "PYTH_SOL_USD", at - 120_001, at))
        assertFalse(PaperExitEvidence6738.observeSolUsd(140.0, "PYTH_SOL_USD", at + 5_001, at))
        assertNull(PaperExitEvidence6738.freshFx(at))
    }
    @Test fun older_fx_cannot_refresh_or_overwrite_newer_observation() {
        val at = now
        PaperExitEvidence6738.observeSolUsd(100.0, "JUPITER_SOL_USD", at, at)
        PaperExitEvidence6738.observeSolUsd(90.0, "COINGECKO_SOL_USD", at - 1000, at)
        assertEquals(100.0, PaperExitEvidence6738.freshFx(at)!!.usdPerSol, 0.0)
        assertNull(PaperExitEvidence6738.freshFx(at + 120_001))
    }
    @Test fun settlement_uses_current_fx_canonical_quantity_and_explicit_friction() {
        val at = now
        val fx = PaperExitEvidence6738.Fx(200.0, "JUPITER_SOL_USD", at)
        // Cost was 1 SOL when USD/SOL was 100. Unchanged token USD price now returns 0.5 SOL gross.
        val r = PaperExitEvidence6738.settle(BigDecimal("100"), 1.0, 1.0, fx, 2.0, 1.0, 10_000.0, at)!!
        assertEquals(0.5, r.grossSol, 1e-12)
        assertEquals(0.4851, r.proceedsSol, 1e-12)
        assertEquals(0.0149, r.frictionSol, 1e-12)
        assertEquals(-0.5149, r.pnlSol, 1e-12)
        assertEquals(r.grossSol - r.frictionSol, r.proceedsSol, 1e-12)
    }
    @Test fun catastrophic_gaps_are_not_clamped_to_a_stop_loss_label() {
        val at = now
        val r = PaperExitEvidence6738.settle(BigDecimal("100"), 1.0, 0.01,
            PaperExitEvidence6738.Fx(100.0, "JUPITER_SOL_USD", at), 0.0, 0.0, 1000.0, at)!!
        assertEquals(-0.99, r.pnlSol, 1e-12)
    }
    @Test fun zero_unknown_liquidity_or_stale_fx_cannot_finalize_a_paper_sell() {
        val at = now; val fx = PaperExitEvidence6738.Fx(100.0, "PYTH_SOL_USD", at)
        assertNull(PaperExitEvidence6738.settle(BigDecimal.ONE, 1.0, 1.0, fx, 1.0, 1.0, 0.0, at))
        assertNull(PaperExitEvidence6738.settle(BigDecimal.ONE, 1.0, 1.0, fx, 1.0, 1.0, 1000.0, at + 120_001))
        assertNull(PaperExitEvidence6738.settle(BigDecimal.ONE, 1.0, Double.NaN, fx, 1.0, 1.0, 1000.0, at))
    }
    @Test fun a_partial_remaining_lot_uses_remaining_raw_quantity_not_original_size() {
        val at = now
        val qty = BigInteger("60000000000000").toBigDecimal().movePointLeft(12)
        val r = PaperExitEvidence6738.settle(qty, 0.6, 1.0,
            PaperExitEvidence6738.Fx(100.0, "JUPITER_SOL_USD", at), 0.0, 0.0, 10_000.0, at)!!
        assertEquals(0.6, r.proceedsSol, 1e-12)
        assertEquals(0.0, r.pnlSol, 1e-12)
    }
    @Test fun the_exit_reads_the_freshest_accepted_whole_mark_tuple() {
        val at = now
        val a = CanonicalPriceMark6522("MARK6738", "pool", "MARK6738", "USD", "DEXSCREENER_PAIR_POLL",
            at - 1000, PriceUsd(BigDecimal("0.1234")), BigDecimal("12345"), CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE)
        val b = a.copy(timestampMs = at, priceUsd = PriceUsd(BigDecimal("0.1245")), purpose = CanonicalMarkPurpose6570.OBSERVATION_SCORING)
        assertTrue(CanonicalPriceMarkRegistry6522.publish(a)); assertTrue(CanonicalPriceMarkRegistry6522.publish(b))
        assertEquals(b, PaperExitEvidence6738.freshMark("MARK6738", at))
        assertNull(PaperExitEvidence6738.freshMark("MARK6738", at + 120_001))
    }
    @Test fun legitimate_large_terminal_proceeds_are_not_dropped_from_replay() {
        val qty = BigInteger.valueOf(1000)
        EconomicEventSchema6464.recordBuy("paper", "BIG6738", "BIG", "BIG", "buy-big", 1.0, qty, 0.1)
        EconomicEventSchema6464.recordSell("paper", "BIG6738", "BIG", "BIG", "sell-big", false,
            qty, qty, 1.0, 51.0, 0.5)
        val replay = CanonicalPaperReplay6464.replay(10.0)
        assertEquals(1, replay.fullSells); assertEquals(0, replay.invalidRowsQuarantined)
        assertEquals(0.0, replay.openCostBasisSol, 1e-9)
        assertEquals(59.5, replay.cashSol, 1e-9)
        assertEquals(50.0, replay.realizedPnlSol, 1e-9)
        assertEquals(0.5, replay.feesSol, 1e-9)
    }
    @Test fun production_callers_use_retry_semantics_and_no_fabricated_fill_path() {
        val executor = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val cyclic = File("src/main/kotlin/com/lifecyclebot/engine/CyclicTradeEngine.kt").readText()
        val paper = executor.substringAfter("fun paperSell(").substringBefore("fun liveSell(")
        assertTrue(executor.contains("return PaperSellOutcome6738.blocked(guard)"))
        assertTrue(paper.contains("PaperExitEvidence6738.freshMark(ts.mint)"))
        assertTrue(paper.contains("PaperExitEvidence6738.settle("))
        assertFalse(paper.contains("val price = getActualPrice(ts)"))
        assertFalse(paper.contains("cost basis × price return"))
        assertFalse(cyclic.contains("executor.paperSell(ts"))
        assertTrue(bot.contains("if (!terminal6738) paperStaleZombieLatch6504.remove(zombieLatchKey6504)"))
        val fallback = bot.substringAfter("private fun tryFallbackPriceData(").substringBefore("private val entryHydrationPending6647")
        assertFalse(fallback.contains("usd_market_cap")); assertFalse(fallback.contains("PAIR_FALLBACK"))
        assertTrue(fallback.contains("getQuoteByAddress(mint, \"solana\")"))
        assertFalse(bot.contains("ts.lastPrice = effectiveExitPrice"))
    }
}
