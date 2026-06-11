package com.lifecyclebot.engine

/**
 * V5.9.1103 — hard runtime regression assertions for the architecture train.
 *
 * Pure diagnostics: computes pass/fail bands from counters so the pipeline dump
 * directly says what is still broken instead of requiring manual arithmetic.
 */
object RuntimeRegressionGuards {
    data class Check(val name: String, val ok: Boolean, val detail: String)

    data class Input(
        val intake: Long = 0L,
        val laneEval: Long = 0L,
        val execOpenRequest: Long = 0L,
        val actualBuyAttempts: Long = 0L,
        val learningTrades: Long = 0L,
        val uniqueClosedPositionIds: Long = 0L,
        val paperTradesInLiveStore: Long = 0L,
        val forensicsEvents: Long = 0L,
        val forensicLoggingOn: Boolean = true,
        val runtimeActive: Boolean = false,
        val uiRunning: Boolean = false,
        val sellReconcilerStarted: Boolean = false,
        val hostTrackerOpenCount: Int = 0,
        val positionStoreOpenCount: Int = 0,
        val paperOpenPositions: Int = 0,
        val liveOpenPositions: Int = 0,
        val walletHeldMints: Int = 0,
        val canonicalOpenPositions: Int = 0,
        // V5.9.1522 — live execution finalisation guards
        val orphanLivePositions: Int = 0,
        val reconcilerTotalTicks: Long = 0L,
        val sellJobsActive: Int = 0,
        val noSignatureLeakedLock: Boolean = false,
        val reconciliationGraceElapsed: Boolean = true,
        // V5.9.1526 — canonical close-authority guards
        val closedPositionsWithNonDustBalance: Int = 0,
        val closedPositionsWithoutSignature: Int = 0,
        val duplicateCanonicalOpenMints: Int = 0,
    )

    fun evaluate(input: Input): List<Check> {
        val laneRatio = if (input.intake > 0) input.laneEval.toDouble() / input.intake.toDouble() else 0.0
        val execRatio = if (input.actualBuyAttempts > 0) input.execOpenRequest.toDouble() / input.actualBuyAttempts.toDouble() else 0.0
        return listOf(
            Check(
                "runtime_ui_truth",
                ok = !(input.runtimeActive && !input.uiRunning),
                detail = "runtimeActive=${input.runtimeActive} uiRunning=${input.uiRunning}",
            ),
            Check(
                "sell_reconciler_running",
                ok = !input.runtimeActive || input.sellReconcilerStarted || input.liveOpenPositions == 0,
                detail = "runtimeActive=${input.runtimeActive} sellReconcilerStarted=${input.sellReconcilerStarted} liveOpen=${input.liveOpenPositions} paperOpen=${input.paperOpenPositions}",
            ),
            Check(
                "host_tracker_open_match",
                ok = input.liveOpenPositions == input.hostTrackerOpenCount,
                detail = "hostLive=${input.hostTrackerOpenCount} liveStore=${input.liveOpenPositions} paperStore=${input.paperOpenPositions} walletHeld=${input.walletHeldMints} canonical=${input.canonicalOpenPositions}",
            ),
            Check(
                "lane_eval_intake_ratio",
                ok = input.intake <= 0 || laneRatio <= 12.0,
                detail = "laneEval=${input.laneEval} intake=${input.intake} ratio=${"%.2f".format(laneRatio)} max=12",
            ),
            Check(
                "exec_request_buy_ratio",
                ok = input.actualBuyAttempts <= 0 || execRatio <= 5.0,
                detail = "execOpenRequest=${input.execOpenRequest} actualBuyAttempts=${input.actualBuyAttempts} ratio=${"%.2f".format(execRatio)} max=5",
            ),
            Check(
                "learning_equals_unique_closes",
                ok = input.learningTrades == input.uniqueClosedPositionIds,
                detail = "learningTrades=${input.learningTrades} uniqueClosedPositionIds=${input.uniqueClosedPositionIds}",
            ),
            Check(
                "paper_not_in_live_store",
                ok = input.paperTradesInLiveStore == 0L,
                detail = "paperTradesInLiveStore=${input.paperTradesInLiveStore}",
            ),
            Check(
                "forensics_events_present",
                ok = !input.forensicLoggingOn || input.forensicsEvents > 0L,
                detail = "forensicLoggingOn=${input.forensicLoggingOn} forensicsEvents=${input.forensicsEvents}",
            ),
            // ── V5.9.1522 LIVE EXECUTION FINALISATION GUARDS ──
            Check(
                "live_reconciler_mandatory",
                // live + active + wallet holds mints ⇒ reconciler MUST be started
                ok = !input.runtimeActive || input.walletHeldMints == 0 || input.sellReconcilerStarted,
                detail = "runtimeActive=${input.runtimeActive} walletHeld=${input.walletHeldMints} reconcilerStarted=${input.sellReconcilerStarted}",
            ),
            Check(
                "wallet_canonical_parity",
                ok = input.walletHeldMints == input.canonicalOpenPositions,
                detail = "walletHeld=${input.walletHeldMints} canonical=${input.canonicalOpenPositions}",
            ),
            Check(
                "live_store_not_empty_when_wallet_held",
                ok = !(input.walletHeldMints > 0 && input.liveOpenPositions == 0),
                detail = "walletHeld=${input.walletHeldMints} liveOpen=${input.liveOpenPositions}",
            ),
            Check(
                "no_reconciler_zombie",
                // activeJobs>0 with totalTicks==0 is an illegal zombie state
                ok = !(input.sellJobsActive > 0 && input.reconcilerTotalTicks == 0L && input.runtimeActive && input.liveOpenPositions + input.walletHeldMints > 0),
                detail = "sellJobsActive=${input.sellJobsActive} reconcilerTotalTicks=${input.reconcilerTotalTicks}",
            ),
            Check(
                "no_orphan_live_after_grace",
                ok = !input.reconciliationGraceElapsed || input.orphanLivePositions == 0,
                detail = "orphanLive=${input.orphanLivePositions} graceElapsed=${input.reconciliationGraceElapsed}",
            ),
            Check(
                "no_signature_lock_cleared",
                ok = !input.noSignatureLeakedLock,
                detail = "noSignatureLeakedLock=${input.noSignatureLeakedLock}",
            ),
            // ── V5.9.1526 CANONICAL CLOSE AUTHORITY GUARDS ──
            Check(
                "no_closed_with_nondust_balance",
                ok = input.closedPositionsWithNonDustBalance == 0,
                detail = "closedWithNonDustBalance=${input.closedPositionsWithNonDustBalance}",
            ),
            Check(
                "no_closed_without_signature",
                // a CLOSED live position claimed via SELL must carry a sell sig
                // (explicit reaps are tagged RECONCILER_REAP_NOSIG and excluded
                //  by the collector, so any count here is a real violation)
                ok = input.closedPositionsWithoutSignature == 0,
                detail = "closedWithoutSig=${input.closedPositionsWithoutSignature}",
            ),
            Check(
                "no_duplicate_canonical_opens",
                ok = input.duplicateCanonicalOpenMints == 0,
                detail = "duplicateCanonicalOpenMints=${input.duplicateCanonicalOpenMints}",
            ),
        )
    }

    fun summary(checks: List<Check>): String {
        val bad = checks.filter { !it.ok }
        val failedNames = bad.map { it.name }.joinToString(",")
        return if (bad.isEmpty()) "REGRESSION_GUARDS_OK ${checks.size}/${checks.size}"
        else "REGRESSION_GUARDS_FAIL ${checks.size - bad.size}/${checks.size} failed=$failedNames"
    }
}
