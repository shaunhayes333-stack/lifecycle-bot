package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.*
import com.lifecyclebot.network.ExitQuoteBudget7863
import com.lifecyclebot.v3.MemeUnifiedScorerBridge
import java.math.BigInteger
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

class Aate7863RuntimeContinuityTest {
    @Before fun reset() {
        CanonicalPositionAuthority6441.resetForTest()
        PaperEconomicAtomicCommit6632.resetForTest()
        ProviderInferenceHealth6727.resetForTest6734()
        EntryConvictionRegistry6909.clearForTest()
        StrategyHypothesisEngine.reset()
    }
    @After fun cleanup() { reset() }

    @Test fun experiment_binding_requires_the_original_mode_lane_and_generation() {
        LearningEnvironment7835.withMode("LIVE") {
            StrategyHypothesisEngine.getSizeBias("QUALITY", 70, "NORMAL", "Mint7863H", "", 50L)
        }
        assertEquals("", StrategyHypothesisEngine.bindExecutedPosition7428("pos7863H", "Mint7863H", 51L, "QUALITY", "LIVE"))
        assertEquals("", StrategyHypothesisEngine.bindExecutedPosition7428("pos7863H", "Mint7863H", 50L, "QUALITY", "PAPER"))
        assertEquals("", StrategyHypothesisEngine.bindExecutedPosition7428("pos7863H", "Mint7863H", 50L, "CORE", "LIVE"))
        assertEquals("QUALITY_BASELINE", StrategyHypothesisEngine.bindExecutedPosition7428("pos7863H", "Mint7863H", 50L, "QUALITY", "LIVE"))
    }

    @Test fun buy_success_counts_proved_positions_once_and_excludes_paper() {
        PipelineHealthCollector.resetModeCountersForRuntime("LIVE")
        PipelineHealthCollector.onCanonicalBuyCommitted7863("pos7863A", false)
        PipelineHealthCollector.onCanonicalBuyCommitted7863("pos7863A", false)
        PipelineHealthCollector.onCanonicalBuyCommitted7863("pos7863B", false)
        PipelineHealthCollector.onCanonicalBuyCommitted7863("paper7863", true)
        assertEquals(2L, PipelineHealthCollector.execLiveBuyOkCount())
        PipelineHealthCollector.resetModeCountersForRuntime("LIVE")
    }

    private fun intent() = ExecutableOpenGate.ExecutionIntent(
        attemptId = "attempt7863", candidateId = "candidate7863", candidateVersion = 7863L,
        mint = "Mint7863", mode = "LIVE", canonicalLane = "MOONSHOT", fdgVerdict = "BUY",
        fdgAllowed = true, authorityVersion = 1L, resolvedSize = 0.05, createdAt = 1_000_000L,
        symbol = "TEST", liquidityUsd = 20_000.0,
        finalDecision6613 = ExecutableOpenGate.CanonicalFinalDecision6613.BUY,
        executableMarkSource6613 = "DEXSCREENER", executableMarkTimestampMs6613 = 1_000_000L,
        executableMarkPriceUsd6613 = 0.02, expiresAtMs6613 = 1_180_000L,
    )

