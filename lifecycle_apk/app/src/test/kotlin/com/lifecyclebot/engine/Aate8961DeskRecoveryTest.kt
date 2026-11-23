package com.lifecyclebot.engine

import com.lifecyclebot.perps.CryptoSignalPriority8961
import com.lifecyclebot.xstocks.XsRecovery8961
import org.junit.Assert.*
import org.junit.Test

class Aate8961DeskRecoveryTest {
    private data class Signal(val name: String, val record: Int, val score: Int, val confidence: Int)

    @Test fun spotEvaluationSlotsReachTheirBackedStrategyBeforeHigherScoreLosingSetups() {
        val signals = listOf(Signal("losing", -1, 99, 99), Signal("unknown", 0, 95, 90),
            Signal("backed", 1, 61, 52))
        val selected = CryptoSignalPriority8961.order(signals, 2, { it.record }, { it.score }, { it.confidence })
        assertEquals(listOf("backed", "unknown"), selected.map { it.name })
        assertEquals(3, signals.size)
    }

    @Test fun absentRecordsRetainScorePriorityAndTheWorkLimit() {
        val signals = listOf(Signal("low", 0, 60, 90), Signal("high", 0, 90, 50),
            Signal("tie", 0, 90, 80))
        assertEquals(listOf("tie", "high"),
            CryptoSignalPriority8961.order(signals, 2, { it.record }, { it.score }, { it.confidence }).map { it.name })
        assertTrue(CryptoSignalPriority8961.order(signals, 0, { it.record }, { it.score }, { it.confidence }).isEmpty())
    }

    @Test fun aRefreshingRecordIsReadOncePerCandidateBeforeSorting() {
        val signals = listOf(Signal("a", 0, 70, 80), Signal("b", 1, 60, 60), Signal("c", -1, 99, 99))
        val calls = mutableMapOf<String, Int>()
        val selected = CryptoSignalPriority8961.order(signals, 3, {
            val n = (calls[it.name] ?: 0) + 1
            calls[it.name] = n
            if (n == 1) it.record else -it.record
        }, { it.score }, { it.confidence })
        assertEquals(mapOf("a" to 1, "b" to 1, "c" to 1), calls)
        assertEquals(listOf("b", "a", "c"), selected.map { it.name })
    }

    @Test fun aPartialStockPanelDoesNotTurnAnUnansweredHoldingIntoASell() {
        val symbols = XsRecovery8961.rebalanceSymbols(setOf("AAPL"), setOf("TSLA", "NVDA"), setOf("TSLA"))
        assertEquals(setOf("AAPL", "NVDA"), symbols)
        assertFalse(symbols.contains("TSLA"))
        assertTrue(XsRecovery8961.rebalanceSymbols(emptySet(), setOf("TSLA"), emptySet()).contains("TSLA"))
    }

    @Test fun unchangedPartialHistoryDoesNotChargeAnotherDailyRebalanceOnEveryRetry() {
        assertFalse(XsRecovery8961.needsEvaluation(100, setOf("AAPL"), 100, setOf("AAPL")))
        assertTrue(XsRecovery8961.needsEvaluation(100, setOf("AAPL", "TSLA"), 100, setOf("AAPL")))
        assertTrue(XsRecovery8961.needsEvaluation(101, setOf("AAPL"), 100, setOf("AAPL")))
        assertFalse(XsRecovery8961.needsEvaluation(101, emptySet(), 100, setOf("AAPL")))
    }

    @Test fun researchRecoversNewCostsAndFailuresWithoutRepeatingEveryQuoteTick() {
        assertTrue(XsRecovery8961.researchDue(200, 100, false, setOf("AAPL"), setOf("AAPL", "TSLA")))
        assertTrue(XsRecovery8961.researchDue(200, 100, true, setOf("AAPL"), setOf("AAPL")))
        assertFalse(XsRecovery8961.researchDue(99, 100, true, emptySet(), setOf("AAPL")))
        assertFalse(XsRecovery8961.researchDue(200, 100, false, setOf("AAPL"), setOf("AAPL")))
        assertTrue(XsRecovery8961.researchDue(200, 100, false, setOf("AAPL"), emptySet()))
    }

    @Test fun unansweredHoldingsAndFailedClosesCannotFinanceAnotherFullBudget() {
        val proposed = mapOf("AAPL" to 0.3, "NVDA" to 0.2)
        val sizes = XsRecovery8961.boundOpenSizes(proposed, budget = 0.5, committed = 0.4, minimum = 0.02)
        assertEquals(0.06, sizes.getValue("AAPL"), 1e-10)
        assertEquals(0.04, sizes.getValue("NVDA"), 1e-10)
        assertTrue(sizes.values.sum() + 0.4 <= 0.5 + 1e-10)
        assertTrue(XsRecovery8961.boundOpenSizes(proposed, 0.5, 0.5, 0.02).isEmpty())
    }

    @Test fun missingCapitalAndSubminimumRemaindersCannotOpenPositions() {
        val proposed = mapOf("AAPL" to 0.3, "NVDA" to 0.2)
        assertTrue(XsRecovery8961.boundOpenSizes(proposed, 0.5, Double.NaN, 0.02).isEmpty())
        assertTrue(XsRecovery8961.boundOpenSizes(proposed, Double.NaN, 0.1, 0.02).isEmpty())
        assertTrue(XsRecovery8961.boundOpenSizes(proposed, 0.5, 0.49, 0.02).isEmpty())
        assertEquals(proposed, XsRecovery8961.boundOpenSizes(proposed, 0.5, 0.0, 0.02))
    }
}
