package com.lifecyclebot.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * V5.9.495z29 — Acceptance tests for operator spec items 1-3 (lifecycle).
 *
 * Covers scenarios A-E and I from the 10-item spec:
 *   A. Buy succeeds, token lands, tracker registers position.
 *   B. Buy succeeds but token confirmation delayed; tracker waits.
 *   C. Sell returns SOL but leaves token balance; bot keeps position active.
 *   D. Sell returns SOL and token balance is zero; bot clears position.
 *   E. RPC token map empty; bot does NOT falsely clear position.
 *   I. Wallet contains untracked bought token; bot auto-imports.
 *
 * Pure-logic tests; no Android Context dependency. The tracker's persistence
 * paths are exercised by leaving appCtx null (init not called) so save/load
 * are short-circuit no-ops — exactly the path used in JUnit.
 */
class TokenLifecycleTrackerTest {

    @After
    fun resetTrackerState() {
        // Walk every record into RECONCILE_FAILED so isolation between tests
        // is preserved. The internal `records` map is package-private via the
        // public `all()` accessor — markReconcileFailed is the only public
        // way to terminate a record we don't control.
        TokenLifecycleTracker.all().forEach {
            if (it.status != TokenLifecycleTracker.Status.CLEARED &&
                it.status != TokenLifecycleTracker.Status.RECONCILE_FAILED) {
                TokenLifecycleTracker.markReconcileFailed(it.mint, "test_teardown")
            }
        }
    }

    private val MINT_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val MINT_B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"

    @Test
    fun scenarioA_buy_succeeds_token_lands_tracker_registers_position() {
        TokenLifecycleTracker.onBuyPending(MINT_A, "AAA", "pump.fun", 0.1)
        TokenLifecycleTracker.onBuyConfirmed(MINT_A, "sigA", confirmedTokenQty = 0.0)
        TokenLifecycleTracker.onTokenLanded(MINT_A, walletUiAmount = 1_000_000.0)

        val r = TokenLifecycleTracker.get(MINT_A)
        assertNotNull("position must be registered after onTokenLanded", r)
        assertEquals(TokenLifecycleTracker.Status.HELD, r!!.status)
        assertEquals(1_000_000.0, r.currentWalletTokenQty, 1e-9)
        assertEquals(0.1, r.entrySolSpent, 1e-9)
        assertEquals("sigA", r.buyTx)
    }

    @Test
    fun scenarioB_buy_confirmation_delayed_position_stays_in_BUY_CONFIRMED() {
        TokenLifecycleTracker.onBuyPending(MINT_A, "AAA", "pump.fun", 0.1)
        TokenLifecycleTracker.onBuyConfirmed(MINT_A, "sigA")
        // No onTokenLanded call yet — simulating delayed wallet confirmation.

        val r = TokenLifecycleTracker.get(MINT_A)
        assertNotNull(r)
        assertEquals(TokenLifecycleTracker.Status.BUY_CONFIRMED, r!!.status)
        assertEquals("position must NOT be flipped to HELD until tokens land",
            0.0, r.currentWalletTokenQty, 1e-9)
    }

    @Test
    fun scenarioC_sell_returns_sol_but_leaves_token_kept_active_as_PARTIAL_SELL() {
        // Setup: buy + land
        TokenLifecycleTracker.onBuyPending(MINT_A, "AAA", "pump.fun", 0.1)
        TokenLifecycleTracker.onBuyConfirmed(MINT_A, "sigA")
        TokenLifecycleTracker.onTokenLanded(MINT_A, 1_000_000.0)

        // Sell partially fills: SOL returned, but 250k tokens remain in wallet.
        TokenLifecycleTracker.onSellPending(MINT_A, "sigSell")
        TokenLifecycleTracker.onSellSettled(
            mint = MINT_A,
            sig = "sigSell",
            solReceived = 0.07,
            walletTokenAfter = 250_000.0,
        )

        val r = TokenLifecycleTracker.get(MINT_A)!!
        assertEquals(TokenLifecycleTracker.Status.PARTIAL_SELL, r.status)
        assertEquals("residual must remain in wallet field",
            250_000.0, r.currentWalletTokenQty, 1e-6)
        assertTrue("sold qty must be positive", r.soldTokenQty > 0)
    }

