package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.NetEdge8030
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8030 — positive EV and net profit always: entry move >= 2x cost, no profit exit under cost + 1%. */
class Aate8030NetEdgeTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun entryNeedsTheMoveToCoverTwiceTheRoundTrip() {
        assertTrue(NetEdge8030.entryClears8030(50.0, 6.0))      // runner
        assertTrue(NetEdge8030.entryClears8030(12.0, 6.0))      // treasury at the edge
        assertFalse(NetEdge8030.entryClears8030(12.0, 6.5))     // treasury scalp the cost eats
        assertFalse(NetEdge8030.entryClears8030(3.0, 4.0))      // a 3% scalp
        assertEquals(2.0, NetEdge8030.EDGE_MULT, 0.0)
        val g = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(g.indexOf("NetEdge8030.entryRefusal8030(ts, lane)") < g.indexOf("if (chartAdmits7950(ts, lane, nowMs)) return null"))
        assertTrue(g.indexOf("cortex7950?.let { return it }") < g.indexOf("if (chartAdmits7950(ts, lane, nowMs)) return null"))
    }

    @Test fun noProfitExitBelowNetBreakEven() {
        assertEquals(6.0, NetEdge8030.exitFloorPct8030(5.0), 1e-9)
        assertTrue(NetEdge8030.profitExitHeld8030(3.0, 6.0, structural = false))     // +3% at a 5% round trip: held
        assertFalse(NetEdge8030.profitExitHeld8030(7.0, 6.0, structural = false))    // clears: sells
        assertFalse(NetEdge8030.profitExitHeld8030(-4.0, 6.0, structural = false))   // a loss is never held
        assertFalse(NetEdge8030.profitExitHeld8030(3.0, 6.0, structural = true))     // rug / manual: never held
        assertTrue(NetEdge8030.structural8030("RUG_DETECTED"))
        assertTrue(NetEdge8030.structural8030("MANUAL_SELL"))
        assertTrue(NetEdge8030.structural8030("STRICT_SL_-8"))
        assertFalse(NetEdge8030.structural8030("RAPID_TRAILING_STOP"))
        assertFalse(NetEdge8030.structural8030("TREASURY_TAKE_PROFIT"))
        assertFalse(NetEdge8030.structural8030("PROFIT_LOCK_+1"))
        assertEquals(1.0, NetEdge8030.NET_BUFFER_PCT, 0.0)
        assertEquals(0.0273, NetEdge8030.DEFAULT_TICKET_SOL, 0.0)
        val e = src("engine/Executor.kt")
        assertTrue(e.contains("NetEdge8030.exitRefusal8030(ts, reason, getActualPrice(ts), partial = false)"))
        assertTrue(e.contains("NetEdge8030.exitRefusal8030(ts, reason, getActualPrice(ts), partial = true)"))
        assertTrue(NetEdge8030.statusLine().contains("profitExitsHeld="))
    }

    @Test fun oneStopPerLaneAndScalpsClearTheirCost() {
        assertTrue(src("engine/Executor.kt").contains("val rawSL = com.lifecyclebot.engine.cortex.StopAuthority7887.stopMagFor(ts)"))
        assertTrue(src("v3/scoring/MoonshotTraderAI.kt").contains("if (holdMinutes <= 12) stop = maxOf(stop, -15.0)"))
        assertTrue(src("v3/scoring/CashGenerationAI.kt").contains("NetEdge8030.EDGE_MULT * com.lifecyclebot.engine.truth.NetEdge8030.costPct8030(\"TREASURY\", entrySol, 0.0)"))
    }
}
