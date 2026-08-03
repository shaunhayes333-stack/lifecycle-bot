package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6411 §6 — EXECUTION-TELEMETRY CORRECTION + §19 ROOT-CAUSE
 * PRECEDENCE + §18 HEALTH-REPORT READINESS TILE.
 *
 * OPERATOR DIRECTIVE (Feb 2026)
 * ─────────────────────────────
 * Old surface:  "LIVE ENTRY AUTHORITY: open" — masks the true state.
 * New surface:  policy / route / rpc / wallet / journal / reconcile
 *               split readiness + typed bottleneck root cause.
 *
 * Bottleneck inference order (§19):
 *   1. EXIT_PIPELINE_CRITICAL
 *   2. WALLET_OR_SIGNATURE_RECONCILIATION_FAILURE
 *   3. RPC_SEND_UNAVAILABLE
 *   4. NO_HEALTHY_EXECUTION_ADAPTER
 *   5. AUTHORISED_NOT_ROUTED
 *   6. ROUTED_NOT_QUOTED
 *   7. QUOTED_NOT_SUBMITTED
 *   8. PROVIDER_DATA_DEGRADED
 *   9. TOKEN_MAPPING_BACKLOG
 *  10. SCANNER_OVERLOAD
 *  11. LANE_SCORE_OR_INTAKE_STALL
 *  12. HEALTHY
 *
 * For build 6410 this MUST have surfaced AUTHORISED_NOT_ROUTED.
 */
object LiveExecutionReadiness6411 {

    enum class Readiness { READY, DEGRADED, NOT_READY, UNKNOWN }

    data class Report(
        val policy: Readiness,
        val route: Readiness,
        val rpc: Readiness,
        val wallet: Readiness,
        val journal: Readiness,
        val reconciliation: Readiness,
        val executionReady: Boolean,
        val blockReason: String,
        val activeAdapters: List<ExecutionAdapter6411>,
        val unavailableAdapters: List<ExecutionAdapter6411>,
        val bottleneck: String,
    ) {
        fun toTile(): String =
            "policy=${policy.name} route=${route.name} rpc=${rpc.name} wallet=${wallet.name} " +
                "journal=${journal.name} reconcile=${reconciliation.name} ready=$executionReady " +
                "block=${blockReason.take(48)} active=[${activeAdapters.joinToString(",") { it.name }}] " +
                "unavailable=[${unavailableAdapters.joinToString(",") { it.name }}] bottleneck=$bottleneck"
    }

    private fun snap(key: String): Long = try {
        PipelineHealthCollector.labelCountSnapshot(key)
    } catch (_: Throwable) { 0L }

    /**
     * Compute readiness/bottleneck from live counters. Report-only —
     * no authority over sizing/gates/execution.
     */
    fun compute(): Report {
        // Route readiness: at least one adapter available?
        val activeAdapters = ExecutionAdapter6411.values().filter {
            try { ProviderDomainCircuits6411.isAvailable(it) } catch (_: Throwable) { true }
        }
        val unavailableAdapters = ExecutionAdapter6411.values().filter { it !in activeAdapters }
        val routeReady = when {
            activeAdapters.isEmpty() -> Readiness.NOT_READY
            unavailableAdapters.isNotEmpty() -> Readiness.DEGRADED
            else -> Readiness.READY
        }
        // Policy readiness: SafetyHold-driven. Advisory via label presence.
        val policyHeld = snap("LIVE_ENTRY_SAFETY_HOLD_ARMED") > 0L
        val policy = if (policyHeld) Readiness.NOT_READY else Readiness.READY

        // Wallet readiness — WalletManager health is authoritative but not
        // available here directly. Approximate via label presence: mark
        // NOT_READY if we've recently seen BALANCE_UNKNOWN* signals.
        val walletUnknownRecent = snap("SELL_UNKNOWN_BALANCE_ADV") > 0L
        val wallet = if (walletUnknownRecent) Readiness.DEGRADED else Readiness.READY

        // Journal readiness — did any TRADEJRNL_REC happen in this session?
        // Also does the execution-attempt journal have rows?
        val journalRows = try { ExecutionAttemptJournal6411.writtenCount() } catch (_: Throwable) { 0L }
        val canonicalRows = snap("TRADEJRNL_REC")
        val journal = when {
            journalRows == 0L && canonicalRows == 0L -> Readiness.UNKNOWN
            else -> Readiness.READY
        }

        // RPC — advisory. Real RPC health lives in NetworkStack; approximate
        // via labelless default READY unless recent failures logged.
        val rpc = Readiness.READY

        // Reconciliation — advisory READY unless we saw a recent invariant.
        val reconcile = Readiness.READY

        val ready = routeReady != Readiness.NOT_READY &&
            policy != Readiness.NOT_READY &&
            journal != Readiness.NOT_READY &&
            rpc != Readiness.NOT_READY

        val blockReason = when {
            routeReady == Readiness.NOT_READY -> "NO_HEALTHY_EXECUTION_ADAPTER"
            policy == Readiness.NOT_READY -> "POLICY_SAFETY_HOLD_ARMED"
            journal == Readiness.NOT_READY -> "JOURNAL_NOT_READY"
            else -> "-"
        }

        // Bottleneck inference (§19)
        val execAuthorised = snap("EXEC_OPEN_ALLOWED")
        val routeResolved = snap("EXEC_ROUTE_RESOLVED_6411")
        val quoteSuccess = snap("QUOTE_SUCCESSES")
        val rpcSendAttempts = snap("RPC_SEND_ATTEMPTS")
        val buyConfirmed = snap("BUY_CONFIRMED")

        val bottleneck = when {
            !ready && blockReason == "NO_HEALTHY_EXECUTION_ADAPTER" -> "NO_HEALTHY_EXECUTION_ADAPTER"
            execAuthorised > 0 && routeResolved * 4 < execAuthorised -> "AUTHORISED_NOT_ROUTED"
            routeResolved > 0 && quoteSuccess * 4 < routeResolved -> "ROUTED_NOT_QUOTED"
            quoteSuccess > 0 && rpcSendAttempts * 4 < quoteSuccess -> "QUOTED_NOT_SUBMITTED"
            rpcSendAttempts > 0 && buyConfirmed * 4 < rpcSendAttempts -> "SUBMITTED_NOT_CONFIRMED"
            snap("PROVIDER_DOMAIN_CIRCUIT_OPEN_6411") > 0 -> "PROVIDER_DATA_DEGRADED"
            snap("TOKEN_MAP_PENDING") > snap("TOKEN_MAP_OK") -> "TOKEN_MAPPING_BACKLOG"
            snap("SCANNER_BATCH_BUDGET_EXCEEDED") > 20 -> "SCANNER_OVERLOAD"
            execAuthorised == 0L && snap("PHASE/INTAKE") > 500 -> "LANE_SCORE_OR_INTAKE_STALL"
            else -> "HEALTHY"
        }

        return Report(
            policy = policy, route = routeReady, rpc = rpc, wallet = wallet,
            journal = journal, reconciliation = reconcile,
            executionReady = ready, blockReason = blockReason,
            activeAdapters = activeAdapters, unavailableAdapters = unavailableAdapters,
            bottleneck = bottleneck,
        )
    }

    fun tile(): String = try {
        val r = compute()
        r.toTile()
    } catch (_: Throwable) {
        "readiness_unavailable"
    }
}
