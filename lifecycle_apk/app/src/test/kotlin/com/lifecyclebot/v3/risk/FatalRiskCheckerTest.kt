package com.lifecyclebot.v3.risk

import com.lifecyclebot.v3.core.TradingConfigV3
import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.core.V3BotMode
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.v3.scanner.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.9.1566 — pins rugcheck semantics: 100 clean, 1 pending, 0 confirmed rug. */
class FatalRiskCheckerTest {
    private fun candidate(
        rawRiskScore: Int?,
        liquidity: Double = 1_600.0,
        extra: Map<String, Any?> = emptyMap(),
    ) = CandidateSnapshot(
        mint = "MintRcPending111111111111111111111111111",
        symbol = "RCP",
        source = SourceType.PUMP_FUN_GRADUATE,
        discoveredAtMs = 1L,
        ageMinutes = 0.5,
        liquidityUsd = liquidity,
        marketCapUsd = liquidity * 10.0,
        buyPressurePct = 50.0,
        volume1mUsd = 0.0,
        volume5mUsd = 0.0,
        holders = null,
        topHolderPct = null,
        hasIdentitySignals = false,
        isSellable = true,
        rawRiskScore = rawRiskScore,
        extra = extra,
    )

    private val ctx = TradingContext(TradingConfigV3(), V3BotMode.PAPER)

    @Test fun score_100_maps_to_zero_rug_risk_clean() {
        assertEquals(0, RugModel().score(candidate(rawRiskScore = 100), ctx))
    }

    @Test fun score_1_maps_to_neutral_pending_not_extreme_rug_risk() {
        val risk = RugModel().score(candidate(rawRiskScore = 1), ctx)
        assertTrue("RC_PENDING should be neutral-ish, not 99/100 risk; got $risk", risk < 90)
    }

    @Test fun score_1_with_transient_fresh_launch_flag_does_not_fatal_paper() {
        val checker = FatalRiskChecker(TradingConfigV3())
        val result = checker.check(candidate(rawRiskScore = 1, extra = mapOf("liquidityDraining" to true)), ctx)
        assertFalse("pending RC + transient fresh-launch flag must not BLOCK_FATAL in paper", result.blocked)
    }

    @Test fun score_0_confirmed_rug_still_fatals() {
        val checker = FatalRiskChecker(TradingConfigV3())
        val result = checker.check(candidate(rawRiskScore = 0), ctx)
        assertTrue("confirmed rug score=0 must remain fatal", result.blocked)
    }
}
