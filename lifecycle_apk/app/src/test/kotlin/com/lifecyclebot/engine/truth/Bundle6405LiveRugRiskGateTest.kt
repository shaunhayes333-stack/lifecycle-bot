package com.lifecyclebot.engine.truth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Bundle6405LiveRugRiskGateTest {

    private fun signals(
        advisor: List<String> = emptyList(),
        topHolder: Double? = null,
        liq: Double? = 10_000.0,
        sellPct: Double = 45.0,
        mintAuth: Boolean = false,
        freezeAuth: Boolean = false,
        isPaper: Boolean = false,
    ) = LiveRugRiskGate6405.Signals(
        advisorLabels = advisor,
        topHolderConcentrationPct = topHolder,
        liquidityUsd = liq,
        lastSellPressurePct = sellPct,
        mintAuthorityLive = mintAuth,
        freezeAuthorityLive = freezeAuth,
        isPaper = isPaper,
    )

    @Test fun paper_never_blocks() {
        val v = LiveRugRiskGate6405.evaluate(
            "M", "SYM", "MOONSHOT",
            signals(
                advisor = listOf("BRAIN_RUGCHECK_FLOOR", "PROVIDER_PROOF_HOLDER_CASCADE_BLIND"),
                mintAuth = true,
                isPaper = true,
            ),
        )
        assertFalse(v.block)
    }

    @Test fun clean_live_signal_passes() {
        val v = LiveRugRiskGate6405.evaluate(
            "M", "SYM", "MOONSHOT",
            signals(topHolder = 20.0, liq = 15_000.0, sellPct = 45.0),
        )
        assertFalse(v.block)
        assertEquals(0, v.score)
    }

    @Test fun mint_authority_live_is_single_source_hard_block() {
        val v = LiveRugRiskGate6405.evaluate(
            "M", "SYM", "STANDARD",
            signals(mintAuth = true, topHolder = 10.0, liq = 20_000.0),
        )
        assertTrue(v.block)
        assertTrue(v.flags.any { it == "MINT_OR_FREEZE_AUTHORITY_LIVE" })
    }

    @Test fun rugcheck_plus_cascade_blind_blocks() {
        // 2 + 2 = 4 >= 3 threshold
        val v = LiveRugRiskGate6405.evaluate(
            "M", "SYM", "STANDARD",
            signals(
                advisor = listOf("BRAIN_RUGCHECK_FLOOR", "PROVIDER_PROOF_HOLDER_CASCADE_BLIND"),
            ),
        )
        assertTrue(v.block)
    }

    @Test fun rugcheck_alone_below_threshold() {
        // 2 < 3
        val v = LiveRugRiskGate6405.evaluate(
            "M", "SYM", "STANDARD",
            signals(advisor = listOf("BRAIN_RUGCHECK_FLOOR")),
        )
        assertFalse(v.block)
        assertEquals(2, v.score)
    }

    @Test fun top_holder_50_plus_low_liquidity_blocks() {
        // topHolder=55 (2) + liq $1500 (2) = 4
        val v = LiveRugRiskGate6405.evaluate(
            "M", "SYM", "STANDARD",
            signals(topHolder = 55.0, liq = 1_500.0),
        )
        assertTrue(v.block)
    }

    @Test fun momentum_avoid_plus_sell_pressure_below_threshold() {
        // momentum_avoid (1) + sell_pressure_70pct (1) = 2 < 3
        val v = LiveRugRiskGate6405.evaluate(
            "M", "SYM", "STANDARD",
            signals(advisor = listOf("MOMENTUM_AVOID"), sellPct = 70.0),
        )
        assertFalse(v.block)
        assertEquals(2, v.score)
    }

    @Test fun momentum_avoid_plus_high_holder_plus_sell_pressure_blocks() {
        // momentum_avoid (1) + top_holder_50 (2) + sell_pressure (1) = 4
        val v = LiveRugRiskGate6405.evaluate(
            "M", "SYM", "STANDARD",
            signals(
                advisor = listOf("MOMENTUM_AVOID"),
                topHolder = 52.0,
                sellPct = 70.0,
            ),
        )
        assertTrue(v.block)
    }

    @Test fun null_signals_do_not_score() {
        val v = LiveRugRiskGate6405.evaluate(
            "M", "SYM", "STANDARD",
            signals(topHolder = null, liq = null),
        )
        assertFalse(v.block)
    }
}
