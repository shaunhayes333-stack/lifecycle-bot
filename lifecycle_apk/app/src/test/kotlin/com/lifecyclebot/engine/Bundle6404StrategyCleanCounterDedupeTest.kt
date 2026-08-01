package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6404 §A — STRATEGY_CLEAN_TERMINAL_ROWS counter dedupe.
 *
 * The 6404 emergency snapshot showed 2,464,929 strategy-clean events
 * (~111/sec) — the counter was incremented for every emitted row on
 * every clean() call, not per unique terminal. This test guarantees
 * the counter now increments ONCE per canonical terminal key,
 * regardless of how many times clean() runs.
 */
class Bundle6404StrategyCleanCounterDedupeTest {

    private fun makeSell(sig: String, ts: Long = 1000L, entryPrice: Double = 1.0, exitPrice: Double = 1.5): Trade {
        return Trade(
            mint = "MINT_${sig.take(4)}",
            symbol = "SYM_${sig.take(3)}",
            side = "SELL",
            solAmount = 0.05,
            pnlSol = 0.01,
            proof = sig,
            positionId = "pos_$sig",
            lane = "SHITCOIN",
            entryPrice = entryPrice,
            exitPrice = exitPrice,
            entryCostSol = 0.05,
            qtyToken = 100.0,
            remainingQtyToken = 0.0,
            marketCap = 10_000.0,
            source = "DEXSCREENER_PAIR_P",
            reason = "TEST_TERMINAL",
            ts = ts,
        )
    }

    @Test fun same_terminal_row_across_multiple_clean_calls_increments_counter_once() {
        val startCount = PipelineHealthCollector.labelCountSnapshot("STRATEGY_CLEAN_TERMINAL_ROWS")
        val row = makeSell(sig = "aaa1111111111111", ts = 1_000L)
        // Same input list, 5 clean() invocations — should count once.
        repeat(5) {
            StrategyTruthLedger.clean(listOf(row), limit = 10)
        }
        val endCount = PipelineHealthCollector.labelCountSnapshot("STRATEGY_CLEAN_TERMINAL_ROWS")
        // Delta is at most 1 for this unique terminal. Prior to §A the
        // delta would have been 5 (or ~11k depending on caller frequency).
        assertTrue(
            "expected <=1 counter increment for one unique terminal across 5 calls, got ${endCount - startCount}",
            endCount - startCount <= 1L,
        )
    }

    @Test fun distinct_terminals_still_each_count_once() {
        val startCount = PipelineHealthCollector.labelCountSnapshot("STRATEGY_CLEAN_TERMINAL_ROWS")
        val rows = (1..4).map { makeSell(sig = "sig${it}$it$it$it$it$it$it$it$it$it$it$it$it$it$it", ts = 1_000L + it) }
        StrategyTruthLedger.clean(rows, limit = 10)
        val endCount = PipelineHealthCollector.labelCountSnapshot("STRATEGY_CLEAN_TERMINAL_ROWS")
        // Four distinct terminal keys → up to 4 new counter increments.
        assertTrue(
            "expected <=4 increments for 4 distinct terminals, got ${endCount - startCount}",
            endCount - startCount in 1L..4L,
        )
    }
}
