package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7254ModeScopedExitTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun canonicalExitFeedUsesOnlyActiveAccount() {
        val bot = source("src/main/kotlin/com/lifecyclebot/engine/BotService.kt")
        assertTrue(bot.contains("val activeExitMode7254 = if (RuntimeModeAuthority.isPaper()) \"paper\" else \"live\""))
        assertTrue(bot.contains(".filter { it.mode.equals(activeExitMode7254, ignoreCase = true) }"))
    }

    @Test
    fun independentRiskClockUsesOnlyActiveAccount() {
        val clock = source("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalRiskClock6454.kt")
        assertTrue(clock.contains("val activeMode7254 = if (RuntimeModeAuthority.isPaper()) \"paper\" else \"live\""))
        assertTrue(clock.contains(".filter { it.mode.equals(activeMode7254, ignoreCase = true) }"))
    }

    @Test
    fun doctorDoesNotCountFanoutSuppressionAsFanout() {
        val guardian = source("src/main/kotlin/com/lifecyclebot/engine/InvariantGuardian.kt")
        assertTrue(guardian.contains("FDG/FDG_FANOUT_CAP_7232"))
        assertTrue(guardian.contains("fdgDecisionsRaw7254 - cappedFdgBlocks7254"))
    }
}
