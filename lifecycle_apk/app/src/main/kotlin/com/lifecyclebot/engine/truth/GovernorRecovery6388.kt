package com.lifecyclebot.engine.truth

import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6388 — GOVERNOR RECOVERY STATE MACHINE (directive 6388 Sections 1-13, 24).
 *
 * Replaces the binary HOLD/open with a staged, evidence-driven, dynamic
 * promotion+demotion machine. Persisted. Restart-safe. No operator interaction.
 */
object GovernorRecovery6388 {
    enum class State { BLOCKED_INFRASTRUCTURE, EXIT_ONLY, HOLD_PROBATION, SOFT_TIGHT, BASELINE, EXPANSION }

    const val EVIDENCE_EPOCH: Int = 6388

    data class InfrastructureSignals(
        val runtimeActive: Boolean, val walletProviderAvailable: Boolean,
        val ownerFilteredBalanceAvailable: Boolean, val fillLotLedgerAvailable: Boolean,
        val canonicalRegistryAvailable: Boolean, val sellReconcilerStarted: Boolean,
        val sellReconcilerHealthy: Boolean, val walletReconciliationConclusive: Boolean,
        val noCanonicalDiscrepancy: Boolean, val noUnresolvedFill: Boolean,
    ) {
        fun healthy(): Boolean = runtimeActive && walletProviderAvailable &&
            ownerFilteredBalanceAvailable && fillLotLedgerAvailable &&
            canonicalRegistryAvailable && sellReconcilerStarted && sellReconcilerHealthy &&
            walletReconciliationConclusive && noCanonicalDiscrepancy && noUnresolvedFill
        fun failedReason(): String = when {
            !runtimeActive -> "RUNTIME_INACTIVE"
            !walletProviderAvailable -> "WALLET_PROVIDER_UNAVAILABLE"
            !ownerFilteredBalanceAvailable -> "OWNER_FILTERED_BALANCE_UNAVAILABLE"
            !fillLotLedgerAvailable -> "FILL_LOT_LEDGER_UNAVAILABLE"
            !canonicalRegistryAvailable -> "CANONICAL_REGISTRY_UNAVAILABLE"
            !sellReconcilerStarted -> "SELL_RECONCILER_NOT_STARTED"
            !sellReconcilerHealthy -> "SELL_RECONCILER_STALE"
            !walletReconciliationConclusive -> "WALLET_RECONCILIATION_INCONCLUSIVE"
            !noCanonicalDiscrepancy -> "CANONICAL_DISCREPANCY"
            !noUnresolvedFill -> "UNRESOLVED_FILL"
            else -> "OK"
        }
    }

    data class RollingEvidence(
        val postEpochCanonicalN: Int, val rollingLast5Wins: Int, val rollingLast5Losses: Int,
        val rollingLast10Wins: Int, val rollingLast10Losses: Int,
        val rollingLast5ProfitFactor: Double, val rollingLast10ProfitFactor: Double,
        val rollingLast20ProfitFactor: Double, val rollingLast5ExpectancySol: Double,
        val rollingLast10ExpectancySol: Double, val rollingLast20ExpectancySol: Double,
        val consecutiveLosses: Int, val tradesCompletedInState: Int,
        val reconcilerHealthyThroughout: Boolean, val allSignaturesComplete: Boolean,
        val noDecimalSkew: Boolean, val noQtyIntegrityFault: Boolean,
        val noAccountingQuarantineOfConfirmedFill: Boolean,
    )

    private val currentState = AtomicReference(State.BLOCKED_INFRASTRUCTURE)
    private val stateEnteredAtMs = AtomicReference(System.currentTimeMillis())
    @Volatile private var lastPromotionReason: String = ""
    @Volatile private var lastDemotionReason: String = ""

    fun state(): State = currentState.get()
    fun isActive(): Boolean = currentState.get() != State.BASELINE && currentState.get() != State.EXPANSION
    fun lastPromotionReason(): String = lastPromotionReason
    fun lastDemotionReason(): String = lastDemotionReason

