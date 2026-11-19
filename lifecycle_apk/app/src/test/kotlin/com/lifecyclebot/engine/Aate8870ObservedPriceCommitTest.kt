package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.ObservedPriceCommit8870
import com.lifecyclebot.engine.truth.QuoteFreshnessGuard6452
import com.lifecyclebot.engine.truth.TokenFacts
import org.junit.Assert.*
import org.junit.Test

class Aate8870ObservedPriceCommitTest {
    @Test fun rejectedOldRescueCannotOverwriteNewerCanonicalQuoteProvenance() {
        val now = System.currentTimeMillis()
        val ts = TokenState(mint = "ObservedCommit8870pump")
        assertTrue(TokenFacts.writePrice(ts, 0.002, "PUMP_CURVE_RPC", TokenFacts.Rank.CHAIN, now - 1_000L, now))
        QuoteFreshnessGuard6452.note(ts.mint, 0.002, QuoteFreshnessGuard6452.Provenance.WS_LIVE, 1_000L)
        val quote = QuoteFreshnessGuard6452.lastPrice(ts.mint)
        assertFalse(ObservedPriceCommit8870.write(ts, 0.001, "DEFILLAMA", now - 50_000L, 1, now))
        assertEquals(quote, QuoteFreshnessGuard6452.lastPrice(ts.mint))
        assertEquals(0.002, ts.lastPrice, 0.0)
        assertEquals(now - 1_000L, ts.lastPriceUpdate)
    }

    @Test fun acceptedRescueKeepsObservationAgeInsteadOfDeliveryAge() {
        val now = System.currentTimeMillis()
        val ts = TokenState(mint = "ObservedCommitNew8870")
        assertTrue(ObservedPriceCommit8870.write(ts, 0.001, "DEFILLAMA", now - 12_000L, 2, now))
        assertEquals(now - 12_000L, ts.lastPriceUpdate)
        val quote = QuoteFreshnessGuard6452.lastPrice(ts.mint)!!
        assertEquals(0.001, quote.priceUsd, 0.0)
        assertTrue(quote.stampedAtMs in (now - 12_000L)..(System.currentTimeMillis() - 11_900L))
        assertTrue(quote.corroborated7188)
        assertFalse(ObservedPriceCommit8870.write(ts, 0.003, "UNKNOWN", 0L, 1, now))
        assertFalse(ObservedPriceCommit8870.write(ts, 0.003, "FUTURE", now + 1L, 1, now))
        assertEquals(quote, QuoteFreshnessGuard6452.lastPrice(ts.mint))
    }
}
