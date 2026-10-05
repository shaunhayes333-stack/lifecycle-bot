package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EarlyMoonshotHunter6415
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7798MoonshotExpansionEngineTest {
    @Test fun broadOrganicExpansionRanksAboveConcentratedFlow() {
        val broad = EarlyMoonshotHunter6415.scoreCandidate(
            "mint_a","RUNNER",14_000.0,5_000.0,20_000.0,3,12,4,true,
            150,20.0,15.0,3,10,20.0,45.0,75.0,"LOW",10.0,false,8.0,false
        )
        val concentrated = EarlyMoonshotHunter6415.scoreCandidate(
            "mint_b","NOISE",14_000.0,5_000.0,20_000.0,1,9,1,false,
            10,0.0,60.0,0,1,90.0,98.0,75.0,"HIGH",60.0,true,0.0,false
        )
        assertTrue(broad.tier == EarlyMoonshotHunter6415.Tier.ELITE)
        assertTrue(broad.composite > concentrated.composite + 50.0)
        assertTrue("INDEPENDENT_BUYER_BREADTH_STRONG" in broad.signalsFired)
        assertTrue("SMART_MONEY_CONVERGENCE" in broad.signalsFired)
        assertTrue("HOLDER_GROWTH_VIRAL" in broad.signalsFired)
        assertTrue("ONE_BUYER_DOMINATES_FLOW" in concentrated.signalsFired)
        assertTrue("DEV_SELLING" in concentrated.signalsFired)
    }

    @Test fun productionMoonshotFeedsRealLaunchBreadth() {
        val s=File("src/main/kotlin/com/lifecyclebot/v3/scoring/MoonshotTraderAI.kt").readText()
        assertTrue(s.contains("launch7798?.distinctBuyers60s"))
        assertTrue(s.contains("launch7798?.largestBuyerSharePct60s"))
        assertTrue(s.contains("launch7798?.top3BuyerSharePct60s"))
        assertTrue(s.contains("SmartMoneyFeed6394.smartMoneyBuysLast60s"))
        assertTrue(s.contains("ts.holderGrowthRate"))
        assertTrue(s.contains("ts.safety.bundleRisk"))
    }
}
