package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6817 §STALE_MARK_EXIT — operator directive Feb 2026:
 *   "Stale/missing quote alone MUST NOT synthesize an economic losing close.
 *    On stale price: request canonical mark refresh -> try trusted fallback ->
 *    retain previous validated mark -> HOLD/DEFER. Terminal scratch close is
 *    permitted only when (a) multiple refresh attempts expire AND (b) an
 *    independently validated mark proves an executable exit, OR (c) token
 *    is positively confirmed dead/unroutable. Never calculate terminal PnL
 *    from a stale/sentinel/fallback-shaped quote."
 *
 * DESIGN — additive, decision-surface consultation.
 *   • `evaluate(positionId, priceAuthority, refreshAttempts, deadConfirmed)`
 *     returns a Verdict:
 *       - HOLD_DEFER  → caller must NOT close; refresh attempts still valid
 *       - VALIDATED   → mark is VALIDATED_MARK; caller may close and train
 *       - SCRATCH_DIAGNOSTIC → refresh budget exhausted, no validated mark,
 *         token not dead. Caller MAY close for diagnostic purposes but the
 *         close is TRAINABLE=FALSE. RewardPurityAdmission6817 will reject.
 *       - DEAD_CLOSE  → token confirmed dead/unroutable; close permitted,
 *         but still non-trainable (stale-mark loss training forbidden).
 *   • `isTrainable(positionId)` — quick read for reward/learner gates.
 *
 * Pure evaluator: no ledger mutation. Consumers wire the verdict into
 * their existing close paths (advisory today; enforcement lands in a
 * follow-up ship once GH CI proves the authority is stable under load).
 */
object StaleMarkExitGate6817 {

    enum class PriceAuthority { VALIDATED_MARK, STALE, SENTINEL, FALLBACK_SHAPED, MISSING }
    enum class Verdict { HOLD_DEFER, VALIDATED, SCRATCH_DIAGNOSTIC, DEAD_CLOSE }

    private const val REFRESH_BUDGET = 3

    private val trainableClosures = ConcurrentHashMap<String, Boolean>()
    private val evaluations = AtomicLong(0L)
    private val holds = AtomicLong(0L)
    private val validated = AtomicLong(0L)
    private val scratchDiagnostics = AtomicLong(0L)
    private val deadCloses = AtomicLong(0L)

    fun evaluate(
        positionId: String,
        priceAuthority: PriceAuthority,
        refreshAttempts: Int,
        deadConfirmed: Boolean,
    ): Verdict {
        evaluations.incrementAndGet()
        val verdict = when {
            priceAuthority == PriceAuthority.VALIDATED_MARK -> Verdict.VALIDATED
            deadConfirmed -> Verdict.DEAD_CLOSE
            refreshAttempts < REFRESH_BUDGET -> Verdict.HOLD_DEFER
            else -> Verdict.SCRATCH_DIAGNOSTIC
        }
        val trainable = verdict == Verdict.VALIDATED
        if (positionId.isNotBlank()) trainableClosures[positionId] = trainable
        try {
            when (verdict) {
                Verdict.VALIDATED -> {
                    validated.incrementAndGet()
                    PipelineHealthCollector.labelInc("STALE_MARK_EXIT_VALIDATED_6817")
                }
                Verdict.HOLD_DEFER -> {
                    holds.incrementAndGet()
                    PipelineHealthCollector.labelInc("STALE_MARK_EXIT_HOLD_DEFER_6817")
                    PipelineHealthCollector.labelInc(
                        "STALE_MARK_EXIT_HOLD_DEFER_6817_${priceAuthority.name}"
                    )
                }
                Verdict.SCRATCH_DIAGNOSTIC -> {
                    scratchDiagnostics.incrementAndGet()
                    PipelineHealthCollector.labelInc("STALE_MARK_EXIT_SCRATCH_DIAGNOSTIC_6817")
                    ForensicLogger.lifecycle(
                        "STALE_MARK_EXIT_SCRATCH_DIAGNOSTIC_6817",
                        "positionId=${positionId.take(24)} priceAuthority=$priceAuthority " +
                            "refreshAttempts=$refreshAttempts deadConfirmed=$deadConfirmed " +
                            "action=close_permitted_but_not_trainable",
                    )
                }
                Verdict.DEAD_CLOSE -> {
                    deadCloses.incrementAndGet()
                    PipelineHealthCollector.labelInc("STALE_MARK_EXIT_DEAD_CLOSE_6817")
                }
            }
        } catch (_: Throwable) {}
        return verdict
    }

    /** Consumer read — is a closed position trainable? Defaults to true when
     *  the gate never saw the position (i.e. non-stale-mark exit path). */
    fun isTrainable(positionId: String): Boolean {
        if (positionId.isBlank()) return true
        return trainableClosures[positionId] ?: true
    }

    fun statusLine(): String =
        "evals=${evaluations.get()} holds=${holds.get()} validated=${validated.get()} " +
            "scratchDiagnostics=${scratchDiagnostics.get()} deadCloses=${deadCloses.get()}"

    internal fun clearForTest() {
        trainableClosures.clear()
        evaluations.set(0L); holds.set(0L); validated.set(0L)
        scratchDiagnostics.set(0L); deadCloses.set(0L)
    }
}
