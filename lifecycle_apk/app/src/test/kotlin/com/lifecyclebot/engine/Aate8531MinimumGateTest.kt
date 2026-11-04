package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8531 — owner, on the 8529 log: "a moonshot isn't a buy-in on a token already at 500k"; "if we can clear a minimum
 * gate for tokenomics, metadata etc then the buy should proceed"; tokens ignored on missing data.
 */
class Aate8531MinimumGateTest {

    private fun cand(missing: List<String>, live: Boolean = false) = CandidateAdmission8136.Candidate8136(
        mint = "gate8531pump", lane = "SHITCOIN", selectedSetup = null, priceAgeMs = 1_000L,
        missingInputs = missing, expectedGrossPct = 30.0, costPct = 4.0, genericScore = 60.0, genericFloor = 30.0, live = live,
    )

    @Test fun optional_context_gaps_get_a_verdict_not_a_wait() {
        for (gap in listOf("launchTape:UNKNOWN", "age:UNKNOWN", "opp:UNKNOWN", "chg:DEFAULT_UNOBSERVED", "bp:UNKNOWN", "shape:UNKNOWN")) {
            val paper = CandidateAdmission8136.decide8136(cand(listOf(gap)))
            assertNotEquals(gap, Terminal8136.DEFER_DATA, paper.terminal)
            assertTrue(gap, paper.trace.any { it.startsWith("optionalUnobserved8170=") })
            // live: no supported evidence is still not a buy — it is refused by name, not parked
            val live = CandidateAdmission8136.decide8136(cand(listOf(gap), live = true))
            assertNotEquals(gap, Terminal8136.DEFER_DATA, live.terminal)
        }
    }

    @Test fun the_minimum_gate_still_defers() {
        for (gap in listOf("liquidity:UNKNOWN", "price:STALE", "route:UNKNOWN", "sellability:UNKNOWN", "decimals:UNKNOWN",
                "supply:UNKNOWN", "quote:UNKNOWN", "economics:UNKNOWN", "mint:UNKNOWN", "move:HISTORY_SPARSE"))
            assertEquals(gap, Terminal8136.DEFER_DATA, CandidateAdmission8136.decide8136(cand(listOf("age:UNKNOWN", gap))).terminal)
        assertEquals(Terminal8136.DEFER_DATA, CandidateAdmission8136.decide8136(cand(emptyList()).copy(priceAgeMs = Long.MAX_VALUE)).terminal)
        assertEquals(Terminal8136.DEFER_DATA, CandidateAdmission8136.decide8136(cand(emptyList()).copy(costPct = Double.NaN)).terminal)
        assertEquals("SAFETY_REFUSED_8136", CandidateAdmission8136.decide8136(cand(listOf("age:UNKNOWN")).copy(safetyRefusal = "MINT_AUTH")).reason)
    }

    @Test fun trading_age_falls_back_to_the_pool_open_time_never_first_sight() {
        val now = 1_900_000_000_000L
        assertEquals(42.0, LanePlaybook7907.tradingAgeMin8531(42.0, now - 3_600_000L, now), 1e-9)   // verified birth wins
        assertEquals(60.0, LanePlaybook7907.tradingAgeMin8531(Double.NaN, now - 3_600_000L, now), 1e-9)
        assertTrue(LanePlaybook7907.tradingAgeMin8531(Double.NaN, null, now).isNaN())
        assertTrue(LanePlaybook7907.tradingAgeMin8531(Double.NaN, now + 60_000L, now).isNaN())        // a future pool is no reading
        assertTrue(LanePlaybook7907.tradingAgeMin8531(Double.NaN, 0L, now).isNaN())
    }

    @Test fun moonshot_does_not_enter_at_half_a_million_or_more() {
        assertEquals(500_000.0, com.lifecyclebot.v3.scoring.MoonshotTraderAI.MAX_MARKET_CAP_USD, 0.0)
        assertFalse("MOONSHOT" in SpecialistBrainBridge7542.nativeScope8168(703_186.0, emptySet()) &&
            703_186.0 <= com.lifecyclebot.v3.scoring.MoonshotTraderAI.MAX_MARKET_CAP_USD)
    }

    @Test fun one_close_reported_twice_is_one_loss() {
        val now = 1_900_000_000_000L
        val mint = "TWICE8531_TEST"
        // a small non-stop loss, reported by the journal row and again by the bus 20 s later
        RebuyLockout8019.onClose8019(mint, now - 11 * 60_000L, -2.0, "TIME_EXIT")
        RebuyLockout8019.onClose8019(mint, now - 11 * 60_000L + 20_000L, -2.0, "TIME_EXIT")
        assertNull("one loss is not a repeat loser", RebuyLockout8019.refusal8019(mint, now))
        assertTrue(RebuyLockout8019.sameClose8531(listOf(now), now + 119_000L))
        assertFalse(RebuyLockout8019.sameClose8531(listOf(now), now + 121_000L))
        assertFalse(RebuyLockout8019.sameClose8531(null, now))
    }

    @Test fun the_5tdwkb_rebuy_is_refused_at_the_buy_door() {
        val now = 1_900_000_000_000L
        val mint = "5TDWKB8531_TEST"
        RebuyLockout8019.onClose8019(mint, now - 26_000L, -13.2, "RAPID_CATASTROPHE_STOP")
        assertEquals("BIG_LOSER_8130", RebuyLockout8019.refusal8019(mint, now))
    }
}
