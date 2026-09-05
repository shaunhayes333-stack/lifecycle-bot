package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522
import com.lifecyclebot.engine.truth.MarketDataProvenance6471
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6674 — regression locks for the two source contradictions surfaced by
 * the operator's 5.0.6673 runtime dump.
 */
class Aate6674CausalAndProvenanceRepairTest {

    @Test
    fun `specialist canonical sizing propagates one causal event id into order resolver`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalSizingBridge6532.kt").readText()
        assertTrue(src.contains("V5.0.6674 §SPECIALIST_CAUSAL_SIZING_CONTINUITY"))
        assertTrue(src.contains("resolvedCausalEventId6674"))
        assertTrue(src.contains("activeExecutionIntent6519"))
        assertTrue(src.contains("causalEventId = resolvedCausalEventId6674"))
        assertTrue(src.contains("SPECIALIST_CAUSAL_SIZING_ID_PROPAGATED_6674"))
    }

    @Test
    fun `unproven mint-route remains non-authoritative`() {
        MarketDataProvenance6471.resetForTest()
        CanonicalPriceMarkRegistry6522.resetForTest()
        val mint = "Mint6674Unproven111111111111111111111111111"
        val p = MarketDataProvenance6471.classify(
            price = 0.001234,
            mcap = 100_000.0,
            liquidity = 12_345.0,
            source = "DEXSCREENER_PAIR_POLL",
            poolAddress = "MINT_ROUTE:${mint.take(12)}",
            identity = mint,
        )
        assertEquals(MarketDataProvenance6471.Provenance.NON_AUTHORITATIVE_SENTINEL, p)
    }

    @Test
    fun `canonically promoted executable mint-route admits exact immutable tuple`() {
        MarketDataProvenance6471.resetForTest()
        CanonicalPriceMarkRegistry6522.resetForTest()
        val mint = "Mint6674Proven11111111111111111111111111111"
        val price = 0.001234567
        val liq = 12_345.67
        val now = System.currentTimeMillis()

        val promoted = CanonicalPriceMarkRegistry6522.resolveExecutableFromSourceEvidence6616(
            mint = mint,
            observedBaseMint = mint,
            pairOrPool = "",
            quoteMint = "USD",
            source = "DEXSCREENER_PAIR_POLL",
            priceUsd = price,
            liquidityUsd = liq,
            evidenceTimestampMs = now,
            nowMs = now,
        )
        assertTrue(promoted.promoted)
        assertNotNull(promoted.mark)

        val p = MarketDataProvenance6471.classify(
            price = price,
            mcap = 123_456.0,
            liquidity = liq,
            source = "DEXSCREENER_PAIR_POLL",
            poolAddress = "MINT_ROUTE:${mint.take(12)}",
            identity = mint,
        )
        assertEquals(MarketDataProvenance6471.Provenance.AUTHORITATIVE, p)
    }

    @Test
    fun `canonical mint-route proof does not admit mismatched price or source`() {
        MarketDataProvenance6471.resetForTest()
        CanonicalPriceMarkRegistry6522.resetForTest()
        val mint = "Mint6674Mismatch111111111111111111111111111"
        val price = 0.002345678
        val liq = 20_000.0
        val now = System.currentTimeMillis()
        assertTrue(CanonicalPriceMarkRegistry6522.resolveExecutableFromSourceEvidence6616(
            mint, mint, "", "USD", "DEXSCREENER_PAIR_POLL", price, liq, now, now,
        ).promoted)

        val priceMismatch = MarketDataProvenance6471.classify(
            price * 1.01, 200_000.0, liq, "DEXSCREENER_PAIR_POLL",
            "MINT_ROUTE:${mint.take(12)}", mint,
        )
        assertEquals(MarketDataProvenance6471.Provenance.NON_AUTHORITATIVE_SENTINEL, priceMismatch)

        val sourceMismatch = MarketDataProvenance6471.classify(
            price, 200_000.0, liq, "JUPITER_PRICE",
            "MINT_ROUTE:${mint.take(12)}", mint,
        )
        assertEquals(MarketDataProvenance6471.Provenance.NON_AUTHORITATIVE_SENTINEL, sourceMismatch)
    }
}