    @Test
    fun scenarioD_sell_returns_sol_and_token_zero_clears_position() {
        TokenLifecycleTracker.onBuyPending(MINT_A, "AAA", "pump.fun", 0.1)
        TokenLifecycleTracker.onBuyConfirmed(MINT_A, "sigA")
        TokenLifecycleTracker.onTokenLanded(MINT_A, 1_000_000.0)

        TokenLifecycleTracker.onSellPending(MINT_A, "sigSell")
        TokenLifecycleTracker.onSellSettled(
            mint = MINT_A,
            sig = "sigSell",
            solReceived = 0.18,
            walletTokenAfter = 0.0,
        )

        val r = TokenLifecycleTracker.get(MINT_A)!!
        assertEquals(TokenLifecycleTracker.Status.CLEARED, r.status)
        assertEquals(0.0, r.currentWalletTokenQty, 1e-9)
        assertEquals(0.18, r.solRecovered, 1e-9)
        assertNotNull("closedAtMs must be stamped on CLEARED", r.closedAtMs)
    }

    @Test
    fun scenarioE_rpc_empty_does_NOT_falsely_clear_position() {
        TokenLifecycleTracker.onBuyPending(MINT_A, "AAA", "pump.fun", 0.1)
        TokenLifecycleTracker.onBuyConfirmed(MINT_A, "sigA")
        TokenLifecycleTracker.onTokenLanded(MINT_A, 1_000_000.0)

        // Sell broadcast settled but wallet RPC returned empty — caller passed null.
        TokenLifecycleTracker.onSellPending(MINT_A, "sigSell")
        TokenLifecycleTracker.onSellSettled(
            mint = MINT_A,
            sig = "sigSell",
            solReceived = 0.18,
            walletTokenAfter = null,            // RPC empty
        )

        val r = TokenLifecycleTracker.get(MINT_A)!!
        assertEquals(
            "RPC empty must move to RESIDUAL_HELD, NOT CLEARED",
            TokenLifecycleTracker.Status.RESIDUAL_HELD, r.status,
        )
        assertNull("must not stamp closedAtMs on RESIDUAL_HELD", r.closedAtMs)
    }

    @Test
    fun scenarioI_unknown_wallet_token_auto_imports() {
        TokenLifecycleTracker.autoImportFromWallet(
            mint = MINT_B, symbol = "BBB",
            walletUiAmount = 5_000.0, venue = "wallet-scan",
        )
        val r = TokenLifecycleTracker.get(MINT_B)
        assertNotNull("auto-import must register the position", r)
        assertEquals(TokenLifecycleTracker.Status.HELD, r!!.status)
        assertEquals(5_000.0, r.currentWalletTokenQty, 1e-9)
        assertEquals("wallet-scan", r.venue)
    }

    @Test
    fun reconcile_failed_is_terminal() {
        TokenLifecycleTracker.onBuyPending(MINT_A, "AAA", "pump.fun", 0.1)
        TokenLifecycleTracker.markReconcileFailed(MINT_A, "lp_pulled")
        val r = TokenLifecycleTracker.get(MINT_A)!!
        assertEquals(TokenLifecycleTracker.Status.RECONCILE_FAILED, r.status)
        assertEquals("lp_pulled", r.reconcileFailReason)
    }

    @Test
    fun openCount_excludes_terminal_states() {
        TokenLifecycleTracker.onBuyPending(MINT_A, "AAA", "pump.fun", 0.1)
        TokenLifecycleTracker.onBuyPending(MINT_B, "BBB", "raydium", 0.2)
        TokenLifecycleTracker.markReconcileFailed(MINT_A, "test")

        // openCount() must count MINT_B but not MINT_A (terminal).
        assertEquals(1, TokenLifecycleTracker.openCount())
    }
}
