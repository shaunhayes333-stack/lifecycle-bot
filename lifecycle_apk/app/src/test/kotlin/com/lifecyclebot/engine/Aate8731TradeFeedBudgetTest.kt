package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.HeliusCreditEconomy7881
import com.lifecyclebot.network.HeliusCurveTape8354
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8731 — the market-wide trade feed's byte ceiling is what its share of the owner's Helius plan pays for. */
class Aate8731TradeFeedBudgetTest {
    @Test fun theCeilingFollowsThePlan() {
        assertEquals(100_000L, HeliusCreditEconomy7881.wsBytesForCredits8731(2.0))
        assertEquals(2.0, HeliusCreditEconomy7881.creditsForWsBytes(HeliusCreditEconomy7881.wsBytesForCredits8731(2.0)), 1e-9)
        // today's default day (400k): about 5.5 MB a minute, above the old fixed 3 MB
        val atDefault = HeliusCurveTape8354.highBytesPerMin8731(400_000.0)
        assertTrue("$atDefault", atDefault in 5_400_000L..5_700_000L)
        // a 100M-a-month plan (3.33M a day): about 46 MB a minute
        assertTrue(HeliusCurveTape8354.highBytesPerMin8731(3_333_333.0) in 45_000_000L..47_000_000L)
        // never below the old mark, whatever the setting
        assertEquals(HeliusCurveTape8354.HIGH_BYTES_PER_MIN_8354, HeliusCurveTape8354.highBytesPerMin8731(10_000.0))
        assertEquals(HeliusCurveTape8354.LOW_BYTES_PER_MIN_8354, HeliusCurveTape8354.lowBytesPerMin8731(HeliusCurveTape8354.HIGH_BYTES_PER_MIN_8354))
        // the governor steps on the plan's marks
        assertEquals(0, HeliusCurveTape8354.nextLevel8354(0, 4_000_000L, atDefault, HeliusCurveTape8354.lowBytesPerMin8731(atDefault)))
        assertEquals(1, HeliusCurveTape8354.nextLevel8354(0, 6_000_000L, atDefault, HeliusCurveTape8354.lowBytesPerMin8731(atDefault)))
        assertEquals(HeliusCreditEconomy7881.DEFAULT_DAILY_BUDGET_CREDITS_8731, 400_000.0, 0.0)
    }

    @Test fun theSettingReachesTheBudget() {
        val card = File("src/main/kotlin/com/lifecyclebot/ui/DataKeysCard7962.kt").readText()
        assertTrue(card.contains("HeliusCreditEconomy7881.setDailyBudget8731(typed)"))
        assertTrue(File("src/main/res/layout/activity_main.xml").readText().contains("@+id/etHeliusDailyCredits8731"))
        val tape = File("src/main/kotlin/com/lifecyclebot/network/HeliusCurveTape8354.kt").readText()
        assertTrue(tape.contains("programAffordable8354(programCreditsToday(), bytesLastMinute, dayFraction(nowMs), over, budget = dailyCredits8731())"))
    }
}
