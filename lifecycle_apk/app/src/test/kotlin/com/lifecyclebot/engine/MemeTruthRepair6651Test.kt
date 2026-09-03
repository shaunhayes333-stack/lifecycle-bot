package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.truth.DeskPerformanceAuthority6648
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

class MemeTruthRepair6651Test {

    private fun terminal(id: String, pnlPct: Double) = Trade(
        side = "SELL", mode = "paper", mint = "mint-$id", sol = 1.0,
        price = 1.0, ts = id.hashCode().toLong(), pnlSol = pnlPct / 100.0,
        netPnlSol = pnlPct / 100.0, pnlPct = pnlPct,
        tradingMode = "SHITCOIN", positionId = "MEME:$id",
        entryCostSol = 1.0, soldCostBasisSol = 1.0,
        grossProceedsSol = 1.0 + pnlPct / 100.0,
        canonicalConsumedRaw = BigInteger.ONE,
        economicEventId = "paper-full-$id",
    )

    @Test fun deskUsesTheCanonicalSymmetricOutcomeBand() {
        val rows = listOf(
            terminal("win", 0.6), terminal("loss", -0.6),
            terminal("scratch-positive", 0.2), terminal("scratch-negative", -0.2),
        )
        val meme = DeskPerformanceAuthority6648.reduce(rows, "paper", accountingAvailable = true)
            .getValue(DeskPerformanceAuthority6648.Book.MEME)
        assertEquals(4, meme.trades)
        assertEquals(1, meme.wins)
        assertEquals(1, meme.losses)
        assertEquals(2, meme.scratches)
        assertEquals(50.0, meme.winRate, 0.0001)
    }

    @Test fun fullPaperSellProjectsTheCanonicalReceiptVerbatim() {
        val executor = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(executor.contains("soldCostBasisSol = close6474.soldCostBasisSol"))
        assertTrue(executor.contains("grossProceedsSol = close6474.grossProceedsSol"))
        assertTrue(executor.contains("economicEventId = close6474.economicEventId"))
        assertTrue(executor.contains("canonicalConsumedRaw = rawVerdict6520.normalizedRaw"))
        assertTrue(executor.contains("preCostSol = close6474.preRemainingCostBasisSol"))
        assertTrue(executor.contains("postCostSol = close6474.postRemainingCostBasisSol"))
    }

    @Test fun learningWaitsForItsExactEventNotUnrelatedPortfolioMarks() {
        val purity = File("src/main/kotlin/com/lifecyclebot/engine/truth/RewardPurityGate6441.kt").readText()
        val consumers = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        val finalized = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalTradeFinalizedBus6450.kt").readText()
        assertTrue(purity.contains("committedTerminalEventForPosition(positionId, economicEventId)"))
        assertTrue(consumers.contains("committedTerminalEventForPosition(env.positionId, env.economicEventId)"))
        assertTrue(consumers.contains("\"RewardPurity\"       -> deliverToRewardPurity"))
        assertTrue(finalized.contains("afterCommitted(event.economicEventId)"))
        assertFalse(purity.contains("fallbackMarkMints"))
        assertFalse(consumers.contains("fallbackMarkMints"))
        assertFalse(consumers.contains("ForensicReconciliation6635.reconcile6635()"))
    }

    @Test fun staleMarksRefreshBeforeExitAndNeverBecomeStrategyLabels() {
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val executor = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val truth = File("src/main/kotlin/com/lifecyclebot/engine/StrategyTruthLedger.kt").readText()
        assertTrue(bot.contains("stateMarkStale6651") && bot.contains("refreshNeeded6651"))
        assertTrue(executor.contains("STALE_FEED_FAST_EVICT_HELD_FOR_REFRESH_6651"))
        assertTrue(executor.contains("data_quality_stale_feed_evict"))
        assertTrue(executor.contains("suppressLearningFanout6490 = reason.contains(\"stale_feed\""))
        assertTrue(truth.contains("return \"DATA_QUALITY_EXIT\""))
    }

    @Test fun pipelinePerformanceIsCurrentModeMemeOnly() {
        val report = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(report.contains("DeskPerformanceAuthority6648.Book.MEME"))
        assertTrue(report.contains("it.mode.equals(currentMode6651"))
        assertTrue(report.contains("CanonicalOutcomeClassifier6576.BREAKEVEN_BAND_PCT"))
        assertTrue(report.contains("getRecentCleanStrategyTerminalTrades"))
        assertFalse(report.contains("getRecentValidClosedTrades(limit.coerceAtLeast(1), includePartials = true)"))
    }

    @Test fun specialistExecutionStagesKeepTheSealedAttemptIdentity() {
        val executor = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val fdg = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(executor.contains("recordDeskStage(entryLane6450, \"EXEC\", executionAttemptId6514)"))
        assertTrue(executor.contains("recordDeskStage(entryLane6450, \"POSITION_OPENED\", executionAttemptId6514)"))
        assertTrue(fdg.contains("causalEventId = \"${'$'}{ts.mint}:${'$'}{com.lifecyclebot.engine.LaneExecutionCoordinator.candidateVersionFor(ts.mint)}\""))
    }
}
