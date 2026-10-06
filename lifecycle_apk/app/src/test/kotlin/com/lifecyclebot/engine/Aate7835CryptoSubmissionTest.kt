package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import com.lifecyclebot.engine.truth.AssetClass as EntryAssetClass
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class Aate7835CryptoSubmissionTest {
    private fun mode(paper: Boolean) {
        RuntimeModeAuthority.publishConfig(paper, true)
        RuntimeModeAuthority.publishUiMode(paper)
        RuntimeModeAuthority.publishExecutorMode(paper)
        RuntimeModeAuthority.publishPipelineMode(paper)
    }

    @After fun cleanup() { mode(true); CanonicalPositionAuthority6441.resetForTest() }

    @Before fun reset() {
        mode(true)
        FillLotLedger6504.setTestMemoryMode6641(true)
        CanonicalEntryAuthority6540.clearAllForTest()
        CanonicalPositionAuthority6441.resetForTest()
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(10.0)
    }

    private fun candidate(id: String) = CanonicalAssetEntryCandidate6551(
        assetId = id, symbol = id, assetClass = EntryAssetClass.CRYPTO_ALT,
        mode = "PAPER", direction = "LONG", requestedVenue = "CRYPTO",
        adapter = "test", source = "test", specialist = "CRYPTO", score = 80.0,
        confidence = 1.0, requestedSizeSol = 0.1, price = 2.0,
        liquidityUsd = 100_000.0, candidateVersion = 78350001L,
    )

    private fun counts() = CanonicalEntryAuthority6540.assetClassStats6567()
        .single { it.assetClass == EntryAssetClass.CRYPTO_ALT }

    @Test fun submitted_crypto_has_one_sealed_intent_and_no_wallet_debit() {
        val result = CanonicalEntryAuthority6551.submit(candidate("Crypto7835Positive"))
        assertTrue("$result", result is CanonicalAssetEntryResult6551.Allowed)
        val intent = (result as CanonicalAssetEntryResult6551.Allowed).intent
        assertTrue(intent.fdgAllowed)
        assertEquals("BUY", intent.fdgVerdict)
        assertEquals(result.resolvedSizeSol, intent.resolvedSize, 1e-9)
        assertEquals(1L, counts().submits)
        assertEquals(1L, counts().allows)
        assertEquals(1L, counts().intents)
        assertEquals(10.0, PaperAccountLedger6430.cashSol(), 1e-9)
        CanonicalEntryAuthority6551.markFailed(intent, "TEST_COMPLETE")
    }

    @Test fun missing_live_route_is_submitted_and_explicitly_refused_without_intent() {
        mode(false)
        // Satisfy the real live-risk precondition with canonical deployed test
        // equity. No wallet mock, paper debit, or kill-switch bypass is used.
        assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED,
            CanonicalPositionAuthority6441.openPosition(
                idempotencyKey = "equity7836", positionId = "equity7836", mint = "Crypto7836Equity",
                symbol = "EQUITY", lane = "CORE", runId = "test7836",
                entryCostSol = 1.0, openedQtyRaw = java.math.BigInteger.valueOf(1_000_000L),
                tokenDecimals = 6, feesSol = 0.0, paperMode = false,
                entryPriceUsd = 0.001, entryPriceSource = "TEST_7836",
            ))
        assertTrue(LiveRiskPolicy7807.liveEquitySol(WalletManager.cachedSolBalance()) > 0.0)
        val result = CanonicalEntryAuthority6551.submit(candidate("Crypto7835NoRoute")
            .copy(mode = "LIVE", routeAvailable = false, adapter = "DEFERRED_ROUTE"))
        assertEquals("LIVE_ROUTE_UNAVAILABLE", (result as CanonicalAssetEntryResult6551.Blocked).reason)
        assertEquals(1L, counts().submits)
        assertEquals(1L, counts().blocks)
        assertEquals(0L, counts().intents)
        assertEquals(10.0, PaperAccountLedger6430.cashSol(), 1e-9)
    }

    @Test fun producer_refusal_is_visible_without_pretending_submission_or_dispatch() {
        CanonicalEntryAuthority6540.markPreSubmitRefusal7835(EntryAssetClass.CRYPTO_ALT, "PRE_SUBMIT_PRICE_ZERO")
        assertEquals(0L, counts().submits)
        assertEquals(0L, counts().dispatchRejects)
        val report = CanonicalEntryAuthority6540.producerLivenessReport6569()
        assertTrue(report.contains("preSubmitRefused=1"))
        assertTrue(report.contains("PRE_SUBMIT_PRICE_ZERO=1"))
    }
    @Test fun live_candidate_cannot_override_paper_runtime_even_without_a_route() {
        val result = CanonicalEntryAuthority6551.submit(candidate("Crypto7836ModeMismatch")
            .copy(mode = "LIVE", routeAvailable = false, adapter = "DEFERRED_ROUTE"))
        assertTrue(result is CanonicalAssetEntryResult6551.Blocked)
        assertEquals("LIVE_ENTRY_WHILE_RUNTIME_PAPER_7835", (result as CanonicalAssetEntryResult6551.Blocked).reason)
        assertEquals(1L, counts().submits)
        assertEquals(1L, counts().blocks)
        assertEquals(0L, counts().intents)
        assertEquals(10.0, PaperAccountLedger6430.cashSol(), 1e-9)
    }

    @Test fun missing_live_equity_stays_a_safety_refusal_before_route_admission() {
        mode(false)
        val result = CanonicalEntryAuthority6551.submit(candidate("Crypto7836NoEquity")
            .copy(mode = "LIVE", routeAvailable = false, adapter = "DEFERRED_ROUTE"))
        assertEquals("KILL_SWITCH_EQUITY_UNAVAILABLE_7835", (result as CanonicalAssetEntryResult6551.Blocked).reason)
        assertEquals(0L, counts().intents)
        assertEquals(10.0, PaperAccountLedger6430.cashSol(), 1e-9)
    }

}
