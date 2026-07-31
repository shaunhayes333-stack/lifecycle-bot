package com.lifecyclebot.engine.truth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * V5.0.6401 §7/§8 — SellIntentQuantityAuthority6401 invariants.
 *
 * The 6400 snapshot's sell-path signature: UI 1409 → raw 1.409e5
 * (100× inflated). Every invariant here protects against that
 * regression re-emerging, and guarantees the sell path never
 * broadcasts a decimal-scale error to chain.
 */
class Bundle6401SellIntentQuantityAuthorityTest {

    private fun known(decimals: Int) = MintDecimals.Known(
        count = decimals,
        source = "TEST_MINT_METADATA",
        proofSignature = "sig_test",
    )

    // ─── convertUiToRaw ─────────────────────────────────────────────

    @Test fun convert_ui_to_raw_uses_decimals_exactly() {
        // 1.5 tokens at 6 decimals → 1_500_000 raw
        val raw = SellIntentQuantityAuthority6401.convertUiToRaw(1.5, known(6))
        assertEquals(BigInteger.valueOf(1_500_000L), raw)
    }

    @Test fun convert_ui_to_raw_zero_decimals_is_identity() {
        // 42 tokens at 0 decimals (proven zero-decimal mint) → 42 raw
        val raw = SellIntentQuantityAuthority6401.convertUiToRaw(42.0, known(0))
        assertEquals(BigInteger.valueOf(42L), raw)
    }

    @Test(expected = IllegalStateException::class)
    fun convert_ui_to_raw_refuses_unknown_decimals() {
        // Unknown decimals MUST throw — never coerce to zero.
        SellIntentQuantityAuthority6401.convertUiToRaw(1.0, MintDecimals.Unknown)
    }

    @Test(expected = IllegalArgumentException::class)
    fun convert_ui_to_raw_refuses_non_finite() {
        SellIntentQuantityAuthority6401.convertUiToRaw(Double.NaN, known(6))
    }

    @Test(expected = IllegalArgumentException::class)
    fun convert_ui_to_raw_refuses_negative() {
        SellIntentQuantityAuthority6401.convertUiToRaw(-0.5, known(6))
    }

    // ─── validateSellIntent — happy path ────────────────────────────

    @Test fun exact_balance_sell_is_accepted() {
        val wallet = BigInteger.valueOf(1_500_000_000L)   // 1500 tokens at 6dp
        val req = BigInteger.valueOf(1_500_000_000L)
        val v = SellIntentQuantityAuthority6401.validateSellIntent(
            mint = "M", rawQtyRequested = req, walletRawBalance = wallet,
            decimals = known(6),
        )
        assertTrue(v is SellIntentQuantityAuthority6401.Verdict.Accept)
        assertEquals(req, (v as SellIntentQuantityAuthority6401.Verdict.Accept).rawQty)
    }

    @Test fun partial_sell_within_wallet_is_accepted() {
        val wallet = BigInteger.valueOf(1_500_000_000L)
        val req = BigInteger.valueOf(500_000_000L)   // 500 of 1500
        val v = SellIntentQuantityAuthority6401.validateSellIntent(
            mint = "M", rawQtyRequested = req, walletRawBalance = wallet,
            decimals = known(6),
        )
        assertTrue(v is SellIntentQuantityAuthority6401.Verdict.Accept)
    }

    @Test fun overshoot_within_tolerance_is_clamped_to_wallet() {
        // 0.4% overshoot — within OVERSHOOT_TOLERANCE_PCT = 0.5%.
        val wallet = BigInteger.valueOf(1_000_000_000L)
        val req = BigInteger.valueOf(1_004_000_000L)
        val v = SellIntentQuantityAuthority6401.validateSellIntent(
            mint = "M", rawQtyRequested = req, walletRawBalance = wallet,
            decimals = known(6),
        )
        assertTrue(v is SellIntentQuantityAuthority6401.Verdict.Accept)
        assertEquals(wallet, (v as SellIntentQuantityAuthority6401.Verdict.Accept).rawQty)
    }

    // ─── validateSellIntent — rejections ────────────────────────────

    @Test fun sell_qty_non_positive_rejected() {
        val v = SellIntentQuantityAuthority6401.validateSellIntent(
            mint = "M", rawQtyRequested = BigInteger.ZERO,
            walletRawBalance = BigInteger.TEN, decimals = known(6),
        )
        assertTrue(v is SellIntentQuantityAuthority6401.Verdict.Reject)
        assertEquals("SELL_QTY_NON_POSITIVE",
            (v as SellIntentQuantityAuthority6401.Verdict.Reject).reason)
    }

