package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6665PatchRotRegressionTest {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "src/main/kotlin/com/lifecyclebot/perps/ForexTrader.kt").exists() }

    @Test
    fun `all cloned markets terminal paths preserve position mode`() {
        val files = listOf("ForexTrader", "MetalsTrader", "CommoditiesTrader", "TokenizedStockTrader")
        files.forEach { name ->
            val src = File(root, "src/main/kotlin/com/lifecyclebot/perps/$name.kt").readText()
            val terminal = src.substringAfter("V5.9.248:")
            assertTrue("$name must gate its live receipt by immutable position", terminal.contains("if (!position.isPaper)"))
            assertTrue("$name must journal that receipt as live", terminal.contains("mode             = \"live\""))
            assertFalse("$name must not re-read mutable mode after terminal selection", terminal.contains("modeStr248"))
        }
    }

    @Test
    fun `production does not retain constant false stale merge branch`() {
        val bot = File(root, "src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertFalse(bot.contains("val v3OwnsMemes = false"))
        assertFalse(bot.contains("Dead branch retained only as a structural guard for stale merge context"))
        assertTrue(bot.contains("V3 contributes") && bot.contains("does not replace this executor authority"))
    }
}
