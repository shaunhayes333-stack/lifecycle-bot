package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7673ExecutionCostMeasuredFillRepairTest {
    @Test fun bridgePreservesExactSubmittedQuoteOutputAcrossRequote() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/UniversalBridgeEngine.kt").readText()
        assertTrue(s.contains("data class JupiterSwapMeasurement7673"))
        assertTrue(s.contains("JupiterSwapMeasurement7673(it, quote.outAmount)"))
        assertTrue(s.contains("expectedTargetRaw7673 = swap7673.expectedOutRaw"))
        assertTrue(s.contains("executeJupiterSwapMeasured7673("))
    }

    @Test fun cryptoOpenPublishesOnlyProvenRawFillWithRealLiquidity() {
        val e = File("src/main/kotlin/com/lifecyclebot/perps/crypto/CryptoUniverseExecutor.kt").readText()
        assertTrue(e.contains("bridge.expectedTargetRaw7673 > 0L"))
        assertTrue(e.contains("bridge.targetAmountRaw > 0L"))
        assertTrue(e.contains("liquidityUsd6493 > 0.0"))
        assertTrue(e.contains("MathematicalEdgeEngine.captureRawFill7673("))

        val t = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        assertTrue(t.contains("liquidityUsd6493 = exactAssetMetrics6493(signal).liquidityUsd"))
    }

    @Test fun mathematicalEdgeFeedsRawFillToExecutionCostLearner() {
        val m = File("src/main/kotlin/com/lifecyclebot/engine/MathematicalEdgeEngine.kt").readText()
        assertTrue(m.contains("expectedOutRaw7673"))
        assertTrue(m.contains("actualOutRaw7673"))
        assertTrue(m.contains("ExecutionCostPredictorAI.learnFromRawOutput7673("))

        val c = File("src/main/kotlin/com/lifecyclebot/v3/scoring/ExecutionCostPredictorAI.kt").readText()
        assertTrue(c.contains("fun learnFromRawOutput7673("))
        assertTrue(c.contains("BigDecimal.valueOf(actualOutRaw)"))
        assertTrue(c.contains("divide(java.math.BigDecimal.valueOf(expectedOutRaw)"))
    }
}