    @Test fun wallet_balance_zero_rejected() {
        val v = SellIntentQuantityAuthority6401.validateSellIntent(
            mint = "M", rawQtyRequested = BigInteger.TEN,
            walletRawBalance = BigInteger.ZERO, decimals = known(6),
        )
        assertTrue(v is SellIntentQuantityAuthority6401.Verdict.Reject)
        assertEquals("WALLET_RAW_BALANCE_NON_POSITIVE",
            (v as SellIntentQuantityAuthority6401.Verdict.Reject).reason)
    }

    @Test fun small_overshoot_rejects_as_plain_exceeds() {
        // 5% overshoot — over tolerance but well under 50× decimal skew.
        val wallet = BigInteger.valueOf(1_000_000_000L)
        val req = BigInteger.valueOf(1_050_000_000L)
        val v = SellIntentQuantityAuthority6401.validateSellIntent(
            mint = "M", rawQtyRequested = req, walletRawBalance = wallet,
            decimals = known(6),
        )
        val r = v as SellIntentQuantityAuthority6401.Verdict.Reject
        assertEquals("SELL_QTY_EXCEEDS_WALLET_RAW_BALANCE", r.reason)
        assertTrue("overshootPct ~ 5.0, got ${r.overshootPct}",
            r.overshootPct in 4.5..5.5)
    }

    @Test fun hundred_x_overshoot_flagged_as_decimal_skew() {
        // This is the exact 6400-snapshot signature: raw is 100× wallet.
        val wallet = BigInteger.valueOf(1_500_000_000L)   // 1500 at 6dp
        val req = wallet.multiply(BigInteger.valueOf(100L))
        val v = SellIntentQuantityAuthority6401.validateSellIntent(
            mint = "M", rawQtyRequested = req, walletRawBalance = wallet,
            decimals = known(6),
        )
        val r = v as SellIntentQuantityAuthority6401.Verdict.Reject
        assertEquals("QTY_DECIMAL_SKEW_6401_LIKELY_10X_DECIMALS", r.reason)
    }

    // ─── validateSellIntentFromUi ───────────────────────────────────

    @Test fun ui_helper_accepts_correct_ui_qty_against_raw_wallet() {
        // 1409 tokens UI at 6dp → 1_409_000_000 raw; wallet exactly equal.
        val wallet = BigInteger.valueOf(1_409_000_000L)
        val v = SellIntentQuantityAuthority6401.validateSellIntentFromUi(
            mint = "M", uiQtyRequested = 1409.0,
            walletRawBalance = wallet, decimals = known(6),
        )
        assertTrue(v is SellIntentQuantityAuthority6401.Verdict.Accept)
    }

    @Test fun ui_helper_rejects_unknown_decimals() {
        val v = SellIntentQuantityAuthority6401.validateSellIntentFromUi(
            mint = "M", uiQtyRequested = 1.0,
            walletRawBalance = BigInteger.TEN, decimals = MintDecimals.Unknown,
        )
        val r = v as SellIntentQuantityAuthority6401.Verdict.Reject
        assertEquals("MINT_DECIMALS_UNKNOWN", r.reason)
    }

    @Test fun ui_helper_reproduces_the_6400_snapshot_bug_signature() {
        // The snapshot bug: UI qty 1409 mis-scaled to 1.409e5 raw
        // AGAINST a wallet that only holds 1409 raw (correct scale).
        // The caller has clearly double-applied decimals somewhere.
        // Wallet raw ~= 1409 (as if decimals=0 or a similar mismatch).
        val wallet = BigInteger.valueOf(1409L)
        // Simulate the bad callsite passing the ALREADY-raw 140_900 value
        // through the UI helper — the UI helper multiplies by 10^6 again,
        // giving a 100_000× overshoot which the authority rejects.
        val v = SellIntentQuantityAuthority6401.validateSellIntentFromUi(
            mint = "M", uiQtyRequested = 140_900.0,
            walletRawBalance = wallet, decimals = known(6),
        )
        val r = v as SellIntentQuantityAuthority6401.Verdict.Reject
        assertEquals("QTY_DECIMAL_SKEW_6401_LIKELY_10X_DECIMALS", r.reason)
    }
}
