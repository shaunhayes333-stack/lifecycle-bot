package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6581 — 6580 forensic P0 subset:
 *
 * P0-1  Canonical SELL fallback (PAPER_CLOSE_FALLBACK_6572=51/52) now routes
 *       through CanonicalPaperTransaction6486.refund() so the canonical
 *       journal / FinalizedBus / lane / learning consumers all see the
 *       terminal event.
 *
 * P0-2  MINT_ROUTE observation-authoritative: valid DexScreener prices with
 *       MINT_ROUTE:* pool identity are no longer rejected pre-V3 (was
 *       blocking 1,365 valid intake candidates in 6580).
 *
 * P0-3  CryptoAlt actionable → candidate handoff: producer now stamps
 *       CANDIDATE stage just before executeSignal so the cross-asset
 *       funnel shows candidate == dispatched_after_dedup (was 0 in 6580).
 */
class BuildRepair6581CoverageTest {

    private val stockSrc = File("src/main/kotlin/com/lifecyclebot/perps/TokenizedStockTrader.kt").readText()
    private val markGateSrc = File("src/main/kotlin/com/lifecyclebot/engine/truth/MarkAuthorityIntegrityGate6496.kt").readText()
    private val cryptoAltSrc = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()

    @Test
    fun p0_1_canonical_sell_fallback_uses_canonical_refund() {
        assertTrue(
            "Uncloseable-market path must route through CanonicalPaperTransaction6486.refund " +
                "(the pre-6581 PaperAccountLedger6430.onSell fallback bypassed the canonical journal)",
            stockSrc.contains("CanonicalPaperTransaction6486.refund(") &&
                stockSrc.contains("PAPER_CLOSE_CANONICAL_REFUND_6581")
        )
        // The pre-6581 direct-onSell fallback lived under PAPER_CLOSE_FALLBACK_6572;
        // V5.0.6581 replaced it with the canonical refund path. V5.0.6616
        // §JOURNAL_BALANCE_HERO_SINGLE_AUTHORITY_REPAIR now goes further:
        // when the canonical refund ALSO refuses, we must NOT mutate the
        // ledger via a fallback onSell — the journal is the sole economic
        // authority. The 6581 PAPER_CLOSE_UNJOURNALED_LEAK label was
        // superseded by PAPER_CLOSE_JOURNAL_REFUSED_NO_LEDGER_MUTATION_6616
        // and the historical mention lives only inside the 6616 rationale
        // comment. Accept either token so this earlier acceptance test
        // stays valid across the 6616 patch.
        assertTrue(
            "The 6581 divergence signal must still be counted (either as the historical " +
                "PAPER_CLOSE_UNJOURNALED_LEAK_6581 label OR as the 6616 successor " +
                "PAPER_CLOSE_JOURNAL_REFUSED_NO_LEDGER_MUTATION_6616 that removed the ledger " +
                "bypass entirely)",
            stockSrc.contains("PAPER_CLOSE_UNJOURNALED_LEAK_6581") ||
                stockSrc.contains("PAPER_CLOSE_JOURNAL_REFUSED_NO_LEDGER_MUTATION_6616")
        )
    }

    @Test
    fun p0_2_mint_route_observation_authoritative() {
        assertTrue(
            "MarkAuthorityIntegrityGate6496.isObservationAuthoritative6570 must accept " +
                "MINT_ROUTE:* pool identities (execution boundary still rejects them)",
            markGateSrc.contains("V5.0.6581 §P0-2 — OBSERVATION ACCEPTS MINT_ROUTE POOL PROVENANCE")
        )
    }

    @Test
    fun p0_3_crypto_alt_candidate_stamp() {
        assertTrue(
            "CryptoAltTrader must stamp CANDIDATE stage before executeSignal so the funnel " +
                "reports candidate == actionable_after_dedup (was 0 in 6580)",
            cryptoAltSrc.contains("markProducerStage6569(\n                        com.lifecyclebot.engine.truth.AssetClass.CRYPTO_ALT, \"CANDIDATE\"")
                || cryptoAltSrc.contains("AssetClass.CRYPTO_ALT, \"CANDIDATE\"")
        )
    }
}
