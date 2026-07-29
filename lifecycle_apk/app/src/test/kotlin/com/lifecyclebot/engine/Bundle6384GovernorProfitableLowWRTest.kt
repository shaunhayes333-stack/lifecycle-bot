package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6384 — Governor "profitable low-WR" escape hatch.
 *
 * V5.0.6383 snapshot exposed the next dominant blocker after live volume
 * recovered: 202 live buys vetoed as GOVERNOR_HOLD_VETO_6342 because the
 * governor was in HOLD state.
 *
 * The stats that triggered HOLD:
 *   canonicalN = 10
 *   winRatePct = 20.0    ← below SEVERE_WR_PCT (25%)
 *   profitFactor = 7.58  ← STRONG (way above SEVERE_PF 0.70)
 *   expectancySol = +0.0024
 *
 * This is a PROFITABLE strategy — the winning trades are 7.5× the losing
 * trades. AATE doctrine: "we make money, not high WR." A moonshot-style
 * low-WR-high-PF profile is exactly what live memecoin trading looks like
 * and MUST NOT trigger safety HOLD.
 *
 * Fix: skip HOLD when profitFactor >= 2.0 AND expectancy > 0. SOFT_TIGHT /
 * CAUTION / RECOVERY branches remain intact so mild penalties still apply.
 */
class Bundle6384GovernorProfitableLowWRTest {

    @Test
    fun governor_bypasses_hold_when_profitable_low_wr() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/LiveEntrySafetyHold.kt").readText()
        assertTrue(
            "V5.0.6384: profitable-low-WR escape hatch must exist",
            txt.contains("profitableLowWr6384"),
        )
        assertTrue(
            "V5.0.6384: bypass gates on PF>=2.0 AND expectancy>0",
            txt.contains("stats.profitFactor >= 2.0") &&
                txt.contains("stats.expectancySol > 0.0"),
        )
        assertTrue(
            "V5.0.6384: severe check must include !profitableLowWr6384",
            txt.contains("!profitableLowWr6384"),
        )
    }

    @Test
    fun soft_tight_and_caution_states_still_intact() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/LiveEntrySafetyHold.kt").readText()
        assertTrue(
            "V5.0.6384: SOFT_TIGHT branch must still exist (mild penalty still applies)",
            txt.contains("GovernorState.SOFT_TIGHT"),
        )
        assertTrue(
            "V5.0.6384: CAUTION branch must still exist (early bleed still shaped)",
            txt.contains("LIVE_CONFIDENCE_GOVERNOR_CAUTION_6324"),
        )
    }
}
