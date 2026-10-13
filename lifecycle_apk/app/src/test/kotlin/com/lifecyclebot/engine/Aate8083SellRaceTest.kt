package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.SellRouteRace8083
import com.lifecyclebot.network.RaydiumSellRoute7311
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate8083SellRaceTest {
    private val built = RaydiumSellRoute7311.Built(listOf("tx"), 1_000L, 1L)
    @Test fun selectionPicksOneRoute() {
        assertTrue(SellRouteRace8083.preferRaydium(built, drain = true, jupiterOutLamports = 5_000L))
        assertFalse(SellRouteRace8083.preferRaydium(built, drain = false, jupiterOutLamports = 5_000L))
        assertTrue(SellRouteRace8083.preferRaydium(built, drain = false, jupiterOutLamports = null))
        assertFalse(SellRouteRace8083.preferRaydium(null, drain = true, jupiterOutLamports = null))
        assertTrue(SellRouteRace8083.urgent("HARD_FLOOR_STOP", drain = false, fastLand = false))
        assertFalse(SellRouteRace8083.urgent("TAKE_PROFIT_T1", drain = false, fastLand = false))
    }
    @Test fun raceIsWiredBeforeTheJupiterLadderAndNeverDoubleSends() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val start = s.indexOf("val race8083 = startSellRace8083(ts, wallet, reason, confirmedSellUiQty, sellTradeKey)")
        val use = s.indexOf("if (sig == null) sig = trySellRace8083(ts, wallet, c, race8083,")
        val ladder = s.indexOf("for (currentSlip in if (sig != null || jupiterQuoteUnavailable) emptyList<Int>() else broadcastSlipLadder)")
        val serial = s.indexOf("if (rayPlan7311 != null && rayPlan7311.rawAmount > 0L && race8083?.sent != true)")
        assertTrue(start in 0 until use); assertTrue(use < ladder); assertTrue(ladder < serial)
        assertTrue(s.contains("race.sent = true")); assertTrue(s.contains("if (race == null || race.sent) return null"))
    }
}
