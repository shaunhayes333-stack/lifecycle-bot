package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7848AuthorityConsolidationTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test
    fun live_fdg_never_falls_back_to_blended_stats() {
        val s = src("engine/FinalDecisionGate.kt")
        assertTrue(s.contains("val liveMode6025 = !com.lifecyclebot.engine.RuntimeModeAuthority.isPaper()"))
        assertTrue(s.contains("TradeHistoryStore.getCleanStatsSnapshot4517(mode = if (liveMode6025) \"LIVE\" else \"PAPER\")"))
        assertTrue(s.contains("val canonicalLearning = if (liveMode6025) null"))
        assertTrue(s.contains("if (liveMode6025) TradeHistoryStore.rollingWinRatePctLive7706(50) else TradeHistoryStore.rollingWinRatePct(50)"))
        val history = src("engine/TradeHistoryStore.kt")
        assertTrue(history.contains("fun getCleanStatsSnapshot4517(limit: Int = 2_500, mode: String? = null)"))
        assertTrue(history.contains("rows.filter { it.mode.equals(mode, true) }"))
    }

    @Test
    fun clean_stats_mode_filter_is_account_scoped() {
        val rows = listOf(
            com.lifecyclebot.data.Trade("SELL", "paper", 1.0, 1.0, 1L, pnlSol = -1.0),
            com.lifecyclebot.data.Trade("SELL", "live", 1.0, 1.0, 2L, pnlSol = 0.01),
        )
        assertEquals(listOf("live"), TradeHistoryStore.modeRows4517(rows, "LIVE").map { it.mode })
        assertEquals(listOf("paper"), TradeHistoryStore.modeRows4517(rows, "PAPER").map { it.mode })
        assertEquals(2, TradeHistoryStore.modeRows4517(rows, null).size)
    }

    @Test
    fun asymmetric_runner_ignores_generic_global_wr_deficit() {
        val s = src("engine/FinalDecisionGate.kt")
        assertTrue(s.contains("!com.lifecyclebot.engine.WrRecoveryPartial.isRunnerLaneExempt7693(specialistLane)"))
    }

    @Test
    fun policy_synthesizer_cannot_rewrite_owned_specialist_buy() {
        val s = src("engine/FinalDecisionGate.kt")
        assertTrue(s.contains("val policyAllows7848 = specialistLane?.isNotBlank() == true || aateEnvelope6512?.action != \"BLOCK\""))
        assertTrue(s.contains("policyAllows7848 && executableSize7835 > 0.0"))
        assertFalse(s.contains("canonicalEconomicApproval7548 &&\n                aateEnvelope6512?.action != \"BLOCK\""))
    }

    @Test
    fun automode_paused_is_exposed_as_caution_not_runtime_stop() {
        val s = src("engine/AutoModeEngine.kt")
        assertTrue(s.contains("AUTO_CAUTION_CONTINUE_TO_STRATEGY_7848"))
        assertTrue(s.contains("Quiet-hour caution"))
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("AUTOMODE_CAUTION_CURRENT_EPOCH_SHAPED_7858"))
        assertTrue(bot.contains("action=continue_to_strategy"))
        assertFalse(bot.contains("AUTOMODE_PAUSED_CURRENT_EPOCH_SHAPED_6485"))
    }
}
