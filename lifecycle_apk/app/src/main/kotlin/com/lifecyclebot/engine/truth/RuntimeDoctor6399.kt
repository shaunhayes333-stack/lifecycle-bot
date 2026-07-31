package com.lifecyclebot.engine.truth

/**
 * V5.0.6399 — RUNTIME DOCTOR (authority-aware).
 *
 * The runtime doctor MUST NOT report HEALTHY when authority split-brain
 * conditions exist. Callers pass the current counter snapshot and the
 * doctor returns a diagnosis root cause.
 */
object RuntimeDoctor6399 {

    enum class Diagnosis {
        HEALTHY,
        AUTHORITY_PIPELINE_SPLIT_BRAIN,
        AUTHORITY_TICKET_ISSUED_BEFORE_ALLOW,
        SHADOW_ENTERED_LIVE_PATH,
        FDG_COUNTER_PARITY_FAILURE,
        POLICY_BLOCK_MISFILED_AS_BUY_FAILURE,
        LANE_HARD_DISABLED,
        RECONCILER_NOT_RUNNING,
    }

    data class Verdict(val diagnosis: Diagnosis, val evidence: List<String>)

    /**
     * Diagnose the current runtime. Any authority fault OVERRIDES HEALTHY.
     * Priority order: SPLIT_BRAIN > SHADOW_ENTERED > TICKET_BEFORE_ALLOW >
     * PARITY > POLICY_MISFILE > LANE_DISABLED > RECONCILER > HEALTHY.
     */
    fun diagnose(
        liveMode: Boolean,
        sellReconcilerActive: Boolean,
        laneHardDisabledCount: Long = 0L,
        policyBlockedButBuyFailed: Long = 0L,
    ): Verdict {
        val evidence = mutableListOf<String>()

        // Priority 1: shadow entered live path is the highest-severity fault.
        val shadow = AuthorityInvariants6399.shadowEnteredLivePathFailures.get()
        val denylisted = AuthorityInvariants6399.denylistedEnteredLivePathFailures.get()
        if (shadow > 0L || denylisted > 0L) {
            evidence += "shadow_or_denylist_reached_live: shadow=$shadow denylist=$denylisted"
            // Any candidate that shouldn't be live but reached live = SPLIT_BRAIN.
            return Verdict(Diagnosis.AUTHORITY_PIPELINE_SPLIT_BRAIN, evidence)
        }

        // Priority 2: ticket issued before an ALLOW_LIVE outcome existed.
        val ticketBefore = AuthorityInvariants6399.ticketIssuedBeforeAllowFailures.get()
        if (ticketBefore > 0L) {
            evidence += "tickets_issued_before_allow: $ticketBefore"
            return Verdict(Diagnosis.AUTHORITY_TICKET_ISSUED_BEFORE_ALLOW, evidence)
        }

        // Priority 3: FDG counter parity broken.
        val parity = CounterParityLedger6399.checkParity()
        if (!parity.ok) {
            evidence += "parity_violations: ${parity.violations.joinToString("; ")}"
            return Verdict(Diagnosis.FDG_COUNTER_PARITY_FAILURE, evidence)
        }

        // Priority 4: policy rejections misfiled as buy failures.
        if (policyBlockedButBuyFailed > 0L) {
            evidence += "policy_blocked_but_buy_failed: $policyBlockedButBuyFailed"
            return Verdict(Diagnosis.POLICY_BLOCK_MISFILED_AS_BUY_FAILURE, evidence)
        }

        // Priority 5: any enabled lane hard-disabled.
        if (laneHardDisabledCount > 0L) {
            evidence += "lanes_hard_disabled: $laneHardDisabledCount"
            return Verdict(Diagnosis.LANE_HARD_DISABLED, evidence)
        }

        // Priority 6: live runtime with reconciler not running.
        if (liveMode && !sellReconcilerActive) {
            evidence += "live_mode=true but sell_reconciler=not_running"
            return Verdict(Diagnosis.RECONCILER_NOT_RUNNING, evidence)
        }

        return Verdict(Diagnosis.HEALTHY, evidence)
    }
}
