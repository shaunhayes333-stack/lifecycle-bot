package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6817 §REWARD_PURITY_ADMISSION — operator directive Feb 2026:
 *   "rewardPurity admission requires: canonical owner resolved,
 *    valid entry basis, valid terminal mark, terminal economics
 *    reconciled, no stale/sentinel synthetic exit. Diagnostic close
 *    may still exist in journal, but trainable=false."
 *
 * DESIGN — pure predicate. Consumers call `isTrainable(...)` before
 * admitting a close event into any learner. This authority does NOT
 * mutate journals or ledgers.
 *
 * Composite of:
 *   1. UnresolvedOwnerLearningQuarantine6817.isQuarantined(positionId)
 *   2. StaleMarkExitGate6817.isTrainable(positionId)
 *   3. Caller-supplied validity flags (entryBasisValid, terminalMarkValid,
 *      economicsReconciled) — the caller knows these best.
 *
 * The return `Admission` names which specific criterion failed so the
 * operator can see WHY a close was excluded via
 * `REWARD_PURITY_REJECTED_6817_<reason>` counters. Diagnostic closes
 * are not deleted; they simply do not train.
 */
object RewardPurityAdmission6817 {

    data class Admission(
        val trainable: Boolean,
        val reasons: List<String>,
    )

    private val evaluations = AtomicLong(0L)
    private val admissions = AtomicLong(0L)
    private val rejections = AtomicLong(0L)

    /**
     * @param positionId canonical position id
     * @param entryBasisValid the entry cost basis is present + finite
     * @param terminalMarkValid the terminal mark is VALIDATED_MARK
     * @param economicsReconciled paper commit order guard says the
     *   commit batch was complete + acceptance audit passed
     */
    fun isTrainable(
        positionId: String,
        entryBasisValid: Boolean,
        terminalMarkValid: Boolean,
        economicsReconciled: Boolean,
    ): Admission {
        evaluations.incrementAndGet()
        val reasons = mutableListOf<String>()
        val ownerQuarantined = try {
            UnresolvedOwnerLearningQuarantine6817.isQuarantined(positionId)
        } catch (_: Throwable) { false }
        if (ownerQuarantined) reasons += "unresolved_owner"
        val staleGateTrainable = try {
            StaleMarkExitGate6817.isTrainable(positionId)
        } catch (_: Throwable) { true }
        if (!staleGateTrainable) reasons += "stale_mark_close"
        if (!entryBasisValid) reasons += "entry_basis_invalid"
        if (!terminalMarkValid) reasons += "terminal_mark_invalid"
        if (!economicsReconciled) reasons += "economics_not_reconciled"
        val trainable = reasons.isEmpty()
        try {
            if (trainable) {
                admissions.incrementAndGet()
                PipelineHealthCollector.labelInc("REWARD_PURITY_ADMITTED_6817")
            } else {
                rejections.incrementAndGet()
                PipelineHealthCollector.labelInc("REWARD_PURITY_REJECTED_6817")
                for (r in reasons) {
                    PipelineHealthCollector.labelInc(
                        "REWARD_PURITY_REJECTED_6817_${r.uppercase()}"
                    )
                }
                ForensicLogger.lifecycle(
                    "REWARD_PURITY_REJECTED_6817",
                    "positionId=${positionId.take(24)} reasons=${reasons.joinToString(",")} " +
                        "action=diagnostic_visible_learners_excluded",
                )
            }
        } catch (_: Throwable) {}
        return Admission(trainable = trainable, reasons = reasons)
    }

    fun statusLine(): String =
        "evals=${evaluations.get()} admitted=${admissions.get()} rejected=${rejections.get()}"

    internal fun clearForTest() {
        evaluations.set(0L); admissions.set(0L); rejections.set(0L)
    }
}
