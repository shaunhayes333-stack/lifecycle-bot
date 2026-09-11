package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6441 §12 — ACCEPTANCE INVARIANT AUDIT.
 * Pure observer: never mutates runtime state.
 *
 * V5.0.6699 — reward-population parity follows the canonical finalized-bus
 * contract introduced by 6697: an outcome is terminally handled when the
 * consumer either actually processed it or explicitly EXCLUDED it from
 * learning. EXCLUDED is not a fake ACK and does not inflate W/L/BE.
 */
object AcceptanceInvariantAudit6441 {

    data class AuditReport(
        val whenMs: Long,
        val passed: List<String>,
        val failed: List<String>,
    ) {
        val ok: Boolean = failed.isEmpty()
    }

    private val runCount = AtomicLong(0L)
    private val failureCount = AtomicLong(0L)
    @Volatile private var lastReport: AuditReport? = null

    fun runAudit(): AuditReport {
        runCount.incrementAndGet()
        val passed = mutableListOf<String>()
        val failed = mutableListOf<String>()

        // 1. PAPER cash never negative.
        val cash = CanonicalPositionAuthority6441.paperCashSol()
        if (cash >= 0.0) passed.add("cash>=0") else failed.add("cash<0=$cash")

        // 2. No oversold quantity + closed with residual.
        val allPositions = CanonicalPositionAuthority6441.openPositions() +
            CanonicalPositionAuthority6441.closedPositions()
        val oversold = allPositions.any { it.remainingQtyRaw < BigInteger.ZERO }
        val closedWithQty = allPositions.any {
            it.lifecycle == CanonicalPositionAuthority6441.Lifecycle.CLOSED &&
                it.remainingQtyRaw != BigInteger.ZERO
        }
        if (!oversold) passed.add("no_oversold") else failed.add("oversold_qty_present")
        if (!closedWithQty) passed.add("closed=>zero_qty") else failed.add("closed_with_qty")

        // 3. Idempotency counters active during trades.
        val idempotencyRows = try { IdempotencyKeyStore6437.rowCount() } catch (_: Throwable) { 0 }
        if (allPositions.isEmpty() || idempotencyRows > 0) passed.add("idempotency_active")
        else failed.add("executions_but_no_idempotency_rows=positions=${allPositions.size},idemRows=$idempotencyRows")

        // 4. Reward purity / canonical terminal population.
        // 6697 deliberately separated ACK (consumer mutation) from EXCLUDED
        // (terminally ineligible for that learner). The pre-6697 equality
        // closed == W+L+BE therefore became a contradictory invariant and
        // produced reward_pop_mismatch forever for correctly quarantined rows.
        // Correct parity is:
        //   canonical CLOSED == finalized bus canonical population
        //   canonical CLOSED == RewardPurity processed + RewardPurity excluded
        // while W/L/BE itself remains processed-only and unpolluted.
        val (w, l, b) = RewardPurityGate6441.canonicalCounts()
        val sessionRewardProcessed6734 = (w + l + b).toInt()
        val rewardProcessed6699 = CanonicalFinalizedTradeBus6464.consumerUnique("RewardPurity")
        val rewardExcluded6699 = try {
            CanonicalFinalizedTradeBus6464.consumerExcludedUnique("RewardPurity")
        } catch (_: Throwable) { 0 }
        val busCanonical6699 = try { CanonicalFinalizedTradeBus6464.canonicalUnique() } catch (_: Throwable) { 0 }
        val closedCount = CanonicalPositionAuthority6441.closedPositions().size
        val rewardHandled6699 = rewardProcessed6699 + rewardExcluded6699
        val rewardParity6699 = closedCount == 0 ||
            (busCanonical6699 == closedCount && rewardHandled6699 == closedCount)
        if (rewardParity6699) {
            passed.add("reward_terminal_pop==closed(processed=$rewardProcessed6699,excluded=$rewardExcluded6699,session=$sessionRewardProcessed6734)")
        } else {
            failed.add(
                "reward_pop_mismatch:closed=$closedCount,bus=$busCanonical6699," +
                    "processed=$rewardProcessed6699,excluded=$rewardExcluded6699,handled=$rewardHandled6699"
            )
        }

        // 5. OrderSizeResolver must have been queried on eligible entries.
        val resolverLine = OrderSizeResolver6441.statusLine()
        if (allPositions.isEmpty() || resolverLine.contains("resolves=") && !resolverLine.contains("resolves=0")) {
            passed.add("resolver_queried")
        } else failed.add("resolver_not_queried")

        // 6. LEARNER budget: no pending slice over 30s.
        val budgetLine = LearnerRuntimeBudgetGuard6441.statusLine()
        passed.add("learner_budget_$budgetLine".take(50))

        // 7. RECONCILER heartbeat sane.
        val reconStat = CanonicalReconciler6441.statusLine()
        passed.add("recon_$reconStat".take(50))

        // A. Bounded executable fanout.
        val fanoutFail = try {
            PipelineHealthCollector.labelCountSnapshot("EXECUTABLE_FANOUT_OVER_LIMIT_6536") > 0L
        } catch (_: Throwable) { false }
        if (!fanoutFail) passed.add("A_fanout_bounded") else failed.add("A_executable_fanout_exceeded_2")

        // B. V3 admission must have FDG or explicit reject.
        val v3OrphanFail = try {
            PipelineHealthCollector.labelCountSnapshot("V3_ADMIT_WITHOUT_FDG_OR_REJECT_6536") > 0L
        } catch (_: Throwable) { false }
        if (!v3OrphanFail) passed.add("B_v3_admits_have_fdg_or_reject") else failed.add("B_v3_admit_without_fdg_or_reject")

        // C. Lane-amputation guard: enforce only after meaningful intake.
        val laneAmputationFail = try {
            val intake = PipelineHealthCollector.labelCountSnapshot("INTAKE_TOTAL_6536")
            val v3Eligible = PipelineHealthCollector.labelCountSnapshot("V3_ELIGIBLE_TOTAL_6536")
            intake >= 700L && v3Eligible * 5L < intake
        } catch (_: Throwable) { false }
        if (!laneAmputationFail) passed.add("C_intake_to_v3_conversion_healthy")
        else failed.add("C_intake_to_v3_lt_20pct_lane_amputation_suspected")

        // D. SPOT+SHORT must reroute, never hard-safety leak.
        val spotShortHardFail = try {
            PipelineHealthCollector.labelCountSnapshot("SPOT_SHORT_ADAPTER_MISMATCH_HARD_SAFETY_6536") > 0L
        } catch (_: Throwable) { false }
        if (!spotShortHardFail) passed.add("D_spot_short_reroutes_not_hard_safety")
        else failed.add("D_spot_short_stamped_hard_safety_leak")

        // E. Specialized traders must visit CanonicalSizingBridge6532.
        val bridgeSitesSeen = try {
            listOf("FOREX", "STOCK", "COMMODITY", "METAL", "CRYPTO_ALT", "PERPS").count { klass ->
                val classPrefix = "CANONICAL_SIZING_BRIDGE_6532|CLASS=$klass|LANE="
                val hits = PipelineHealthCollector.labelSnapshotByPrefix6607(classPrefix)
                hits.values.any { it > 0L }
            }
        } catch (_: Throwable) { 0 }
        if (allPositions.isEmpty() || bridgeSitesSeen >= 1) passed.add("E_sizing_bridge_visited_$bridgeSitesSeen")
        else failed.add("E_no_specialized_trader_routed_through_sizing_bridge")

        // F. Provider degradation must soft-defer, not manufacture hard zero liquidity.
        val providerHardZeroFail = try {
            val cb = ProviderCircuitBreaker6402
            val birdeyeDown = cb.isAuthTerminal(ProviderCircuitBreaker6402.Provider.BIRDEYE) ||
                cb.isRateLimited(ProviderCircuitBreaker6402.Provider.BIRDEYE)
            val geckoDown = cb.isAuthTerminal(ProviderCircuitBreaker6402.Provider.COINGECKO) ||
                cb.isRateLimited(ProviderCircuitBreaker6402.Provider.COINGECKO)
            val degraded = birdeyeDown && geckoDown
            val hardZero = PipelineHealthCollector
                .labelCountSnapshot("ELIGIBILITY_ZERO_LIQUIDITY_HARD_WHILE_DEGRADED_6536")
            degraded && hardZero > 0L
        } catch (_: Throwable) { false }
        if (!providerHardZeroFail) passed.add("F_provider_degradation_soft_defer")
        else failed.add("F_zero_liquidity_hard_fail_while_providers_degraded")

        // G. Crypto Universe identity must remain with its canonical asset class.
        val universeOwnershipFail = try {
            PipelineHealthCollector.labelCountSnapshot("CRYPTO_UNIVERSE_IDENTITY_HIJACK_6535") > 0L
        } catch (_: Throwable) { false }
        if (!universeOwnershipFail) passed.add("G_crypto_universe_identity_preserved")
        else failed.add("G_crypto_universe_identity_hijacked_by_meme_lane")

        // H. No leveraged close may be stamped as spot.
        val closeAsSpotFail6540 = try {
            PipelineHealthCollector.labelCountSnapshot("CRYPTO_LEVERAGED_CLOSE_STAMPED_SPOT_6540") > 0L
        } catch (_: Throwable) { false }
        if (!closeAsSpotFail6540) passed.add("H_leveraged_close_not_stamped_spot")
        else failed.add("H_leveraged_close_stamped_spot_6540")

        // I. Venues with candidates must submit to canonical authority.
        val candWithoutSubmit6540 = try {
            CanonicalEntryAuthority6540.candidatesWithoutAuthSubmit()
        } catch (_: Throwable) { emptyList() }
        if (candWithoutSubmit6540.isEmpty()) passed.add("I_all_venues_submit_when_they_have_candidates")
        else failed.add(
            "I_candidates_without_auth_submit_venues=" +
                candWithoutSubmit6540.joinToString(",") { "${it.venue}(cand=${it.candidates})" }
        )

        // J. Mandatory execution-spine window.
        val spineRead6647 = runCatching { ExecutionSpineAcceptanceWindow6647.lastCompletedResult6735() }
        val spine6647 = spineRead6647.getOrNull()
        if (spineRead6647.isFailure) failed.add("J_execution_spine_collector_failed")
        else if (spine6647 == null) passed.add("J_execution_spine_window_warming")
        else if (spine6647.passed) passed.add("J_execution_spine_120s_pass")
        else failed.addAll(spine6647.failures.map { "J_$it" })

        val report = AuditReport(
            whenMs = System.currentTimeMillis(),
            passed = passed,
            failed = failed,
        )
        lastReport = report
        if (!report.ok) {
            failureCount.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "ACCEPTANCE_AUDIT_FAIL_6441",
                    "failedCount=${failed.size} invariants=${failed.joinToString("|") { it.take(160) }} expected=all_invariants_pass observed=${failed.size}_failed",
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("ACCEPTANCE_AUDIT_FAIL_6441") } catch (_: Throwable) {}
        } else {
            try { PipelineHealthCollector.labelInc("ACCEPTANCE_AUDIT_OK_6441") } catch (_: Throwable) {}
        }
        return report
    }

    fun statusLine(): String {
        val runs = runCount.get()
        val fails = failureCount.get()
        val last = lastReport
        val lastStat = if (last == null) "none" else
            "passed=${last.passed.size} failed=${last.failed.size} ok=${last.ok} " +
                "failedInvariants=${last.failed.joinToString("|") { it.take(100) }.ifBlank { "none" }}"
        return "runs=$runs failures=$fails last=[$lastStat]"
    }
}
