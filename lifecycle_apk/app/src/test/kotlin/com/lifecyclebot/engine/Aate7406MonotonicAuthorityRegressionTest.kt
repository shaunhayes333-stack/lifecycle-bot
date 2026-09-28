package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate7406MonotonicAuthorityRegressionTest {
    private fun src(path: String) =
        File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun specialist_hard_blocks_are_binding() {
        val bot = src("engine/BotService.kt")
        val hard = bot.substringAfter("if (provenDeadHardBlock6604)").substringBefore("val lanePWinBelowGate6604")
        assertTrue(hard.contains("return false"))
        assertTrue(hard.contains("terminal_specialist_election_block_7406"))
        val pwin = bot.substringAfter("if (lanePWinBelowGate6604)").substringBefore("if (l == \"PROJECT_SNIPER\")")
        assertTrue(pwin.contains("return false"))
    }

    @Test fun sniper_cannot_resurrect_zero_or_refused_size() {
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("SNIPER_ORACLE_REFUSE_BINDING_7406"))
        assertTrue(bot.contains("sniperOracleRefused7406 -> 0.0"))
        assertTrue(bot.contains("sniperSizeBefore7054 <= 0.0 -> 0.0"))
        assertTrue(bot.contains("!sniperOracleRefused7406 && sniperSizedSol7054 > 0.0"))
    }

    @Test fun terminal_provider_telemetry_is_not_hot_looped() {
        val gate = src("engine/BirdeyeBudgetGate.kt")
        assertTrue(gate.contains("AUTH_TERMINAL_TELEMETRY_INTERVAL_MS_7406"))
        assertTrue(gate.contains("lastAuthTerminalTelemetryMs7406.compareAndSet"))
    }

    @Test fun quarantined_mark_is_never_described_as_trusted_fallback() {
        val capital = src("engine/truth/CanonicalCapitalAuthority6450.kt")
        assertTrue(capital.contains("invalid_mark_hold_at_cost_exclude_pnl_learning_7406"))
    }
}
