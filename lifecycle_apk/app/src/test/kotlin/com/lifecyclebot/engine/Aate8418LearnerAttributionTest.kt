package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import com.lifecyclebot.engine.truth.ExactStrategyPerformance7429
import com.lifecyclebot.engine.truth.FieldManual7715
import com.lifecyclebot.engine.truth.LearnerAttribution8418
import com.lifecyclebot.engine.truth.SpecialistMiner7972
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8418 — learners beyond the canonical DNA path: books apart, once per id, no replay after a restart. */
class Aate8418LearnerAttributionTest {
    private fun env(id: String, mode: String, mint: String = "m$id") = CanonicalFinalizedTradeBus6464.Envelope(
        tradeId = id, positionId = id, atMs = 1_000L, realizedPnlSol = 0.01, realizedReturnPct = 12.0,
        mint = mint, lane = "SHITCOIN", mode = mode, learningEligible = true)

    @Test fun theLedgerRefusesADuplicateAndAReplayAndKeepsItsBound() {
        val l = LearnerAttribution8418.Ledger8418(capacity = 2)
        assertEquals(LearnerAttribution8418.Verdict8418.ADMIT, l.admit("a"))
        assertEquals(LearnerAttribution8418.Verdict8418.DUPLICATE, l.admit("a"))
        val restarted = LearnerAttribution8418.Ledger8418()
        restarted.loadPrior(listOf("a"))
        assertEquals(LearnerAttribution8418.Verdict8418.REPLAY, restarted.admit("a"))
        assertEquals(LearnerAttribution8418.Verdict8418.ADMIT, restarted.admit("b"))
        l.admit("b"); l.admit("c")   // capacity 2: the oldest current id is evicted
        assertEquals(LearnerAttribution8418.Verdict8418.ADMIT, l.admit("a"))
    }

    @Test fun booksAreCountedApartAndAnUnknownBookIsRefused() {
        LearnerAttribution8418.resetForTest8418(mapOf("L8418" to listOf("old")))
        assertFalse(LearnerAttribution8418.admit8418("L8418", "old", "live"))          // persisted before the restart
        assertTrue(LearnerAttribution8418.admit8418("L8418", "t1", "live"))
        assertFalse(LearnerAttribution8418.admit8418("L8418", "t1", "live"))           // same close twice
        assertTrue(LearnerAttribution8418.admit8418("L8418", "t2", "paper"))
        assertFalse(LearnerAttribution8418.admit8418("L8418", "t3", "unknown"))        // neither book
        assertFalse(LearnerAttribution8418.admit8418("L8418", "t4", "SHADOW"))
        assertTrue(LearnerAttribution8418.admit8418("L8418", "", "paper"))             // no id: admitted, counted
        val line = LearnerAttribution8418.statusLine8418()
        assertTrue(line, line.contains("L8418: paper=2,live=1,dupRefused=1,replayRefused=1"))
        assertTrue(line, line.contains("bookRefused=2"))
        assertTrue(line, line.contains("noId=1"))
    }

    @Test fun aOneBookLearnerOnlyLearnsFromTheRuntimesBook() {
        LearnerAttribution8418.resetForTest8418()
        assertFalse(LearnerAttribution8418.admitRuntimeBookOf8418("T8418", "p1", "PAPER", "LIVE"))  // paper never moves live
        assertFalse(LearnerAttribution8418.admitRuntimeBookOf8418("T8418", "l1", "LIVE", "PAPER"))
        assertFalse(LearnerAttribution8418.admitRuntimeBookOf8418("T8418", "x1", "LABEL", "LIVE"))
        assertFalse(LearnerAttribution8418.admitRuntimeBookOf8418("T8418", "x2", "LIVE", null))
        assertTrue(LearnerAttribution8418.admitRuntimeBookOf8418("T8418", "l2", "LIVE", "LIVE"))
        assertFalse(LearnerAttribution8418.admitRuntimeBookOf8418("T8418", "l2", "LIVE", "LIVE"))
        assertTrue(LearnerAttribution8418.statusLine8418().contains("T8418: paper=0,live=1,dupRefused=1,replayRefused=0,bookRefused=4"))
    }

    @Test fun theAllocatorBooksAPublishedCloseOnceEvenWhenRepublishedAfterARestart() {
        LearnerAttribution8418.resetForTest8418(mapOf("CellAllocator7962.close" to listOf("replayed8418")))
        fun closes(): Pair<Long, Long> {
            val s = CellAllocator7962.statusLine()
            val live = Regex("liveCloses=(\\d+)").find(s)!!.groupValues[1].toLong()
            val paper = Regex("paperCloses=(\\d+)").find(s)!!.groupValues[1].toLong()
            return live to paper
        }
        val (l0, p0) = closes()
        CellAllocator7962.onClose7962(env("live8418", "live"))
        CellAllocator7962.onClose7962(env("live8418", "live"))        // a second delivery of the same close
        CellAllocator7962.onClose7962(env("replayed8418", "live"))    // the reconciler's post-restart republish
        CellAllocator7962.onClose7962(env("paper8418", "paper"))
        CellAllocator7962.onClose7962(env("unk8418", "unknown"))
        val (l1, p1) = closes()
        assertEquals(1L, l1 - l0)
        assertEquals(1L, p1 - p0)
        val line = LearnerAttribution8418.statusLine8418()
        assertTrue(line, line.contains("CellAllocator7962.close: paper=1,live=1,dupRefused=1,replayRefused=1"))
    }

