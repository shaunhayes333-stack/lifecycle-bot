package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Bundle6405PriceIntegrityAuthorityTest {

    @After fun tearDown() { PriceIntegrityAuthority6405.clearForTest() }

    @Test fun accepts_whitelisted_provider_price_immediately() {
        val v = PriceIntegrityAuthority6405.evaluatePrice(
            "MINT_A", 0.0123, PriceIntegrityAuthority6405.PriceSource.Jupiter,
        )
        assertTrue(v is PriceIntegrityAuthority6405.Verdict.Accept)
    }

    @Test fun rejects_synthetic_source() {
        val v = PriceIntegrityAuthority6405.evaluatePrice(
            "MINT_B", 1.0, PriceIntegrityAuthority6405.PriceSource.Synthetic,
        )
        assertTrue(v is PriceIntegrityAuthority6405.Verdict.Reject)
    }

    @Test fun rejects_zero_or_negative_price() {
        val v = PriceIntegrityAuthority6405.evaluatePrice(
            "MINT_C", 0.0, PriceIntegrityAuthority6405.PriceSource.Jupiter,
        )
        assertTrue(v is PriceIntegrityAuthority6405.Verdict.Reject)
    }

    @Test fun rejects_when_no_prior_observation_and_no_provided() {
        val v = PriceIntegrityAuthority6405.evaluatePrice(
            "MINT_D", null, PriceIntegrityAuthority6405.PriceSource.Unknown,
        )
        assertTrue(v is PriceIntegrityAuthority6405.Verdict.Reject)
    }

    @Test fun reuses_recent_observation_when_no_provided_value() {
        PriceIntegrityAuthority6405.recordPrice(
            "MINT_E", 1.23, PriceIntegrityAuthority6405.PriceSource.Birdeye,
        )
        val v = PriceIntegrityAuthority6405.evaluatePrice(
            "MINT_E", null, PriceIntegrityAuthority6405.PriceSource.Unknown,
        )
        assertTrue(v is PriceIntegrityAuthority6405.Verdict.Accept)
    }

    @Test fun pair_rejected_without_liquidity_observation() {
        val v = PriceIntegrityAuthority6405.evaluatePair("MINT_F")
        assertTrue(v is PriceIntegrityAuthority6405.Verdict.Reject)
    }

    @Test fun pair_accepted_with_positive_fresh_liquidity() {
        PriceIntegrityAuthority6405.recordPairLiquidity("MINT_G", 5_000.0)
        val v = PriceIntegrityAuthority6405.evaluatePair("MINT_G")
        assertTrue(v is PriceIntegrityAuthority6405.Verdict.Accept)
    }

    @Test fun pair_rejected_when_zero_liquidity() {
        PriceIntegrityAuthority6405.recordPairLiquidity("MINT_H", 0.0)
        val v = PriceIntegrityAuthority6405.evaluatePair("MINT_H")
        assertTrue(v is PriceIntegrityAuthority6405.Verdict.Reject)
    }

    @Test fun canTrade_requires_both_gates() {
        assertFalse(PriceIntegrityAuthority6405.canTrade(
            "MINT_I", 1.0, PriceIntegrityAuthority6405.PriceSource.Jupiter,
        ))
        PriceIntegrityAuthority6405.recordPairLiquidity("MINT_I", 100.0)
        assertTrue(PriceIntegrityAuthority6405.canTrade(
            "MINT_I", 1.0, PriceIntegrityAuthority6405.PriceSource.Jupiter,
        ))
    }
}

class Bundle6405TerminalFinalityAuthorityTest {

    @After fun tearDown() { TerminalFinalityAuthority6405.clearForTest() }

    @Test fun new_position_is_not_terminal() {
        assertFalse(TerminalFinalityAuthority6405.isTerminal("MINT_A", 1L))
        assertTrue(TerminalFinalityAuthority6405.allowExit("MINT_A", 1L, "TAKE_PROFIT"))
    }

    @Test fun terminal_blocks_further_exits() {
        TerminalFinalityAuthority6405.markTerminal(
            "MINT_B", 7L, TerminalFinalityAuthority6405.Terminal.CLOSED_FULL_EXIT, "TP_FILL",
        )
        assertTrue(TerminalFinalityAuthority6405.isTerminal("MINT_B", 7L))
        assertFalse(TerminalFinalityAuthority6405.allowExit("MINT_B", 7L, "STOP_LOSS"))
    }

    @Test fun terminal_is_idempotent() {
        TerminalFinalityAuthority6405.markTerminal(
            "MINT_C", 1L, TerminalFinalityAuthority6405.Terminal.CLOSED_STOP, "STRICT_SL",
        )
        TerminalFinalityAuthority6405.markTerminal(
            "MINT_C", 1L, TerminalFinalityAuthority6405.Terminal.CLOSED_LIQUIDATED, "ORPHAN",
        )
        // First terminal wins.
        assertEquals(
            TerminalFinalityAuthority6405.Terminal.CLOSED_STOP,
            TerminalFinalityAuthority6405.terminalOf("MINT_C", 1L),
        )
    }

    @Test fun different_generations_are_independent() {
        TerminalFinalityAuthority6405.markTerminal(
            "MINT_D", 1L, TerminalFinalityAuthority6405.Terminal.CLOSED_FULL_EXIT, "TP",
        )
        assertTrue(TerminalFinalityAuthority6405.allowExit("MINT_D", 2L, "TP"))
    }

    @Test fun clearGeneration_reopens_for_rebuy() {
        TerminalFinalityAuthority6405.markTerminal(
            "MINT_E", 1L, TerminalFinalityAuthority6405.Terminal.CLOSED_FULL_EXIT, "TP",
        )
        TerminalFinalityAuthority6405.clearGeneration("MINT_E", 1L)
        assertFalse(TerminalFinalityAuthority6405.isTerminal("MINT_E", 1L))
    }
}
