package com.lifecyclebot.engine

/**
 * V5.0.4411 — prioritized remediation queue for the choke/butterfly audit.
 *
 * Report-only: this file does not soften gates or change execution. It names
 * the source-level families that should be patched surgically, with sibling and
 * downstream butterfly paths explicitly attached before behavior changes.
 */
object OperatorChokeRemediationQueue {
    data class QueueItem(
        val id: String,
        val pattern: String,
        val priority: String,
        val confirmedSiblings: List<String>,
        val sourceFixShape: String,
        val butterflyPath: String
    )

    private val items = listOf(
        QueueItem(
            id = "CQ1_EXECUTION_ZERO_SIZE_STACK",
            pattern = "zero-size or zero-multiplier source path",
            priority = "P0_VOLUME_CHOKE",
            confirmedSiblings = listOf("Executor.kt", "FinalDecisionGate.kt", "LiveSizingProfile.kt", "SmartSizer.kt", "CashGenerationAI.kt"),
            sourceFixShape = "replace non-safety zero sizing with bounded recovery probe or explicit safety-only zero reason",
            butterflyPath = "FDG -> sizing multiplier stack -> Executor -> journal -> learning admission -> KPI volume"
        ),
        QueueItem(
            id = "CQ2_AUTHORITY_FALSE_RETURN_STACK",
            pattern = "hard return false before route/finality telemetry",
            priority = "P0_LANE_AMPUTATION",
            confirmedSiblings = listOf("BotService.kt", "TradeAuthorizer.kt", "ExecutableOpenGate.kt", "FinalExecutionPermit.kt", "LaneExecutionCoordinator.kt"),
            sourceFixShape = "preserve true hard-safety false returns; convert non-safety false returns into route verdict plus release telemetry",
            butterflyPath = "scanner admission -> lane lease -> FEP -> TradeAuthorizer -> route verdict -> release path -> journal visibility"
        ),
        QueueItem(
            id = "CQ3_DAILY_PAUSE_RECOVERY_PROBE_PARITY",
            pattern = "lane-local daily loss or paused state suppresses recovery probes",
            priority = "P1_RECOVERY_VOLUME",
            confirmedSiblings = listOf("AutoModeEngine.kt", "TradeAuthorizer.kt", "FinalExecutionPermit.kt", "ColdStreakDamper.kt", "SecurityGuard.kt"),
            sourceFixShape = "catastrophic global safety remains hard; lane-local pause becomes bounded probe/sizing telemetry",
            butterflyPath = "lane policy -> AutoMode -> SecurityGuard/KillSwitch boundary -> SmartSizer -> KPI suppressor counters"
        ),
        QueueItem(
            id = "CQ4_REJECT_TAXONOMY_NORMALIZATION",
            pattern = "veto/reject labels drift across advisory, hard safety and trainable labels",
            priority = "P1_LABEL_TRUTH",
            confirmedSiblings = listOf("BotService.kt", "AgenticStyleRouter.kt", "BrainConsensusGate.kt", "CanonicalLearning.kt", "ScannerHardRejectStore.kt"),
            sourceFixShape = "normalize reject taxonomy into hard-safety, cost-reject, penalty, low-liq size-reduction, and advisory classes",
            butterflyPath = "scanner source -> FDG verdict -> executor skip/sell reason -> journal label -> canonical learning -> Golden Tape"
        ),
        QueueItem(
            id = "CQ5_PROVIDER_CALL_BACKGROUND_ISOLATION",
            pattern = "provider/API hint near scanner or executor hot path",
            priority = "P1_THROUGHPUT_LATENCY",
            confirmedSiblings = listOf("SolanaMarketScanner.kt", "BotService.kt", "BirdeyeBudgetGate.kt", "BundleDetector.kt", "CollectiveLearning.kt"),
            sourceFixShape = "move provider reads behind cache-first/background refresh with explicit stale/degraded telemetry",
            butterflyPath = "scanner cadence -> ApiHealthMonitor -> provider budget -> candidate scoring -> FDG throughput -> live/paper parity"
        )
    )

    fun status(): String {
        val ids = items.joinToString(",") { "${it.id}:${it.priority}" }
        val siblings = items.joinToString(";") { "${it.id}=${it.confirmedSiblings.joinToString("|")}" }
        return "OPERATOR_CHOKE_REMEDIATION_QUEUE_4411 items=${items.size} ids=[$ids] siblings=[$siblings] report_only=true no_execution_authority=true no_gate_change=true source_fix_first=true sibling_patterns_named=true butterflies_named=true golden_tape_required=true"
    }
}
