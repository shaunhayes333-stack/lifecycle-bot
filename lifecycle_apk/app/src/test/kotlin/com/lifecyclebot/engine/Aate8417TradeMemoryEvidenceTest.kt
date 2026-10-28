package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class Aate8417TradeMemoryEvidenceTest {
    private fun terminal(id: String, mode: String, lane: String, pnl: Double, hold: Int, time: Long,
                         side: CanonicalEconomicEvent6635.Side = CanonicalEconomicEvent6635.Side.SELL,
                         seal: Boolean = true): CanonicalFinalizedTradeBus6464.Envelope {
        val pid = "DNA8417-$id-${System.nanoTime()}"
        val eid = "$pid-close"
        val event = CanonicalEconomicEvent6635.Event(eid, pid, id, id, id, mode, lane, side,
            time, BigInteger.TEN, 6, 1.0, 0.01, 1.0 + pnl / 100.0, 0.0, 1.0 + pnl / 100.0,
            BigInteger.TEN.negate(), pnl / 100.0, 1)
        assertTrue(CanonicalEconomicEvent6635.openEvent(event))
        CanonicalEconomicEvent6635.Store.values().forEach {
            assertTrue(CanonicalEconomicEvent6635.markCommitted(eid, it, "test.DNA8417"))
        }
        if (seal) entrySnapshots()[pid] = EntryStrategySnapshot6450.Snapshot(
            pid, id, lane, "strategy8417", "BREAKOUT", "risk", "exit", "PUMP_FUN", 70,
            100.0, 100_000.0, time - hold * 60_000L, "sealed",
            entryLiquidityUsd = 25_000.0, entrySetup = "TREND_PULLBACK", mode8090 = mode)
        return CanonicalFinalizedTradeBus6464.Envelope(eid, time, pnl / 100.0, pnl, id, lane,
            positionId = pid, mode = mode, proofState = "canonical_finalized", holdingTimeMs = hold * 60_000L,
            economicEventId = eid, exitReason = "TERMINAL", mfePct = Double.NaN)
    }

    private fun add(id: String, mode: String, pnl: Double, hold: Int, time: Long) {
        if (mode == "CORPUS") LiveWinDNAStore.captureSeed(id, id, "QUALITY", "DEXSCREENER_DAILY",
            "HISTORICAL_SEED_DAILY", "corpus_ignition", "HIGHER_LOW", 70,
            100_000.0, 110_000.0, 25_000.0, hold, 60.0, pnl, pnl, "TERMINAL", mode, time)
        else assertTrue(LiveWinDNAStore.recordCanonical8417(terminal(id, mode, "QUALITY", pnl, hold, time)))
    }

    private fun preferences(values: MutableMap<String, String>, commit: () -> Boolean = { true }): SharedPreferences {
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { values[args!![0] as String] = args[1] as String; proxy }
                "apply" -> null
                "commit" -> commit()
                else -> proxy
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getString" -> values[args!![0]] ?: args[1]
                else -> null
            }
        } as SharedPreferences
    }

    @Suppress("UNCHECKED_CAST")
    private fun entrySnapshots() = EntryStrategySnapshot6450::class.java.getDeclaredField("snapshots")
        .apply { isAccessible = true }.get(EntryStrategySnapshot6450) as MutableMap<String, EntryStrategySnapshot6450.Snapshot>

    @Suppress("UNCHECKED_CAST")
    private fun isolated(block: () -> Unit) {
        val store = LiveWinDNAStore
        val rows = store.javaClass.getDeclaredField("rows").apply { isAccessible = true }
            .get(store) as MutableMap<String, LiveWinDNAStore.WinDNA>
        val saved = rows.toMap()
        val entries = entrySnapshots().toMap()
        val prefField = store.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
        val savedPrefs = prefField.get(store)
        prefField.set(store, preferences(mutableMapOf()))
        val invalidate = store.javaClass.getDeclaredMethod("invalidateSnapshots").apply { isAccessible = true }
        store.beginBulk()
        CanonicalEconomicEvent6635.resetForTest()
        try { rows.clear(); invalidate.invoke(store); block() }
        finally {
            rows.clear(); rows.putAll(saved); invalidate.invoke(store)
            prefField.set(store, savedPrefs); store.endBulk()
            entrySnapshots().clear(); entrySnapshots().putAll(entries)
            CanonicalEconomicEvent6635.resetForTest()
        }
    }

    @Test fun syntheticWinnersCannotSetSizeEvidenceOrHoldingTime() = isolated {
        repeat(10) { add("corpus-$it", "CORPUS", 500.0, 720, 1_900_000_000_000L + it) }
        assertEquals(10, LiveWinDNAStore.size())
        assertEquals(0, LiveWinDNAStore.realCount())
        assertTrue(LiveWinDNAStore.topByPnl(500).isEmpty())
        assertTrue(LiveWinDNAStore.setupFrequency(1).isEmpty())
        assertTrue(LiveWinDNAStore.chartPatternFrequency(1).isEmpty())
        assertNull(LiveWinDNAStore.winnerHoldTimeStats6920())

        repeat(6) { add("live-$it", "LIVE", 8.0, 25, 1_700_000_000_000L + it) }
        add("paper-loss", "PAPER", -7.0, 4, 1_700_000_000_100L)
        assertEquals(7, LiveWinDNAStore.realCount())
        assertEquals(Triple(25, 25, 25), LiveWinDNAStore.winnerHoldTimeStats6920())
        assertEquals(listOf(Triple("TREND_PULLBACK", 6, 8.0)), LiveWinDNAStore.setupFrequency(1))
        assertEquals(listOf(Triple("TREND_PULLBACK", 1, -7.0)), LiveWinDNAStore.losingSetupFrequency(1))
        assertTrue(LiveWinDNAStore.statusLine().contains("syntheticOrUnproven=10"))
    }

    @Test fun futureDatedDailyCorpusCannotEvictCapturedTrades() = isolated {
        add("live-proof", "LIVE", 9.0, 20, 1_700_000_000_000L)
        add("paper-proof", "PAPER", -4.0, 5, 1_700_000_000_001L)
        repeat(600) { add("future-corpus-$it", "CORPUS", 200.0, 720, 1_900_000_000_000L + it) }
        assertEquals(500, LiveWinDNAStore.size())
        assertEquals(setOf("live-proof", "paper-proof"), LiveWinDNAStore.topByPnl(500).map { it.mint }.toSet())
        val real = LiveWinDNAStore.topByPnl(500).first()
        assertTrue(LiveWinDNAStore.observedTrade8417(LiveWinDNAStore.WinDNA.fromJson(real.toJson())))
        assertFalse(LiveWinDNAStore.observedTrade8417(real.copy(paperOrLive = "UNKNOWN")))
        assertFalse(LiveWinDNAStore.observedTrade8417(real.copy(phase = "HISTORICAL_SEED")))
        assertFalse(LiveWinDNAStore.observedTrade8417(real.copy(exitReason = "PAPER_HISTORY_BACKFILL_6285")))
    }

    @Test fun allTwelveLanesReceiveTheSameCanonicalMemoryPathInBothModes() = isolated {
        val lanes = listOf("QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE",
            "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN")
        val at = maxOf(System.currentTimeMillis(), LearningEpoch8164.epochMs8164()) + 1_000L
        for (mode in listOf("paper", "live")) for (lane in lanes) {
            val mint = "$mode-$lane-8417"
            val env = terminal(mint, mode, lane, 8.0, 25, at)
            // A later same-mint candidate and a PUMP_FUN source must not relabel a QUALITY close as MOONSHOT.
            EntryContextRegistry.stamp(mint, "LATER_SETUP", "LATER_CHART", "MOONSHOT", 99, "LATER")
            try {
                repeat(2) { assertTrue(FinalizedBusConsumerBridge6465.deliver("LiveWinDNAStore", env)) }
                val row = LiveWinDNAStore.topByPnl(500).single { it.mint == mint }
                assertEquals(lane, row.lane)
                assertEquals("TREND_PULLBACK", row.entrySetup)
                assertEquals(mode.uppercase(), row.paperOrLive)
                assertEquals(env.positionId, row.positionId)
                assertEquals(env.economicEventId, row.economicEventId)
                assertTrue(row.peakPnl.isNaN())
                assertTrue(LiveWinDNAStore.WinDNA.fromJson(row.toJson()).peakPnl.isNaN())
                assertEquals("LATER_SETUP", EntryContextRegistry.peek(mint)?.entrySetup)
            } finally { EntryContextRegistry.consume(mint) }
        }
        assertEquals(24, LiveWinDNAStore.realCount())
        assertEquals(listOf(Triple("TREND_PULLBACK", 24, 8.0)), LiveWinDNAStore.setupFrequency(1))
        assertNull("unknown excursions cannot establish perfect profit capture", LiveWinDNAStore.captureRatioOverall6925())
    }

    @Test fun partialAndUnattributedSettlementsCannotBecomeCompletedStrategyLessons() = isolated {
        val at = System.currentTimeMillis()
        val partial = terminal("partial8417", "live", "QUALITY", 8.0, 10, at,
            side = CanonicalEconomicEvent6635.Side.PARTIAL_SELL)
        assertNull(CanonicalTerminalProof6699.resolve(partial.positionId, partial.economicEventId))
        assertFalse(LiveWinDNAStore.recordCanonical8417(partial))
        val noEntry = terminal("no-entry8417", "live", "QUALITY", 8.0, 10, at, seal = false)
        assertFalse(LiveWinDNAStore.recordCanonical8417(noEntry))
        val good = terminal("mismatch8417", "live", "QUALITY", 8.0, 10, at)
        assertFalse(LiveWinDNAStore.recordCanonical8417(good.copy(mode = "paper")))
        assertEquals(0, LiveWinDNAStore.realCount())
    }

    @Test fun settledSetupChangesOnlyItsOwningLanesNextGovernorDecision() = isolated {
        val method = LiveLaneGovernor::class.java.getDeclaredMethod("dnaProvenBypass",
            String::class.java, String::class.java, String::class.java).apply { isAccessible = true }
        fun decision(lane: String) = method.invoke(LiveLaneGovernor, lane, "TREND_PULLBACK", "UNKNOWN")
        assertNull(decision("QUALITY"))
        repeat(3) { add("next-$it", "LIVE", 30.0, 25, System.currentTimeMillis()) }
        assertNotNull(decision("QUALITY"))
        assertNull("another lane cannot borrow the setup's wins", decision("MOONSHOT"))
        assertTrue("unknown chart patterns are not observed winning patterns", LiveWinDNAStore.chartPatternFrequency(1).isEmpty())
        repeat(3) { add("next-loss-$it", "LIVE", -40.0, 25, System.currentTimeMillis()) }
        assertNull("settled losses must withdraw the setup proof", decision("QUALITY"))
    }

    @Test fun failedDurableWriteIsRetriedWithoutDuplicatingTheLesson() = isolated {
        val store = LiveWinDNAStore
        val f = store.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
        var commitWorks = false
        f.set(store, preferences(mutableMapOf()) { commitWorks })
        val env = terminal("retry8417", "live", "QUALITY", 8.0, 20, System.currentTimeMillis())
        assertFalse(store.recordCanonical8417(env))
        commitWorks = true
        assertTrue(store.recordCanonical8417(env))
        assertEquals(1, store.realCount())
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun persistedCanonicalIdentityMakesRedeliveryAfterRestartIdempotent() = isolated {
        val values = mutableMapOf<String, String>()
        val prefs = preferences(values)
        val store = LiveWinDNAStore
        val field = store.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
        val oldPrefs = field.get(store)
        try {
            field.set(store, prefs)
            val env = terminal("restart8417", "live", "QUALITY", -7.0, 20, System.currentTimeMillis())
            assertTrue(store.recordCanonical8417(env))
            store.javaClass.getDeclaredMethod("persistNow").apply { isAccessible = true }.invoke(store)
            assertTrue(values["rows"]!!.contains(env.positionId))
            val rows = store.javaClass.getDeclaredField("rows").apply { isAccessible = true }
                .get(store) as MutableMap<String, LiveWinDNAStore.WinDNA>
            rows.clear()
            store.javaClass.getDeclaredMethod("load").apply { isAccessible = true }.invoke(store)
            assertTrue(store.recordCanonical8417(env))
            assertEquals(1, store.realCount())
            assertEquals(-7.0, store.topByPnl(1).single().pnlPct, 0.0)
            assertEquals(env.economicEventId, store.topByPnl(1).single().economicEventId)
        } finally { field.set(store, oldPrefs) }
    }
}
