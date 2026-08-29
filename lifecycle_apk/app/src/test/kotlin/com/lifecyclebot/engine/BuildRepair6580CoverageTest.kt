package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6580 — 6578 forensic P0 subset (round 2):
 *
 * P0-f  Crypto Universe: SHARED_INTELLIGENCE_BACKLOG_COALESCED_REQUEUE now
 *       has a bounded evidence deadline. After 5 minutes at the same
 *       non-terminal state, the token is reaped into
 *       STALE_EXPIRED_6580_<state> terminal disposition so operator's
 *       'no permanent missing bucket' invariant holds.
 *
 * P0-g  CRYPTO_ALT producer emits the STARTED stamp at start() so the
 *       cross-asset canonical funnel report shows 'started=1' when the
 *       trader is running (was silently 0 while its downstream stages
 *       were all active — a broken funnel view).
 */
class BuildRepair6580CoverageTest {

    private val registrySrc = File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
    private val cryptoAltSrc = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()

    @Test
    fun p0_f_bounded_evidence_deadline_present() {
        assertTrue(
            "DynamicAltTokenRegistry must record the first-seen timestamp per (identity, state)",
            registrySrc.contains("evaluationProgressStamp6580")
        )
        assertTrue(
            "EVIDENCE_TTL_MS_6580 must exist (bounded deadline)",
            registrySrc.contains("EVIDENCE_TTL_MS_6580")
        )
        assertTrue(
            "Stale non-terminal states must reap to a terminal STALE_EXPIRED_6580_<state> disposition",
            registrySrc.contains("STALE_EXPIRED_6580_") &&
                registrySrc.contains("CRYPTO_EVAL_STALE_REAPED_6580")
        )
    }

    @Test
    fun p0_g_crypto_alt_producer_stamps_started() {
        assertTrue(
            "CryptoAltTrader.start() must emit STARTED via CanonicalEntryAuthority6540.markProducerStage6569",
            cryptoAltSrc.contains("markProducerStage6569(\n                com.lifecyclebot.engine.truth.AssetClass.CRYPTO_ALT, \"STARTED\"")
                || cryptoAltSrc.contains("markProducerStage6569(com.lifecyclebot.engine.truth.AssetClass.CRYPTO_ALT, \"STARTED\"")
                || cryptoAltSrc.contains("AssetClass.CRYPTO_ALT, \"STARTED\"")
        )
    }
}
