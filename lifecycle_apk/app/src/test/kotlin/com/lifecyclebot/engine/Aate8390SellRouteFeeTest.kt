package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test

/** V5.0.8390 — exits sell through Jupiter (quote-priced) first; PumpPortal (0.5 %/side) is the rescue, or first when Jupiter is out. */
class Aate8390SellRouteFeeTest {
    private fun src(p: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun pumpFirstYieldsToAdmissibleJupiter() {
        val e = src("engine/Executor.kt")
        val fn = e.substring(e.indexOf("private fun shouldTryPumpDirectFirst6099"), e.indexOf("Executor v3 — SecurityGuard integrated"))
        assertTrue(fn.contains("Route8144.JUPITER"))
        // superseded by V5.0.8418 — the same rule plus a bounded-probe "no route" answer, through one pure helper (Aate8418RouteTest)
        assertTrue(fn.contains("Costs8369.sellPumpFirst8418(pumpFirst, jupiterOk8390, Costs8369.cachedJupiterQuotes8369(ts.mint))"))
        assertTrue(fn.contains("return pumpFirst8418"))
        assertTrue(fn.contains("SELL_JUPITER_FIRST_QUOTED_FEE_8391_"))
        // every ordinary exit decides through this one function; the PumpPortal rescue legs stay
        for (k in listOf("\"PROFIT-LOCK\")", "\"PARTIAL\")", "\"ORPHAN-SWEEP\")")) assertTrue(k, e.contains("shouldTryPumpDirectFirst6099(") && e.contains(k))
        for (k in listOf("PROFIT-LOCK-RESCUE", "EXIT-RESCUE", "ORPHAN-RESCUE", "PARTIAL-RESCUE-")) assertTrue(k, e.contains(k))
    }

    @Test fun costModelPricesTheRouteActuallyTaken() {
        val k = Costs8369
        assertEquals("JUPITER", k.plannedSellRoute8369(pumpMint = true, venue = "UNKNOWN"))
        assertEquals("PUMPPORTAL", k.plannedSellRoute8369(pumpMint = true, venue = "PUMPSWAP", jupiterOk = false))
        assertTrue(k.routeFeePerSide8369("JUPITER", "JUPITER") < k.routeFeePerSide8369("JUPITER", "PUMPPORTAL"))
    }
}
