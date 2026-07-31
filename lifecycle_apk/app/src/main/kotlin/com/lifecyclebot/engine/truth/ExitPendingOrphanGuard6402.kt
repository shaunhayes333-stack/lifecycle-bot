package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6402 §G — EXIT-PENDING ORPHAN GUARD.
 *
 * OPERATOR DIRECTIVE
 * ───────────────────
 * `exitPending` must not be a free-standing Boolean with no owner.
 * It must reference a sellIntentId and timestamp. If
 * `exitPending=true` but no valid sell intent exists, emit
 * `EXIT_PENDING_ORPHANED`, clear the orphaned flag transactionally,
 * release the associated orchestration lease, and retain the
 * position as OPEN or PARTIAL.
 *
 * If a sell intent exists but is stale, query executor finality;
 * resolve to CLOSED only with finality; otherwise return to
 * retryable OPEN/PARTIAL state with preserved intent history.
 *
 * DESIGN
 * ──────
 * Pure validation surface. Callers hand in the observed
 * `exitPending`, `sellIntentId`, `intentTimestampMs`, and (via
 * lookup callbacks) the executor's view of whether the intent is
 * live. Returns a [Verdict] the caller must act on.
 */
object ExitPendingOrphanGuard6402 {

    /**
     * Directive: "if a sell intent exists but is stale, query
     * executor finality." We consider an intent stale if it has been
     * pending without executor knowledge for [STALE_INTENT_MS].
     */
    const val STALE_INTENT_MS: Long = 15_000L

    sealed class Verdict {
        /** exitPending correctly ties to a live sell intent. */
        data object HealthyPending : Verdict()
        /** exitPending is false; nothing to do. */
        data object NotPending : Verdict()
        /**
         * Orphaned — exitPending=true but no owned intent. Caller
         * must clear the flag transactionally and release the lease.
         */
        data class Orphaned(val reason: String) : Verdict()
        /**
         * Intent exists but is stale — caller must query executor
         * finality; without finality, return to retryable state.
         */
        data class StaleIntent(val ageMs: Long, val sellIntentId: String) : Verdict()
    }

    /**
     * Classify a single exit-pending state.
     *
     * @param exitPending observed exitPending flag on the position
     * @param sellIntentId the id the position claims to own, if any
     * @param intentTimestampMs when the intent was minted, if known
     * @param intentAliveInExecutor caller-supplied — is the sell
     *   intent still known to the executor queue / registry?
     */
    fun classify(
        exitPending: Boolean,
        sellIntentId: String?,
        intentTimestampMs: Long?,
        intentAliveInExecutor: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): Verdict {
        if (!exitPending) return Verdict.NotPending
        // exitPending == true → must have an owned intent.
        if (sellIntentId.isNullOrBlank()) {
            return Verdict.Orphaned("exitPending_true_without_sellIntentId")
        }
        if (!intentAliveInExecutor) {
            return Verdict.Orphaned("intent_id=${sellIntentId.take(16)}_not_known_to_executor")
        }
        val ts = intentTimestampMs
        if (ts != null) {
            val age = nowMs - ts
            if (age >= STALE_INTENT_MS) {
                return Verdict.StaleIntent(ageMs = age, sellIntentId = sellIntentId)
            }
        }
        return Verdict.HealthyPending
    }

    /**
     * Convenience — record + emit the appropriate telemetry for the
     * verdict. Returns the verdict for chaining.
     */
    fun recordVerdict(
        mint: String,
        verdict: Verdict,
    ): Verdict {
        try {
            when (verdict) {
                is Verdict.Orphaned -> {
                    PipelineHealthCollector.labelInc("EXIT_PENDING_ORPHANED_6402")
                    ForensicLogger.lifecycle(
                        "EXIT_PENDING_ORPHANED_6402",
                        "mint=${mint.take(10)} reason=${verdict.reason}",
                    )
                }
                is Verdict.StaleIntent -> {
                    PipelineHealthCollector.labelInc("EXIT_PENDING_STALE_INTENT_6402")
                    ForensicLogger.lifecycle(
                        "EXIT_PENDING_STALE_INTENT_6402",
                        "mint=${mint.take(10)} intentId=${verdict.sellIntentId.take(16)} ageMs=${verdict.ageMs}",
                    )
                }
                Verdict.HealthyPending, Verdict.NotPending -> {
                    /* no-op */
                }
            }
        } catch (_: Throwable) {}
        return verdict
    }
}
