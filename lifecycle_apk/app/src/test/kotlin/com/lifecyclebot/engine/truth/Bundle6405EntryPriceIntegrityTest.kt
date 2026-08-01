package com.lifecyclebot.engine.truth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Bundle6405EntryPriceIntegrityTest {

    @Test fun derives_trusted_entry_from_costSol_and_qty_with_known_sol_usd() {
        // 0.0254 SOL / 702 120 tokens × $180/SOL ≈ $6.51e-6 per token
        val e = EntryPriceIntegrityAuthority6405
            .deriveTrustedEntryUsd(costSol = 0.0254, qtyUi = 702_120.0, knownSolUsd = 180.0)
        assertNotNull(e)
        assertTrue(e!!.usdPerToken in 6.0e-6..7.0e-6)
        assertEquals("LIVE_PROOF_COST_BASIS", e.source)
    }

    @Test fun falls_back_to_cold_sol_usd_when_feed_zero() {
        val e = EntryPriceIntegrityAuthority6405
            .deriveTrustedEntryUsd(costSol = 0.0254, qtyUi = 702_120.0, knownSolUsd = 0.0)
        assertNotNull(e)
        assertEquals("LIVE_PROOF_COST_BASIS_SOL_USD_FALLBACK", e!!.source)
        assertEquals(EntryPriceIntegrityAuthority6405.SOL_USD_COLD_FALLBACK, e.solUsdUsed, 0.0)
        assertTrue(e.usdPerToken > 0.0)
    }

    @Test fun returns_null_when_costSol_or_qty_missing() {
        assertNull(EntryPriceIntegrityAuthority6405
            .deriveTrustedEntryUsd(costSol = 0.0, qtyUi = 700_000.0, knownSolUsd = 180.0))
        assertNull(EntryPriceIntegrityAuthority6405
            .deriveTrustedEntryUsd(costSol = 0.02, qtyUi = 0.0, knownSolUsd = 180.0))
        assertNull(EntryPriceIntegrityAuthority6405
            .deriveTrustedEntryUsd(costSol = Double.NaN, qtyUi = 1.0, knownSolUsd = 180.0))
    }

    @Test fun detects_priceNative_vs_priceUsd_wire_cross() {
        // Stamped = SOL-per-token (priceNative), Trusted = USD-per-token.
        // Ratio ≈ 180× (1 SOL ≈ $180), well over the 100× threshold.
        val stampedNative = 2.65e-9
        val trustedUsd = 6.5e-6
        assertTrue(
            EntryPriceIntegrityAuthority6405.detectBasisDivergence(stampedNative, trustedUsd),
        )
    }

    @Test fun no_divergence_when_ratios_close() {
        assertFalse(
            EntryPriceIntegrityAuthority6405.detectBasisDivergence(6.5e-6, 6.6e-6),
        )
    }

    @Test fun no_divergence_when_stamped_zero() {
        assertFalse(
            EntryPriceIntegrityAuthority6405.detectBasisDivergence(0.0, 6.5e-6),
        )
    }

    @Test fun runner_untrusted_when_source_is_unknown_label() {
        val ok = EntryPriceIntegrityAuthority6405.isTrustworthyForRunnerExit(
            mint = "M", symbol = "SYM",
            stampedEntryUsd = 6.5e-6,
            entrySource = "SCANNER_FEED_PRICE",
            costSol = 0.0254, qtyUi = 702_120.0, knownSolUsd = 180.0,
        )
        assertFalse(ok)
    }

    @Test fun runner_untrusted_on_basis_divergence() {
        val ok = EntryPriceIntegrityAuthority6405.isTrustworthyForRunnerExit(
            mint = "M", symbol = "SYM",
            stampedEntryUsd = 2.65e-9, // priceNative mistake
            entrySource = "LIVE_PROOF_COST_BASIS",
            costSol = 0.0254, qtyUi = 702_120.0, knownSolUsd = 180.0,
        )
        assertFalse(ok)
    }

    @Test fun runner_trusted_when_source_ok_and_no_divergence() {
        val ok = EntryPriceIntegrityAuthority6405.isTrustworthyForRunnerExit(
            mint = "M", symbol = "SYM",
            stampedEntryUsd = 6.5e-6,
            entrySource = "LIVE_PROOF_COST_BASIS",
            costSol = 0.0254, qtyUi = 702_120.0, knownSolUsd = 180.0,
        )
        assertTrue(ok)
    }

    @Test fun runner_untrusted_when_zero_entry() {
        val ok = EntryPriceIntegrityAuthority6405.isTrustworthyForRunnerExit(
            mint = "M", symbol = "SYM",
            stampedEntryUsd = 0.0,
            entrySource = "LIVE_PROOF_COST_BASIS",
            costSol = 0.0254, qtyUi = 702_120.0, knownSolUsd = 180.0,
        )
        assertFalse(ok)
    }
}
