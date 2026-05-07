package com.lifecyclebot.engine.sell

import com.lifecyclebot.network.PumpPortalSellRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * V5.9.495z39 — operator spec item 9: 10 acceptance tests for the sell
 * execution overhaul. Each test corresponds to one of the spec items.
 *
 * Item 1 — Repair partial sell sizing (SellIntent invariants)
 * Item 2 — PumpPortal percent xor amount semantics
 * Item 3 — ExitReason enum + classifier (kills DRAIN-EXIT for profit locks)
 * Item 4 — Proportional cost-basis PnL math
 * Item 5 — Treasury recovery basis lock
 * Item 6 — Tx-meta-driven state finalization
 * Item 7 — Wallet refresh after sell (smoke test only — no wallet in JVM)
 * Item 8 — Forensic labels use chain-confirmed values (auditor + classifier integration)
 * Item 9 — full pipeline integration (intent → audit → finalize → PnL)
 * Item 10 — TokenLifecycleTracker entry-meta data class shape
 */
class SellExecutionOverhaulTest {

    @Before
    fun reset() {
        // Each test starts with no auditor / recovery locks.
        SellAmountAuditor.unlock("MINT_TEST_A")
        SellAmountAuditor.unlock("MINT_TEST_B")
        SellAmountAuditor.unlock("MINT_TEST_C")
        SellAmountAuditor.unlock("MINT_TEST_D")
        SellAmountAuditor.unlock("MINT_TEST_E")
        RecoveryLockTracker.forceUnlock("MINT_TEST_A")
        RecoveryLockTracker.forceUnlock("MINT_TEST_B")
    }

    // ── Item 1 ──────────────────────────────────────────────────────────

