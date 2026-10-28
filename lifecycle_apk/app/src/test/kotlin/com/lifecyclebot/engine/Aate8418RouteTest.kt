package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8418 — PumpPortal is a coin's route only when Jupiter has no route for it (the bounded quote probe answered "no
 * route", or Jupiter's endpoint is not admitting the mint), for buys, top-ups and sells; the counters show every choice.
 * 5.0.8416: BUY|PUMPPORTAL landed 1/7, SELL|PUMPPORTAL 1/4, while route{jupiter=0,pumpportal=0} never moved.
 */
class Aate8418RouteTest {
    private val k = Costs8369
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private fun count(line: String, key: String): Long =
        Regex("$key=(\\d+)").find(line)?.groupValues?.get(1)?.toLong() ?: -1L

    @Test fun aBuyGoesPumpPortalOnlyWhenJupiterHasNoRoute() {
        for (tried in listOf(false, true)) for (failing in listOf(false, true)) {
            assertTrue(k.jupiterFirstForBuy8369("PUMP_CURVE", jupiterQuotes = true, recentlyTried = tried, jupiterLandFailing = failing))
            assertTrue(k.jupiterFirstForBuy8369("PUMP_CURVE", jupiterQuotes = null, recentlyTried = tried, jupiterLandFailing = failing))
            assertFalse(k.jupiterFirstForBuy8369("PUMP_CURVE", jupiterQuotes = false, recentlyTried = tried, jupiterLandFailing = failing))
            assertTrue(k.jupiterFirstForBuy8369("PUMPSWAP", jupiterQuotes = false, recentlyTried = tried, jupiterLandFailing = failing))
        }
    }

    @Test fun aSellGoesPumpPortalFirstOnlyWhenJupiterHasNoRoute() {
        // not a pump-routed position: never PumpPortal first
        assertFalse(k.sellPumpFirst8418(pumpPriority = false, jupiterAdmitted = false, jupiterQuotes = false))
        // Jupiter admitted and quoting (or not yet probed): Jupiter first, PumpPortal is the rescue after it
        assertFalse(k.sellPumpFirst8418(pumpPriority = true, jupiterAdmitted = true, jupiterQuotes = true))
        assertFalse(k.sellPumpFirst8418(pumpPriority = true, jupiterAdmitted = true, jupiterQuotes = null))
        // no Jupiter route: the probe said so, or the endpoint is not admitting the mint
        assertTrue(k.sellPumpFirst8418(pumpPriority = true, jupiterAdmitted = true, jupiterQuotes = false))
        assertTrue(k.sellPumpFirst8418(pumpPriority = true, jupiterAdmitted = false, jupiterQuotes = true))
    }

    @Test fun theCountersShowEverySellRouteChoice() {
        assertTrue(k.pumpSellIsRescue8418("EXIT-RESCUE"))
        assertTrue(k.pumpSellIsRescue8418("PARTIAL-RESCUE-2"))
        assertFalse(k.pumpSellIsRescue8418("EXIT"))
        val before = k.routeLine8418()
        assertTrue(before, before.contains("sellRoute8418{"))
        k.noteSellFirstRoute8418(false)
        k.noteSellFirstRoute8418(true)
        k.notePumpSell8418("EXIT-RESCUE")
        k.notePumpSell8418("EXIT")
        val after = k.routeLine8418()
        assertEquals(count(before, "jupiterFirst") + 1, count(after, "jupiterFirst"))
        assertEquals(count(before, "pumpFirst") + 1, count(after, "pumpFirst"))
        assertEquals(count(before, "pumpSent") + 2, count(after, "pumpSent"))
        assertEquals(count(before, "pumpRescue") + 1, count(after, "pumpRescue"))
    }

    @Test fun theBuyCounterMovesOnEveryEligibleChoice() {
        val before = k.routeLine8418()
        k.noteBuyRoute8369("Mint8418", "JUPITER")
        val after = k.routeLine8418()
        assertEquals(count(before, "buyRoute8418\\{jupiter") + 1, count(after, "buyRoute8418\\{jupiter"))
        assertTrue(src("engine/Costs8369.kt").contains("routeLine8418() + \" \" +"))
    }

    @Test fun everyRouteDecisionReadsTheOneRule() {
        val ex = src("engine/Executor.kt")
        // top-ups take the fresh-buy route choice instead of PumpPortal first for every mint
        assertTrue(ex.contains("val pumpTopUpPlan = if (costAggregatorFirst8151(ts, wallet)) null else recalcBuyPlanForProcessor("))
        // the fresh-buy choice (liveBuy) is the 8369 helper, counted on every eligible buy
        assertTrue(ex.contains("Costs8369.noteBuyRoute8369(ts.mint, if (jupiterFirst8369) \"JUPITER\" else \"PUMPPORTAL\")"))
        val fn = ex.substring(ex.indexOf("private fun shouldTryPumpDirectFirst6099"), ex.indexOf("Executor v3 — SecurityGuard integrated"))
        assertTrue(fn.contains("Costs8369.sellPumpFirst8418(pumpFirst, jupiterOk8390, Costs8369.cachedJupiterQuotes8369(ts.mint))"))
        assertTrue(ex.contains("return shouldTryPumpDirectFirst6099(ts, \"EXIT\").also { Costs8369.noteSellFirstRoute8418(it) }"))
        assertTrue(ex.contains("Costs8369.notePumpSell8418(labelTag)"))
    }
}
