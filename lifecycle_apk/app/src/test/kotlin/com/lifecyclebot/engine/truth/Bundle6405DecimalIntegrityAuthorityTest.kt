package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * V5.0.6405 §5 — DECIMAL INTEGRITY HARD BLOCK invariants.
 *
 * Operator directive (06 Feb 2026):
 *   "false or unknown data is inexcusable!!! fix the issue instead
 *    of bandaiding".
 *
 * These invariants guarantee the sell path can never proceed with a
 * fabricated decimals value and never over-sells beyond the raw entry
 * quantity for a position lifetime.
 */
class Bundle6405DecimalIntegrityAuthorityTest {

    @After fun tearDown() {
        DecimalIntegrityAuthority6405.clearForTest()
        MintDecimalsAuthority6392.clearForTest()
        PositionGenerationBridge6405.clearForTest()
    }

    // ─── resolveDecimalsStrict ─────────────────────────────────────

    @Test fun wallet_cached_decimals_are_authoritative() {
        val d = DecimalIntegrityAuthority6405.resolveDecimalsStrict(
            mint = "MINT_A",
            wallet = null,
            walletCachedDecimals = 6,
            fallbackDecimals = null,
        )
        assertEquals(6, d)
        // Should also populate the shared chain-resolved cache for reuse.
        assertEquals(6, MintDecimalsAuthority6392.get("MINT_A"))
    }

    @Test fun mint_decimals_authority_cache_used_when_wallet_absent() {
        MintDecimalsAuthority6392.resolveAndCache("MINT_B", 9)
        val d = DecimalIntegrityAuthority6405.resolveDecimalsStrict(
            mint = "MINT_B",
            wallet = null,
            walletCachedDecimals = null,
            fallbackDecimals = null,
        )
        assertEquals(9, d)
    }

    @Test fun caller_fallback_used_only_as_last_resort() {
        val d = DecimalIntegrityAuthority6405.resolveDecimalsStrict(
            mint = "MINT_C",
            wallet = null,
            walletCachedDecimals = null,
            fallbackDecimals = 8,
        )
        assertEquals(8, d)
    }

    @Test fun refuses_when_no_source_available() {
        val ex = assertThrows(
            DecimalIntegrityAuthority6405.UnresolvableDecimalsException::class.java,
        ) {
            DecimalIntegrityAuthority6405.resolveDecimalsStrict(
                mint = "MINT_D",
                wallet = null,
                walletCachedDecimals = null,
                fallbackDecimals = null,
            )
        }
        assertEquals("MINT_D", ex.mint)
        assertEquals("NO_VERIFIABLE_DECIMALS_SOURCE", ex.reason)
    }

    @Test fun refuses_invalid_wallet_decimals_and_falls_through_to_throw() {
        val ex = assertThrows(
            DecimalIntegrityAuthority6405.UnresolvableDecimalsException::class.java,
        ) {
            DecimalIntegrityAuthority6405.resolveDecimalsStrict(
                mint = "MINT_E",
                wallet = null,
                walletCachedDecimals = -1, // invalid
                fallbackDecimals = null,
            )
        }
        assertEquals("MINT_E", ex.mint)
    }

    // ─── clampSoldRawToEntry (lifetime raw invariant) ──────────────

    @Test fun clamp_no_op_when_no_ledger_registered() {
        val req = BigInteger.valueOf(1_000_000L)
        val out = DecimalIntegrityAuthority6405
            .clampSoldRawToEntry("MINT_F", 1_000L, req)
        // No ledger → advisory only; caller's wallet-cap still applies.
        assertEquals(req, out)
    }

    @Test fun clamp_allows_up_to_entry_raw() {
        DecimalIntegrityAuthority6405.recordEntryRaw("MINT_G", 42L, BigInteger.valueOf(100_000L))
        val out = DecimalIntegrityAuthority6405
            .clampSoldRawToEntry("MINT_G", 42L, BigInteger.valueOf(80_000L))
        assertEquals(BigInteger.valueOf(80_000L), out)
    }

    @Test fun clamp_reduces_over_sell_to_remaining_only() {
        DecimalIntegrityAuthority6405.recordEntryRaw("MINT_H", 7L, BigInteger.valueOf(100_000L))
        DecimalIntegrityAuthority6405.recordSoldRaw("MINT_H", 7L, BigInteger.valueOf(60_000L))
        // 60k already sold; request 80k more should clamp to remaining 40k.
        val out = DecimalIntegrityAuthority6405
            .clampSoldRawToEntry("MINT_H", 7L, BigInteger.valueOf(80_000L))
        assertEquals(BigInteger.valueOf(40_000L), out)
    }

