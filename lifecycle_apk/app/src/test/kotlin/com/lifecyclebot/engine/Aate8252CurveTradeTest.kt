package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CurveShapeFeed8236
import com.lifecyclebot.network.CurveTicks7968
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * V5.0.8252 — the bonding-curve account IS the tape: two consecutive reserve snapshots state the executed trade
 * (side, SOL, tokens), so curve ticks stop arriving as price-only prints (5.0.8251: 4,519 ticks, swaps missing on
 * 12,092 evaluations, PumpPortal 94 prints).
 */
class Aate8252CurveTradeTest {
    private fun src(rel: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private fun curve(vTok: Long, vSol: Long, complete: Boolean = false): ByteArray {
        val b = ByteBuffer.allocate(49).order(ByteOrder.LITTLE_ENDIAN)
        b.putLong(0L).putLong(vTok).putLong(vSol).putLong(0L).putLong(0L).putLong(1_000_000_000_000_000L).put((if (complete) 1 else 0).toByte())
        return b.array()
    }

    @Test fun twoSnapshotsStateTheTradeExactly() {
        val launch = CurveTicks7968.decodeReserves8252(curve(1_073_000_000_000_000L, 30_000_000_000L))!!
        // a buy of 0.5 SOL took 17,000,000 tokens out of the curve
        val afterBuy = CurveTicks7968.decodeReserves8252(curve(1_073_000_000_000_000L - 17_000_000_000_000L, 30_500_000_000L))!!
        val buy = CurveTicks7968.tradeBetween8252(launch, afterBuy)!!
        assertTrue(buy.isBuy)
        assertEquals(0.5, buy.sol, 1e-9)
        assertEquals(17_000_000.0, buy.tokens, 1e-3)
        // a sell returns tokens and takes SOL out
        val afterSell = CurveTicks7968.decodeReserves8252(curve(1_073_000_000_000_000L - 12_000_000_000_000L, 30_320_000_000L))!!
        val sell = CurveTicks7968.tradeBetween8252(afterBuy, afterSell)!!
        assertFalse(sell.isBuy)
        assertEquals(0.18, sell.sol, 1e-9)
        assertEquals(5_000_000.0, sell.tokens, 1e-3)
        // first snapshot, no movement, or an inconsistent rewrite: nothing is stated (price-only stays)
        assertNull(CurveTicks7968.tradeBetween8252(null, launch))
        assertNull(CurveTicks7968.tradeBetween8252(launch, launch))
        assertNull(CurveTicks7968.tradeBetween8252(launch, CurveTicks7968.Reserves8252(launch.vTok + 1.0, launch.vSol + 1.0)))
        // a graduated curve states nothing
        assertNull(CurveTicks7968.decodeReserves8252(curve(1L, 1L, complete = true)))
        assertNotNull(CurveTicks7968.decode7968(curve(1_073_000_000_000_000L, 30_000_000_000L)))
    }

    @Test fun theCurveTradeWalksTheOneTapePathUnlessAKeyedStreamHasTheMint() {
        val ct = src("network/CurveTicks7968.kt")
        val handle = ct.substringAfter("val (px, mc) = decode7968(bytes) ?: return").substringBefore("fun statusLine7968()")
        assertTrue(handle.contains("tradeBetween8252(lastReserves8252.put(mint, cur), cur)"))
        assertTrue(handle.contains("onTrade8252(mint, px, trade.sol, trade.isBuy)"))
        // the 8169 price-only print is the fallback, not replaced
        assertTrue(handle.contains("TradeTape8100.notePrice8169(mint, px, com.lifecyclebot.engine.truth.TradeTape8100.SRC_CURVE_8169)"))
        assertTrue(ct.contains("lastReserves8252.remove(mint)"))
        val orch = src("engine/DataOrchestrator.kt")
        val fn = orch.substringAfter("fun onCurveTrade8252(").substringBefore("return true")
        assertTrue(fn.contains("lastPumpPortalTradeMs7773[mint]"))
        assertTrue(fn.contains("lastHeliusTradeMs8252[mint]"))
        assertTrue(fn.contains("TradeTape8100.note8100(mint, priceSol, sol, isBuy, now, com.lifecyclebot.engine.truth.TradeTape8100.SRC_CURVE_8169)"))
        assertTrue(fn.contains("onTapeTrade7773(mint, \"\", sol, isBuy, 0.0)"))
        assertTrue(orch.substringAfter("fun onHeliusTrade7773(").substringBefore("}").contains("lastHeliusTradeMs8252[mint] = System.currentTimeMillis()"))
        assertTrue(src("engine/BotService.kt").contains("CurveTicks7968.onTrade8252 { mint, priceSol, sol, isBuy ->"))
    }

    @Test fun theLaunchCapProxyStandsDownWhenTheCurveStatesRealBuyers() {
        val d = com.lifecyclebot.engine.truth.OnChainDepth8038
        assertTrue(d.atLaunchCap8047(3_218.0 / 104.81))
        assertFalse(d.curveBuyersProven8252(null))
        assertFalse(d.curveBuyersProven8252(1.9))        // CURVE_NOBODY_BOUGHT_8046 territory: still refused there
        assertTrue(d.curveBuyersProven8252(3.0))
        assertTrue(d.curveBuyersProven8252(7.4))
        val src = src("engine/truth/OnChainDepth8038.kt")
        assertTrue(src.contains("if (atLaunchCap8047(capSol8047) && !launchCapStoodDown8094(ts.mint, nowMs)) {"))
        assertTrue(src.contains("LaunchBuyers8252.BUYERS_PROVEN -> return null"))
        // the direct measurement stays binding and runs first
        val door = src.substringAfter("fun buyRefusal8038(").substringBefore("AT_LAUNCH_CAP_8047")
        assertTrue(door.contains("return \"CURVE_NOBODY_BOUGHT_8046\""))
    }

    @Test fun curveSubscriptionsRankNearDecisionCandidatesFirst() {
        val now = 1_000_000L
        val fresh = TokenState(mint = "fresh").also { it.addedToWatchlistAt = now - 10_000L }
        val asking = TokenState(mint = "asking").also { it.addedToWatchlistAt = now - 50L * 60_000L }
        val idle = TokenState(mint = "idle").also { it.addedToWatchlistAt = now - 20_000L }
        val set = CurveShapeFeed8236.subscriptionSet8236(setOf("held"), listOf(idle, fresh, asking), now,
            hasCurveKey = { true }, graduated = { false }, maxCandidates = 2, nearDecision = { it == "asking" })
        assertEquals(listOf("held", "asking", "fresh"), set.toList())
    }
}