    /**
     * Section 4: Automatic HOLD_PROBATION entry when governor is HOLD and
     * infrastructure is healthy. Called after every reconciler tick.
     */
    @Synchronized
    fun onReconcilerTick(governorHold: Boolean, infra: InfrastructureSignals) {
        val s = currentState.get()
        val healthy = infra.healthy()
        // Infrastructure fault → immediate demotion (Section 11).
        if (!healthy) {
            if (s != State.BLOCKED_INFRASTRUCTURE && s != State.EXIT_ONLY) {
                demote(State.EXIT_ONLY, "INFRA_FAULT:${infra.failedReason()}")
            } else if (s == State.EXIT_ONLY && infra.failedReason() != "OK" &&
                       !infra.runtimeActive) {
                demote(State.BLOCKED_INFRASTRUCTURE, "INFRA_FAULT:${infra.failedReason()}")
            }
            return
        }
        // From BLOCKED / EXIT_ONLY → HOLD_PROBATION when governor is HOLD.
        if ((s == State.BLOCKED_INFRASTRUCTURE || s == State.EXIT_ONLY) && governorHold) {
            promote(State.HOLD_PROBATION, "AUTO_HOLD_PROBATION_INFRA_HEALTHY")
            return
        }
        // From BLOCKED / EXIT_ONLY → SOFT_TIGHT when governor is NOT hold.
        if ((s == State.BLOCKED_INFRASTRUCTURE || s == State.EXIT_ONLY) && !governorHold) {
            promote(State.SOFT_TIGHT, "INFRA_HEALTHY_GOVERNOR_ALLOW")
            return
        }
    }

    /** Section 6: Automatic promotion HOLD_PROBATION → SOFT_TIGHT after clean evidence. */
    @Synchronized
    fun evaluatePromotion(e: RollingEvidence) {
        val s = currentState.get()
        when (s) {
            State.HOLD_PROBATION -> {
                val eligible = e.postEpochCanonicalN >= 5 && e.tradesCompletedInState >= 5 &&
                    (e.rollingLast5Wins + e.rollingLast5Losses >= 5) &&
                    e.rollingLast5Wins >= 3 &&
                    e.rollingLast5ProfitFactor >= 1.00 &&
                    e.rollingLast5ExpectancySol >= 0.0 &&
                    e.reconcilerHealthyThroughout && e.allSignaturesComplete &&
                    e.noDecimalSkew && e.noQtyIntegrityFault &&
                    e.noAccountingQuarantineOfConfirmedFill
                if (eligible) promote(State.SOFT_TIGHT, "POST_FIX_EVIDENCE_PROVEN")
            }
            State.SOFT_TIGHT -> {
                val eligible = e.postEpochCanonicalN >= 10 && e.tradesCompletedInState >= 5 &&
                    (e.rollingLast10Wins + e.rollingLast10Losses >= 10) &&
                    (e.rollingLast10Wins.toDouble() / 10.0) >= 0.35 &&
                    e.rollingLast10ProfitFactor >= 1.20 &&
                    e.rollingLast10ExpectancySol > 0.0 &&
                    e.reconcilerHealthyThroughout && e.allSignaturesComplete &&
                    e.noDecimalSkew && e.noQtyIntegrityFault &&
                    e.noAccountingQuarantineOfConfirmedFill
                if (eligible) promote(State.BASELINE, "CLEAN_LIVE_PERFORMANCE_CONFIRMED")
            }
            State.BASELINE -> {
                val eligible = e.postEpochCanonicalN >= 20 &&
                    e.rollingLast20ProfitFactor >= 1.35 &&
                    e.rollingLast20ExpectancySol > 0.0 &&
                    e.reconcilerHealthyThroughout && e.allSignaturesComplete
                if (eligible) promote(State.EXPANSION, "EXPANSION_EVIDENCE_PROVEN")
            }
            else -> {}
        }
    }

    /** Section 11: Performance demotion using meaningful samples. */
    @Synchronized
    fun evaluateDemotion(e: RollingEvidence) {
        val s = currentState.get()
        val decisive5 = (e.rollingLast5Wins + e.rollingLast5Losses) >= 5
        val decisive10 = (e.rollingLast10Wins + e.rollingLast10Losses) >= 10
        when (s) {
            State.EXPANSION -> {
                if (e.rollingLast20ProfitFactor < 1.20 || e.rollingLast20ExpectancySol <= 0.0)
                    demote(State.BASELINE, "EXPANSION_EVIDENCE_LOST")
            }
            State.BASELINE -> {
                if (decisive10 && (e.rollingLast10ProfitFactor < 0.80 || e.rollingLast10ExpectancySol < 0.0))
                    demote(State.SOFT_TIGHT, "BASELINE_PF_OR_EXPECTANCY_BELOW_FLOOR")
            }
            State.SOFT_TIGHT -> {
                if (decisive5 &&
                    (e.rollingLast5ProfitFactor < 0.60 || e.rollingLast5ExpectancySol < 0.0 || e.consecutiveLosses >= 3))
                    demote(State.HOLD_PROBATION, "SOFT_TIGHT_PF_OR_STREAK_BREACH")
            }
            else -> {}
        }
    }

