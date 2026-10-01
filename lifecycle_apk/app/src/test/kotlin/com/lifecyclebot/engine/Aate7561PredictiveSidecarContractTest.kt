package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7561PredictiveSidecarContractTest {
    private fun src(rel: String) =
        File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test
    fun `network and db predictive helpers are explicitly background shaped`() {
        val cg = src("network/CoinGeckoTrending.kt")
        val collective = src("collective/CollectiveLearning.kt")
        val turso = src("collective/TursoClient.kt")
        val corr = src("perps/CorrelationScanner.kt")

        assertTrue(cg.contains("fun getSolanaEcosystemMomentum()"))
        assertTrue(cg.contains("val trending = getTrending()"))
        assertTrue(cg.contains("HealthAwareHttp.execute("))

        assertTrue(collective.contains("suspend fun getNetworkBoostForMint"))
        assertTrue(collective.contains("withContext(Dispatchers.IO)"))

        assertTrue(turso.contains("suspend fun getMarketsAssetRankings"))
        assertTrue(turso.contains("ORDER BY"))

        assertTrue(corr.contains("suspend fun getActionableSignals()"))
        assertTrue(corr.contains("scanAllCorrelations()"))
    }

    @Test
    fun `aggregate learning readers remain sidecar analytics not duplicate fdg authority`() {
        val education = src("v3/scoring/EducationSubLayerAI.kt")
        val shadow = src("v3/learning/ShadowLearningEngine.kt")
        assertTrue(education.contains("fun getEdgeLedger("))
        assertTrue(education.contains("reasonStats.entries"))
        assertTrue(shadow.contains("fun getPerformanceByConfidence()"))
        assertTrue(shadow.contains("completedTrades"))
    }

    @Test
    fun `canonical meme hot path does not synchronously call background sidecars`() {
        val bot = src("engine/BotService.kt")
        val fdg = src("engine/FinalDecisionGate.kt")
        val hot = bot + "\n" + fdg

        assertFalse(hot.contains("getSolanaEcosystemMomentum("))
        assertFalse(hot.contains("getNetworkBoostForMint("))
        assertFalse(hot.contains("getActionableSignals("))
        assertFalse(hot.contains("getMarketsAssetRankings("))
        assertFalse(hot.contains("scoreSentimentWithLlm("))
        assertFalse(hot.contains("getEdgeLedger("))
        assertFalse(hot.contains("getPerformanceByConfidence("))
    }

    @Test
    fun `audit records sidecar classifications`() {
        val audit = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(audit.contains("V5.0.7561 — predictive sidecar/background contract bundle"))
        assertTrue(audit.contains("CoinGeckoTrending.getSolanaEcosystemMomentum"))
        assertTrue(audit.contains("BACKGROUND_CACHE_REQUIRED"))
        assertTrue(audit.contains("CorrelationScanner.getActionableSignals"))
        assertTrue(audit.contains("PERPS_SIDECAR / BACKGROUND_SCAN"))
        assertTrue(audit.contains("TursoClient.getMarketsAssetRankings"))
        assertTrue(audit.contains("BACKGROUND_DB_REQUIRED"))
    }
}
