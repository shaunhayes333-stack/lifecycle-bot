package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CandidateMarkFresh8094
import com.lifecyclebot.engine.truth.TokenFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A fresh reserve read cannot be demoted to an aggregator and held behind its own older chain observation. */
class Aate8870CandidateChainRankTest {
    private fun state(now: Long): TokenState = TokenState(mint = "11111111111111111111111111111111pump").also {
        assertTrue(TokenFacts.writePrice(it, 0.001, "PUMP_CURVE_RPC", TokenFacts.Rank.CHAIN, now - 20_000L, now))
        assertTrue(TokenFacts.writeSnapshot(it, TokenFacts.Field.MCAP_USD, 1_000_000.0,
            "MARK_RESCALE_8094", TokenFacts.Rank.AGGREGATE, now - 20_000L, now))
    }

    @Test fun newChainReadReplacesOlderChainPriceAndMovesItsCap() {
        val now = System.currentTimeMillis()
        val ts = state(now)
        assertTrue(CandidateMarkFresh8094.applyMark8870(ts, 0.0011, "PUMP_CURVE_RPC_KEYLESS_8359", TokenFacts.Rank.CHAIN, now))
        assertEquals(0.0011, ts.lastPrice, 0.0)
        assertEquals(now, ts.lastPriceUpdate)
        assertEquals(TokenFacts.Rank.CHAIN, TokenFacts.priceFact(ts)!!.rank)
        assertEquals(1_100_000.0, ts.lastMcap, 1e-6)
    }

    @Test fun aggregateCannotReplaceRecentChainReadOrRescaleRejectedPrice() {
        val now = System.currentTimeMillis()
        val ts = state(now)
        assertFalse(CandidateMarkFresh8094.applyMark8870(ts, 0.0011,
            CandidateMarkFresh8094.DEX_LANE_SOURCE_8459, TokenFacts.Rank.PROVIDER, now))
        assertEquals(0.001, ts.lastPrice, 0.0)
        assertEquals(now - 20_000L, ts.lastPriceUpdate)
        assertEquals(1_000_000.0, ts.lastMcap, 0.0)
    }

    @Test fun rejectedLowerRankOfferDoesNotClaimRefreshOrChangeCap() {
        val now = System.currentTimeMillis()
        val ts = state(now)
        // Reproduces the old wrong rank, including a truthful source label: the writer must still refuse it.
        assertFalse(CandidateMarkFresh8094.applyMark8870(ts, 0.0011,
            "PUMP_CURVE_RPC_KEYLESS_8359", TokenFacts.Rank.PROVIDER, now))
        assertEquals(0.001, ts.lastPrice, 0.0)
        assertEquals(1_000_000.0, ts.lastMcap, 0.0)
    }
}