    @Test fun theSpecialistMinerBooksOneLabelPerDecision() {
        SpecialistMiner7972.resetForTest7972()
        val facts = listOf("F1", "F2", "F3")
        SpecialistMiner7972.onLabel7972("SHITCOIN", "M8418", facts, 10.0, 12.0, 5_000L)
        SpecialistMiner7972.onLabel7972("SHITCOIN", "M8418", facts, 10.0, 12.0, 5_000L)   // a recovered horizon
        SpecialistMiner7972.onLabel7972("SHITCOIN", "M8418", facts, 10.0, 12.0, 6_000L)   // a different decision
        assertTrue(SpecialistMiner7972.statusLine7972(), SpecialistMiner7972.statusLine7972().contains("labels=2 "))
        assertTrue(LearnerAttribution8418.statusLine8418().contains("SpecialistMiner7972: paper=0,live=0,dupRefused=1,replayRefused=0,label=2"))
        SpecialistMiner7972.resetForTest7972()
    }

    @Test fun liveExactStrategyEvidenceIsNeverSeededByPaper() {
        val ev = ExactStrategyPerformance7429.evidenceFor7431(true, "SHITCOIN", "T8418", "S8418", "STYLE8418", "TACTIC8418")
        if (ev != null) assertFalse(ev.source, ev.source.startsWith("PAPER"))
        else assertNull(ev)
    }

    @Test fun cyclicHasItsOwnRangeCycleMandate() {
        val m = FieldManual7715.mandateFor("CYCLIC")
        assertEquals("CYCLIC", m.lane)
        assertEquals(FieldManual7715.SetupFamily.RANGE_REVERSION, m.setup)
        assertEquals(20.0, m.expectedGrossPct, 0.0)
        assertEquals(10.0, m.invalidationPct, 0.0)
        assertFalse(m.runner)
        // not the generic breakout mandate it used to fall through to
        assertFalse(FieldManual7715.mandateFor("SOMETHING_ELSE").setup == m.setup)
        // the stop CYCLIC trades is never inside its invalidation
        assertTrue(FieldManual7715.stopPct8392("CYCLIC", 2.0) >= 10.0)
        assertEquals(15.0, FieldManual7715.stopPct8392("CYCLIC", 10.0), 1e-9)
    }

    @Test fun liveForecastIsNeverSeededByPaperOrLabels() {
        ForwardOutcomeModel.reset()
        val lane = "FWD8418"
        repeat(30) { i -> ForwardOutcomeModel.recordLabel7734(lane, 85, "A", "NORMAL", "EXPANSION", 40.0, "lab8418|$i") }
        LearningEnvironment7835.withMode("PAPER") {
            repeat(12) { i ->
                ForwardOutcomeModel.stamp("pm8418$i", lane, 85, "A", "NORMAL", "EXPANSION")
                ForwardOutcomeModel.recordOutcome("pm8418$i", 60.0)
            }
        }
        val paper = LearningEnvironment7835.withMode("PAPER") { ForwardOutcomeModel.forecast(lane, 85, "A", "NORMAL", "EXPANSION") }
        assertTrue(paper.source, paper.samples > 0L)
        val live = LearningEnvironment7835.withMode("LIVE") { ForwardOutcomeModel.forecast(lane, 85, "A", "NORMAL", "EXPANSION") }
        assertEquals("bootstrap", live.source)
        assertEquals(0.5, live.pWin, 0.0)
        assertEquals(1.0, live.convictionNudge, 0.0)
        ForwardOutcomeModel.reset()
    }

    @Test fun tradingMemoryLearnsOnlyFromTheRuntimesBookOncePerClose() {
        LearnerAttribution8418.resetForTest8418()
        // the unit-test runtime is PAPER: a live close, an unknown book and a repeat are all refused
        TradingMemory.learnFromWinningTrade("tm8418", "TM", 30.0, "p", "FLAT", "SRC", 5.0, live8418 = true, closeId8418 = "c1")
        TradingMemory.learnFromWinningTrade("tm8418", "TM", 30.0, "p", "FLAT", "SRC", 5.0, closeId8418 = "c2")
        TradingMemory.learnFromWinningTrade("tm8418", "TM", 30.0, "p", "FLAT", "SRC", 5.0, live8418 = false, closeId8418 = "c3")
        TradingMemory.learnFromWinningTrade("tm8418", "TM", 30.0, "p", "FLAT", "SRC", 5.0, live8418 = false, closeId8418 = "c3")
        val line = LearnerAttribution8418.statusLine8418()
        assertTrue(line, line.contains("TradingMemory.win: paper=1,live=0,dupRefused=1,replayRefused=0,bookRefused=2"))
    }

    @Test fun observedAndRebuiltLearnersReportTheirBooks() {
        LearnerAttribution8418.resetForTest8418()
        LearnerAttribution8418.observe8418("Cx8418", "LIVE", LearnerAttribution8418.Verdict8418.ADMIT)
        LearnerAttribution8418.observe8418("Cx8418", "LIVE", LearnerAttribution8418.Verdict8418.DUPLICATE)
        LearnerAttribution8418.observe8418("Cx8418", "PAPER", LearnerAttribution8418.Verdict8418.REPLAY)
        assertTrue(LearnerAttribution8418.statusLine8418().contains("Cx8418: paper=0,live=1,dupRefused=1,replayRefused=1"))
        LearnerAttribution8418.rebuilt8418("Lp8418", 7L, 3L, 2L)
        LearnerAttribution8418.rebuilt8418("Lp8418", 8L, 3L, 1L)   // a rebuild replaces, never adds
        assertTrue(LearnerAttribution8418.statusLine8418().contains("Lp8418: paper=8,live=3,dupRefused=1,replayRefused=0"))
    }
}
