package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * V5.0.6882 §DEFERRAL_IS_NOT_A_DISPOSITION — bound on how long a single
     * position may be held open by this veto.
     *
     * The 6835 design rests on one premise, stated in its own header:
     * "genuine catastrophic moves recur repeatedly and will trigger the veto
     * to lift on the very next fresh tick." That premise holds for a feed
     * blip. It fails for the case the veto fires on most: a token whose pool
     * has actually been drained. There is no next fresh tick — the mark is
     * gone for good — so the veto became an unbounded hold. The position
     * could never close, its basis stayed in openCost forever, and it
     * permanently consumed a slot against POSITION_HARD_CAP.
     *
     * Operator 5.0.6881 shows the end state of that: 103 open positions,
     * cash 6.10 of 41.38 equity, ORDER_SIZE_BLOCKED_EXIT_THROUGHPUT_6758=873,
     * POSITION_HARD_CAP_EXIT_THROUGHPUT=120, CASH_STARVED_EXIT_THROUGHPUT=60.
     * Inventory that cannot be released is inventory that cannot compound.
     *
     * The bound keeps the veto's actual purpose intact — it still refuses to
     * mint a synthetic -N% economic terminal from a bad mark — while giving
     * the deferral a terminal disposition. Past the bound the close is
     * allowed but flagged `markUntrusted6882`, the caller appends
     * MARK_UNTRUSTED_6882 to the reason, and Executor's `accountingTrainable`
     * refuses to train on it. Capital comes back; the learners never see it.
     *
     * Both conditions must hold, so a 1–2 minute provider blip cannot trip
     * the release: the deferral must be older than MAX_DEFER_MS *and* have
     * been retried at least MIN_DEFER_ATTEMPTS times.
     *
     * V5.0.6893 §THE_BOUND_NEEDED_A_CEILING — operator 5.0.6892 measured the
     * 6882 bound doing almost nothing:
     *   vetoed=599 boundReleased6882=1 heldNow=11 oldestHeld=1789s
     * One release. Eleven positions held, the oldest for thirty minutes against
     * a ten-minute bound. The `AND attempts >= 20` is the reason: this veto is
     * only consulted when a catastrophic exit reason is actually proposed, and
     * the universal sweep walks a rotating 24-of-100 slice, so a position can
     * sit half an hour without being consulted twenty times. The condition that
     * was meant to stop a provider blip tripping the release also stopped the
     * release from ever happening for a slowly-visited position.
     *
     * Consequence, straight from the same snapshot: 100 open positions,
     * POSITION_HARD_CAP_EXIT_THROUGHPUT as the top hard block,
     * ORDER_SIZE_BLOCKED_EXIT_THROUGHPUT_6758=1849, BLUECHIP with 480 pending
     * intents and sized=0, CORE holding 0.0 of a 4.3 SOL target. Inventory that
     * cannot be released is inventory that cannot compound.
     *
     * HARD_DEFER_MS is an unconditional ceiling on top of the existing rule.
     * Past it the position is released on age alone, however rarely it has been
     * consulted. Twenty minutes is still far longer than any credible provider
     * blip, so the protection 6882 was built for is intact.
     */
    private const val MAX_DEFER_MS = 600_000L
    private const val MIN_DEFER_ATTEMPTS = 20L
    private const val HARD_DEFER_MS = 1_200_000L

    private val vetoCount = AtomicLong(0L)
    private val allowCount = AtomicLong(0L)
    private val freshBypassCount = AtomicLong(0L)
    private val boundReleaseCount = AtomicLong(0L)

    private class Deferral(val firstAtMs: Long) {
        val attempts = AtomicLong(0L)
    }

    private val deferrals = ConcurrentHashMap<String, Deferral>()

    data class Verdict(
        val allow: Boolean,
        val reason6835: String,
        /** V5.0.6882 — true when `allow` is only granted because the
         *  deferral bound expired, NOT because the mark became fresh. The
         *  resulting closure is economically untrustworthy and must not
         *  reach any learner. */
        val markUntrusted6882: Boolean = false,
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
        // V5.0.7335 — UNIVERSAL_PEAK_LOCK only fires while the position is
        // still in profit (pnl > 0 under its fluid floor): it BANKS, it is not
        // a catastrophe. Deferring it for 10-20 minutes on a quiet feed is how
        // CORE closed UNIVERSAL_PEAK_LOCK_peak107_now0 and _peak33_now0 on
        // 5.0.7333 — the lock was held until the whole gain was gone.
        val catastrophic = exitReason.startsWith("UNIVERSAL_HARD_FLOOR") ||
            exitReason.startsWith("CATASTROPHIC_HARD_BACKSTOP")
        if (!catastrophic) {
            allowCount.incrementAndGet()
            return Verdict(true, "NON_CATASTROPHIC_REASON")
        }

        // Mark price must be a legal number.
        if (!markPrice.isFinite() || markPrice <= 0.0) {
            return deferOrRelease(mintKey, exitReason, nowMs, "MARK_NONFINITE_OR_NONPOSITIVE", "MARK_NONFINITE_OR_NONPOSITIVE")
        }

        // markUpdatedAtMs freshness gate.
        val markAgeMs = if (markUpdatedAtMs > 0L) (nowMs - markUpdatedAtMs).coerceAtLeast(0L) else Long.MAX_VALUE
        if (markAgeMs > MARK_MAX_AGE_MS) {
            return deferOrRelease(
                mintKey, exitReason, nowMs,
                "MARK_AGE_${markAgeMs / 1000L}s_EXCEEDS_${MARK_MAX_AGE_MS / 1000L}s",
                "MARK_STALE_${markAgeMs / 1000L}s",
            )
        }

        // MarkPriceFreshnessTelemetry6832 frozen check — the price may
        // have been touched (markUpdatedAtMs is fresh) but the numeric
        // value hasn't changed in a very long time. That's the
        // carry-forward pocket documented in 6832.
        val frozen6882 = try {
            val snap = MarkPriceFreshnessTelemetry6832.snapshot(mintKey)
            val ageSinceChange = snap.ageSinceChangeMs
            if (ageSinceChange >= 0L && ageSinceChange > MARK_FROZEN_MS) {
                Pair(ageSinceChange, snap.carryForwardCount)
            } else null
        } catch (_: Throwable) {
            // Telemetry not initialised — fall through to allow.
            null
        }
        if (frozen6882 != null) {
            val (ageSinceChange, carryFwd) = frozen6882
            return deferOrRelease(
                mintKey, exitReason, nowMs,
                "MARK_FROZEN_${ageSinceChange / 1000L}s_carryFwd=$carryFwd",
                "MARK_FROZEN_${ageSinceChange / 1000L}s",
            )
        }

        // Fresh mark — the deferral (if any) has served its purpose and is
        // discarded so a later unrelated stall starts its own clock.
        deferrals.remove(mintKey)
        freshBypassCount.incrementAndGet()
        return Verdict(true, "MARK_FRESH_${markAgeMs / 1000L}s")
    }

    /**
     * V5.0.6882 — the single veto exit point. Defers while the bound holds;
     * once the bound expires, releases the position with
     * `markUntrusted6882 = true` so the caller can tag the closure and keep
     * it out of learning.
     */
    private fun deferOrRelease(
        mintKey: String,
        exitReason: String,
        nowMs: Long,
        detail: String,
        verdictReason: String,
    ): Verdict {
        val d = deferrals.computeIfAbsent(mintKey) { Deferral(nowMs) }
        val attempts = d.attempts.incrementAndGet()
        val heldMs = (nowMs - d.firstAtMs).coerceAtLeast(0L)
        // V5.0.6893 — age alone releases past HARD_DEFER_MS. See the constant's
        // note: the attempt count starves for positions the rotating exit slice
        // rarely reaches, which is exactly the population that most needs
        // releasing.
        val ceilingHit6893 = heldMs > HARD_DEFER_MS
        if ((heldMs > MAX_DEFER_MS && attempts >= MIN_DEFER_ATTEMPTS) || ceilingHit6893) {
            deferrals.remove(mintKey)
            boundReleaseCount.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("EXIT_DEFERRAL_BOUND_RELEASED_6882")
                // V5.0.6893 — distinguish the two release paths so the operator
                // can see whether the attempt-counted rule or the age ceiling is
                // doing the work. If the ceiling dominates, the rotating exit
                // slice is not reaching these positions often enough and THAT is
                // the thing to fix next.
                if (ceilingHit6893) {
                    PipelineHealthCollector.labelInc("EXIT_DEFERRAL_AGE_CEILING_RELEASED_6893")
                }
                ForensicLogger.lifecycle(
                    "EXIT_DEFERRAL_BOUND_RELEASED_6882",
                    "mint=${mintKey.take(10)} proposedReason=$exitReason detail=$detail " +
                        "heldMs=$heldMs attempts=$attempts boundMs=$MAX_DEFER_MS " +
                        "ceiling6893=$ceilingHit6893 ceilingMs=$HARD_DEFER_MS " +
                        "action=release_position_mark_untrusted_excluded_from_learning",
                )
            } catch (_: Throwable) {}
            return Verdict(true, "DEFERRAL_BOUND_EXCEEDED_${heldMs / 1000L}s_$verdictReason", markUntrusted6882 = true)
        }
        vetoCount.incrementAndGet()
        emitVeto(mintKey, exitReason, "${detail}_held${heldMs / 1000L}s_try$attempts")
        return Verdict(false, verdictReason)
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
        val boundReleased: Long,
        val heldNow: Int,
        val oldestHeldMs: Long,
    )

    fun summary(): Summary {
        val nowMs = System.currentTimeMillis()
        val oldest = deferrals.values.minOfOrNull { it.firstAtMs } ?: 0L
        return Summary(
            vetoed = vetoCount.get(),
            allowed = allowCount.get(),
            freshBypass = freshBypassCount.get(),
            boundReleased = boundReleaseCount.get(),
            heldNow = deferrals.size,
            oldestHeldMs = if (oldest > 0L) (nowMs - oldest).coerceAtLeast(0L) else 0L,
        )
    }

    /**
     * V5.0.6882 — this line had zero callers, so an authority capable of
     * holding every protective exit open was completely invisible in the
     * operator snapshot. Now rendered by PipelineHealthCollector.
     */
    fun statusLine(): String {
        val s = summary()
        return "vetoed=${s.vetoed} allowedNonCatastrophic=${s.allowed} " +
            "freshCatastrophicAllowed=${s.freshBypass} boundReleased6882=${s.boundReleased} " +
            "heldNow=${s.heldNow} oldestHeld=${s.oldestHeldMs / 1000L}s"
    }

    internal fun clearForTest() {
        vetoCount.set(0L); allowCount.set(0L); freshBypassCount.set(0L)
        boundReleaseCount.set(0L); deferrals.clear()
    }
}
