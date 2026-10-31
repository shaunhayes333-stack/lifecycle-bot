package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExitRegret7752
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8486 — chain reserves answer before Jupiter; each lane's targets learn from its own profit exits. */
class Aate8486ReliableSourcesTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun heldSellEstimateAsksTheReservesBeforeJupiter() {
        val q = src("engine/truth/HeldHotMarkAuthority7419.kt").substringAfter("internal fun quoteSellEstimate(").substringBefore("fun dustRouteQuote8475(")
        assertTrue(q.indexOf("reserveFirst8486(bare, rawQty, decimals, solUsd, observedAt)") in 0 until q.indexOf("quoteApi7876.getQuote("))
    }

    @Test fun stopConfirmAndProfitPreflightAskTheReservesFirst() {
        val stop = src("engine/StopMarkConfirm8418.kt")
        assertTrue(stop.indexOf("reserveVerdict8485(ts, asked, nowMs, thresholdPct)") in 0 until stop.indexOf("getExecutablePriceIfFresh7301(ts.mint)"))
        val pre = src("engine/ProfitExitTruth8163.kt")
        assertTrue(pre.indexOf("PROFIT_EXIT_RESERVE_FIRST_8486") in 0 until pre.indexOf("jupiter.getQuote("))
    }

    @Test fun targetsWidenInProportionToWhatHoldingPaid() {
        assertEquals(1.0, ExitRegret7752.profitMult8486(null), 0.0)
        assertEquals("too few exits", 1.0, ExitRegret7752.profitMult8486(ExitRegret7752.Read7877(5, 10.0, 20.0, 9.0, 0.6)), 0.0)
        assertEquals("hold beat the sale by 5 points", 1.5, ExitRegret7752.profitMult8486(ExitRegret7752.Read7877(20, 10.0, 15.0, 4.0, 0.5)), 1e-9)
        assertEquals("capped", ExitRegret7752.PROFIT_MULT_MAX_8486, ExitRegret7752.profitMult8486(ExitRegret7752.Read7877(20, 10.0, 40.0, 25.0, 0.7)), 1e-9)
        assertEquals("sold before a drop: tighten", 0.9, ExitRegret7752.profitMult8486(ExitRegret7752.Read7877(20, 10.0, 2.0, -8.0, 0.1)), 1e-9)
        assertTrue(src("engine/learning/LaneExitTuner.kt").contains("ExitRegret7752.profitMultFor7888(lane)"))
    }
}
