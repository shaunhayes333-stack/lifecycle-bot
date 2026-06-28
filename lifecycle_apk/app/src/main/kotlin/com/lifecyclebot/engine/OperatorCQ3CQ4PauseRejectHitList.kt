package com.lifecyclebot.engine

/** V5.0.4414 — bundled CQ3/CQ4 pause + reject taxonomy hit list, report-only. */
object OperatorCQ3CQ4PauseRejectHitList {
    data class Hit(
        val id: String,
        val files: List<String>,
        val classification: String,
        val risk: String,
        val butterflyPath: String
    )

    private val hits = listOf(
        Hit(
            id = "CQ3_LANE_LOCAL_PAUSE_RECOVERY_PROBE",
            files = listOf("AutoModeEngine.kt", "FinalExecutionPermit.kt", "TradeAuthorizer.kt", "ColdStreakDamper.kt"),
            classification = "RECOVERY_PROBE_PARITY_CANDIDATE",
            risk = "lane-local pause or cold-streak logic may suppress bounded probes while global safety is healthy",
            butterflyPath = "lane policy -> AutoMode -> FEP/TradeAuthorizer -> SmartSizer -> KPI suppressor counters"
        ),
        Hit(
            id = "CQ3_GLOBAL_SAFETY_BOUNDARY",
            files = listOf("SecurityGuard.kt", "KillSwitch.kt", "RuntimeConfigOverlay.kt"),
            classification = "HARD_SAFETY_BOUNDARY_PRESERVE",
            risk = "catastrophic safety must remain hard and must not be collapsed into recovery-probe logic",
            butterflyPath = "global drawdown/rug safety -> trading pause -> live wallet protection -> operator alert"
        ),
        Hit(
            id = "CQ4_REJECT_TAXONOMY_DRIFT",
            files = listOf("BotService.kt", "AgenticStyleRouter.kt", "BrainConsensusGate.kt", "CanonicalLearning.kt"),
            classification = "REJECT_LABEL_NORMALIZATION_CANDIDATE",
            risk = "advisory veto, penalty, low-liq size reduction, cost reject and hard safety labels can drift",
            butterflyPath = "scanner source -> FDG verdict -> executor skip -> journal reason -> canonical learning -> Golden Tape"
        ),
        Hit(
            id = "CQ4_SCANNER_HARD_REJECT_BOUNDARY",
            files = listOf("ScannerHardRejectStore.kt", "TokenSafetyChecker.kt", "SolanaMarketScanner.kt"),
            classification = "HARD_SAFETY_BOUNDARY_PRESERVE",
            risk = "rug/LP-unlocked hard rejects must remain hard while advisory red flags stay source-balanced",
            butterflyPath = "scanner intake -> hard reject store -> watchlist/probation exclusion -> source balance metrics"
        )
    )

    fun status(): String {
        val ids = hits.joinToString(",") { "${it.id}:${it.classification}" }
        return "OPERATOR_CQ3_CQ4_PAUSE_REJECT_HIT_LIST_4414 total=${hits.size} ids=[$ids] report_only=true no_execution_authority=true no_gate_change=true recovery_probe_candidates_named=true hard_safety_boundaries_preserved=true reject_taxonomy_required=true butterflies_named=true"
    }
}
