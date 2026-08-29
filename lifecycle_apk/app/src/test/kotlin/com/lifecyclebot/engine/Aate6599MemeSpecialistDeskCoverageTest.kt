package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.6599 — Meme lanes are causal specialist desks, not interchangeable labels. */
class Aate6599MemeSpecialistDeskCoverageTest {
    private fun source(path: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun toolkit_retains_per_desk_hypotheses_instead_of_only_global_best() {
        val src = source("engine/ToolkitSignalSheet.kt")
        assertTrue(src.contains("data class DeskHypothesis"))
        assertTrue(src.contains("val bestByDesk = linkedMapOf"))
        assertTrue(src.contains("bestByDesk[lane] = candidate to score"))
        assertTrue(src.contains("laneVotes = deskHypotheses.keys"))
        assertTrue(src.contains("deskHypotheses = deskHypotheses"))
        assertTrue(src.contains("CORE is the ensemble coordinator over real qualified desks"))
    }

    @Test fun project_sniper_pool_excludes_generic_breakout_and_defensive_candidates() {
        val toolkit = source("engine/ToolkitSignalSheet.kt")
        val router = source("engine/AgenticStyleRouter.kt")
        val bot = source("engine/BotService.kt")
        assertTrue(toolkit.contains("lanes = setOf(\"PROJECT_SNIPER\", \"SHITCOIN\", \"EXPRESS\")"))
        assertTrue(toolkit.contains("\"BREAKOUT_CONTINUATION\" -> setOf(\"MOONSHOT\", \"QUALITY\")"))
        assertFalse(toolkit.contains("lanes = setOf(\"MOONSHOT\", \"PROJECT_SNIPER\")"))
        assertFalse(toolkit.contains("lanes = setOf(\"SHITCOIN\", \"PROJECT_SNIPER\", \"EXPRESS\")"))
        assertTrue(router.contains("BREAKOUT_RUNNER(\"breakout_runner\", setOf(\"MOONSHOT\", \"SHITCOIN\", \"QUALITY\")"))
        assertTrue(bot.contains("genuineSniperPool6599"))
        assertTrue(bot.contains("PROJECT_SNIPER_DESIGNATED_POOL_REJECTED_6599"))
    }

    @Test fun strongest_qualified_desk_is_only_specialist_execution_primary() {
        val bot = source("engine/BotService.kt")
        assertTrue(bot.contains("strongestDesk6599"))
        assertTrue(bot.contains("deskHypotheses.values.maxByOrNull { it.conviction }?.lane"))
        assertTrue(bot.contains("l.equals(primaryLane, true) && designatedDeskQualified6599"))
        assertTrue(bot.contains("MEME_DESK_QUALIFIED_CONTRIBUTOR_ONLY_6599"))
        assertFalse(bot.contains("val allowed = profitableRescue"))
        assertFalse(bot.contains("val mixedAllowed6533 = l == mixedRescue6533"))
    }

    @Test fun sniper_open_mission_management_is_not_hidden_behind_entry_gate() {
        val bot = source("engine/BotService.kt")
        assertTrue(bot.contains("projectSniperMissionOpen6599"))
        assertTrue(bot.contains("projectSniperMissionOpen6599 || projectSniperEntryAllowed6599"))
        assertTrue(bot.contains("if (projectSniperEntryAllowed6599)"))
        assertTrue(bot.contains("ProjectSniperAI.hasMission(ts.mint)"))
        assertTrue(bot.contains("ProjectSniperAI.checkExit("))
    }

    @Test fun fdg_plan_size_snapshot_exit_and_terminal_learning_are_causally_wired() {
        val fdg = source("engine/FinalDecisionGate.kt")
        val exec = source("engine/Executor.kt")
        val reward = source("engine/truth/AateDecisionEnvelope6512.kt")
        assertTrue(fdg.contains("specialistDeskContributions6512"))
        assertTrue(fdg.contains("cooperativeDeskSizeMult6599"))
        assertTrue(fdg.contains("coerceIn(0.65, 1.25)"))
        assertTrue(fdg.contains("sizeFinal = cooperativeDeskSize6599"))
        assertTrue(exec.contains("entryDeskHypothesis6599"))
        assertTrue(exec.contains("entryRiskProfile = entryDeskHypothesis6599"))
        assertTrue(exec.contains("entryExitProfile = entryDeskHypothesis6599"))
        assertTrue(exec.contains("recordContributorSummary(it.specialistContributions, \"EXIT_INFLUENCE\""))
        assertTrue(reward.contains("role == \"MEME_SPECIALIST_DESK\""))
        assertTrue(reward.contains("if (!lane.equals(env.lane, true))"))
        assertTrue(reward.contains("LanePolicy.recordOutcome("))
    }

    @Test fun runtime_report_exposes_role_liveness_capital_and_sniper_zero_contract() {
        val sheet = source("engine/ToolkitSignalSheet.kt")
        val report = source("engine/PipelineHealthCollector.kt")
        assertTrue(sheet.contains("===== MEME SPECIALIST ROLE LIVENESS ====="))
        assertTrue(sheet.contains("PROJECT_SNIPER_NON_SNIPER_ADMISSION ="))
        assertTrue(sheet.contains("===== MEME SPECIALIST CAPITAL ====="))
        assertTrue(sheet.contains("capitalStarved="))
        assertTrue(sheet.contains("status=\$status"))
        assertTrue(report.contains("designatedRoleLivenessReport6599()"))
        assertTrue(report.contains("specialistCapitalReport6599()"))
    }
}
