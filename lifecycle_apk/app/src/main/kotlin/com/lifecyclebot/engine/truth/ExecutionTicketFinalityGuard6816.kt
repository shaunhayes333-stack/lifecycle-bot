package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6816 §TICKET_FINALITY — operator directive V5.0.6813 P1 item #4:
 *   "Move Execution-ticket creation to final causal boundary. Ensure
 *    ticket is sealed only *after* exact candidateVersion, lane, FDG,
 *    mark, size, and epoch are resolved. Do not create an intent while
 *    the candidate is waiting for FDG/authority."
 *
 * DESIGN — additive, defensive, telemetry-first.
 *   • Callers about to create an execution ticket call `guardCreate`
 *     with the six mandatory fields.
 *   • The guard verifies each field is present + non-sentinel. If
 *     ANY field is missing, an advisory counter fires
 *     (`EXEC_TICKET_FINALITY_INCOMPLETE_6816`) and, when
 *     `ENFORCE_HARD_BLOCK` is true, the caller returns without
 *     sealing the ticket.
 *   • In this ship `ENFORCE_HARD_BLOCK` is FALSE by default so we
 *     observe how often the guard would fire before we turn on the
 *     hard block in a future ship. This matches the crash-safe
 *     surgical pattern established after V5.0.6811.
 *
 * NOTE — this authority does NOT modify any existing state machine.
 * It is a pure pre-condition audit that a caller consults, similar
 * to `CapitalRecoveryAuthority6814.isActive()`.
 */
object ExecutionTicketFinalityGuard6816 {

    private val enforceHardBlock = AtomicBoolean(false)

    private val evaluations = AtomicLong(0L)
    private val incompleteBlocks = AtomicLong(0L)
    private val incompleteAdvisories = AtomicLong(0L)
    private val complete = AtomicLong(0L)

    data class Verdict(
        val ok: Boolean,
        val missing: List<String>,
        val enforced: Boolean,
    )

    /** Enable/disable the hard block. Default: OFF (advisory only). */
    fun setEnforcement(enable: Boolean) { enforceHardBlock.set(enable) }
    fun isEnforced(): Boolean = enforceHardBlock.get()

    /**
     * Consult the guard. Callers pass the six mandatory finality
     * inputs. Missing fields are named in `Verdict.missing`; when
     * hard-block is enabled the verdict is `ok=false` on any missing.
     *
     * @param candidateVersion monotonic candidate rev; blank = missing
     * @param lane resolved lane name; blank = missing
     * @param fdgSealed FDG decision has been finalised (approved OR blocked)
     * @param markResolved authoritative mark price is fresh + non-sentinel
     * @param sizeResolved OrderSizeResolver has returned executable=true
     * @param epochResolved candidate epoch is bound to the decision
     */
    fun guardCreate(
        candidateVersion: String,
        lane: String,
        fdgSealed: Boolean,
        markResolved: Boolean,
        sizeResolved: Boolean,
        epochResolved: Boolean,
        callSite: String = "",
    ): Verdict {
        evaluations.incrementAndGet()
        val missing = mutableListOf<String>()
        if (candidateVersion.isBlank()) missing += "candidateVersion"
        if (lane.isBlank()) missing += "lane"
        if (!fdgSealed) missing += "fdg"
        if (!markResolved) missing += "mark"
        if (!sizeResolved) missing += "size"
        if (!epochResolved) missing += "epoch"
        return if (missing.isEmpty()) {
            complete.incrementAndGet()
            try { PipelineHealthCollector.labelInc("EXEC_TICKET_FINALITY_OK_6816") } catch (_: Throwable) {}
            Verdict(ok = true, missing = emptyList(), enforced = false)
        } else {
            val enforce = enforceHardBlock.get()
            if (enforce) incompleteBlocks.incrementAndGet()
            else incompleteAdvisories.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc(
                    if (enforce) "EXEC_TICKET_FINALITY_BLOCKED_6816"
                    else "EXEC_TICKET_FINALITY_INCOMPLETE_6816"
                )
                for (m in missing) {
                    PipelineHealthCollector.labelInc(
                        "EXEC_TICKET_FINALITY_MISSING_${m.uppercase()}_6816"
                    )
                }
                ForensicLogger.lifecycle(
                    if (enforce) "EXEC_TICKET_FINALITY_BLOCKED_6816"
                    else "EXEC_TICKET_FINALITY_INCOMPLETE_6816",
                    "candidateVersion=${candidateVersion.take(24)} lane=$lane " +
                        "fdgSealed=$fdgSealed markResolved=$markResolved " +
                        "sizeResolved=$sizeResolved epochResolved=$epochResolved " +
                        "missing=${missing.joinToString(",")} " +
                        "callSite=${callSite.take(48)} enforced=$enforce",
                )
            } catch (_: Throwable) {}
            Verdict(ok = !enforce, missing = missing, enforced = enforce)
        }
    }

    fun statusLine(): String =
        "evals=${evaluations.get()} complete=${complete.get()} " +
            "advisories=${incompleteAdvisories.get()} blocks=${incompleteBlocks.get()} " +
            "enforced=${enforceHardBlock.get()}"

    internal fun clearForTest() {
        enforceHardBlock.set(false)
        evaluations.set(0L); complete.set(0L)
        incompleteBlocks.set(0L); incompleteAdvisories.set(0L)
    }
}
