package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6835 §MISSING_MARK_EXIT_VETO — operator diagnosis (Feb 2026):
 *
 *   "Many recent trades are being closed at effectively 100% loss,
 *    including UNIVERSAL_HARD_FLOOR_-99PCT, despite normal-looking entry
 *    values. That pattern strongly points at price/mark failure being
 *    interpreted as economic collapse rather than genuine price
 *    movement.
 *
 *    canonicalOpen=83, exitVisible=83, missingMark=71 — extremely poor
 *    mark coverage for an active portfolio. Many marks shown in the
 *    event stream are 2–8 minutes old against a 60-second freshness
 *    requirement."
 *
 * PURPOSE — refuse to produce a catastrophic terminal exit
 * (UNIVERSAL_HARD_FLOOR / CATASTROPHIC_HARD_BACKSTOP / anything with a
 * -N% suffix meaning the position has ostensibly lost near everything)
 * when the mark that would justify that closure is missing or stale.
 * Instead, defer the exit and emit EXIT_DEFERRED_MISSING_MARK_6835.
 *
 * WHY THIS IS THE CORRECT PLACE — the ledger, journal and adaptive
 * learners all consume terminal closures. Once a synthetic -100% enters
 * the stream it poisons every downstream surface (strategy expectancy,
 * losing-pattern memory, forward-outcome model, unified policy head).
 * The only economically safe response to a missing mark on a
 * catastrophic threshold is to WAIT for a live mark; genuine
 * catastrophic moves recur repeatedly and will trigger the veto to
 * lift on the very next fresh tick.
 *
 * SCOPE
 *   * Blocks: UNIVERSAL_HARD_FLOOR_*, CATASTROPHIC_HARD_BACKSTOP_*,
 *             UNIVERSAL_PEAK_LOCK_* when signal is stale.
 *   * Does NOT block: THIN_LIQ_*, LIQ_DRAIN_*, RUG_* (these read
 *             liquidity, not mark price — different failure surface).
 *   * Does NOT block: NORMAL_STOP, TRAIL_LOCK, TARGET_HIT (these are
 *             already computed from live-price paths and the operator
 *             already reports NORMAL_STOP latency is excellent).
 *
 * WIRING — Executor.doHardStops() line 7078+ and
 *          BotService.universalExitSweep() line 19570+.
 */
object MissingMarkExitVeto6835 {

    /** Marks are considered stale beyond this age. Operator directive
     *  cited a 60-second freshness requirement. */
    private const val MARK_MAX_AGE_MS = 60_000L

    /** If MarkPriceFreshnessTelemetry6832 says the numeric price value
     *  has not changed for this long, the mark is treated as frozen
     *  (carry-forward pocket) regardless of `markUpdatedAtMs`. This is
     *  the actual poisoning surface documented in 6832. */
    private const val MARK_FROZEN_MS = 120_000L

    private val vetoCount = AtomicLong(0L)
    private val allowCount = AtomicLong(0L)
    private val freshBypassCount = AtomicLong(0L)

    data class Verdict(
        val allow: Boolean,
        val reason6835: String,
    )

    /**
     * Consult before emitting any terminal catastrophic exit reason.
     *  * mintKey            — canonical mint key for
     *                         MarkPriceFreshnessTelemetry6832 lookup.
     *  * markPrice          — the price value about to be used to
     *                         compute the -N% closure.
     *  * markUpdatedAtMs    — publish time of that price on the
     *                         position (0 if unknown).
     *  * exitReason         — the terminal reason being proposed.
     */
    fun evaluate(
        mintKey: String,
        markPrice: Double,
        markUpdatedAtMs: Long,
        exitReason: String,
    ): Verdict {
        val nowMs = System.currentTimeMillis()

        // Reason must be catastrophic to gate. Anything else passes.
        val catastrophic = exitReason.startsWith("UNIVERSAL_HARD_FLOOR") ||
            exitReason.startsWith("CATASTROPHIC_HARD_BACKSTOP") ||
            exitReason.startsWith("UNIVERSAL_PEAK_LOCK")
        if (!catastrophic) {
            allowCount.incrementAndGet()
            return Verdict(true, "NON_CATASTROPHIC_REASON")
        }

        // Mark price must be a legal number.
        if (!markPrice.isFinite() || markPrice <= 0.0) {
            vetoCount.incrementAndGet()
            emitVeto(mintKey, exitReason, "MARK_NONFINITE_OR_NONPOSITIVE")
            return Verdict(false, "MARK_NONFINITE_OR_NONPOSITIVE")
        }

        // markUpdatedAtMs freshness gate.
        val markAgeMs = if (markUpdatedAtMs > 0L) (nowMs - markUpdatedAtMs).coerceAtLeast(0L) else Long.MAX_VALUE
        if (markAgeMs > MARK_MAX_AGE_MS) {
            vetoCount.incrementAndGet()
            emitVeto(mintKey, exitReason, "MARK_AGE_${markAgeMs / 1000L}s_EXCEEDS_${MARK_MAX_AGE_MS / 1000L}s")
            return Verdict(false, "MARK_STALE_${markAgeMs / 1000L}s")
        }

        // MarkPriceFreshnessTelemetry6832 frozen check — the price may
        // have been touched (markUpdatedAtMs is fresh) but the numeric
        // value hasn't changed in a very long time. That's the
        // carry-forward pocket documented in 6832.
        try {
            val snap = MarkPriceFreshnessTelemetry6832.snapshot(mintKey)
            val ageSinceChange = snap.ageSinceChangeMs
            if (ageSinceChange >= 0L && ageSinceChange > MARK_FROZEN_MS) {
                vetoCount.incrementAndGet()
                emitVeto(
                    mintKey, exitReason,
                    "MARK_FROZEN_${ageSinceChange / 1000L}s_carryFwd=${snap.carryForwardCount}"
                )
                return Verdict(false, "MARK_FROZEN_${ageSinceChange / 1000L}s")
            }
        } catch (_: Throwable) {
            // Telemetry not initialised — fall through to allow.
        }

        freshBypassCount.incrementAndGet()
        return Verdict(true, "MARK_FRESH_${markAgeMs / 1000L}s")
    }

    private fun emitVeto(mintKey: String, exitReason: String, detail: String) {
        try {
            PipelineHealthCollector.labelInc("EXIT_DEFERRED_MISSING_MARK_6835")
            PipelineHealthCollector.labelInc("EXIT_DEFERRED_MISSING_MARK_6835_${
                exitReason.take(48).replace("[^A-Za-z0-9_]".toRegex(), "_")
            }")
            ForensicLogger.lifecycle(
                "EXIT_DEFERRED_MISSING_MARK_6835",
                "mint=${mintKey.take(10)} proposedReason=$exitReason detail=$detail " +
                    "action=defer_terminal_close_until_fresh_mark",
            )
        } catch (_: Throwable) {}
    }

    data class Summary(
        val vetoed: Long,
        val allowed: Long,
        val freshBypass: Long,
    )

    fun summary(): Summary = Summary(vetoCount.get(), allowCount.get(), freshBypassCount.get())

    fun statusLine(): String {
        val s = summary()
        return "MissingMarkExitVeto6835 vetoed=${s.vetoed} allowedNonCatastrophic=${s.allowed} " +
            "freshCatastrophicAllowed=${s.freshBypass}"
    }

    internal fun clearForTest() {
        vetoCount.set(0L); allowCount.set(0L); freshBypassCount.set(0L)
    }
}
