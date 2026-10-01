package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7563ExitHelperLedgerTruthTest {
    private fun src(rel: String) =
        File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test
    fun `c exit helper family is getter analytics or diagnostic shaped`() {
        assertTrue(src("engine/HistoricalChartScanner.kt").contains("fun getProgress() = tokensAnalyzed.get()"))
        assertTrue(src("engine/GlobalTradeRegistry.kt").contains("fun getProbationStats(): String"))
        assertTrue(src("engine/truth/GovernorRecovery6388.kt").contains("fun lastPromotionReason(): String"))
        assertTrue(src("perps/PerpsMarketDataFetcher.kt").contains("fun getPriceSource(symbol: String): String"))
        assertTrue(src("perps/PriceAggregator.kt").contains("fun getPrunedSymbols(): Map<String, Long>"))
        assertTrue(src("engine/quant/QuantMetrics.kt").contains("fun calculateProfitFactor("))
        assertTrue(src("engine/TacticBleedPivot.kt").contains("fun getLastPivot("))
        assertTrue(src("engine/TreasuryOpportunityEngine.kt").contains("fun getPendingOpportunities(): List<Opportunity>"))
        assertTrue(src("network/SolanaWallet.kt").contains("fun getPublicKeyOnly(): String"))
        assertTrue(src("v3/scoring/EducationSubLayerAI.kt").contains("diagnostic snapshot of curriculum hold-bucket performance"))
    }

    @Test
    fun `audit records helper classifications without inventing sell authority`() {
        val audit = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(audit.contains("V5.0.7563 — C_EXIT helper/report ledger cleanup"))
        assertTrue(audit.contains("HistoricalChartScanner.getProgress"))
        assertTrue(audit.contains("DIAGNOSTIC COUNTER ACCESSOR"))
        assertTrue(audit.contains("QuantMetrics.calculateProfitFactor"))
        assertTrue(audit.contains("ANALYTICS API"))
        assertTrue(audit.contains("SolanaWallet.getPublicKeyOnly"))
        assertTrue(audit.contains("SECURE IDENTITY GETTER"))
    }
}
