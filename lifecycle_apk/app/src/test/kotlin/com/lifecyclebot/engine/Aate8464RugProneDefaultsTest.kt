package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8464 — the selection sweep (owner: "find out where and why live trading quality and profitability have totally
 * collapsed"). TokenMetricStageRouter staged RUG_PRONE on "valuation air" (mcap / liquidity >= 85 on a $150k+ coin) with
 * a weak book (bp < 52) — but an unread pool reads liq 0 -> mcapToLiq 9999 and an unobserved book reads the default
 * bp 50, so every non-established $150k+ coin whose pool and book had not arrived was RUG_PRONE: refused
 * STRUCTURAL_RISK_8136 (8457: n47, runner share 21 %, 28 of the 65 wrongly refused runners), routed observation-only.
 */
class Aate8464RugProneDefaultsTest {
    @Test fun anUnreadPoolAndADefaultBookAreNotValuationAir() {
        val ts = TokenState(mint = "Aate8464RugProneMint11111111111111111111111", symbol = "VA").apply {
            lastMcap = 600_000.0
            lastLiquidityUsd = 0.0          // pool not read yet
            lastBuyPressurePct = 50.0       // the default, never observed
        }
        assertNotEquals(TokenMetricStageRouter.Stage.RUG_PRONE, TokenMetricStageRouter.snapshot(ts).stage)

        val src = File("src/main/kotlin/com/lifecyclebot/engine/TokenMetricStageRouter.kt").readText()
        assertTrue(src.contains("val valuationAir = liq > 0.0 && mcapToLiq >= 85.0 && mcap >= 150_000.0 && !isEstablished7306(ts, mcap, liq, ageMin)"))
        assertTrue(src.contains("val rugProne = topHeavy || (valuationAir && bookObserved8464 && (sp >= 55.0 || bp < 52.0)) || (liqThin && runup >= 80.0)"))
        assertTrue(src.contains("val bookObserved8464 = ts.buyPressureObservedAtMs8084 > 0L"))
    }
}
