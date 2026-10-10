package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8029 — every lane learns its own coins, graded on its own play; pump.fun capped at half the watchlist. */
class Aate8029LaneParticipationTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val ladder: (Double, Double) -> Double = { pk, rest -> SpikeCapture7943.realisableGrossPct(pk, rest).let { if (it > rest) it else rest } }

    @Test fun aLabelIsWhatTheLanesPlayBanks() {
        // MOONSHOT (15% floor): dipped -20% before running +120% -> stopped, not a run.
        assertEquals(-15.0, LaneParticipation8029.playGross8029(15.0, 120.0, -20.0, -20.0, 80.0, ladder), 1e-9)
        // Dip -10% inside the stop, then +120%: the run is credited as before.
        assertEquals(ladder(120.0, 80.0), LaneParticipation8029.playGross8029(15.0, 120.0, -10.0, -10.0, 80.0, ladder), 1e-9)
        // Peak first, then through the stop: the rest is sold at the stop, not at the -60% mark.
        assertEquals(ladder(30.0, -15.0), LaneParticipation8029.playGross8029(15.0, 30.0, -2.0, -60.0, -60.0, ladder), 1e-9)
        // Never above entry, straight through a CASHGEN 6% stop.
        assertEquals(-6.0, LaneParticipation8029.playGross8029(6.0, 0.0, Double.NaN, -25.0, -25.0, ladder), 1e-9)
        // No known play (NaN stop): the old ladder grade.
        assertEquals(ladder(50.0, 5.0), LaneParticipation8029.playGross8029(Double.NaN, 50.0, -30.0, -30.0, 5.0, ladder), 1e-9)
        assertTrue(src("engine/truth/ForwardReturnLabeler7731.kt").contains("StopAuthority7887.laneStopMag8029(o.lane)"))
    }

    @Test fun readySpecialistsLearnWithoutOwningTheCoin() {
        assertTrue(src("engine/SpecialistOwnership7951.kt").contains("if (p != l) LaneParticipation8029.shadow8029(mint, l)"))
        assertEquals("SHADOW_NOT_PRIMARY_8029", LaneParticipation8029.SHADOW_REASON)
        assertTrue(LaneParticipation8029.statusLine().contains("shadowLabels="))
    }

    @Test fun pumpFunHoldsAtMostHalfTheWatchlist() {
        assertTrue(LaneParticipation8029.pumpFamily8029("PUMP_FUN_NEW"))
        assertTrue(LaneParticipation8029.pumpFamily8029("pump.fun"))
        assertTrue(LaneParticipation8029.pumpFamily8029("PUMP_LIVESTREAM_7973"))
        assertFalse(LaneParticipation8029.pumpFamily8029("MARKET_HUNT_QUALITY"))
        assertFalse(LaneParticipation8029.pumpFamily8029("RAYDIUM_NEW_POOL"))
        assertEquals(0.5, LaneParticipation8029.PUMP_MAX_SHARE, 0.0)
        assertTrue(src("engine/GlobalTradeRegistry.kt").contains("LaneParticipation8029.PUMP_MAX_SHARE * watchlist.size"))
        assertTrue(src("engine/market/MarketSweep7297.kt").contains("\"/toptraded/24h?limit=50\""))
    }

    @Test fun nonTradingKeysHaveNoPlay() {
        assertTrue(com.lifecyclebot.engine.cortex.StopAuthority7887.laneStopMag8029("PLANADMIT_TOO_FEW_BARS_7871").isNaN())
    }
}
