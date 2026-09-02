package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import com.lifecyclebot.engine.truth.AssetClass as TruthAssetClass
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CanonicalEntryAuthority6551Test {
    @Before fun reset() {
        FillLotLedger6504.setTestMemoryMode6641(true)
        CanonicalEntryAuthority6540.clearAllForTest()
        CanonicalPositionAuthority6441.resetForTest()
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(10.0)
    }

    private fun candidate(
        id: String = "EURUSD",
        cls: TruthAssetClass = TruthAssetClass.FOREX,
        score: Double = 50.0,
        confidence: Double = 1.0,
        size: Double = 0.1,
        direction: String = "LONG",
        version: Long = 6551L,
    ) = CanonicalAssetEntryCandidate6551(
        assetId = id, symbol = id, assetClass = cls, mode = "PAPER", direction = direction,
        requestedVenue = cls.tag, adapter = "test", source = "test", specialist = cls.tag,
        score = score, confidence = confidence, requestedSizeSol = size, price = 1.0,
        candidateVersion = version,
    )

    @Test fun mechanically_valid_paper_candidate_seals_buy_intent_before_open() {
        val r = CanonicalEntryAuthority6551.submit(candidate())
        assertTrue(r is CanonicalAssetEntryResult6551.Allowed)
        val intent = (r as CanonicalAssetEntryResult6551.Allowed).intent
        assertTrue(intent.fdgAllowed)
        assertEquals("BUY", intent.authoritativeSignal)
        assertEquals(0.0, 10.0 - PaperAccountLedger6430.cashSol(), 0.000001)
        val opened = CanonicalPaperTransaction6486.open("p1", "EURUSD", "EURUSD", "FOREX", "test", intent.resolvedSize, qtyRaw = BigInteger.ONE, decimals = 0, quantityScale = 0, assetClass = TruthAssetClass.FOREX, entryPriceUsd = 1.0)
        assertTrue(opened.applied)
        assertEquals(1, CanonicalPositionAuthority6441.openPositions().size)
    }

    @Test fun low_score_mechanical_paper_candidate_becomes_probe() {
        val r = CanonicalEntryAuthority6551.submit(candidate(id = "XAUUSD", cls = TruthAssetClass.METAL, score = 20.0))
        assertTrue(r is CanonicalAssetEntryResult6551.Probe)
    }

    @Test fun missing_intent_rejects_without_cash_or_position_mutation() {
        val r = CanonicalPaperTransaction6486.open("p2", "GBPJPY", "GBPJPY", "FOREX", "test", 0.1, qtyRaw = BigInteger.ONE, decimals = 0, quantityScale = 0, assetClass = TruthAssetClass.FOREX, entryPriceUsd = 1.0)
        assertFalse(r.applied)
        assertEquals("MISSING_CANONICAL_EXECUTION_INTENT", r.reason)
        assertEquals(0.0, 10.0 - PaperAccountLedger6430.cashSol(), 0.000001)
        assertTrue(CanonicalPositionAuthority6441.openPositions().isEmpty())
    }

    @Test fun authoritative_buy_survives_diagnostic_unknown() {
        val r = CanonicalEntryAuthority6551.submit(candidate(id = "AAPL", cls = TruthAssetClass.STOCK)) as CanonicalAssetEntryResult6551.Allowed
        assertEquals("BUY", r.intent.signal)
        assertEquals("UNKNOWN", r.intent.diagnosticSignal)
        assertFalse(ExecutableOpenGate.mutableSignalCanVeto6519(r.intent, "UNKNOWN"))
    }

    @Test fun duplicate_candidate_cannot_seal_two_intents() {
        assertTrue(CanonicalEntryAuthority6551.submit(candidate(id = "BTC", cls = TruthAssetClass.CRYPTO_ALT)) is CanonicalAssetEntryResult6551.Allowed)
        assertTrue(CanonicalEntryAuthority6551.submit(candidate(id = "BTC", cls = TruthAssetClass.CRYPTO_ALT)) is CanonicalAssetEntryResult6551.Blocked)
    }

    @Test fun every_non_solana_paper_universe_can_seal_and_debit_exact_size() {
        val classes = listOf(
            TruthAssetClass.CRYPTO_ALT to "ETH-6565",
            TruthAssetClass.PERPS to "BTC-PERP-6565",
            TruthAssetClass.FOREX to "EURUSD-6565",
            TruthAssetClass.METAL to "XAUUSD-6565",
            TruthAssetClass.COMMODITY to "WTI-6565",
        )
        classes.forEachIndexed { i, (cls, id) ->
            val admitted = CanonicalEntryAuthority6551.submit(
                candidate(id = id, cls = cls, size = 0.1, version = 656500L + i)
            )
            assertTrue("$cls must receive canonical admission", admitted is CanonicalAssetEntryResult6551.Allowed)
            val intent = (admitted as CanonicalAssetEntryResult6551.Allowed).intent
            val opened = CanonicalPaperTransaction6486.open(
                positionId = "p6565-$i", mint = id, symbol = id, lane = cls.tag, source = "test6565",
                costSol = intent.resolvedSize, qtyRaw = BigInteger.ONE, decimals = 0, quantityScale = 0,
                assetClass = cls, entryPriceUsd = 1.0, executionIntent = intent,
            )
            assertTrue("$cls canonical debit/open failed: ${opened.reason}", opened.applied)
        }
        assertEquals(5, CanonicalPositionAuthority6441.openPositions().size)
        assertEquals(9.5, PaperAccountLedger6430.cashSol(), 1e-9)
    }

    @Test fun paper_short_is_allowed_but_exact_size_conflict_is_rejected() {
        val id = "GBPJPY-SHORT-6565"
        val short = CanonicalEntryAuthority6551.submit(
            candidate(id = id, cls = TruthAssetClass.FOREX, direction = "SHORT", version = 656599L)
        )
        assertTrue(short is CanonicalAssetEntryResult6551.Allowed)
        val intent = (short as CanonicalAssetEntryResult6551.Allowed).intent
        assertEquals("SHORT", intent.direction)
        val mismatch = CanonicalPaperTransaction6486.open(
            "p-short-6565", id, id, "FOREX", "test", intent.resolvedSize + 0.01,
            qtyRaw = BigInteger.ONE, decimals = 0, quantityScale = 0,
            assetClass = TruthAssetClass.FOREX, entryPriceUsd = 1.0, executionIntent = intent,
        )
        assertFalse(mismatch.applied)
        assertEquals("CANONICAL_EXECUTION_INTENT_MISMATCH", mismatch.reason)
        assertEquals(10.0, PaperAccountLedger6430.cashSol(), 1e-9)
    }


    @Test fun crypto_alt_identity_survives_intent_dispatch_and_conservation_6569() {
        val id = "SOL-ALT-6569-${System.nanoTime()}"
        val admitted = CanonicalEntryAuthority6551.submit(candidate(id=id, cls=TruthAssetClass.CRYPTO_ALT, version=6569001L))
        assertTrue(admitted is CanonicalAssetEntryResult6551.Allowed)
        val intent = (admitted as CanonicalAssetEntryResult6551.Allowed).intent
        assertEquals(TruthAssetClass.CRYPTO_ALT.tag, intent.assetClassTag)
        var row = CanonicalEntryAuthority6540.assetClassStats6567().first { it.assetClass == TruthAssetClass.CRYPTO_ALT }
        assertEquals(1L, row.intents)
        assertEquals(1L, row.pending)
        CanonicalEntryAuthority6551.markDispatch(intent)
        row = CanonicalEntryAuthority6540.assetClassStats6567().first { it.assetClass == TruthAssetClass.CRYPTO_ALT }
        assertEquals(1L, row.dispatches)
        assertEquals(0L, row.pending)
        assertEquals(0L, row.intents-row.dispatches-row.dispatchRejects-row.pending)
        assertEquals(0L, CanonicalEntryAuthority6540.assetClassStats6567().first { it.assetClass == TruthAssetClass.SOLANA_TOKEN }.dispatches)
        CanonicalEntryAuthority6551.markFailed(intent, "test_cleanup")
    }

    @Test fun enabled_running_empty_producer_reports_three_window_liveness_fault_context_6569() {
        repeat(3) { CanonicalEntryAuthority6540.completeProducerWindow6569(TruthAssetClass.STOCK, true, true, "SOURCE_OK_NO_SIGNAL") }
        val report = CanonicalEntryAuthority6540.producerLivenessReport6569()
        assertTrue(report.contains("STOCK:"))
        assertTrue(report.contains("zeroCandidateWindows=3"))
        assertTrue(report.contains("SOURCE_OK_NO_SIGNAL"))
    }


    @Test fun leveraged_return_with_zero_realized_settles_but_cannot_train_6569() {
        val id="TRUMP-LEV-6569-${System.nanoTime()}"; val pid="p-lev-6569-${System.nanoTime()}"
        val admitted=CanonicalEntryAuthority6551.submit(candidate(id=id, cls=TruthAssetClass.CRYPTO_ALT, size=0.5, version=6569002L)) as CanonicalAssetEntryResult6551.Allowed
        val opened=CanonicalPaperTransaction6486.open(pid,id,id,"CRYPTO_LEV","test",admitted.intent.resolvedSize,qtyRaw=BigInteger.ONE,decimals=0,quantityScale=0,assetClass=TruthAssetClass.CRYPTO_ALT,entryPriceUsd=2.76,executionIntent=admitted.intent)
        assertTrue(opened.applied)
        val zeroRealized=CanonicalPaperTransaction6486.close(positionId=pid,mint=id,symbol=id,grossProceedsSol=admitted.intent.resolvedSize,exitReason="HARD_TP",terminalSequence=1L,expectedRealizedPnlSol6569=12.5,leveragedReturnPct6569=2500.0)
        assertTrue(zeroRealized.applied)
        assertFalse(PaperLearningEligibility6519.decision(pid,id).eligible)
        assertFalse(CanonicalPerformanceFilter6395.isCanonicalEligible(pid))
    }

}
