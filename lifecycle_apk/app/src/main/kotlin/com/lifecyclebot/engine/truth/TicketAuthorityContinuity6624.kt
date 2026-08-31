package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6624 §MEME_SOURCE_LEVEL_EXECUTION_PROVENANCE P1 (operator forensic
 * on V5.0.6622):
 *
 *   "The biggest real entry choke is now intent → executable ticket:
 *      CORE     : 168 pending intents
 *      BLUECHIP :  51 pending intents
 *      EXPRESS  :  33 pending intents
 *    252 pending specialist intents across only three lanes.
 *      NO_EXECUTION_INTENT               = 139
 *      EXPIRED_TICKET_ECONOMIC_REJECT_6614 = 53
 *      AUTHORITY_VERSION_BUMPED_6464     = 567
 *    A specialist produces a legitimate decision. Then something bumps
 *    the authority version. The previously valid intent/ticket
 *    becomes stale before execution. Rather than cheaply validating
 *    that the underlying economics and canonical decision are still
 *    unchanged and resealing it, execution rejects it.
 *
 *    Fix: FDG allow → canonical intent seal → executable ticket
 *    as one canonical transaction. If the authority version changes
 *    after sealing:
 *      1. load the existing immutable intent
 *      2. compare mint/lane/side/economic basis/safety state
 *      3. if materially unchanged, reseal against the current
 *         authority version
 *      4. preserve the original specialist decision
 *      5. execute
 *      6. reject only if economics or safety genuinely changed."
 *
 * This authority is the RECEIVER every ticket-revalidation caller
 * routes through. Instead of rejecting on version drift, callers ask:
 * "did economics or safety change materially?" — and if not, reseal.
 *
 * Slice-P1-telemetry-first: today the receiver validates + emits
 * counters. Broad callsite rewiring (Executor's ticket-restore path
 * currently living behind EXPIRED_TICKET_ECONOMIC_REJECT_6614) will
 * consume this receiver in the follow-up mechanical rollout.
 */
object TicketAuthorityContinuity6624 {

    /**
     * A snapshot of the economic + safety dimensions that CAN
     * legitimately reject a resealed ticket. All other dimensions
     * (authority version, wall-clock, scanner refresh, watchlist
     * refresh) are TRANSIENT and MUST NOT reject.
     */
    data class MaterialBasis(
        val mint: String,
        val lane: String,
        val side: MemeExecutionIntent6621.Side,
        val requestedSol: Double,
        val safetyOk: Boolean,
        val markSol: Double,
    ) {
        fun materiallyEqualTo(other: MaterialBasis, sizeTolerancePct: Double = 5.0, markTolerancePct: Double = 3.0): Boolean {
            if (mint != other.mint) return false
            if (lane != other.lane) return false
            if (side != other.side) return false
            if (safetyOk != other.safetyOk) return false
            if (!safetyOk) return false  // both unsafe → reject
            // Size drift within tolerance is OK — specialist can absorb small size drift
            val sizeDriftPct = if (requestedSol > 0.0) kotlin.math.abs(other.requestedSol - requestedSol) / requestedSol * 100.0 else 0.0
            if (sizeDriftPct > sizeTolerancePct) return false
            // Mark drift within tolerance is OK — mark is transient
            val markDriftPct = if (markSol > 0.0) kotlin.math.abs(other.markSol - markSol) / markSol * 100.0 else 0.0
            if (markDriftPct > markTolerancePct) return false
            return true
        }
    }

    private val originalBasis = ConcurrentHashMap<String, MaterialBasis>()
    private val reseals = AtomicLong(0L)
    private val genuineEconomicRejects = AtomicLong(0L)
    private val safetyRejects = AtomicLong(0L)
    private val versionDriftOnlyReseals = AtomicLong(0L)

    /** Called at intent seal to capture the ORIGINAL material basis. */
    fun recordOriginalBasis6624(attemptId: String, basis: MaterialBasis) {
        originalBasis[attemptId] = basis
        try { PipelineHealthCollector.labelInc("TICKET_ORIGINAL_BASIS_RECORDED_6624") } catch (_: Throwable) {}
    }

    /**
     * Called at ticket revalidation (when the executor observes an
     * authority-version drift). Returns true when the ticket should
     * be RESEALED under the new version (economics + safety unchanged),
     * false when it should be genuinely rejected.
     */
    fun shouldResealOrReject6624(
        attemptId: String,
        currentBasis: MaterialBasis,
    ): Boolean {
        val original = originalBasis[attemptId] ?: run {
            try { PipelineHealthCollector.labelInc("TICKET_RESEAL_NO_ORIGINAL_BASIS_6624") } catch (_: Throwable) {}
            return false
        }
        if (!currentBasis.safetyOk) {
            safetyRejects.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("TICKET_RESEAL_SAFETY_REJECT_6624")
                ForensicLogger.lifecycle(
                    "TICKET_RESEAL_SAFETY_REJECT_6624",
                    "attemptId=$attemptId mint=${currentBasis.mint.take(10)} " +
                        "action=refuse_reseal_safety_no_longer_ok",
                )
            } catch (_: Throwable) {}
            return false
        }
        val materiallyEqual = original.materiallyEqualTo(currentBasis)
        if (materiallyEqual) {
            reseals.incrementAndGet()
            versionDriftOnlyReseals.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("TICKET_RESEALED_VERSION_DRIFT_ONLY_6624")
                PipelineHealthCollector.labelInc("TICKET_RESEALED_VERSION_DRIFT_ONLY_${original.lane}_6624")
                ForensicLogger.lifecycle(
                    "TICKET_RESEALED_VERSION_DRIFT_ONLY_6624",
                    "attemptId=$attemptId mint=${original.mint.take(10)} lane=${original.lane} " +
                        "action=reseal_economics_and_safety_unchanged_preserve_specialist_decision",
                )
            } catch (_: Throwable) {}
            return true
        }
        genuineEconomicRejects.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("TICKET_GENUINE_ECONOMIC_REJECT_6624")
            PipelineHealthCollector.labelInc("TICKET_GENUINE_ECONOMIC_REJECT_${original.lane}_6624")
            ForensicLogger.lifecycle(
                "TICKET_GENUINE_ECONOMIC_REJECT_6624",
                "attemptId=$attemptId mint=${original.mint.take(10)} lane=${original.lane} " +
                    "originalSize=${"%.4f".format(original.requestedSol)} currentSize=${"%.4f".format(currentBasis.requestedSol)} " +
                    "originalMark=${"%.6g".format(original.markSol)} currentMark=${"%.6g".format(currentBasis.markSol)} " +
                    "action=genuine_reject_economics_moved_beyond_tolerance",
            )
        } catch (_: Throwable) {}
        return false
    }

    fun statusLine(): String =
        "reseals=${reseals.get()} versionDriftReseals=${versionDriftOnlyReseals.get()} " +
            "genuineEconomicRejects=${genuineEconomicRejects.get()} " +
            "safetyRejects=${safetyRejects.get()} liveBases=${originalBasis.size}"

    internal fun resetForTest() {
        originalBasis.clear()
        reseals.set(0L); genuineEconomicRejects.set(0L)
        safetyRejects.set(0L); versionDriftOnlyReseals.set(0L)
    }
}
