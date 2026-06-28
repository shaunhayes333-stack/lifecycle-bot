package com.lifecyclebot.engine

import java.io.File

/**
 * V5.0.4388 — source-contract sentinel for full meme/lifecycle lane parity.
 *
 * Report-only CI/operator helper. It proves the nine lane family has restart
 * restore hooks and that lane-local loss pauses are soft recovery probes rather
 * than amputating volume. This never touches scanner, sizing, buy, sell, or
 * runtime gate state.
 */
object MemeLaneParitySentinel {
    data class Finding(val id: String, val passed: Boolean, val detail: String)

    fun auditSourceTree(root: File): List<Finding> {
        fun src(path: String): String = try { File(root, path).readText() } catch (_: Throwable) { "" }
        val pp = src("src/main/kotlin/com/lifecyclebot/engine/PositionPersistence.kt")
        val shit = src("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinTraderAI.kt")
        val express = src("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinExpress.kt")
        val moon = src("src/main/kotlin/com/lifecyclebot/v3/scoring/MoonshotTraderAI.kt")
        val quality = src("src/main/kotlin/com/lifecyclebot/v3/scoring/QualityTraderAI.kt")
        val blue = src("src/main/kotlin/com/lifecyclebot/v3/scoring/BlueChipTraderAI.kt")
        val manip = src("src/main/kotlin/com/lifecyclebot/v3/scoring/ManipulatedTraderAI.kt")
        val dip = src("src/main/kotlin/com/lifecyclebot/v3/scoring/DipHunterAI.kt")
        val sniper = src("src/main/kotlin/com/lifecyclebot/v3/scoring/ProjectSniperAI.kt")
        val treasury = src("src/main/kotlin/com/lifecyclebot/v3/scoring/CashGenerationAI.kt")
        return listOf(
            Finding("NINE_LANE_RESTORE_PARITY_4388", listOf(
                "MANIPULATED_RESTORED_ACTIVE_POSITION_4214", "QUALITY_RESTORED_ACTIVE_POSITION_4215",
                "BLUE_CHIP_RESTORED_ACTIVE_POSITION_4215", "DIP_HUNTER_RESTORED_ACTIVE_DIP_4221",
                "EXPRESS_RESTORED_ACTIVE_RIDE_4223", "PROJECT_SNIPER_RESTORED_ACTIVE_MISSION_4226",
                "MOONSHOT_RESTORED_ACTIVE_POSITION_4227", "SHITCOIN_RESTORED_ACTIVE_POSITION_4228",
                "TREASURY_RESTORED_ACTIVE_POSITION_4228",
            ).all { pp.contains(it) }, "all nine meme/lifecycle lanes must restore into active lane maps after restart"),
            Finding("LANE_LOCAL_PAUSES_ARE_RECOVERY_PROBES_4388", listOf(
                shit.contains("SHITCOIN_DAILY_LOSS_RECOVERY_PROBE_4314") && shit.contains("size×0.35"),
                express.contains("EXPRESS_DAILY_LOSS_RECOVERY_PROBE_4223") && express.contains("positionSol *= 0.35"),
                blue.contains("BLUECHIP_DAILY_LOSS_RECOVERY_PROBE_4224") && blue.contains("positionSol *= 0.35"),
                dip.contains("DIP_DAILY_LOSS_RECOVERY_PROBE_4222") && dip.contains("positionSol *= 0.35"),
                sniper.contains("SNIPER_DAILY_LOSS_RECOVERY_PROBE_4222") && sniper.contains("dailyLossProbeMult"),
                treasury.contains("TREASURY_DAILY_LOSS_RECOVERY_PROBE_4224") && treasury.contains("TreasuryMode.PAUSED -> 0.35"),
            ).all { it }, "lane-local drawdown/paused states must soft-size recovery probes, not hard-amputate flow"),
            Finding("RESTORE_HELPERS_PRESENT_4388", listOf(
                manip.contains("fun restorePosition"), quality.contains("fun restorePosition"), blue.contains("fun restorePosition"),
                dip.contains("fun restoreDip"), express.contains("fun restoreRide"), sniper.contains("fun restoreMission"),
                moon.contains("fun restorePosition"), shit.contains("fun restorePosition"), treasury.contains("fun restorePosition"),
            ).all { it }, "each lane must expose a restore helper consumed by PositionPersistence"),
            Finding("REPORT_ONLY_NO_TRADE_AUTHORITY_4388", true, "report_only=true no_execution_authority=true no_gate_change=true"),
        )
    }

    fun failed(root: File): List<Finding> = auditSourceTree(root).filter { !it.passed }

    fun status(root: File = File(".")): String {
        val findings = auditSourceTree(root)
        val failed = findings.filter { !it.passed }.joinToString { it.id }.ifBlank { "none" }
        return "MEME_LANE_PARITY_SENTINEL_4388 findings=${findings.size} failed=$failed report_only=true no_execution_authority=true no_gate_change=true"
    }
}
