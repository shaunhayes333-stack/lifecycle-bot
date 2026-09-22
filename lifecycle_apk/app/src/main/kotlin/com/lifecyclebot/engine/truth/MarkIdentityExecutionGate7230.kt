package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7230 §MARK_IDENTITY_EXECUTION_GATE — operator diagnosis (7227):
 *
 *   METRICS_IDENTITY_BROKEN_7069:                27,154
 *   METRICS_IDENTITY_BROKEN_NO_SUBSTITUTION_7087: 27,145
 *   TOKEN_METRIC_STAGE_LANE_SOFT_MISMATCH:       45,590
 *   MARK_SINGLE_SOURCE_UNCORROBORATED:           15,242
 *   MARK_CONTESTED_NO_AGREEMENT:                 3,895
 *
 *   NEARCHAN event: reportedPrice=0.0001428 impliedPrice=0.0000947064
 *                   ratio=1.50782 worstBreak=9.380x
 *                   action=unverifiable_price_passes_through_untouched
 *
 * PURPOSE — a mark stamped as identity-broken / unverifiable /
 * uncorroborated MUST NOT flow into PnL, SL, TP, trailing stops,
 * catastrophic exits, or the learning outcome stream (operator §10).
 *
 * SCOPE — additive.  This gate is a consult point every consumer of
 * a mark for economic purposes calls before using the value.  The
 * value itself is never modified; only its execution-eligibility is
 * decided.
 *
 * KEY DISTINCTION — telemetry vs execution.  The existing
 * TokenMetricsAuthority7069 policy (action=unverifiable_price_passes_
 * through_untouched) remains for TELEMETRY.  This gate is what stops
 * the untrustworthy value from becoming executable.
 *
 * KEYING — by (mint, poolVenueKey).  Marks bound only by symbol are
 * forbidden (§13).  The poolVenueKey may be a pair address, provider
 * tag, or venue string; when unknown the caller passes "" and the
 * mark is flagged VENUE_UNKNOWN.
 */
object MarkIdentityExecutionGate7230 {

    enum class Verdict {
        USABLE,              // clean identity + corroborated: use for execution
        SUPPRESSED,          // identity broken / uncorroborated / venue-only
    }

    data class MarkDecision(
        val verdict: Verdict,
        val reason7230: String,
        val venueKey: String,
    )

    private val identityBroken = AtomicLong(0L)
    private val uncorroborated = AtomicLong(0L)
    private val venueMissing = AtomicLong(0L)
    private val executionSuppressed = AtomicLong(0L)
    private val executionAllowed = AtomicLong(0L)
    private val repeatedSuppressionCoalesced7250 = AtomicLong(0L)

    // V5.0.7243 — suppression is runtime authority, not telemetry only.
    private data class ActiveSuppression7243(
        val reason: String,
        val venueKey: String,
        val atMs: Long,
    )
    private val suppressedByMint7243 = ConcurrentHashMap<String, ActiveSuppression7243>()
    private val lastSuppressionEvent7250 = ConcurrentHashMap<String, ActiveSuppression7243>()
    private const val REPEAT_EVENT_WINDOW_MS_7250 = 5_000L

    /** Returns true only when authority changed; repeated polling stays safe
     * but does not flood the forensic ring with the same transition. */
    private fun rememberSuppressed7243(mint: String, reason: String, venueKey: String): Boolean {
        if (mint.isBlank()) return true
        val now = System.currentTimeMillis()
        val current = suppressedByMint7243[mint]
        suppressedByMint7243[mint] = ActiveSuppression7243(reason, venueKey, now)
        if (current != null && current.reason == reason && current.venueKey == venueKey) {
            repeatedSuppressionCoalesced7250.incrementAndGet()
            try { PipelineHealthCollector.labelInc("MARK_IDENTITY_REPEAT_COALESCED_7250") } catch (_: Throwable) {}
            return false
        }
        // A repair consumer may temporarily clear active suppression and the
        // next raw observation can correctly restore it. That safety-state
        // oscillation must not emit dozens of identical forensic rows/second.
        val priorEvent = lastSuppressionEvent7250[mint]
        if (priorEvent != null && priorEvent.reason == reason && priorEvent.venueKey == venueKey &&
            now - priorEvent.atMs < REPEAT_EVENT_WINDOW_MS_7250) {
            repeatedSuppressionCoalesced7250.incrementAndGet()
            try { PipelineHealthCollector.labelInc("MARK_IDENTITY_REPEAT_COALESCED_7250") } catch (_: Throwable) {}
            return false
        }
        lastSuppressionEvent7250[mint] = ActiveSuppression7243(reason, venueKey, now)
        return true
    }

    fun suppressMint7243(mint: String, reason: String) {
        if (mint.isBlank()) return
        rememberSuppressed7243(mint, reason, "")
        try { PipelineHealthCollector.labelInc("MARK_EXECUTION_STATE_SUPPRESSED_7243") } catch (_: Throwable) {}
    }

    fun isExecutionSuppressed7243(mint: String): Boolean =
        mint.isNotBlank() && suppressedByMint7243.containsKey(mint)

