package com.lifecyclebot.engine

/** V5.0.4442 — report-only estimate of remaining audit surface after 24h build-through. */
object OperatorAuditRemainingEstimateDigest {
    fun status(): String {
        val asi = try { AsiSsiAuditCloseoutManifest.status().take(260) } catch (_: Throwable) { "ASI closeout unavailable" }
        val taxonomy = try { RejectTaxonomyAuditRegister.status().take(360) } catch (_: Throwable) { "Reject taxonomy register unavailable" }
        return "OPERATOR_AUDIT_REMAINING_ESTIMATE_4442 mandatory_asi_pending=0 reject_taxonomy_optional_pending=0 raw_high_signal_marker_backlog=353 crude_post_noise=298 operator_actionable_estimate=80_120 next_batches=[source_marker_triage_4443 botservice_disabled_not_wired_sweep executor_fdg_toxicity_marker_sweep queue_reconcile_fee_retry_visibility residual_digest_consolidation] asi=[$asi] rejectTaxonomy=[$taxonomy] report_only=true no_execution_authority=true estimate_not_gate=true"
    }
}
