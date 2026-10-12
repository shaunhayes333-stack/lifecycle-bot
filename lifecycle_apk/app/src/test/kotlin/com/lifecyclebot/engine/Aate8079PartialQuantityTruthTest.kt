package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

/** Every processor handling a partial must preserve the requested raw amount. */
class Aate8079PartialQuantityTruthTest {
    private val wallet = BigInteger("142650442934")

    @Test fun simpleSixtyPercentNeverBecomesFullWallet() {
        val requestedUi = 85590.265760
        val selected = ProcessorAmountPlanner.partialRequestedRaw8079(wallet, requestedUi, 6, null)
        assertEquals(BigInteger("85590265760"), selected)
        assertTrue(selected!! < wallet)
    }

    @Test fun amountAndFractionAreIndependentUpperBounds() {
        val a = ProcessorAmountPlanner.partialRequestedRaw8079(wallet, 140000.0, 6, 0.25)
        assertEquals(BigInteger("35662610733"), a)
        val b = ProcessorAmountPlanner.partialRequestedRaw8079(wallet, 80000.0, 6, 0.75)
        assertEquals(BigInteger("80000000000"), b)
    }

    @Test fun missingUnpricedAndTinyPartialsNeverDrainWallet() {
        assertNull(ProcessorAmountPlanner.partialRequestedRaw8079(wallet, Double.NaN, 6, null))
        assertNull(ProcessorAmountPlanner.partialRequestedRaw8079(wallet, 0.0000001, 6, null))
        assertNull(ProcessorAmountPlanner.partialRequestedRaw8079(wallet, 10.0, -1, null))
        assertEquals(wallet, ProcessorAmountPlanner.partialRequestedRaw8079(wallet, 99999999.0, 6, null))
    }

    @Test fun chainConsumedQtyDeterminesValidPartialVersusFullExitIntent() {
        val reason = com.lifecyclebot.engine.sell.SellReasonClassifier
        val raw = BigInteger("142650442934")
        val part = com.lifecyclebot.engine.sell.SellIntent.build(
            "mint", "Simple", reason.fromString("SPIKE_CAPTURE_7943_T1_40PCT"),
            6000, raw, 6, 100, false, 0.0296, raw)
        assertEquals(com.lifecyclebot.engine.sell.ExitReason.PARTIAL_TAKE_PROFIT, part.reason)
        assertEquals(BigInteger("85590265760"), part.requestedSellRaw)
        val full = com.lifecyclebot.engine.sell.SellIntent.build(
            "mint", "Simple", reason.fullExitFromString("LIFECYCLE_PROFIT_GIVEBACK_8065"),
            10000, raw, 6, 100, true, 0.0296, raw)
        assertEquals(raw, full.requestedSellRaw)
        val exe = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(exe.contains("val fullConsumed8081 = proof.rawTokenConsumed >= proof.preTokenRaw7835"))
        assertTrue(exe.contains("emergencyDrain = fullConsumed8081"))
    }

    @Test fun chainPartialOfHardStopStaysPartialAndFullDrainsRemainTerminal() {
        val classifier = com.lifecyclebot.engine.sell.SellReasonClassifier
        val reason = classifier.fromChainConsumption8081("RAPID_CATASTROPHE_STOP", false)
        assertEquals(com.lifecyclebot.engine.sell.ExitReason.STOP_LOSS, reason)
        val raw = BigInteger("142650442934")
        val partial = com.lifecyclebot.engine.sell.SellIntent.build(
            "mint", "Simple", reason, 6000, raw, 6, 100, false, 0.0296, raw)
        assertTrue(partial.requestedSellRaw < raw)
        assertEquals(com.lifecyclebot.engine.sell.ExitReason.RUG_DRAIN,
            classifier.fromChainConsumption8081("RUG_DRAIN", true))
        assertEquals(com.lifecyclebot.engine.sell.ExitReason.HARD_STOP,
            classifier.fromChainConsumption8081("RAPID_CATASTROPHE_STOP", true))
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(executor.contains("proof.preTokenRaw7835.signum() <= 0"))
        assertTrue(executor.contains("fromChainConsumption8081(reason, fullConsumed8081)"))
    }

    @Test fun sharedJupiterSellQuoteUsesExitHttpPriorityScope() {
        val scope = com.lifecyclebot.network.ExitHttpScope7314
        assertFalse(scope.active())
        scope.run { assertTrue(scope.active()) }
        assertFalse(scope.active())
        val exe = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val sellQuote = exe.substringAfter("private fun getQuoteWithSlippageGuard(")
            .substringBefore("// V5.0.7241")
        assertTrue(sellQuote.contains("return com.lifecyclebot.network.ExitHttpScope7314.run"))
    }

    @Test fun regressionSourceIncludesRpcAndRecoveredPartialSizing() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ProcessorAmountPlanner.kt").readText()
        assertTrue(src.contains("partialRequestedRaw8079(availableRaw, requestedUiQty, decimals, fraction)"))
        assertTrue(src.contains("partialRequestedRaw8079(availableRaw, requestedUiQty, decimals, null)"))
        assertTrue(src.contains("PARTIAL_FULL_WALLET_BLOCKED_8079"))
    }
}
