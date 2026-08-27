package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import com.lifecyclebot.engine.truth.AssetClass as TruthAssetClass
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CanonicalEntryAuthority6551Test {
    @Before fun reset() {
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
    ) = CanonicalAssetEntryCandidate6551(
        assetId = id, symbol = id, assetClass = cls, mode = "PAPER", direction = "LONG",
        requestedVenue = cls.tag, adapter = "test", source = "test", specialist = cls.tag,
        score = score, confidence = confidence, requestedSizeSol = size, price = 1.0,
        candidateVersion = 6551L,
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
}
