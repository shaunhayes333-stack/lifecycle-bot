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
        // V5.0.7809 — the mode filter lives in the protective exit scope.
        assertTrue(bot.contains("CanonicalPositionAuthority6441.protectiveExitScope7809(activeExitMode7254)"))
        val authority = source("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt")
        assertTrue(authority.contains("all7807.filter { it.mode.equals(m7807, true) }"))
    }

    @Test
    fun independentRiskClockUsesOnlyActiveAccount() {
        val clock = source("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalRiskClock6454.kt")
        assertTrue(clock.contains("val activeMode7254 = if (RuntimeModeAuthority.isPaper()) \"paper\" else \"live\""))
        // V5.0.7807 — the mode filter lives in the protective inventory.
        assertTrue(clock.contains("CanonicalPositionAuthority6441.protectiveInventory7807(activeMode7254)"))
    }

    @Test
    fun doctorDoesNotCountFanoutSuppressionAsFanout() {
        val guardian = source("src/main/kotlin/com/lifecyclebot/engine/InvariantGuardian.kt")
        assertTrue(guardian.contains("FDG/FDG_FANOUT_CAP_7232"))
        assertTrue(guardian.contains("fdgDecisionsRaw7254 - cappedFdgBlocks7254"))
    }
}
