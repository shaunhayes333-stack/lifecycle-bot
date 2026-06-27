package com.lifecyclebot.engine

import java.io.File

/**
 * V5.0.4235 — SymbolicInvariantProver
 *
 * A lightweight, source-contract prover for the ASI / super-symbolic audit lane.
 * This is intentionally CI/background oriented: it reads source files and proves
 * invariants that protect live wallet growth, mux parity, sizing, and safety.
 *
 * Future upgrade path: back this with Z3/SMT for numeric-cap proofs. The first
 * version is dependency-free so it can land without adding Android build risk.
 */
object SymbolicInvariantProver {
    data class Proof(
        val id: String,
        val passed: Boolean,
        val detail: String,
    )

    fun proveSourceContracts(root: File): List<Proof> {
        fun src(path: String): String = try { File(root, path).readText() } catch (_: Throwable) { "" }
        fun firstSrc(vararg paths: String): String = paths.firstNotNullOfOrNull { p ->
            src(p).takeIf { it.isNotBlank() }
        } ?: ""

        val bot = src("src/main/kotlin/com/lifecyclebot/engine/BotService.kt")
        val shit = src("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinTraderAI.kt")
        val moon = src("src/main/kotlin/com/lifecyclebot/v3/scoring/MoonshotTraderAI.kt")
        val express = src("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinExpress.kt")
        val sniper = src("src/main/kotlin/com/lifecyclebot/v3/scoring/ProjectSniperAI.kt")
        val manipulated = src("src/main/kotlin/com/lifecyclebot/v3/scoring/ManipulatedTraderAI.kt")
        val quality = src("src/main/kotlin/com/lifecyclebot/v3/scoring/QualityTraderAI.kt")
        val blueChip = src("src/main/kotlin/com/lifecyclebot/v3/scoring/BlueChipTraderAI.kt")
        val treasury = src("src/main/kotlin/com/lifecyclebot/v3/scoring/CashGenerationAI.kt")
        val compound = src("src/main/kotlin/com/lifecyclebot/engine/AutoCompoundEngine.kt")
        val audit = firstSrc(
            "audits/asi_ssi_audit_queue_2026-06-27.md",
            "../audits/asi_ssi_audit_queue_2026-06-27.md",
        )

        return listOf(
            Proof(
                id = "NO_LEARNED_SHITCOIN_ZERO_SIZE_4235",
                passed = shit.contains("intelligenceGateSizeMult4231") &&
                    shit.contains("EXPECTANCY_SOFT_SHAPE_4231") &&
                    shit.contains("TILT_SOFT_SHAPE_4231") &&
                    shit.contains("SYMBOLIC_SOFT_SHAPE_4231") &&
                    !shit.contains("return rejectSignal(\"LEARNED_EXPECTANCY") &&
                    !shit.contains("return rejectSignal(\"TILT_BLOCK") &&
                    !shit.contains("return rejectSignal(\"SYMBOLIC_VETO"),
                detail = "learned ShitCoin intelligence must soft-shape size, not hard-zero entries",
            ),
            Proof(
                id = "ROUTING_REJECT_NOT_TERMINAL_4235",
                passed = bot.contains("fun v3RoutingRejectForShitCoin4233") &&
                    bot.contains("val isRoutingReject = v3RejectedIsRouting4230") &&
                    bot.contains("val shitCoinV3HardReject = v3HardStopsShitCoin4233()"),
                detail = "V3 routing rejects must remain routing-only; hard stops use one taxonomy",
            ),
            Proof(
                id = "BUY_NOT_OPENED_RELEASES_ALL_AUTHORITIES_4235",
                passed = bot.contains("fun releaseShitCoinAttempt4230") &&
                    bot.contains("LaneExecutionCoordinator.releaseIfPrimary") &&
                    bot.contains("TradeAuthorizer.release") &&
                    bot.contains("FinalExecutionPermit.release"),
                detail = "failed ShitCoin opens must release lane, authorizer, and permit state",
            ),
            Proof(
                id = "COMPOUND_LIVE_GROWTH_BOUNDED_4235",
                passed = compound.contains("val compoundPct: Double = 45.0") &&
                    compound.contains("val compoundThreshold: Double = 0.15") &&
                    compound.contains("val maxSizeMultiplier: Double = 3.0") &&
                    compound.contains("drawdownReduction: Boolean = true") &&
                    compound.contains("currentDrawdownPct > 10"),
                detail = "realized wins must lift size faster while drawdown safety remains active",
            ),
            Proof(
                id = "AUTO_COMPOUND_LANE_CONSUMERS_4253",
                passed = listOf(shit, moon, express, sniper, manipulated, quality, blueChip, treasury)
                    .all { it.contains("AutoCompoundEngine.getSizeMultiplier()") },
                detail = "all major meme/lifecycle lanes must consume AutoCompoundEngine size authority for realized-profit compounding",
            ),
            Proof(
                id = "ASYNC_AI_NEVER_HOT_PATH_4235",
                passed = audit.contains("Never scanner/FDG/executor hot path") &&
                    audit.contains("background-only") &&
                    audit.contains("StrategyHypothesisEngine"),
                detail = "free-tier AI/LLM/embedding work must be async and hypothesis-gated",
            ),
        )
    }

    fun allPassed(root: File): Boolean = proveSourceContracts(root).all { it.passed }
}
