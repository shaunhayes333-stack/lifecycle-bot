package com.lifecyclebot.engine

import com.lifecyclebot.perps.crypto.brain.CryptoTacticEvidence8535
import com.lifecyclebot.perps.crypto.brain.CryptoTacticSwitcher.Tactic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8535 — audit C11: a zero-count provider row's default bp=50 could satisfy MEAN_REVERT (`bp >= 50`) on a bouncing tape. */
class Aate8535CryptoTacticEvidenceTest {

    // four observed prices: a 10 % pullback from the high, 3 % rebound off the low
    private val bounce = listOf(100.0, 95.0, 87.4, 90.0)

    @Test fun a_bouncing_tape_with_no_trade_counts_is_a_price_only_shape_not_a_confirmed_buyer_reversal() {
        val s = CryptoTacticEvidence8535.evaluate(bounce, 90.0, buyPressurePct = 50.0, buyersObserved = false, change24hPct = 3.0, isTrending = true)
        assertTrue("the price structure is real", s.meanRevert && s.pullback)
        assertTrue("and it is named as price-only", s.priceOnly)
        assertFalse("momentum is a buyer tactic: nothing without buyers, trending or not", s.momentum)
        assertEquals(Tactic.PULLBACK, s.fired())
        // the synthetic 50 also buys nothing when the provider happens to send it with the flag set
        val observed50 = CryptoTacticEvidence8535.evaluate(bounce, 90.0, 50.0, buyersObserved = true, change24hPct = 3.0, isTrending = false)
        assertTrue(observed50.meanRevert); assertFalse(observed50.pullback); assertFalse(observed50.priceOnly)
    }

    @Test fun with_actual_buys_and_sells_the_proper_shape_fires_with_full_confirmation() {
        val s = CryptoTacticEvidence8535.evaluate(bounce, 90.0, buyPressurePct = 60.0, buyersObserved = true, change24hPct = 3.0, isTrending = false)
        assertTrue(s.meanRevert && s.pullback && s.momentum)
        assertFalse(s.priceOnly)
        assertTrue(s.forTactic(Tactic.MEAN_REVERT)); assertTrue(s.forTactic(Tactic.LAB_PROPOSED))
        val sellers = CryptoTacticEvidence8535.evaluate(bounce, 90.0, buyPressurePct = 40.0, buyersObserved = true, change24hPct = 3.0, isTrending = false)
        assertFalse("observed sellers refuse every buyer-gated shape", sellers.meanRevert || sellers.pullback || sellers.momentum)
        assertFalse(sellers.priceOnly)
    }

    @Test fun no_tape_no_shape_and_breakout_needs_a_new_high() {
        val none = CryptoTacticEvidence8535.evaluate(emptyList(), 90.0, 70.0, true, 3.0, false)
        assertFalse(none.pullback || none.breakout || none.meanRevert)
        assertTrue("momentum needs no tape, only observed buyers", none.momentum)
        assertEquals(Tactic.MOMENTUM, none.fired())
        val brk = CryptoTacticEvidence8535.evaluate(listOf(100.0, 101.0, 100.5, 102.0), 102.0, 55.0, true, 3.0, false)
        assertTrue(brk.breakout); assertEquals(Tactic.BREAKOUT, brk.fired())
        val brkNoBuyers = CryptoTacticEvidence8535.evaluate(listOf(100.0, 101.0, 100.5, 102.0), 102.0, 50.0, false, 3.0, false)
        assertTrue(brkNoBuyers.breakout && brkNoBuyers.priceOnly)
        assertTrue(CryptoTacticEvidence8535.PRICE_ONLY_CONF_PENALTY > 0)
    }
}
