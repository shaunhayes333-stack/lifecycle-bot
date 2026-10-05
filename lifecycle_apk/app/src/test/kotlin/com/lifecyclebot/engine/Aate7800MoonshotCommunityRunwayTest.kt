package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EarlyMoonshotHunter6415
import com.lifecyclebot.engine.truth.MoonshotExpansionIntelligence7799
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7800MoonshotCommunityRunwayTest {
    @Test fun tinyValuationPlusOrganicAccelerationCreatesRunwayEvidence() {
        MoonshotExpansionIntelligence7799.resetForTest()
        val t0 = 1_000_000L
        MoonshotExpansionIntelligence7799.observe(
            mint="mint_a",mcapUsd=14_000.0,holderCount=100,boostAmount=0,
            socialDepth=3,telegramPresent=true,sentimentScore=50.0,telegramCommunityScore=0.0,nowMs=t0
        )
        val s = MoonshotExpansionIntelligence7799.observe(
            mint="mint_a",mcapUsd=14_000.0,holderCount=150,boostAmount=0,
            socialDepth=3,telegramPresent=true,sentimentScore=60.0,telegramCommunityScore=20.0,
            nowMs=t0 + 5L * 60_000L
        )
        assertTrue(s.runwayTo1mX > 70.0)
        assertTrue(s.runwayTo5mX > 300.0)
        assertTrue(s.attentionVelocityScore >= 8.0)
        assertTrue(s.evidenceAheadOfValuation)
    }

    @Test fun valuationCanOutrunEvidence() {
        MoonshotExpansionIntelligence7799.resetForTest()
        val t0 = 2_000_000L
        MoonshotExpansionIntelligence7799.observe(
            mint="mint_fast",mcapUsd=14_000.0,holderCount=100,boostAmount=0,
            socialDepth=2,telegramPresent=true,sentimentScore=50.0,telegramCommunityScore=0.0,nowMs=t0
        )
        val s = MoonshotExpansionIntelligence7799.observe(
            mint="mint_fast",mcapUsd=70_000.0,holderCount=101,boostAmount=0,
            socialDepth=2,telegramPresent=true,sentimentScore=50.0,telegramCommunityScore=0.0,
            nowMs=t0 + 2L * 60_000L
        )
        assertTrue(s.valuationGrowthPctPerMin > 100.0)
        assertFalse(s.evidenceAheadOfValuation)
    }

    @Test fun broadOrganicConfluenceBeatsPaidConcentratedHype() {
        val broad = EarlyMoonshotHunter6415.scoreCandidate(
            mint="mint_b",symbol="RUNNER",mcapUsd=14_000.0,liquidityUsd=5_000.0,
            vol1hUsd=20_000.0,sourceCount=3,buysLastWindow=12,sellsLastWindow=4,
            rugSafetyConfirmed=true,holderCount=150,holderGrowthPct=20.0,topHolderPct=15.0,
            smartMoneyBuys60s=3,launchAgeMs=60_000L,createMultiple=1.2,devBuyTx60s=1,
            distinctBuyers60s=10,largestBuyerSharePct60s=20.0,
            top3BuyerSharePct60s=45.0,momentumScore=75.0,bundleRisk="LOW",
            firstBlockSupplyPct=10.0,devSelling=false,socialVelocityScore=8.0,
            valuationRunwayScore=25.0,attentionVelocityScore=25.0,telegramCommunityScore=20.0,
            evidenceAheadOfValuation=true,creatorSampleCount=8,creatorWinRate=0.75,
            creatorScoreHint=6,creatorRugCount=0,emitTelemetry=false
        )
        val hype = EarlyMoonshotHunter6415.scoreCandidate(
            mint="mint_c",symbol="HYPE",mcapUsd=14_000.0,liquidityUsd=5_000.0,
            vol1hUsd=20_000.0,sourceCount=1,buysLastWindow=9,sellsLastWindow=1,
            rugSafetyConfirmed=false,holderCount=12,holderGrowthPct=0.0,topHolderPct=60.0,
            smartMoneyBuys60s=0,launchAgeMs=60_000L,createMultiple=4.0,devBuyTx60s=3,
            distinctBuyers60s=1,largestBuyerSharePct60s=90.0,
            top3BuyerSharePct60s=98.0,momentumScore=75.0,bundleRisk="HIGH",
            firstBlockSupplyPct=60.0,devSelling=true,socialVelocityScore=10.0,
            valuationRunwayScore=25.0,attentionVelocityScore=10.0,telegramCommunityScore=20.0,
            evidenceAheadOfValuation=false,creatorSampleCount=0,creatorWinRate=0.5,
            creatorScoreHint=0,creatorRugCount=0,emitTelemetry=false
        )
        assertTrue("MOONSHOT_CONFLUENCE_6" in broad.signalsFired)
        assertTrue("TELEGRAM_COMMUNITY_ACCEL_STRONG" in broad.signalsFired)
        assertTrue("SMART_MONEY_EARLY" in broad.signalsFired)
        assertTrue("CREATOR_ALIGNED_EARLY_BUY" in broad.signalsFired)
        assertFalse("MOONSHOT_CONFLUENCE_6" in hype.signalsFired)
        assertTrue("EARLY_PRICE_ALREADY_EXPANDED" in hype.signalsFired)
        assertTrue(broad.composite > hype.composite + 50.0)
    }

    @Test fun productionPathUsesKeylessTelegramVelocity() {
        val trader=File("src/main/kotlin/com/lifecyclebot/v3/scoring/MoonshotTraderAI.kt").readText()
        val tg=File("src/main/kotlin/com/lifecyclebot/engine/truth/TelegramCommunityVelocity7800.kt").readText()
        val scraper=File("src/main/kotlin/com/lifecyclebot/network/TelegramScraper.kt").readText()
        assertTrue(trader.contains("TelegramCommunityVelocity7800.peekAndRefresh"))
        assertTrue(tg.contains("MAX_REQUESTS_PER_MIN = 10"))
        assertTrue(tg.contains("AppDispatchers.sideEffect"))
        assertTrue(scraper.contains("https://t.me/s/"))
        assertTrue(scraper.contains("scrapePublicChannelStats"))
    }

    @Test fun moonshotLearnerIsPersistentAndFatTailWeighted() {
        val s=File("src/main/kotlin/com/lifecyclebot/engine/truth/MoonshotSignalLearner6415.kt").readText()
        assertTrue(s.contains("pnlPct >= 1000.0 -> 8L"))
        assertTrue(s.contains("pnlPct >= 500.0 -> 5L"))
        assertTrue(s.contains("pnlPct >= 150.0 -> 3L"))
        assertTrue(s.contains("moonshot_signal_learner_6415"))
        assertTrue(s.contains("creatorTailEvidence7799"))
    }
}
