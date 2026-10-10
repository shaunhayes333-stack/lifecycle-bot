package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8020ExitTruthTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aHeldOrRefusedExitIsNotTimedAsDispatchLatency() {
        val e = src("engine/Executor.kt")
        val core = e.substringAfter("private fun requestSellCore7948(").substringBefore("// V5.0.7768")
        assertTrue(core.contains("RunnerGrab7967.deferSell7967(ts, reason)"))
        assertTrue(core.split("ExitTelemetryStamper6732.withdrawDeferred7809(ts.mint, ts.position.positionId)").size == 3)
    }

    @Test fun aRedRunnerHoldNeedsATapeThatSaysTheRunStands() {
        val g = src("engine/RunnerGrab7967.kt").substringAfter("fun deferSell7967(").substringBefore("private val peaks7980")
        assertTrue(g.contains("if (g < 0.0 && !runStanding8019(r60) && !runStanding8019(r15))"))
        assertTrue(!RunnerGrab7967.runStanding8019(null))
    }

    @Test fun aSellReReadsTheBalanceBeforeBlocking() {
        val p = src("engine/ProcessorAmountPlanner.kt")
        assertTrue(p.contains("wallet.getTokenAccountsWithDecimalsBounded(3_000L)"))
        assertTrue(p.contains("SELL_BALANCE_REREAD_8020"))
    }
}
