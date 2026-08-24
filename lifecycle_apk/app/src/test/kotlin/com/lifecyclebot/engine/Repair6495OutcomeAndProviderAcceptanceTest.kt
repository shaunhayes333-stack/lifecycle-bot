package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450
import org.junit.Assert.*
import org.junit.Test

class Repair6495OutcomeAndProviderAcceptanceTest {
    private fun event(pnl: Double, fraction: Double, pct: Double, gross: Double = pnl, fees: Double = 0.0) =
        CanonicalTradeFinalizedBus6450.Event(
            positionId = "p6495", mint = "mint6495", outcome = CanonicalTradeFinalizedBus6450.Outcome.WIN,
            netRealizedPnlSol = pnl, grossRealizedPnlSol = gross, returnFraction = fraction,
            netReturnPct = pct, feesSol = fees, entryLane = "MOONSHOT", entryStrategyPid = "s",
            entryTactic = "MOMENTUM", exitReason = "TP", holdingTimeMs = 1000,
            dataQuality = "CANONICAL", priceIntegrity = "CONFIRMED", mode = "PAPER", settledAtMs = 1L,
        )

    @Test fun impossibleFinalizedEconomicsAreQuarantinedBeforeFanout() {
        assertEquals("IMPLIED_PROCEEDS_ABOVE_5000_SOL",
            CanonicalTradeFinalizedBus6450.economicInvalidReasonForTest6495(event(10_000.0, 1.0, 100.0)))
        assertEquals("RETURN_FRACTION_PCT_MISMATCH",
            CanonicalTradeFinalizedBus6450.economicInvalidReasonForTest6495(event(1.0, 0.10, 92_000_000.0)))
        assertNull(CanonicalTradeFinalizedBus6450.economicInvalidReasonForTest6495(event(0.05, 0.50, 50.0)))
    }

    @Test fun persistedExpectancyPoisonIsDroppedNotClamped() {
        ScoreExpectancyTracker.reset()
        ScoreExpectancyTracker.importState(mapOf("MOONSHOT:4" to listOf(10.0, 92_000_000.0, -20.0)))
        assertEquals(listOf(10.0, -20.0), ScoreExpectancyTracker.exportState()["MOONSHOT:4"])
    }

    @Test fun dexscreenerCannotRawRetryAroundHealthCircuit() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/network/DexscreenerApi.kt").readText()
        assertTrue(src.contains("HealthAwareHttp.execute(http, req, host = host)"))
        assertTrue(src.contains("private fun get(url: String, host: String = \"dexscreener\")"))
        assertFalse(src.contains("http.newCall(req).execute()"))
    }
}
