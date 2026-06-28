package com.lifecyclebot.engine

/**
 * V5.0.4410 — report-only sibling/butterfly/choke audit ledger.
 *
 * This ledger deliberately does not call the target mechanisms. It records the
 * pattern families that must be handled as siblings before surgical fixes:
 * zero-sizing, hard false returns, daily-loss pauses, veto/reject taxonomies,
 * and provider-call/hot-path risk hints.
 */
object OperatorChokeButterflyAuditLedger {
    data class PatternFamily(
        val id: String,
        val fileCount: Int,
        val risk: String,
        val butterflies: String,
        val sampleFiles: List<String>
    )

    private val families = listOf(
        PatternFamily(
            id = "ZERO_SIZE_OR_MULTIPLIER",
            fileCount = 60,
            risk = "silent dust sizing or learned-strategy zero sizing can choke throughput",
            butterflies = "SmartSizer, FinalDecisionGate, Executor, journal, learning admission, KPI volume",
            sampleFiles = listOf("Executor.kt", "FinalDecisionGate.kt", "LiveSizingProfile.kt", "BotBrain.kt", "CashGenerationAI.kt")
        ),
        PatternFamily(
            id = "HARD_RETURN_FALSE",
            fileCount = 158,
            risk = "non-safety false returns can amputate sibling lanes before FDG/executor telemetry",
            butterflies = "scanner intake, lane coordinator, FinalExecutionPermit, TradeAuthorizer, route verdict, release paths",
            sampleFiles = listOf("BotService.kt", "TradeAuthorizer.kt", "ExecutableOpenGate.kt", "AICrossTalk.kt", "CollectiveLearning.kt")
        ),
        PatternFamily(
            id = "DAILY_LOSS_PAUSE_OR_STREAK",
            fileCount = 31,
            risk = "lane-local pause logic may suppress recovery probes despite global safety remaining healthy",
            butterflies = "lane policy, SmartSizer, AutoMode, SecurityGuard, KillSwitch, KPI suppressor counters",
            sampleFiles = listOf("AutoModeEngine.kt", "FinalExecutionPermit.kt", "TradeAuthorizer.kt", "SecurityGuard.kt", "ColdStreakDamper.kt")
        ),
        PatternFamily(
            id = "VETO_OR_REJECT_TAXONOMY",
            fileCount = 197,
            risk = "drifty reject labels can hide hard blocks under advisory/intelligence names",
            butterflies = "Golden Tape, ScannerHardRejectStore, FDG route verdict, lane taxonomy, terminal learning labels",
            sampleFiles = listOf("BotService.kt", "AgenticStyleRouter.kt", "BrainConsensusGate.kt", "DataOrchestrator.kt", "CanonicalLearning.kt")
        ),
        PatternFamily(
            id = "HOTPATH_PROVIDER_HINT",
            fileCount = 189,
            risk = "provider/API work near scanner or executor hot paths can throttle live volume",
            butterflies = "SolanaMarketScanner, ApiHealthMonitor, BirdeyeBudgetGate, Jupiter quote handling, background cache policy",
            sampleFiles = listOf("BotService.kt", "BirdeyeBudgetGate.kt", "BirdeyeTradeDataProvider.kt", "CollectiveLearning.kt", "BundleDetector.kt")
        )
    )

    fun status(): String {
        val ids = families.joinToString(",") { "${it.id}:${it.fileCount}" }
        val samples = families.joinToString(";") { "${it.id}=${it.sampleFiles.joinToString("|")}" }
        return "OPERATOR_CHOKE_BUTTERFLY_AUDIT_LEDGER_4410 families=[$ids] samples=[$samples] report_only=true no_execution_authority=true no_gate_change=true no_hot_path_provider_calls=true sibling_pattern_first=true butterflies_considered=true"
    }
}
