package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7579PerpsReadbackTrancheTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/" + rel).readText()
    @Test fun `perps tranche remains read only diagnostics`() {
        assertTrue(src("perps/CorrelationScanner.kt").contains("fun getDataPointCounts(): Map<String, Int>"))
        assertTrue(src("perps/DynamicAltTokenRegistry.kt").contains("fun getStaticCount()"))
        assertTrue(src("perps/DynamicAltTokenRegistry.kt").contains("fun getDynamicCount()"))
        assertTrue(src("perps/JupiterPerps.kt").contains("fun getActiveOrderCount(): Int = activeOrders.size"))
        assertTrue(src("perps/MarketsScanner.kt").contains("fun getCategoryStats(): Map<ScanCategory, Int>"))
        assertTrue(src("perps/MarketsScanner.kt").contains("fun getTotalAssetsCount(): Int = PerpsMarket.values().size"))
        assertTrue(src("perps/PerpsAutoReplayLearner.kt").contains("fun getTradeHistorySize(): Int = tradeHistory.size"))
        assertTrue(src("perps/PerpsExecutionEngine.kt").contains("fun getExecutionCount(): Int = executionCount.get()"))
        assertTrue(src("perps/PerpsMarketScanners.kt").contains("fun getScannerStats(): Map<ScannerType, Int>"))
        assertTrue(src("perps/PerpsUnifiedScorerBridge.kt").contains("fun openEntryCount(): Int = openEntries.size"))
        assertTrue(src("perps/PriceAggregator.kt").contains("fun getSourceStats(): Map<String, Map<String, Int>>"))
    }
    @Test fun `audit count advances`() {
        val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(a.contains("97 / 1,458"))
        assertTrue(a.contains("1,361 remain"))
    }
}
