package com.lifecyclebot.perps

import com.lifecyclebot.engine.StrategyTelemetry
import com.lifecyclebot.engine.truth.CryptoSpotPaperBacking8788
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8883 — owner: "the shadow trading engine is meant to basically be exactly the same as the live trader in all forms
 * and functions for the meme and crypto traders". The crypto exits are one pair of functions the live monitor and the crypto
 * twin both call; the twin's record backs the crypto lanes in LIVE once it is decisive.
 */
class Aate8883CryptoTwinTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Before fun setUp() = CryptoTwin8883.resetForTest8883()
    @After fun tearDown() = CryptoTwin8883.resetForTest8883()

    private fun pos(current: Double, highest: Double = 0.0, sl: Double = 0.0, tp: Double = 0.0) = CryptoAltTrader.AltPosition(
        id = "t8883", market = PerpsMarket.SOL, direction = PerpsDirection.LONG, isSpot = true, isPaper = false,
        canonicalAssetKey = "SOL", markAssetKey = "SOL", markUpdatedAtMs = System.currentTimeMillis(),
        entryPrice = 100.0, currentPrice = current, sizeSol = 0.1, leverage = 1.0, takeProfitPrice = tp, stopLossPrice = sl,
        aiScore = 60, aiConfidence = 60, reasons = emptyList(), highestPnlPct = highest,
    )

    private class Sink : CryptoAltTrader.ExitSink8883 {
        val warns = ArrayList<String>(); val labels = ArrayList<String>(); var published = 0
        override fun warn(msg: String) { warns += msg }
        override fun label(name: String) { labels += name }
        override fun forensic(event: String, detail: String) {}
        override fun published(p: CryptoAltTrader.AltPosition) { published++ }
    }

    @Test fun theEvictionPassIsTheLiveOnesRungsTrailAndCleanups() {
        val rung = CryptoAltTrader.evict8883(6.0, 60_000L, 0, null)
        assertEquals("the +5 % rung fires once, recorded for the trail", 0, rung.rungFired)
        assertEquals(1, rung.rungIdx)
        assertNull("a winner is protected", rung.reason)
        val trail = CryptoAltTrader.evict8883(2.0, 60_000L, 1, 10.0)
        assertEquals("FLUID_TRAIL", trail.kind)
        assertEquals("FLUID_TRAIL_LOCK: peak=+10.0% → now=+2.0% (50% floor)", trail.reason)
        assertNull(CryptoAltTrader.evict8883(1.5, 3 * 3_600_000L, 0, null).reason)
        assertEquals("DEADWEIGHT: +0% after 61min — freeing slot", CryptoAltTrader.evict8883(0.2, 61 * 60_000L, 0, null).reason)
        assertEquals("STAGNANT: 0% after 50min — no momentum", CryptoAltTrader.evict8883(-0.3, 50 * 60_000L, 0, null).reason)
        assertEquals("FAST_LOSER: -4% after 4min — cut early", CryptoAltTrader.evict8883(-4.0, 4 * 60_000L, 0, null).reason)
        assertNull("under three minutes a small loss is held", CryptoAltTrader.evict8883(-4.0, 2 * 60_000L, 0, null).reason)
    }

    @Test fun theTickIsTheLiveOnesFloorLockAndStops() {
        val s = Sink()
        val floor = CryptoAltTrader.exitTick8883("t", pos(85.0), 85.0, "SOL", false, s)
        assertEquals(CryptoAltTrader.ExitAct8883.CLOSE, floor.act)
        assertEquals("TICK_HARD_FLOOR_-15PCT", floor.reason)
        assertTrue(floor.strikeAfter)
        val phantom = CryptoAltTrader.exitTick8883("t", pos(40.0), 40.0, "SOL", false, s)
        assertEquals("a read under -50 % waits for a second one", CryptoAltTrader.ExitAct8883.HOLD, phantom.act)
        assertTrue(phantom.strikeAfter)
        assertTrue(s.labels.contains("CRYPTO_ALT_TICK_FLOOR_PHANTOM_DEFERRED_7264"))
        assertEquals("TICK_HARD_FLOOR_-60PCT", CryptoAltTrader.exitTick8883("t", pos(40.0), 40.0, "SOL", true, s).reason)
        val lock = CryptoAltTrader.exitTick8883("t", pos(110.0, highest = 40.0), 110.0, "SOL", false, s)
        assertEquals("TICK_PROFIT_LOCK_peak40_now10", lock.reason)
        assertFalse(lock.strikeAfter)
        assertTrue(s.warns.any { it.startsWith("🔒 TICK_PROFIT_LOCK ") })
        assertEquals("HARD_SL: price=95.000000 crossed SL=96.000000 (-5.00%)",
            CryptoAltTrader.exitTick8883("t", pos(95.0, sl = 96.0), 95.0, "SOL", false, s).reason)
        assertEquals("nothing is published before the stops have been read", 0, s.published)
    }

    @Test fun theLiveMonitorAndTheTwinCallTheSameFunctions() {
        val alt = src("perps/CryptoAltTrader.kt")
        val evict = alt.substringAfter("private fun evictStagnantAndLosers() {").substringBefore("private fun evictWeakestForReplacement(")
        assertTrue(evict.contains("val ev8883 = try { evict8883(pnlPct, holdMs, partialLadderHit[id] ?: 0, trailPeakPct[id]) }"))
        assertTrue(evict.contains("closePosition(id, why8883)"))
        val monitor = alt.substringAfter("private suspend fun monitorPositions() {").substringBefore("private fun partialPosition6566(")
        assertTrue(monitor.contains("val tick8883 = exitTick8883(id, updated, markPrice, validatedMarkKey, tickFloorStrike7264[id] == true, liveSink8883(id))"))
        assertTrue(monitor.contains("ExitAct8883.PARTIAL -> partialPosition6566(id, tick8883.reason)"))
        assertFalse("no exit check is left inline", monitor.contains("SymbolicExitReasoner.assess(") || monitor.contains("TICK_PROFIT_LOCK_peak"))
        assertTrue(monitor.contains("if (liveRuntime8883() && CryptoTwin8883.hasOpen8883()) scope.launch(kotlinx.coroutines.Dispatchers.IO)"))
        val twin = src("perps/CryptoTwin8883.kt")
        assertTrue(twin.contains("CryptoAltTrader.evict8883(pos.getPnlPct(), nowMs - pos.openTime, t.rungIdx, t.trailPeak)"))
        assertTrue(twin.contains("CryptoAltTrader.exitTick8883(updated.id, updated, mark, markKey, t.strike, NO_SINK_8883)"))
        // the twin opens on the position the live book builds, through one construction
        assertTrue(alt.contains("val position = entryPosition8883(\"ALT:\${canonicalCryptoIntent6565.attemptId}\", signal, isSpot, candidate, mktSym,"))
        assertTrue(alt.contains("if (!paper7803) try { CryptoTwin8883.open8883(position, candidate.selectedLane) }"))
        assertTrue(alt.contains("if (!paper7803 && !com.lifecyclebot.engine.truth.CryptoSignalEngine8857.integrityReason8857(canonicalCryptoAdmission6565.reason))"))
    }

    @Test fun theTwinsRecordIsGradedByTheLeaderboardsOwnComputation() {
        for ((i, pnl) in listOf(10.0, -5.0, 0.2, 3.0).withIndex()) CryptoTwin8883.addCloseForTest8883("CRYPTO_ALT", 1_000L + i, pnl, 0.1)
        val m = CryptoTwin8883.metric8883("crypto_alt")!!
        assertEquals(4, m.trades); assertEquals(2, m.wins); assertEquals(1, m.losses); assertEquals(1, m.scratches)
        assertEquals(2.05, m.meanPnlPct, 1e-9)
        assertEquals(0.0082, m.totalSolPnl, 1e-9)
        assertEquals(6.5, m.avgWinPct, 1e-9)
        assertEquals(-5.0, m.avgLossPct, 1e-9)
        assertEquals("the leaderboard's own win rate over decisive closes", 200.0 / 3.0, m.winRatePct, 1e-9)
        val rows = CryptoTwin8883.rows8883(listOf(doubleArrayOf(1.0, 10.0, 0.1)))
        assertEquals("pnl SOL is size x pnl %, as the paper book booked it", 0.01, rows.single().pnlSol, 1e-12)
        assertTrue(src("engine/StrategyTelemetry.kt").contains("metricOf8883(strategy, trades.map { Row8883(it.pnlPct, it.pnlSol, it.sol, it.entryCostSol) })"))
        val stored = CryptoTwin8883.encode8883(listOf(doubleArrayOf(5.0, 1.5, 0.2)))
        assertEquals("5,1.5,0.2", stored)
        assertEquals(1.5, CryptoTwin8883.decode8883("$stored;bad;7,x,1")[0][1], 0.0)
        assertEquals(1, CryptoTwin8883.decode8883("$stored;bad;7,x,1").size)
    }

    @Test fun theTwinBacksOnlyOnceItIsDecisiveAndThePaperRecordStandsUntilThen() {
        fun metric(w: Int, l: Int) = StrategyTelemetry.StrategyMetric("CRYPTO_ALT", w + l, w, l, 0, 10.0, 1.0, 50.0, 0.1)
        val paper = metric(100, 80)
        assertSame(paper, CryptoSpotPaperBacking8788.record8883(metric(10, 9), paper))
        val twin = metric(12, 8)
        assertSame(twin, CryptoSpotPaperBacking8788.record8883(twin, paper))
        assertSame(paper, CryptoSpotPaperBacking8788.record8883(null, paper))
        val backing = src("engine/truth/CryptoSpotPaperBacking8788.kt")
        assertTrue(backing.contains("com.lifecyclebot.engine.LiveNoPaper8184.closeOutInLive8872()"))
        assertTrue(backing.contains("val m = record8883(twin, paper)"))
        val mirror = src("engine/truth/PaperMirror8757.kt").substringAfter("internal fun bookLosing8883(").substringBefore("fun economicsClear8758(")
        // superseded in part by V5.0.8890: the shadow's proof is read beside the paper book's, and can only add a stop
        assertTrue(mirror.contains("return stops8890(paperLosing, p.n, MemePaperTrader8544.provenLosing8608(p.n, p.meanPct, p.sePct, p.pf))"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("CryptoTwin8883.statusLine8883()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 72. The crypto twin (8883)"))
    }

    @Test fun oneTwinPerAssetAndNoneWithoutAPrice() {
        CryptoTwin8883.open8883(pos(100.0), "CRYPTO_SPOT")
        CryptoTwin8883.open8883(pos(100.0), "CRYPTO_SPOT")
        assertTrue(CryptoTwin8883.hasOpen8883())
        assertTrue(CryptoTwin8883.statusLine8883(), CryptoTwin8883.statusLine8883().contains("open=1/60 opened=1 dup=1"))
        CryptoTwin8883.resetForTest8883()
        CryptoTwin8883.open8883(pos(100.0).copy(entryPrice = 0.0), "CRYPTO_SPOT")
        assertFalse(CryptoTwin8883.hasOpen8883())
        assertNotNull(CryptoTwin8883.statusLine8883())
    }
}