    @Test
    fun item1_partial_sell_sizing_uses_strict_integer_math() {
        // 25% of 1_000_000 raw = 250_000 — exact, no double rounding.
        val intent = SellIntent.build(
            mint = "MINT_TEST_A", symbol = "SYMA",
            reason = ExitReason.PARTIAL_TAKE_PROFIT,
            requestedFractionBps = 2_500,
            confirmedWalletRaw = BigInteger.valueOf(1_000_000L),
            decimals = 6, slippageBps = 300,
            emergencyDrain = false,
            entrySolSpent = 0.5,
            entryTokenRaw = BigInteger.valueOf(1_000_000L),
        )
        assertEquals(BigInteger.valueOf(250_000L), intent.requestedSellRaw)
        assertTrue(intent.requestedSellRaw <= intent.confirmedWalletRaw)
        assertEquals(0.25, intent.requestedSellUi, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun item1_partial_must_reject_full_balance_drain() {
        // PROFIT_LOCK with fraction=10000 must fail (must use HARD_STOP / RUG_DRAIN).
        SellIntent.build(
            mint = "MINT_TEST_B", symbol = "SYMB",
            reason = ExitReason.PROFIT_LOCK,
            requestedFractionBps = 10_000,
            confirmedWalletRaw = BigInteger.valueOf(1000L),
            decimals = 6, slippageBps = 300,
            emergencyDrain = false,
            entrySolSpent = 0.5, entryTokenRaw = BigInteger.valueOf(1000L),
        )
    }

    // ── Item 2 ──────────────────────────────────────────────────────────

    @Test
    fun item2_pumpportal_percent_and_amount_are_disjoint() {
        val pct = PumpPortalSellRequest.Percent(
            mint = "MINT_TEST_A", percentage = 25,
            slippagePercent = 5, priorityFeeSol = 0.0001,
        )
        val raw = PumpPortalSellRequest.RawAmount(
            mint = "MINT_TEST_A",
            amountRaw = BigInteger.valueOf(123_456_000L), decimals = 6,
            slippagePercent = 5, priorityFeeSol = 0.0001,
        )
        assertEquals("25%", pct.toApiAmountField())
        assertEquals("123.456", raw.toApiAmountField())
        assertNotEquals(pct.javaClass, raw.javaClass)
    }

    @Test(expected = IllegalArgumentException::class)
    fun item2_pumpportal_rejects_zero_percent() {
        PumpPortalSellRequest.Percent(
            mint = "MINT_TEST_A", percentage = 0,
            slippagePercent = 5,
        )
    }

    // ── Item 3 ──────────────────────────────────────────────────────────

    @Test
    fun item3_exit_reason_classifier_routes_strings_correctly() {
        assertEquals(ExitReason.PROFIT_LOCK,
            SellReasonClassifier.fromString("PROFIT_LOCK"))
        assertEquals(ExitReason.RUG_DRAIN,
            SellReasonClassifier.fromString("RAPID_CATASTROPHE_STOP"))
        assertEquals(ExitReason.CAPITAL_RECOVERY,
            SellReasonClassifier.fromString("CAPITAL_RECOVERY"))
        // Operator spec: profit-lock must NOT classify as RUG_DRAIN.
        assertNotEquals(ExitReason.RUG_DRAIN,
            SellReasonClassifier.fromString("PROFIT_LOCK"))
        // Full-exit promotion: profit-lock on the full-balance path → HARD_STOP.
        assertEquals(ExitReason.HARD_STOP,
            SellReasonClassifier.fullExitFromString("PROFIT_LOCK"))
    }

    // ── Item 4 ──────────────────────────────────────────────────────────

    @Test
    fun item4_proportional_pnl_uses_chain_confirmed_amounts() {
        // Bought 1_000_000 raw for 1.0 SOL. Sold 250_000 raw for 0.4 SOL.
        // proportionalCostBasis = 1.0 * 0.25 = 0.25 SOL.
        // realisedPnl = 0.4 - 0.25 - 0.001 fees = 0.149 SOL.
        val r = RealizedPnLCalculator.calculate(
            entrySolSpent = 1.0,
            entryTokenRaw = BigInteger.valueOf(1_000_000L),
            actualConsumedRaw = BigInteger.valueOf(250_000L),
            sellSolReceived = 0.4,
            feesSol = 0.001,
        )
        assertEquals(0.25, r.proportionalCostBasisSol, 1e-9)
        assertEquals(0.149, r.realizedPnlSol, 1e-9)
        assertTrue(r.isProfit)
        assertFalse(r.degenerate)
    }

    @Test
    fun item4_pnl_loss_with_fees_is_not_falsely_logged_as_profit() {
        val r = RealizedPnLCalculator.calculate(
            entrySolSpent = 1.0,
            entryTokenRaw = BigInteger.valueOf(1_000_000L),
            actualConsumedRaw = BigInteger.valueOf(1_000_000L),
            sellSolReceived = 0.6,
            feesSol = 0.005,
        )
        assertEquals(1.0, r.proportionalCostBasisSol, 1e-9)
        assertEquals(-0.405, r.realizedPnlSol, 1e-6)
        assertFalse(r.isProfit)
    }

    // ── Item 5 ──────────────────────────────────────────────────────────

    @Test
    fun item5_recovery_lock_blocks_until_basis_loaded() {
        RecoveryLockTracker.lock("MINT_TEST_A", "SYMA", "TREASURY_RECOVERY")
        assertTrue(RecoveryLockTracker.isLockedAwaitingChainBasis("MINT_TEST_A"))
        // No basis → unlock fails.
        val ok1 = RecoveryLockTracker.tryUnlockWithChainBasis(
            mint = "MINT_TEST_A",
            entrySolSpent = 0.0,
            entryTokenRaw = BigInteger.ZERO,
            currentWalletTokenRaw = BigInteger.ZERO,
            liveQuoteSolOut = 0.0, feesSol = 0.0,
        )
        assertFalse(ok1)
        assertTrue(RecoveryLockTracker.isLockedAwaitingChainBasis("MINT_TEST_A"))
        // Profitable basis → unlock.
        val ok2 = RecoveryLockTracker.tryUnlockWithChainBasis(
            mint = "MINT_TEST_A",
            entrySolSpent = 1.0,
            entryTokenRaw = BigInteger.valueOf(1_000_000L),
            currentWalletTokenRaw = BigInteger.valueOf(1_000_000L),
            liveQuoteSolOut = 1.5, feesSol = 0.005,
        )
        assertTrue(ok2)
        assertFalse(RecoveryLockTracker.isLockedAwaitingChainBasis("MINT_TEST_A"))
    }

    // ── Item 6 ──────────────────────────────────────────────────────────

    @Test
    fun item6_tx_meta_finalizer_drives_state() {
        // Pre = 1_000_000, post = 0 → CLEARED.
        val cleared = TxMetaSellFinalizer.finalize(
            preTokenBalanceRaw = BigInteger.valueOf(1_000_000L),
            postTokenBalanceRaw = BigInteger.ZERO,
            walletPollRaw = BigInteger.ZERO,
            solReceivedLamports = 500_000_000L,
        )
        assertEquals(TxMetaSellFinalizer.FinalState.CLEARED, cleared.finalState)
        assertEquals(BigInteger.valueOf(1_000_000L), cleared.actualConsumedRaw)
        assertEquals(0.5, cleared.solReceived, 1e-9)

        // Partial sell: post still has half.
        val partial = TxMetaSellFinalizer.finalize(
            preTokenBalanceRaw = BigInteger.valueOf(1_000_000L),
            postTokenBalanceRaw = BigInteger.valueOf(500_000L),
            walletPollRaw = BigInteger.valueOf(500_000L),
            solReceivedLamports = 250_000_000L,
        )
        assertEquals(TxMetaSellFinalizer.FinalState.PARTIAL_SELL, partial.finalState)
        assertEquals(BigInteger.valueOf(500_000L), partial.actualConsumedRaw)

        // RPC blip: walletPollRaw and postTokenBalanceRaw both null → INDETERMINATE.
        val indet = TxMetaSellFinalizer.finalize(
            preTokenBalanceRaw = null, postTokenBalanceRaw = null,
            walletPollRaw = null, solReceivedLamports = 0L,
        )
        assertEquals(TxMetaSellFinalizer.FinalState.INDETERMINATE, indet.finalState)
    }

    // ── Item 7 ──────────────────────────────────────────────────────────

    @Test
    fun item7_wallet_refresh_returns_zero_when_no_wallet() {
        // In a JVM unit-test environment WalletManager is not initialised;
        // forceRefresh must short-circuit safely and return 0.0 instead of throwing.
        val sol = WalletRefreshAfterSell.forceRefresh("unit-test")
        assertEquals(0.0, sol, 0.0)
    }

    // ── Item 8 ──────────────────────────────────────────────────────────

    @Test
    fun item8_audit_locks_mint_when_actual_exceeds_requested() {
        val intent = SellIntent.build(
            mint = "MINT_TEST_C", symbol = "SYMC",
            reason = ExitReason.PARTIAL_TAKE_PROFIT,
            requestedFractionBps = 2_500,
            confirmedWalletRaw = BigInteger.valueOf(1_000_000L),
            decimals = 6, slippageBps = 300,
            emergencyDrain = false,
            entrySolSpent = 1.0, entryTokenRaw = BigInteger.valueOf(1_000_000L),
        )
        // Requested 250_000 but actual was 900_000 (3.6×) — must fail audit.
        val ok = SellAmountAuditor.audit(intent, BigInteger.valueOf(900_000L))
        assertFalse(ok)
        assertTrue(SellAmountAuditor.isLocked("MINT_TEST_C"))
        val v = SellAmountAuditor.getViolation("MINT_TEST_C")
        assertNotNull(v)
        assertEquals(BigInteger.valueOf(650_000L), v!!.overconsumedRaw)
    }

    @Test
    fun item8_audit_passes_within_tolerance() {
        val intent = SellIntent.build(
            mint = "MINT_TEST_D", symbol = "SYMD",
            reason = ExitReason.PARTIAL_TAKE_PROFIT,
            requestedFractionBps = 2_500,
            confirmedWalletRaw = BigInteger.valueOf(1_000_000L),
            decimals = 6, slippageBps = 300,
            emergencyDrain = false,
            entrySolSpent = 1.0, entryTokenRaw = BigInteger.valueOf(1_000_000L),
        )
        // Requested 250_000 + 0.5% rounding = 251_250. Tolerance is max(1% , 1000 raw) = 2_500.
        // So 252_500 should still pass.
        val ok = SellAmountAuditor.audit(intent, BigInteger.valueOf(252_500L))
        assertTrue(ok)
        assertFalse(SellAmountAuditor.isLocked("MINT_TEST_D"))
    }

    // ── Item 9 ──────────────────────────────────────────────────────────

    @Test
    fun item9_full_pipeline_intent_audit_finalize_pnl() {
        val intent = SellIntent.build(
            mint = "MINT_TEST_E", symbol = "SYME",
            reason = ExitReason.RUG_DRAIN,
            requestedFractionBps = 10_000,
            confirmedWalletRaw = BigInteger.valueOf(1_000_000L),
            decimals = 6, slippageBps = 5_000,
            emergencyDrain = true,
            entrySolSpent = 1.0, entryTokenRaw = BigInteger.valueOf(1_000_000L),
        )
        // Tx meta confirms 1M consumed.
        val fin = TxMetaSellFinalizer.finalize(
            preTokenBalanceRaw = BigInteger.valueOf(1_000_000L),
            postTokenBalanceRaw = BigInteger.ZERO,
            walletPollRaw = BigInteger.ZERO,
            solReceivedLamports = 100_000_000L,        // 0.1 SOL on a rug
        )
        assertEquals(TxMetaSellFinalizer.FinalState.CLEARED, fin.finalState)
        val auditPass = SellAmountAuditor.audit(intent, fin.actualConsumedRaw)
        assertTrue(auditPass)
        val pnl = RealizedPnLCalculator.calculate(
            entrySolSpent = intent.entrySolSpent,
            entryTokenRaw = intent.entryTokenRaw,
            actualConsumedRaw = fin.actualConsumedRaw,
            sellSolReceived = fin.solReceived,
            feesSol = 0.005,
        )
        // 0.1 - 1.0 - 0.005 = -0.905 SOL realised loss.
        assertEquals(-0.905, pnl.realizedPnlSol, 1e-6)
        assertFalse(pnl.isProfit)
    }

    // ── Item 10 ─────────────────────────────────────────────────────────

    @Test
    fun item10_lifecycle_entry_metadata_default_shape() {
        val em = com.lifecyclebot.engine.TokenLifecycleTracker.EntryMetadata(
            mint = "MINT_TEST_A",
            entryPriceSol = 0.0000123,
            entryPriceUsd = 0.001,
            entryDecimals = 6,
            entryTokenRawConfirmed = BigInteger.valueOf(1_000_000L),
            entrySolSpent = 0.5,
            poolLiquidityUsd = 12_345.0,
            poolTokenReservesRaw = BigInteger.valueOf(987_654_321L),
            poolSolReservesLamports = 5_000_000_000L,
            atMs = System.currentTimeMillis(),
        )
        assertNotNull(em)
        assertEquals(6, em.entryDecimals)
        assertEquals(BigInteger.valueOf(1_000_000L), em.entryTokenRawConfirmed)
        assertEquals(12_345.0, em.poolLiquidityUsd, 0.0)
        assertEquals(BigInteger.valueOf(987_654_321L), em.poolTokenReservesRaw)
    }
}
