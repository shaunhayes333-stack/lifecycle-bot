package com.lifecyclebot.engine

/**
 * V5.0.4533 — full learning-system audit source contract.
 *
 * Operator finding: the bot cannot improve as designed if engines only learn from
 * terminal result rows. Every learning/decision engine must be audited for full
 * lifecycle exposure: candidate, reject/admit, sizing, route/fill, hold/defer,
 * exit intent, terminal result, counterfactual miss, persistence, and authority.
 *
 * Report-only. No trading authority, no hot-path work, no external calls.
 */
object OperatorFullLearningSystemAuditDigest {
    const val VERSION = "V5.0.4533_FULL_LEARNING_SYSTEM_AUDIT"

    val lifecycleSignalsRequired = listOf(
        "candidate_seen",
        "candidate_scored",
        "reject_or_admit_reason",
        "fdg_decision",
        "sizing_components",
        "route_and_fill_quality",
        "hold_decision",
        "exit_intent",
        "exit_defer",
        "terminal_result",
        "counterfactual_missed_edge",
        "persistence_state",
        "authority_consumer",
        "live_paper_lane_source_regime_mux",
    )

    val auditCategories = listOf(
        "DATA_STARVATION",
        "RESULT_ONLY_LEARNING",
        "DEAD_ADVISORY_NO_CONSUMER",
        "AMNESIA_NO_PERSISTENCE",
        "HOT_PATH_RISK",
        "MUX_BLINDNESS",
        "AUTHORITY_DISCONNECTED",
        "COUNTERFACTUAL_BLINDNESS",
    )

    val resultOnlyOrUnderfedPriority = listOf(
        "ForwardOutcomeModel",
        "UnifiedPolicyHead",
        "UnifiedExitPolicyHead",
        "StrategyHypothesisEngine",
        "CounterfactualReplayEngine",
        "SemanticPatternGraph",
        "ScoreExpectancyTracker",
        "RunnerRetentionOptimizer",
        "RunnerExitShadowLedger",
        "ExitCostMicrobrain",
        "CapitalEfficiencyBrain",
        "BehaviorLearning",
        "AdaptiveLearningEngine",
        "MarketRegimeAI",
        "PatternAutoTuner",
        "TokenWinMemory",
        "LosingPatternMemory",
    )

    val advisoryAuthorityPriority = listOf(
        "UltimateEdgeEngine",
        "MultiplierAttributionLedger",
        "SourceFamilyOpportunityScorecard",
        "ExecutionCostPredictorAI",
        "LiveProbabilityEngine",
        "LiveStrategyTuner",
        "LaneExpectancyDamper",
        "ExecutionRouteReliabilityMemory",
        "MetaCognitionExecutorBridge",
        "PaperLiveIntelligenceBridge",
        "RegimeVolatilityExecutorBridge",
        "ScannerDiversityBandit",
        "MemeCrossTalkEntryBridge",
        "TreasurySourceRouter",
        "LiquidityBucketRouter",
    )

    val scorerFeedbackPriority = listOf(
        "EntryAI",
        "FearGreedAI",
        "SocialVelocityAI",
        "MEVDetectionAI",
        "LiquidityExitPathAI",
        "FlowImbalanceModel",
        "VenueLagModel",
        "PanicReversionModel",
        "MarketStructureRouter",
        "ArbLearning",
        "ScannerSourceBrain",
        "SecondScorer",
    )

    val patchSequence = listOf(
        "4534 scanner_v3_fdg_reject_label_data_plane",
        "4535 scorer_model_feedback_fanout",
        "4536 exit_hold_counterfactual_feedback_fanout",
        "4537 advisory_authority_and_sizing_consumer_wiring",
        "4538 persistence_amnesia_and_mux_closeout",
    )

    fun summary(): String = buildString {
        appendLine("===== Full Learning System Audit ($VERSION) =====")
        appendLine("finding=result_only_learning_is_insufficient_for_self_improvement")
        appendLine("required_signals=${lifecycleSignalsRequired.joinToString(",")}")
        appendLine("audit_categories=${auditCategories.joinToString(",")}")
        appendLine("underfed_priority=${resultOnlyOrUnderfedPriority.joinToString(",")}")
        appendLine("advisory_priority=${advisoryAuthorityPriority.joinToString(",")}")
        appendLine("scorer_priority=${scorerFeedbackPriority.joinToString(",")}")
        appendLine("patch_sequence=${patchSequence.joinToString(",")}")
        appendLine("authority=report_only_source_contract no_hot_path_work=true no_trade_block=true")
    }
}
