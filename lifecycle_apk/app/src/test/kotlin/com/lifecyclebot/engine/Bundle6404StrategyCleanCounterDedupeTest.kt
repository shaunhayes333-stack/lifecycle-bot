package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6404 §A — STRATEGY_CLEAN_TERMINAL_ROWS counter dedupe.
 *
 * The 6404 emergency dump showed 2,464,929 strategy-clean events
 * (~111/sec). The counter fired for every emitted row on every
 * clean() call, not per unique terminal. This test guarantees the
 * counter now increments AT MOST ONCE per canonical terminal key,
 * regardless of how many times clean() runs on the same input.
 */
class Bundle6404StrategyCleanCounterDedupeTest {

    private fun makeSell(sig: String, mint: String, ts: Long = 1_000L): Trade =
        Trade(
            side = "SELL",
            mode = "paper",
            sol = 0.05,
            price = 1.5,
            ts = ts,
            reason = "TEST_TERMINAL",
            pnlSol = 0.01,
            sig = sig,
            mint = mint,
            positionId = "pos_$sig",
            entryPriceSnapshot = 1.0,
            entryCostSol = 0.05,
            entryQtyToken = 100.0,
            soldQtyToken = 100.0,
            remainingQtyToken = 0.0,
        )

    @Test fun same_terminal_row_across_multiple_clean_calls_counter_at_most_once() {
        val start = PipelineHealthCollector.labelCountSnapshot("STRATEGY_CLEAN_TERMINAL_ROWS")
        val row = makeSell(sig = "SIGaaaaaaaaaaaaaa", mint = "MINTaaaaaaaaaaaa", ts = 1_000L)
        // Same input list, 5 clean() invocations — must count at most once.
        repeat(5) {
            StrategyTruthLedger.clean(listOf(row), limit = 10)
        }
        val delta = PipelineHealthCollector.labelCountSnapshot("STRATEGY_CLEAN_TERMINAL_ROWS") - start
        assertTrue(
            "expected <=1 counter increment for 1 unique terminal across 5 calls, got $delta",
            delta <= 1L,
        )
    }

    @Test fun distinct_terminals_each_count_once() {
        val start = PipelineHealthCollector.labelCountSnapshot("STRATEGY_CLEAN_TERMINAL_ROWS")
        val rows = (1..4).map {
            makeSell(
                sig = "SIGdistinct$it$it$it$it$it$it$it",
                mint = "MINTdist$it$it$it$it$it$it$it$it",
                ts = 1_000L + it,
            )
        }
        StrategyTruthLedger.clean(rows, limit = 10)
        val delta = PipelineHealthCollector.labelCountSnapshot("STRATEGY_CLEAN_TERMINAL_ROWS") - start
        assertTrue(
            "expected 1..4 counter increments for 4 distinct terminals, got $delta",
            delta in 1L..4L,
        )
    }
}