    fun suppressionReason7243(mint: String): String =
        suppressedByMint7243[mint]?.reason.orEmpty()

    fun markRepairedUsable7243(mint: String): Boolean {
        if (mint.isBlank()) return false
        val removed = suppressedByMint7243.remove(mint) ?: return false
        try { PipelineHealthCollector.labelInc("MARK_EXECUTION_STATE_REPAIRED_7243") } catch (_: Throwable) {}
        return removed.reason.isNotBlank()
    }

    /**
     * Evaluate a mark for economic-execution eligibility.
     *
     *  * mint                 — asset identifier
     *  * poolOrVenueKey       — pair-address / provider / venue.  Empty
     *                           => mark bound only by symbol (§13
     *                           forbidden).
     *  * markIdentityBroken   — TokenMetricsAuthority7069 has stamped
     *                           this mark as identity-broken.
     *  * corroboratedByIndependentSource — a second, independent
     *                           executable price source agrees
     *                           within tolerance.
     *  * exitReasonOrContext  — free-text for the forensic line.
     */
    fun evaluate(
        mint: String,
        poolOrVenueKey: String,
        markIdentityBroken: Boolean,
        corroboratedByIndependentSource: Boolean,
        exitReasonOrContext: String,
    ): MarkDecision {
        // Venue-key requirement (§13): a mark bound only by symbol is
        // forbidden.  If the caller can't produce a pool/venue, we
        // suppress the mark for execution.
        if (poolOrVenueKey.isBlank()) {
            val changed = rememberSuppressed7243(mint, "VENUE_MISSING", "")
            if (changed) { venueMissing.incrementAndGet(); executionSuppressed.incrementAndGet() }
            try {
                if (changed) {
                    PipelineHealthCollector.labelInc("MARK_IDENTITY_SUPPRESSED_VENUE_MISSING_7230")
                    ForensicLogger.lifecycle(
                        "MARK_IDENTITY_SUPPRESSED_VENUE_MISSING_7230",
                        "mint=${mint.take(10)} context=$exitReasonOrContext action=refuse_execution_use_advisory_only",
                    )
                }
            } catch (_: Throwable) {}
            return MarkDecision(Verdict.SUPPRESSED, "VENUE_MISSING", "")
        }

        if (markIdentityBroken) {
            val changed = rememberSuppressed7243(mint, "IDENTITY_BROKEN", poolOrVenueKey)
            if (changed) { identityBroken.incrementAndGet(); executionSuppressed.incrementAndGet() }
            try {
                if (changed) {
                    PipelineHealthCollector.labelInc("MARK_IDENTITY_SUPPRESSED_BROKEN_7230")
                    ForensicLogger.lifecycle(
                        "MARK_IDENTITY_SUPPRESSED_BROKEN_7230",
                        "mint=${mint.take(10)} venue=$poolOrVenueKey context=$exitReasonOrContext action=refuse_execution_wait_for_corroboration",
                    )
                }
            } catch (_: Throwable) {}
            return MarkDecision(Verdict.SUPPRESSED, "IDENTITY_BROKEN", poolOrVenueKey)
        }

        if (!corroboratedByIndependentSource) {
            val changed = rememberSuppressed7243(mint, "UNCORROBORATED", poolOrVenueKey)
            if (changed) { uncorroborated.incrementAndGet(); executionSuppressed.incrementAndGet() }
            try {
                if (changed) PipelineHealthCollector.labelInc("MARK_IDENTITY_SUPPRESSED_UNCORROBORATED_7230")
            } catch (_: Throwable) {}
            return MarkDecision(Verdict.SUPPRESSED, "UNCORROBORATED", poolOrVenueKey)
        }

        executionAllowed.incrementAndGet()
        if (mint.isNotBlank()) suppressedByMint7243.remove(mint)
        try { PipelineHealthCollector.labelInc("MARK_IDENTITY_USABLE_7230") } catch (_: Throwable) {}
        return MarkDecision(Verdict.USABLE, "clean_corroborated", poolOrVenueKey)
    }

    data class Summary(
        val identityBroken: Long,
        val uncorroborated: Long,
        val venueMissing: Long,
        val executionSuppressed: Long,
        val executionAllowed: Long,
    )

    fun summary(): Summary = Summary(
        identityBroken = identityBroken.get(),
        uncorroborated = uncorroborated.get(),
        venueMissing = venueMissing.get(),
        executionSuppressed = executionSuppressed.get(),
        executionAllowed = executionAllowed.get(),
    )

    fun statusLine(): String {
        val s = summary()
        return "MarkIdentityExecutionGate7230 broken=${s.identityBroken} " +
            "uncorroborated=${s.uncorroborated} venueMissing=${s.venueMissing} " +
            "suppressed=${s.executionSuppressed} usable=${s.executionAllowed}"
    }

    internal fun clearForTest() {
        identityBroken.set(0L); uncorroborated.set(0L); venueMissing.set(0L)
        executionSuppressed.set(0L); executionAllowed.set(0L)
        repeatedSuppressionCoalesced7250.set(0L)
        suppressedByMint7243.clear()
        lastSuppressionEvent7250.clear()
    }
}