    @Test fun clamp_returns_zero_after_full_exit() {
        DecimalIntegrityAuthority6405.recordEntryRaw("MINT_I", 3L, BigInteger.valueOf(50_000L))
        DecimalIntegrityAuthority6405.recordSoldRaw("MINT_I", 3L, BigInteger.valueOf(50_000L))
        val out = DecimalIntegrityAuthority6405
            .clampSoldRawToEntry("MINT_I", 3L, BigInteger.valueOf(1_000L))
        assertEquals(BigInteger.ZERO, out)
    }

    @Test fun different_generations_are_independent_lifetimes() {
        DecimalIntegrityAuthority6405.recordEntryRaw("MINT_J", 1L, BigInteger.valueOf(50_000L))
        DecimalIntegrityAuthority6405.recordSoldRaw("MINT_J", 1L, BigInteger.valueOf(50_000L))
        // Gen 1 exhausted; gen 2 is a fresh position.
        DecimalIntegrityAuthority6405.recordEntryRaw("MINT_J", 2L, BigInteger.valueOf(30_000L))
        val out = DecimalIntegrityAuthority6405
            .clampSoldRawToEntry("MINT_J", 2L, BigInteger.valueOf(10_000L))
        assertEquals(BigInteger.valueOf(10_000L), out)
    }

    @Test fun negative_requested_returns_zero() {
        val out = DecimalIntegrityAuthority6405
            .clampSoldRawToEntry("MINT_K", 1L, BigInteger.valueOf(-5L))
        assertEquals(BigInteger.ZERO, out)
    }

    @Test fun top_up_raises_entry_but_lower_writes_are_ignored() {
        DecimalIntegrityAuthority6405.recordEntryRaw("MINT_L", 9L, BigInteger.valueOf(100_000L))
        // Lower entryRaw is treated as a spurious re-stamp; ignored.
        DecimalIntegrityAuthority6405.recordEntryRaw("MINT_L", 9L, BigInteger.valueOf(50_000L))
        val remaining = DecimalIntegrityAuthority6405.remainingRaw("MINT_L", 9L)
        assertEquals(BigInteger.valueOf(100_000L), remaining)
        // Higher entryRaw (a top-up) DOES raise the ceiling.
        DecimalIntegrityAuthority6405.recordEntryRaw("MINT_L", 9L, BigInteger.valueOf(150_000L))
        assertEquals(BigInteger.valueOf(150_000L),
            DecimalIntegrityAuthority6405.remainingRaw("MINT_L", 9L))
    }

    // ─── PositionGenerationBridge6405 ─────────────────────────────

    @Test fun position_bridge_defaults_to_zero() {
        assertEquals(0L, PositionGenerationBridge6405.get("MINT_M"))
    }

    @Test fun position_bridge_roundtrip() {
        PositionGenerationBridge6405.set("MINT_N", 12345L)
        assertEquals(12345L, PositionGenerationBridge6405.get("MINT_N"))
        PositionGenerationBridge6405.clear("MINT_N")
        assertEquals(0L, PositionGenerationBridge6405.get("MINT_N"))
    }

    @Test fun position_bridge_ignores_non_positive_generation() {
        PositionGenerationBridge6405.set("MINT_O", 0L)
        assertEquals(0L, PositionGenerationBridge6405.get("MINT_O"))
        PositionGenerationBridge6405.set("MINT_O", -1L)
        assertEquals(0L, PositionGenerationBridge6405.get("MINT_O"))
    }

    // ─── snapshot / remaining diagnostics ─────────────────────────

    @Test fun snapshot_returns_null_when_ledger_absent() {
        assertNull(DecimalIntegrityAuthority6405.snapshot("MINT_P", 1L))
        assertNull(DecimalIntegrityAuthority6405.remainingRaw("MINT_P", 1L))
    }

    @Test fun snapshot_reports_entry_and_sold_totals() {
        DecimalIntegrityAuthority6405.recordEntryRaw("MINT_Q", 5L, BigInteger.valueOf(200_000L))
        DecimalIntegrityAuthority6405.recordSoldRaw("MINT_Q", 5L, BigInteger.valueOf(70_000L))
        DecimalIntegrityAuthority6405.recordSoldRaw("MINT_Q", 5L, BigInteger.valueOf(30_000L))
        val snap = DecimalIntegrityAuthority6405.snapshot("MINT_Q", 5L)
        assertTrue(snap != null)
        assertEquals(BigInteger.valueOf(200_000L), snap!!.first)
        assertEquals(BigInteger.valueOf(100_000L), snap.second)
    }
}
