package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchPrints8942
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** V5.0.8942 — a pump.fun launch is run through the full pipeline again at its 20th, 40th and 80th print, within 15 min. */
class Aate8942LaunchPrintsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val pump = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmpump"

    @Before fun setUp() = LaunchPrints8942.resetForTest8942()
    @After fun tearDown() = LaunchPrints8942.resetForTest8942()

    @Test fun theListenerRunsAtTheMilestonesOnlyAndOnlyInsideTheWindow() {
        val calls = ArrayList<Pair<String, Int>>()
        LaunchPrints8942.listener8942 = { m, n -> calls += m to n }
        for (i in 1..85) LaunchPrints8942.onPrint8942(pump, 1_000_000L + i * 1_000L)
        assertEquals(listOf(pump to 20, pump to 40, pump to 80), calls)
        calls.clear()
        // another launch whose 20th print comes after 15 minutes: not re-read
        val slow = "So1owLaunchMint1111111111111111111111111pump"
        for (i in 1..20) LaunchPrints8942.onPrint8942(slow, 1_000_000L + i * 60_000L)
        assertTrue(calls.isEmpty())
        assertTrue(LaunchPrints8942.statusLine8942().contains("late=1"))
        // a non-pump mint is not counted
        for (i in 1..25) LaunchPrints8942.onPrint8942("7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr", 1_000_000L + i)
        assertTrue(calls.isEmpty())
        assertTrue(LaunchPrints8942.milestone8942(20) && !LaunchPrints8942.milestone8942(21))
        assertFalse(LaunchPrints8942.inWindow8942(0L, 15L * 60_000L + 1L))
    }

    @Test fun theMilestoneRunsTheSamePipelineUnderItsOwnKey() {
        assertTrue(src("engine/truth/TradeTape8100.kt").contains("LaunchPrints8942.onPrint8942(mint, eventMs)"))
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("fastLaneEvaluate7277(mint, ConfigStore.load(applicationContext), \"LAUNCH_PRINTS_8942_\$n\", onceKey = \"\$mint|8942|\$n\")"))
        assertTrue(bot.contains("if (!valid9054() || !fastLaneOnce7277.add(key9054)) return"))
        assertTrue(bot.contains("delay(60_000L); fastLaneOnce7277.remove(key9054)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 130. The launch is read at its twentieth print (8942)"))
    }
}
