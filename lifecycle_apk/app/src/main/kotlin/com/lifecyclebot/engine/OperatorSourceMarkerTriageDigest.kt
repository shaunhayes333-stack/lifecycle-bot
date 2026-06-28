package com.lifecyclebot.engine

/** V5.0.4443 — report-only triage map for residual TODO/disabled/dormant/not-wired source markers. */
object OperatorSourceMarkerTriageDigest {
    fun status(): String = "OPERATOR_SOURCE_MARKER_TRIAGE_DIGEST_4443 raw_markers=353 estimated_noise=55 crude_post_noise=298 operator_actionable_estimate=80_120 top_clusters=[BotService:48 Executor:18 FinalDecisionGate:18 TokenSafetyChecker:18 ToxicModeCircuitBreaker:16 RuntimeRepairState:14 ExecutionEndpointHealth:13 DataPipeline:12 AntiChokeManager:10 StrategyTelemetry:10 ExitProviderHealth:9] next_batches=[botservice_intentional_vs_real_disabled executor_fdg_toxicity_marker_sweep safety_checker_hard_vs_soft_marker_sweep runtime_repair_endpoint_health_digest] report_only=true no_behavior_change=true estimate_not_gate=true"
}