    /**
     * Section 5 + 7 + 9: What entry authority the executor should apply now.
     */
    data class EntryAuthority(
        val allowBuys: Boolean, val probationSized: Boolean,
        val softTightSized: Boolean, val fullSized: Boolean, val reason: String,
    )
    fun entryAuthority(): EntryAuthority = when (currentState.get()) {
        State.BLOCKED_INFRASTRUCTURE -> EntryAuthority(false, false, false, false, "BLOCKED_INFRASTRUCTURE")
        State.EXIT_ONLY -> EntryAuthority(false, false, false, false, "EXIT_ONLY")
        State.HOLD_PROBATION -> EntryAuthority(true, true, false, false, "HOLD_PROBATION")
        State.SOFT_TIGHT -> EntryAuthority(true, false, true, false, "SOFT_TIGHT")
        State.BASELINE -> EntryAuthority(true, false, false, true, "BASELINE")
        State.EXPANSION -> EntryAuthority(true, false, false, true, "EXPANSION")
    }

    /** Section 5: probation sizing 0.005–0.010 SOL clamp on 10% of configured normal. */
    fun probationSize(configuredNormalSizeSol: Double): Double {
        val target = configuredNormalSizeSol * 0.10
        val clamped = target.coerceIn(0.005, 0.010)
        return minOf(configuredNormalSizeSol, clamped)
    }

    private fun promote(next: State, reason: String) {
        val prior = currentState.getAndSet(next)
        stateEnteredAtMs.set(System.currentTimeMillis())
        lastPromotionReason = "from=${prior.name} to=${next.name} reason=$reason"
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("GOVERNOR_AUTO_PROMOTED_6388_${prior.name}_TO_${next.name}")
            com.lifecyclebot.engine.ForensicLogger.lifecycle("GOVERNOR_AUTO_PROMOTED_6388",
                "from=${prior.name} to=${next.name} reason=$reason evidenceEpoch=$EVIDENCE_EPOCH")
        } catch (_: Throwable) {}
    }
    private fun demote(next: State, reason: String) {
        val prior = currentState.getAndSet(next)
        stateEnteredAtMs.set(System.currentTimeMillis())
        lastDemotionReason = "from=${prior.name} to=${next.name} reason=$reason"
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("GOVERNOR_AUTO_DEMOTED_6388_${prior.name}_TO_${next.name}")
            com.lifecyclebot.engine.ForensicLogger.lifecycle("GOVERNOR_AUTO_DEMOTED_6388",
                "from=${prior.name} to=${next.name} reason=$reason")
        } catch (_: Throwable) {}
    }
    internal fun resetForTest(state: State = State.BLOCKED_INFRASTRUCTURE) {
        currentState.set(state); stateEnteredAtMs.set(System.currentTimeMillis())
        lastPromotionReason = ""; lastDemotionReason = ""
    }
}

/**
 * V5.0.6388 — POLICY-BLOCK DEDUP (Section 13).
 * Governor HOLD rejection is a policy decision, NOT a BUY failure.
 * Deduplicate by (runtimeGen + mint + fdgDecisionId + governorState + recoveryState)
 * with 60s TTL. Ordinary policy blocks emit LIVE_ENTRY_POLICY_BLOCKED and do
 * NOT increment BUY_FAIL / route fail / provider fail / signing fail / etc.
 */
object PolicyBlockDedup6388 {
    private data class Key(val gen: Long, val mint: String, val fdgId: String, val gov: String, val recov: String)
    private val emissions = java.util.concurrent.ConcurrentHashMap<Key, Long>()
    const val DEDUP_TTL_MS: Long = 60_000L
    fun shouldEmit(runtimeGen: Long, mint: String, fdgDecisionId: String, governorState: String, recoveryState: String): Boolean {
        val k = Key(runtimeGen, mint, fdgDecisionId, governorState, recoveryState)
        val now = System.currentTimeMillis()
        val prior = emissions[k]
        if (prior != null && (now - prior) < DEDUP_TTL_MS) return false
        emissions[k] = now
        return true
    }
    fun recordPolicyBlock(mint: String, governorState: String, recoveryState: String, reason: String) {
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LIVE_ENTRY_POLICY_BLOCKED_6388")
            com.lifecyclebot.engine.ForensicLogger.lifecycle("LIVE_ENTRY_POLICY_BLOCKED_6388",
                "mint=${mint.take(10)} gov=$governorState recov=$recoveryState reason=$reason executionFailure=false attemptedRoute=false attemptedQuote=false attemptedBuild=false attemptedSignature=false attemptedBroadcast=false")
        } catch (_: Throwable) {}
    }
    internal fun clearAllForTest() { emissions.clear() }
}
