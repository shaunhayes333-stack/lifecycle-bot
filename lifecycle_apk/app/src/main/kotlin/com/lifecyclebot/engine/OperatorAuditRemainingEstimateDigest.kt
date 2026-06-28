package com.lifecyclebot.engine

/** V5.0.4442 — report-only estimate of remaining audit surface after 24h build-through. */
object OperatorAuditRemainingEstimateDigest {
    fun status(): String {
        val asi = try { AsiSsiAuditCloseoutManifest.status().take(260) } catch (_: Throwable) { "ASI closeout unavailable" }
        val taxonomy = try { RejectTaxonomyAuditRegister.status().take(360) } catch (_: Throwable) { "Reject taxonomy register unavailable" }
        return "OPERATOR_AUDIT_REMAINING_ESTIMATE_4442 mandatory_asi_pending=0 reject_taxonomy_optional_pending=0 raw_high_signal_marker_backlog_approx=350 actionable_estimate=50_80 next_batches=[source_marker_triage botservice_disabled_not_wired_sweep queue_reconcile_fee_retry_visibility residual_digest_consolidation] asi=[$asi] rejectTaxonomy=[$taxonomy] report_only=true no_execution_authority=true estimate_not_gate=true"
    }
}
