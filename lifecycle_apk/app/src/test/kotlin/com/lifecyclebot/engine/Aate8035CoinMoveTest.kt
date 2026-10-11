package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.NetEdge8030
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8035 — a lane expects no more than the coin itself is moving (BONK: BLUECHIP's 20% vs a 1-3% hour). */
class Aate8035CoinMoveTest {
    @Test fun aSlowCoinCannotClearTwiceTheRoundTrip() {
        // BONK-like: mandate 20%, the hour moved 2.5% -> expect 2.5% < 2 x 3.9% -> refused.
        val e = NetEdge8030.expectedMove8035(20.0, 2.5)
        assertEquals(2.5, e, 1e-9)
        assertFalse(NetEdge8030.entryClears8030(e, 3.9))
        // A $2M coin actually moving 18% in the hour still clears.
        assertTrue(NetEdge8030.entryClears8030(NetEdge8030.expectedMove8035(20.0, 18.0), 3.9))
        // No data (a brand-new launch): the lane's mandate stands.
        assertEquals(50.0, NetEdge8030.expectedMove8035(50.0, null), 1e-9)
        // Never more than the mandate.
        assertEquals(20.0, NetEdge8030.expectedMove8035(20.0, 80.0), 1e-9)
    }

    @Test fun earlyChartHasTooFewIndependentCandlesToCapItsExpectedMove() {
        val s = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/NetEdge8030.kt").readText()
        assertTrue(s.contains("if (bars.size >= 6)"))
        assertEquals(0.0, NetEdge8030.expectedMove8035(20.0, 0.0), 0.0)
        assertEquals(20.0, NetEdge8030.expectedMove8035(20.0, null), 0.0)
    }

    @Test fun theRangeNeedsEnoughPrints() {
        assertNull(NetEdge8030.rangePct8035(listOf(1.0, 1.01, 1.02)))
        assertEquals(10.0, NetEdge8030.rangePct8035(listOf(1.0, 1.02, 1.05, 1.1, 1.03, 1.0))!!, 1e-9)
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/NetEdge8030.kt").readText().contains("NET_EDGE_COIN_TOO_SLOW_8035"))
    }
}
