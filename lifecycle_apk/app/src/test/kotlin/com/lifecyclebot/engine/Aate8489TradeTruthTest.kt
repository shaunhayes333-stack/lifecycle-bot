package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveRiskPolicy7807
import com.lifecyclebot.network.JupiterLatency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8489 — one position per mint at the send, venue-checked stops, trade-1 size, wider locks, Jupiter-slow routing, SOL pools. */
class Aate8489TradeTruthTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun stopsNameTheirDepthAndAVenueReadRefutesABadMark() {
        assertEquals(-4.0, StopMarkConfirm8418.stopDepthPct8489("STRICT_SL_-4")!!, 0.0)
        assertEquals(-99.0, StopMarkConfirm8418.stopDepthPct8489("RUNNER_HARD_STOP_7967_-99PCT")!!, 0.0)
        assertEquals(-25.0, StopMarkConfirm8418.stopDepthPct8489("CATASTROPHIC_HARD_BACKSTOP_-25")!!, 0.0)
        assertNull(StopMarkConfirm8418.stopDepthPct8489("HARD_STOP"))
        // 8488: STRICT_SL_-4 sold at +2.7 %; RUNNER_HARD_STOP -99 % on a coin at -7 %
        assertTrue(StopMarkConfirm8418.venueRefutes8489(2.7, -4.0))
        assertTrue(StopMarkConfirm8418.venueRefutes8489(-7.0, -99.0))
        assertFalse("the venue agrees: the stop fires", StopMarkConfirm8418.venueRefutes8489(-24.0, -25.0))
        assertFalse(StopMarkConfirm8418.venueRefutes8489(-1.0, null))
        assertTrue(StopMarkConfirm8418.priceLossStop8489("STRICT_SL_-4"))
        assertTrue(StopMarkConfirm8418.priceLossStop8489("RUNNER_HARD_STOP_7967_-99PCT"))
        assertFalse("a rug exit is never held", StopMarkConfirm8418.priceLossStop8489("RUG_DRAIN_EXIT"))
        assertFalse(StopMarkConfirm8418.priceLossStop8489("LIQUIDITY_COLLAPSE"))
        assertFalse(StopMarkConfirm8418.priceLossStop8489("TICK_PROFIT_LOCK_peak13_now10"))
        assertTrue(src("engine/Executor.kt").contains("StopMarkConfirm8418.stopRefutedByVenue8489(ts, reason)"))
    }

    @Test fun oneLivePositionPerMintAtTheSendAndAdoptionAtTheVenuePrice() {
        val x = src("engine/Executor.kt")
        assertTrue(x.indexOf("if (liveMintAlreadyHeld8489(ts))") in 0 until x.indexOf("var buyTerminalRecorded = false"))
        assertTrue(src("engine/LiveCanonicalRecovery6686.kt").contains("val priceUsd = reservePx8489 ?: tsMark ?: trackerMark"))
    }

    @Test fun tradeOneRiskScalesWithASmallAccount() {
        assertEquals(0.04, LiveRiskPolicy7807.tradeOneRiskFrac8489(0.015, 0.25), 1e-9)
        assertEquals(0.02, LiveRiskPolicy7807.tradeOneRiskFrac8489(0.015, 5.0), 1e-9)
        assertEquals(0.05, LiveRiskPolicy7807.tradeOneRiskFrac8489(0.05, 5.0), 1e-9)
        assertTrue(src("engine/truth/LiveRiskPolicy7807.kt").contains("tradeOneRisk8489 = true,  // V5.0.8489"))
        assertTrue(src("engine/FinalDecisionGate.kt").contains("\"TRADE_ONE_RISK_SIZE_8489\" in risk.labels"))
    }

    @Test fun stopsAndLocksFollowTheLane() {
        assertTrue(src("engine/Executor.kt").contains("val hardFloor = laneHeldStop8489(runnerLane7335, metrics6725?.healthTier, fluidStopNegative, hardFloorRaw7335)"))
        assertTrue(src("engine/BotService.kt").contains("val lockArm8489 = tickLockArm8489(ts)"))
    }

    @Test fun aSlowJupiterIsDetected() {
        assertFalse("too few samples", JupiterLatency.slowOf(listOf(3_000L to true), Long.MAX_VALUE))
        assertTrue("a 429 inside the window", JupiterLatency.slowOf(emptyList(), 5_000L))
        assertTrue(JupiterLatency.slowOf(listOf(2_000L to true, 2_500L to true, 1_800L to true, 3_000L to true), Long.MAX_VALUE))
        assertFalse(JupiterLatency.slowOf(listOf(400L to true, 600L to true, 900L to true, 700L to true), Long.MAX_VALUE))
        assertTrue("mostly failing", JupiterLatency.slowOf(listOf(400L to false, 600L to false, 900L to false, 700L to true), Long.MAX_VALUE))
        assertTrue(src("network/JupiterApi.kt").contains("): SwapQuote = timedQuote8489 { getQuoteUntimed8489("))
    }

    @Test fun aPumpCoinsSolPoolIsDerivedFromItsMint() {
        assertTrue(src("engine/truth/OnChainDepth8038.kt").contains("listOfNotNull(derivedSolPool8489(ts.mint))"))
    }
}
