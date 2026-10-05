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

    @Test fun broadOrganicConfluenceBeatsPaidConcentratedHype() {
        val broad = EarlyMoonshotHunter6415.scoreCandidate(
            "mint_b","RUNNER",14_000.0,5_000.0,20_000.0,3,12,4,true,
            150,20.0,15.0,3,10,20.0,45.0,75.0,"LOW",10.0,false,8.0,
            25.0,25.0,20.0,true,8,0.75,6,0,false
        )
        val hype = EarlyMoonshotHunter6415.scoreCandidate(
            "mint_c","HYPE",14_000.0,5_000.0,20_000.0,1,9,1,false,
            12,0.0,60.0,0,1,90.0,98.0,75.0,"HIGH",60.0,true,10.0,
            25.0,10.0,20.0,false,0,0.5,0,0,false
        )
        assertTrue("MOONSHOT_CONFLUENCE_6" in broad.signalsFired)
        assertTrue("TELEGRAM_COMMUNITY_ACCEL_STRONG" in broad.signalsFired)
        assertFalse("MOONSHOT_CONFLUENCE_6" in hype.signalsFired)
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
