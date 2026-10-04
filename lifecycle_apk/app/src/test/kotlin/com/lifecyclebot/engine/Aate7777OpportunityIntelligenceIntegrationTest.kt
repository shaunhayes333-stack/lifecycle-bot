package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7777OpportunityIntelligenceIntegrationTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/" + path).readText()

    @Test
    fun marketSweepOwnsBoundedTemporalAndCrossSectionalOpportunityIntelligence() {
        val s = src("engine/market/MarketSweep7297.kt")
        assertTrue(s.contains("data class RealtimeObservation"))
        assertTrue(s.contains("data class OpportunitySignal"))
        assertTrue(s.contains("OPPORTUNITY_OBS_PER_MINT_7777 = 24"))
        assertTrue(s.contains("OPPORTUNITY_MAX_MINTS_7777 = 6_000"))
        assertTrue(s.contains("fun recordRealtime7777("))
        assertTrue(s.contains("rebuildOpportunitySignals7777(previous7777, snap)"))
        assertTrue(s.contains("EARLY_MOMENTUM_IGNITION"))
        assertTrue(s.contains("RELATIVE_STRENGTH_LEADER"))
        assertTrue(s.contains("fun opportunityFor7777("))
        assertTrue(s.contains("authority=ordering_only no_execution_authority=true"))
    }

    @Test
    fun liveMarketDataFeedsOpportunityTapeWithoutAProviderCall() {
        val d = src("engine/DataOrchestrator.kt")
        assertTrue(d.contains("MarketSweep7297.recordRealtime7777("))
        assertTrue(d.contains("volume5mUsd = volume5m"))
        assertTrue(d.contains("txCount5m = txns5m"))
    }

    @Test
    fun laneHuntersUseOpportunityForOrderingNeverAsAFilterOrAuthority() {
        val h = src("engine/market/LaneHunter7297.kt")
        assertTrue(h.contains("opportunityLaneMultiplier7777(p.lane, r)"))
        assertTrue(h.contains("MarketSweep7297.opportunityMultiplier7777(r.mint)"))
        val helper = h.substringAfter("private fun opportunityLaneMultiplier7777")
            .substringBefore("/**\n     * Each lane picks")
        assertFalse(helper.contains("return null"))
        assertFalse(helper.contains("filter"))
    }

    @Test
    fun ultimateEdgeCombinesExistingModelsAndMarketRankForExistingSpecialists() {
        val e = src("engine/UltimateEdgeEngine.kt")
        assertTrue(e.contains("MarketSweep7297.opportunityFor7777(mint)"))
        assertTrue(e.contains("LiveProbabilityEngine.forecast("))
        assertTrue(e.contains("ForwardOutcomeModel.forecast("))
        assertTrue(e.contains("CapitalEfficiencyBrain.sizeMultiplier("))
        assertTrue(e.contains("opportunityRank"))
        assertTrue(e.contains("no_execution_authority=true"))

        val specialists = listOf(
            "v3/scoring/ShitCoinTraderAI.kt",
            "v3/scoring/MoonshotTraderAI.kt",
            "v3/scoring/QualityTraderAI.kt",
            "v3/scoring/ProjectSniperAI.kt",
            "v3/scoring/DipHunterAI.kt",
        )
        specialists.forEach { p ->
            assertTrue(p + " must consume the upgraded existing edge cache", src(p).contains("UltimateEdgeEngine.cached"))
        }
    }

    @Test
    fun pipelineReportMakesOpportunityLossVisible() {
        val p = src("engine/PipelineHealthCollector.kt")
        assertTrue(p.contains("Opportunity intelligence (§7777)"))
        assertTrue(p.contains("MarketSweep7297.opportunityStatusLine7777()"))
    }
}