    @Test fun sealed_entry_survives_mutable_cache_replacement_without_splicing_new_data() {
        val ts = TokenState("Mint7863", lastPrice = 90.0, lastMcap = 9_000_000.0)
        val mark = SealedEntryContinuity7863.marketSnapshot(ts, intent(), 1_000_100L)!!
        assertEquals(0.02, mark.priceUsd, 0.0)
        assertEquals(1_000_000L, mark.capturedAtMs)
        assertEquals(0.0, mark.marketCapUsd, 0.0)
    }
    @Test fun only_the_identical_cached_observation_keeps_optional_venue_metadata() {
        val ts = TokenState("Mint7863")
        val cached = MintEntryMarketSnapshot(0.02, 900_000.0, 20_000.0, "pool7863", "DEXSCREENER", "RAYDIUM", 1_000_000L)
        assertEquals(cached, SealedEntryContinuity7863.marketSnapshot(ts, intent(), 1_000_100L, cached))
        val changed = cached.copy(capturedAtMs = 1_000_001L)
        assertEquals(0.0, SealedEntryContinuity7863.marketSnapshot(ts, intent(), 1_000_100L, changed)!!.marketCapUsd, 0.0)
    }
    @Test fun missing_expired_foreign_or_non_executable_seals_never_supply_an_entry_mark() {
        val ts = TokenState("Mint7863")
        val base = intent()
        val invalid = listOf(base.copy(mode = "PAPER"), base.copy(mint = "Other"),
            base.copy(fdgAllowed = false), base.copy(hardNoReasons = listOf("MINT_AUTHORITY")),
            base.copy(finalDecision6613 = ExecutableOpenGate.CanonicalFinalDecision6613.UNKNOWN),
            base.copy(expiresAtMs6613 = 1_000_010L), base.copy(executableMarkPriceUsd6613 = Double.NaN),
            base.copy(liquidityUsd = 0.0), base.copy(executableMarkTimestampMs6613 = 1_100_000L))
        invalid.forEach { assertNull(SealedEntryContinuity7863.marketSnapshot(ts, it, 1_000_100L)) }
        assertNull(SealedEntryContinuity7863.marketSnapshot(ts, base, 1_121_000L))
        assertNull(SealedEntryContinuity7863.marketSnapshot(ts, null, 1_000_100L))
    }
    private fun open() {
        assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED,
            CanonicalPositionAuthority6441.openPosition("buy7863", "pos7863", "Mint7863", "TEST", "QUALITY", "run7863",
                1.0, BigInteger.valueOf(1000), 2, 0.0, false, entryPriceUsd = 0.1, entryPriceSource = "LIVE_FINALIZED"))
    }
    @Test fun late_buy_confirmation_preserves_already_sold_quantity_and_basis() {
        open()
        assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED,
            CanonicalPositionAuthority6441.partialSell("sell7863", "pos7863", BigInteger.valueOf(400), 0.5, 0.4, 0.01, false))
        repeat(2) {
            assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED,
                CanonicalPositionAuthority6441.promotePendingToOpen("pos7863", BigInteger.valueOf(1000), 1.0, 0.0, 2, false))
            val p = CanonicalPositionAuthority6441.getPosition("pos7863")!!
            assertEquals(BigInteger.valueOf(600), p.remainingQtyRaw)
            assertEquals(0.4, p.soldCostBasisSol, 1e-10)
            assertEquals(0.01, p.feesSol, 1e-10)
            assertEquals(0.09, p.realizedPnlSol, 1e-10)
            assertEquals(CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED, p.lifecycle)
        }
    }
    @Test fun partial_confirmation_cannot_change_account_or_shrink_below_sold_quantity() {
        open()
        CanonicalPositionAuthority6441.partialSell("sell7863", "pos7863", BigInteger.valueOf(400), 0.5, 0.4, 0.0, false)
        assertEquals(CanonicalPositionAuthority6441.MutateResult.INVARIANT_VIOLATION,
            CanonicalPositionAuthority6441.promotePendingToOpen("pos7863", BigInteger.valueOf(300), 1.0, 0.0, 2, false))
        assertEquals(CanonicalPositionAuthority6441.MutateResult.INVARIANT_VIOLATION,
            CanonicalPositionAuthority6441.promotePendingToOpen("pos7863", BigInteger.valueOf(1000), 1.0, 0.0, 2, true))
        assertEquals(BigInteger.valueOf(600), CanonicalPositionAuthority6441.getPosition("pos7863")!!.remainingQtyRaw)
    }
    @Test fun delayed_journal_ack_completes_the_original_receipt_after_timeout() {
        val a = PaperEconomicAtomicCommit6632
        a.stampLedger("late7863", "Mint7863", PaperEconomicAtomicCommit6632.Side.SELL, "ledger")
        a.sweepUnpaired6632(-1)
        assertFalse(a.isCommitted("late7863"))
        a.stampJournal("late7863", "Mint7863", PaperEconomicAtomicCommit6632.Side.SELL, "durableJournal")
        assertTrue(a.isCommitted("late7863"))
    }
    @Test fun observed_zero_success_capacity_is_not_available_even_with_three_samples() {
        repeat(3) { ProviderInferenceHealth6727.recordFailure("OPENROUTER", "HTTP_402") }
        assertFalse(ProviderInferenceHealth6727.health("OPENROUTER").isHealthy)
        assertEquals(0L, ProviderInferenceHealth6727.health("OPENROUTER").successes)
        ProviderInferenceHealth6727.recordSuccess("GROQ")
        assertTrue(ProviderInferenceHealth6727.health("GROQ").isHealthy)
    }
    @Test fun explicit_account_proof_conflict_cannot_count_as_a_terminal_trade() {
        assertFalse(LiveTerminalSemanticsAuthority7236.isTerminalOutcome("live", "PAPER_SIMULATED"))
        assertFalse(LiveTerminalSemanticsAuthority7236.isTerminalOutcome("paper", "LIVE_FINALIZED"))
        assertFalse(LiveTerminalSemanticsAuthority7236.isTerminalOutcome("live", "LIVE_BROADCAST"))
        assertTrue(LiveTerminalSemanticsAuthority7236.isTerminalOutcome("live", "LIVE_RECONCILED"))
    }
    @Test fun sizing_conviction_is_scoped_to_account_and_owner_lane() {
        EntryConvictionRegistry6909.stamp6909("Mint7863", 0.3, "MOONSHOT", "PAPER")
        EntryConvictionRegistry6909.stamp6909("Mint7863", 0.7, "QUALITY", "LIVE")
        assertEquals(1.0, EntryConvictionRegistry6909.convictionFor6909("Mint7863", "MOONSHOT", "LIVE"), 0.0)
        assertEquals(0.7, EntryConvictionRegistry6909.convictionFor6909("Mint7863", "QUALITY", "LIVE"), 0.0)
    }
    @Test fun foreign_chain_cannot_receive_a_live_solana_executable_size() {
        val r = CanonicalSizingBridge6532.resolve(0.1, com.lifecyclebot.engine.truth.AssetClass.SOLANA_TOKEN, "SHITCOIN", 1.0, false,
            canonicalAssetId = "bsc|0xbdfd")
        assertFalse(r.executable)
        assertEquals(0.0, r.finalSizeSol, 0.0)
    }
    @Test fun missing_v3_decision_never_rescores_synthetic_market_data() {
        assertFalse(MemeUnifiedScorerBridge.scoreForEntry(TokenState("Mint7863")).shouldEnter)
    }
    @Test fun nested_quote_fallbacks_share_deadline_and_restore_thread_state() {
        assertNull(ExitQuoteBudget7863.remainingMs(0L))
        ExitQuoteBudget7863.run(0L) {
            assertEquals(3500L, ExitQuoteBudget7863.remainingMs(0L))
            ExitQuoteBudget7863.run(1_000_000_000L) {
                assertEquals(2500L, ExitQuoteBudget7863.remainingMs(1_000_000_000L))
                try { ExitQuoteBudget7863.remainingMs(3_500_000_000L); fail("must time out") }
                catch (_: java.net.SocketTimeoutException) { }
            }
        }
        assertNull(ExitQuoteBudget7863.remainingMs(0L))
    }
}
