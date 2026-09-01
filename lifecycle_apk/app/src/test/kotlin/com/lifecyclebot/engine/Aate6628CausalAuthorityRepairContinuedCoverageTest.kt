package com.lifecyclebot.engine

import org.junit.After
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * V5.0.6628 §CAUSAL_AUTHORITY_REPAIR_CONTINUED coverage.
 *
 *   §5 Journal atomicity — the paperSell path writes the TradeHistoryStore
 *      row synchronously before PaperTerminalProjectionConvergence6509
 *      fires markClosed, so PAPER_CLOSE_NO_JOURNAL_ROW_6623 stops racing.
 *   §4 Mark split — CanonicalPriceMarkRegistry6522 now exposes
 *      resolveObservationFromSourceEvidence6628 which publishes an
 *      OBSERVATION_SCORING mark without requiring liquidity, so a valid
 *      price + fresh identity survives an incomplete DexScreener
 *      liquidity field. Executable/route boundary remains strict.
 *
 * Items 6 and 8 are shipped in a follow-up commit (V5.0.6629+).
 */
class Aate6628CausalAuthorityRepairContinuedCoverageTest {

    @After
    fun tearDown() {
        try { com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522.resetForTest() } catch (_: Throwable) {}
    }

    @Test
    fun aate6628_observation_only_resolver_publishes_without_liquidity() {
        com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522.resetForTest()
        val mint = "aate6628-obs-mintA"
        val now = System.currentTimeMillis()
        val result = com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522
            .resolveObservationFromSourceEvidence6628(
                mint = mint,
                observedBaseMint = mint,
                pairOrPool = "aate6628-pool-A",
                quoteMint = "USD",
                source = "DEXSCREENER_PAIR_POLL",
                priceUsd = 3.162E-6,
                evidenceTimestampMs = now,
                nowMs = now,
            )
        assertTrue("V5.0.6628 §4: valid observation must be admitted regardless of liquidity",
            result.promoted)
        assertEquals("V5.0.6628 §4: reason must be OBSERVATION_ADMITTED_6628",
            "OBSERVATION_ADMITTED_6628", result.reason)
        val obs = com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522
            .get(mint, com.lifecyclebot.engine.truth.CanonicalMarkPurpose6570.OBSERVATION_SCORING)
        assertTrue("V5.0.6628 §4: OBSERVATION_SCORING mark must be published for the mint",
            obs != null && obs.priceUsd.value.toDouble() > 0.0)
    }

    @Test
    fun aate6628_observation_resolver_rejects_stale_evidence() {
        com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522.resetForTest()
        val mint = "aate6628-obs-mintB"
        val now = System.currentTimeMillis()
        val stale = com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522
            .resolveObservationFromSourceEvidence6628(
                mint = mint,
                observedBaseMint = mint,
                pairOrPool = "aate6628-pool-B",
                quoteMint = "USD",
                source = "DEXSCREENER_PAIR_POLL",
                priceUsd = 3.162E-6,
                evidenceTimestampMs = now - 400_000L,  // >300s stale
                nowMs = now,
            )
        assertTrue("V5.0.6628 §4: stale evidence must be rejected",
            !stale.promoted && stale.reason == "SOURCE_EVIDENCE_STALE")
    }

    @Test
    fun aate6628_observation_resolver_rejects_invalid_price() {
        com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522.resetForTest()
        val mint = "aate6628-obs-mintC"
        val now = System.currentTimeMillis()
        val bad = com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522
            .resolveObservationFromSourceEvidence6628(
                mint = mint,
                observedBaseMint = mint,
                pairOrPool = "aate6628-pool-C",
                quoteMint = "USD",
                source = "DEXSCREENER_PAIR_POLL",
                priceUsd = 0.0,   // invalid
                evidenceTimestampMs = now,
                nowMs = now,
            )
        assertTrue("V5.0.6628 §4: non-positive price must be rejected",
            !bad.promoted && bad.reason == "SOURCE_PRICE_INVALID")
    }

    @Test
    fun aate6628_observation_resolver_rejects_identity_mismatch() {
        com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522.resetForTest()
        val now = System.currentTimeMillis()
        val bad = com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522
            .resolveObservationFromSourceEvidence6628(
                mint = "aate6628-obs-mintD",
                observedBaseMint = "aate6628-obs-mintZ",  // different
                pairOrPool = "aate6628-pool-D",
                quoteMint = "USD",
                source = "DEXSCREENER_PAIR_POLL",
                priceUsd = 3.162E-6,
                evidenceTimestampMs = now,
                nowMs = now,
            )
        assertTrue("V5.0.6628 §4: base-mint identity mismatch must be rejected",
            !bad.promoted && bad.reason == "SOURCE_BASE_IDENTITY_MISMATCH")
    }

    @Test
    fun aate6628_paper_sell_journal_write_is_synchronous_source_authority() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/Executor.kt"
        ).readText()
        assertTrue("V5.0.6628 §5: paperSell must journal synchronously before markClosed",
            src.contains("PAPER_SELL_JOURNAL_SYNC_APPENDED_6628") &&
                src.contains("V5.0.6628 §5 CANONICAL_CLOSE_JOURNAL_ATOMICITY"))
    }

    @Test
    fun aate6628_mark_split_observation_fallback_wired_source_authority() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/BotService.kt"
        ).readText()
        assertTrue("V5.0.6628 §4: pre-V3 mark path must fall back to observation on SOURCE_LIQUIDITY_INVALID",
            src.contains("CANONICAL_MARK_OBSERVATION_FALLBACK_ADMITTED_6628") &&
                src.contains("resolveObservationFromSourceEvidence6628"))
    }
}
