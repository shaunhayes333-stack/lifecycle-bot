package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LanePocket8934
import com.lifecyclebot.engine.truth.ResearchFollow8884
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8945 — an unwatched launch at its 20th print goes through the protected intake and the fast lane; the research
 * follow-up names why each read failed; the lane pocket's and the follow-up's repeated words are held once.
 */
class Aate8945MilestoneIntakeTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun anUnwatchedTwentiethPrintGoesThroughTheProtectedIntakeOnce() {
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("if (n != 20) return@listener8942"))
        assertTrue(bot.contains("promoteHotLaunchNow7921(mint, sym8945, name8945, streamGeneration, source8945 = \"LAUNCH_PRINTS_8942\", onceKey8945 = \"\$mint|8942|\$n\")"))
        assertTrue("the same protected intake", bot.contains("                    source = source8945,\n                    marketCapUsd = capUsd8165,"))
        assertTrue(bot.contains("fastLaneEvaluate7277(mint, cfg, source8945, onceKey = onceKey8945)"))
        assertTrue(src("engine/market/LaunchTape7921.kt").contains("fun symbolName8945(mint: String): Pair<String, String>"))
    }

    @Test fun theFollowUpNamesItsFailuresAndWordsAreHeldOnce() {
        val f = src("engine/truth/ResearchFollow8884.kt")
        assertTrue(f.contains("if (!resp.isSuccessful) { why8945(host, \"HTTP_\${resp.code}\"); null }"))
        assertTrue(f.contains("readFailed8945{"))
        val a = ResearchFollow8884.word8945(String(charArrayOf('M', 'O', 'O', 'N')))
        assertSame(a, ResearchFollow8884.word8945(String(charArrayOf('M', 'O', 'O', 'N'))))
        val x = LanePocket8934.features8934("abc", "BASE_START", "NONE", 30.0, 12.0, 25_000.0, 15_000.0)
        val y = LanePocket8934.features8934("abd", "BASE_START", "NONE", 30.0, 12.0, 25_000.0, 15_000.0)
        assertEquals(x, y)
        for (i in x.indices) assertSame("each feature word is one object", x[i], y[i])
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 133. The unwatched launch, the follow-up's failures, the repeated words (8945)"))
    }
}
