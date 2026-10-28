package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test

class Aate8417TradeMemoryEvidenceTest {
    private fun add(id: String, mode: String, pnl: Double, hold: Int, time: Long) {
        LiveWinDNAStore.captureSeed(id, id, "QUALITY", if (mode == "CORPUS") "DEXSCREENER_DAILY" else "TEST_SETTLEMENT",
            if (mode == "CORPUS") "HISTORICAL_SEED_DAILY" else "MID_ACCUMULATION",
            if (mode == "CORPUS") "corpus_ignition" else "TREND_PULLBACK", "HIGHER_LOW", 70,
            100_000.0, 110_000.0, 25_000.0, hold, 60.0, pnl, pnl, "TERMINAL", mode, time)
    }

    @Suppress("UNCHECKED_CAST")
    private fun isolated(block: () -> Unit) {
        val store = LiveWinDNAStore
        val rows = store.javaClass.getDeclaredField("rows").apply { isAccessible = true }
            .get(store) as MutableMap<String, LiveWinDNAStore.WinDNA>
        val saved = rows.toMap()
        val invalidate = store.javaClass.getDeclaredMethod("invalidateSnapshots").apply { isAccessible = true }
        store.beginBulk()
        try { rows.clear(); invalidate.invoke(store); block() }
        finally { rows.clear(); rows.putAll(saved); invalidate.invoke(store); store.endBulk() }
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

    @Test fun delayedOutcomeKeepsItsSettlementModeRatherThanCurrentAppMode() = isolated {
        val prefix = "settled-mode-8417-${System.nanoTime()}"
        for (mode in listOf("paper", "live")) {
            TokenWinMemory.recordTradeOutcome(
                mint = "$prefix-$mode", symbol = "MODE8417", name = "Mode evidence",
                pnlPercent = 12.0, peakPnl = 15.0, entryMcap = 100_000.0, exitMcap = 112_000.0,
                entryLiquidity = 25_000.0, holdTimeMinutes = 20, buyPercent = 60.0,
                source = "SETTLEMENT_REPLAY", phase = "MID_ACCUMULATION",
                mode = mode, proofState = "canonical_finalized",
            )
        }
        val captured = LiveWinDNAStore.topByPnl(500).associateBy { it.mint }
        assertEquals("PAPER", captured["$prefix-paper"]?.paperOrLive)
        assertEquals("LIVE", captured["$prefix-live"]?.paperOrLive)
    }
}
