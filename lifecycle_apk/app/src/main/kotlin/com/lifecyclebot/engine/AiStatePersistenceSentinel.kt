package com.lifecyclebot.engine

/**
 * V5.0.4295 — A38 report-only source-contract for learned/AI state persistence.
 *
 * This object has no scanner, FDG, sizing, or execution authority. It simply
 * lists the AI/SSI helpers that must remain wired through LearningPersistence
 * save/import/reset so future brains do not become restart-amnesiac.
 */
object AiStatePersistenceSentinel {
    data class ExpectedState(
        val key: String,
        val helper: String,
        val requiresExport: Boolean = true,
        val requiresImport: Boolean = true,
        val requiresReset: Boolean = true,
    )

    val expectedStates: List<ExpectedState> = listOf(
        ExpectedState("ASYNC_STRATEGY_LAB", "AsyncStrategyLab"),
        ExpectedState("SEMANTIC_PATTERN_GRAPH", "SemanticPatternGraph"),
        ExpectedState("COUNTERFACTUAL_REPLAY", "CounterfactualReplayEngine"),
        ExpectedState("RESEARCH_SCOUT", "ResearchScout"),
        ExpectedState("MULTIPLIER_ATTRIBUTION", "MultiplierAttributionLedger"),
        ExpectedState("EXIT_COST_MICROBRAIN", "ExitCostMicrobrain"),
        ExpectedState("CAPITAL_EFFICIENCY", "CapitalEfficiencyBrain"),
        ExpectedState("SOURCE_FAMILY_SCORECARD", "SourceFamilyOpportunityScorecard"),
        ExpectedState("RUNNER_EXIT_SHADOW_LEDGER", "RunnerExitShadowLedger"),
        ExpectedState("LIVE_WALLET_GROWTH_GOVERNOR", "LiveWalletGrowthGovernorReport"),
    )

    fun status(): String {
        val keys = expectedStates.joinToString(",") { it.key }
        return "AI_STATE_PERSISTENCE_SENTINEL_4295 expected=${expectedStates.size} keys=$keys report_only=true no_execution_authority=true"
    }

    fun emit() {
        try {
            ForensicLogger.lifecycle("AI_STATE_PERSISTENCE_SENTINEL_4295", status().take(900))
            PipelineHealthCollector.labelInc("AI_STATE_PERSISTENCE_SENTINEL_4295")
        } catch (_: Throwable) {}
    }
}
