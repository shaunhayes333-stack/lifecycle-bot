package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EarlyMoonshotHunter6415
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7798MoonshotExpansionEngineTest {
    @Test fun broadOrganicExpansionRanksAboveConcentratedFlow() {
        val broad = EarlyMoonshotHunter6415.scoreCandidate(
            mint="mint_a",symbol="RUNNER",mcapUsd=14_000.0,liquidityUsd=5_000.0,
            vol1hUsd=20_000.0,sourceCount=3,buysLastWindow=12,sellsLastWindow=4,
            rugSafetyConfirmed=true,holderCount=150,holderGrowthPct=20.0,topHolderPct=15.0,
            smartMoneyBuys60s=3,distinctBuyers60s=10,largestBuyerSharePct60s=20.0,
            top3BuyerSharePct60s=45.0,momentumScore=75.0,bundleRisk="LOW",
            firstBlockSupplyPct=10.0,devSelling=false,socialVelocityScore=8.0,emitTelemetry=false
        )
        val concentrated = EarlyMoonshotHunter6415.scoreCandidate(
            mint="mint_b",symbol="NOISE",mcapUsd=14_000.0,liquidityUsd=5_000.0,
            vol1hUsd=20_000.0,sourceCount=1,buysLastWindow=9,sellsLastWindow=1,
            rugSafetyConfirmed=false,holderCount=10,holderGrowthPct=0.0,topHolderPct=60.0,
            smartMoneyBuys60s=0,distinctBuyers60s=1,largestBuyerSharePct60s=90.0,
            top3BuyerSharePct60s=98.0,momentumScore=75.0,bundleRisk="HIGH",
            firstBlockSupplyPct=60.0,devSelling=true,socialVelocityScore=0.0,emitTelemetry=false
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
