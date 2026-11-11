package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.SellRouteEndpoint8144
import com.lifecyclebot.engine.sell.SellRouteEndpoint8144.Route8144
import com.lifecyclebot.engine.sell.SellRouteEndpoint8144.Venue8144
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8693 — owner: PumpPortal (0.5 % per trade) only for a pump.fun coin on its curve; everything else Jupiter + Helius Sender. */
class Aate8693PumpPortalOnlyOnCurveTest {
    @Test fun onlyACoinStillOnItsPumpCurveMayUsePumpPortal() {
        assertTrue(SellRouteEndpoint8144.pumpPortalVenue8693(pumpCoin = true, graduated = false))
        assertFalse("graduated to a pool", SellRouteEndpoint8144.pumpPortalVenue8693(pumpCoin = true, graduated = true))
        assertFalse("not a pump.fun coin", SellRouteEndpoint8144.pumpPortalVenue8693(pumpCoin = false, graduated = false))
    }

    @Test fun sellsLeaveTheCurveVenueThroughJupiterFirst() {
        assertEquals(Route8144.PUMPPORTAL, SellRouteEndpoint8144.routeOrder8144(Venue8144.PUMP_CURVE).first())
        for (v in listOf(Venue8144.PUMPSWAP, Venue8144.UNKNOWN, Venue8144.AGGREGATOR_ONLY)) {
            assertEquals("$v", Route8144.JUPITER, SellRouteEndpoint8144.routeOrder8144(v).first())
            assertEquals("$v keeps PumpPortal only as the last rescue", Route8144.PUMPPORTAL, SellRouteEndpoint8144.routeOrder8144(v).last())
        }
    }

    @Test fun buysAndTopUpsAskTheCurveNotTheDiscoverySource() {
        val e = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(e.contains("val pumpPortalAutoEligible4559 = !deepUnsupportedAmm4559 && onActivePumpCurve8693(ts.mint)"))
        assertTrue(e.contains("val pumpTopUpPlan = if (!onActivePumpCurve8693(ts.mint) || costAggregatorFirst8151(ts, wallet)) null"))
        assertFalse(e.contains("srcUpperForRoute.contains(\"PUMPSWAP\")"))
    }
}
