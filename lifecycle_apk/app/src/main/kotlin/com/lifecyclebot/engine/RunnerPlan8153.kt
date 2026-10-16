package com.lifecyclebot.engine

/**
 * V5.0.8153 — ONE RUNNER PLAN PER POSITION (pure: no Android, no runtime state, no clock).
 *
 * Device, 5.0.8151: eight 4x+ opportunities, one held runner, seven missed, zero confirmed runner-exit captures.
 * Four readers each answered "is this position a runner?" their own way, and they contradicted each other:
 *  - RunnerPlay8018.onMark8018 promoted only when a +25% print, a +25% verified peak, a +100% corroborated peak
 *    (ExitTruthArbiter8144 with the lane forced on), a firming chart AND an age under 30 minutes all agreed — i.e.
 *    never before +100%, and never for a late continuation, by which time the scalp ladder had sold 60% at +40%;
 *  - RunnerGrab7967.promote8018 wrote the promotion into the GRAB map (an entry-time admit), and holding7967 then
 *    dropped it again whenever the sealed style was a known short style (TradeExitContract8077.longHold == false);
 *  - TradeExitContract8077.runnerBasis8136 read the sealed style only, the arbiter read style OR strategy, so a
 *    strategy-sealed runner kept the scalp ladder while the arbiter protected its residual as a runner's;
 *  - the arbiter never knew the runner's continuation (continuationConfirmed was always false), so a soft full exit
 *    (chart top, trail, clock) sold a standing runner whole.
 *
 * Now [plan8153] is the one classification every reader uses: the immutable sealed entry style (or, when the style
 * is unknown, the sealed strategy), an entry-time runner admit (grab / ticket / proven cell, only when the sealed
 * style is unknown), or a PROVEN promotion of this position — latched once earned, or the corroborated position peak
 * at +[RunnerExitContract8136.PROMOTE_PCT_8136]%. The lane only says the lane CAN host a runner.
 *
 * [promotion8153] earns the latch on independently verified continuation: the chain-verified, corroborated peak
 * (PositionDisplayTruth8078 highStepOk8133 / acceptedHigh8133 ratchet position.peakGainPct) AND the live print both at
 * +[STRUCTURE_PROMOTE_PCT_8153]% or better, with the chart firming (2+ higher swings, a higher low, unbroken, buyers)
 * — before the scalp ladder's +40% rung. A print the verified mark does not back, a basis in the wrong units, a broken
 * structure or a position already distributing is refused.
 *
 * [continuation8153] keeps the runner's continuation / give-back / distribution / structure-break state; only a
 * standing, non-distributing structure is a confirmed continuation (holds soft full exits, never tiers 1-3).
 *
 * [recoveryTranche8153] validates the capital-recovery tranche against what is really held and recoverable.
 */
object RunnerPlan8153 {
    /** Verified peak AND print a structure-backed promotion needs (the RunnerPlay8018 promotion level). */
    private const val STRUCTURE_PROMOTE_PCT_8153 = RunnerPlay8018.PROMOTE_MIN_PCT_8018
    /** A gross this large is a basis in the wrong units, never a price. */
    const val BAD_UNITS_PCT_8153 = 100_000.0
    /** A position at or below this share of its verified peak gain is distributing, not continuing. */
    private const val DISTRIBUTION_KEEP_8153 = 0.5
    /** A position below this share of its verified peak gain is giving back. */
    private const val GIVEBACK_KEEP_8153 = 0.75
    /** The give-back read starts at this verified peak. */
    private const val GIVEBACK_MIN_PEAK_PCT_8153 = 40.0
    /** A promotion is earned inside the runner hold window (RunnerGrab7967: 6 h), not only in the first 30 minutes. */
    private const val PROMOTION_WINDOW_MS_8153 = 6L * 3_600_000L
    /** A recovery tranche must leave at least this share of the holding as the residual it is funding. */
    private const val MIN_RESIDUAL_OF_HELD_8153 = 0.10

    enum class Plan8153 { SCALP, RUNNER_SEALED, RUNNER_PROMOTED }

    /** What one position's plan is decided from. [verifiedPeakPct] is position.peakGainPct (chain-verified). */
    data class Evidence8153(
        val laneHostsRunners: Boolean,
        val sealedStyle: String,
        val sealedStrategy: String,
        val grabbedAtEntry: Boolean = false,
        val promotedLatched: Boolean = false,
        val verifiedPeakPct: Double = 0.0,
    )

    /** Pure: is [style] a style the router knows (not blank, not UNKNOWN)? */
    private fun styleKnown8153(style: String): Boolean {
        val n = style.trim().uppercase()
        if (n.isBlank() || n == "UNKNOWN") return false
        return AgenticStyleRouter.Style.values().any { it.name == n }
    }

    /** Pure: the sealed setup is a runner — the sealed style decides; the sealed strategy only when the style is unknown. */
    private fun sealedRunner8153(style: String, strategy: String): Boolean =
        if (styleKnown8153(style)) RunnerExitContract8136.sealedRunnerStyle8136(style)
        else RunnerExitContract8136.sealedRunnerStyle8136(strategy)

    /** Pure: the one runner classification of a position. */
    fun plan8153(e: Evidence8153): Plan8153 = when {
        !e.laneHostsRunners -> Plan8153.SCALP
        sealedRunner8153(e.sealedStyle, e.sealedStrategy) -> Plan8153.RUNNER_SEALED
        e.grabbedAtEntry && !styleKnown8153(e.sealedStyle) -> Plan8153.RUNNER_SEALED
        e.promotedLatched || RunnerExitContract8136.provenPromotion8136(e.verifiedPeakPct) -> Plan8153.RUNNER_PROMOTED
        else -> Plan8153.SCALP
    }

    /** Pure: the runner basis the exit contract and the arbiter read. */
    fun basis8153(p: Plan8153): RunnerExitContract8136.RunnerBasis8136 = when (p) {
        Plan8153.SCALP -> RunnerExitContract8136.RunnerBasis8136.NONE
        Plan8153.RUNNER_SEALED -> RunnerExitContract8136.RunnerBasis8136.SEALED_SETUP
        Plan8153.RUNNER_PROMOTED -> RunnerExitContract8136.RunnerBasis8136.PROVEN_PROMOTION
    }

    // ── promotion ─────────────────────────────────────────────────────────

    enum class Promotion8153 {
        ALREADY_RUNNER, PROMOTE_PROVEN_PEAK, PROMOTE_STRUCTURE, NOT_YET, NOT_HOSTED, STOOD_DOWN,
        REFUSE_BAD_UNITS, REFUSE_UNCORROBORATED, REFUSE_DISTRIBUTION, REFUSE_STRUCTURE_BROKEN, REFUSE_OUTSIDE_WINDOW,
    }

    data class PromotionInput8153(
        val plan: Plan8153,
        val laneHostsRunners: Boolean,
        val latched: Boolean,
        val verifiedPeakPct: Double,
        val grossPct: Double,
        val firming: Boolean,
        val structureBroken: Boolean,
        val ageMs: Long,
        val standingDown: Boolean,
    )

    /** Pure: does this position earn the runner promotion now? PROMOTE_* verdicts latch it. */
    fun promotion8153(i: PromotionInput8153): Promotion8153 {
        if (!i.laneHostsRunners) return Promotion8153.NOT_HOSTED
        if (i.latched || i.plan == Plan8153.RUNNER_SEALED) return Promotion8153.ALREADY_RUNNER
        if (!i.grossPct.isFinite() || !i.verifiedPeakPct.isFinite() || i.grossPct > BAD_UNITS_PCT_8153 ||
            i.verifiedPeakPct >= BAD_UNITS_PCT_8153 || i.grossPct <= -100.0) return Promotion8153.REFUSE_BAD_UNITS
        // The corroborated peak already proved the run (plan reads it): the latch only records the transition.
        if (i.plan == Plan8153.RUNNER_PROMOTED) return Promotion8153.PROMOTE_PROVEN_PEAK
        if (i.ageMs !in 0L..PROMOTION_WINDOW_MS_8153) return Promotion8153.REFUSE_OUTSIDE_WINDOW
        if (i.structureBroken) return Promotion8153.REFUSE_STRUCTURE_BROKEN
        val level = STRUCTURE_PROMOTE_PCT_8153
        if (i.verifiedPeakPct >= level && i.grossPct <= i.verifiedPeakPct * DISTRIBUTION_KEEP_8153) return Promotion8153.REFUSE_DISTRIBUTION
        // A print the chain-verified peak does not back is a spike, not a run (the evidence is the lower of the two).
        if (i.grossPct >= level && i.verifiedPeakPct < level) return Promotion8153.REFUSE_UNCORROBORATED
        if (minOf(i.grossPct, i.verifiedPeakPct) < level || !i.firming) return Promotion8153.NOT_YET
        if (i.standingDown) return Promotion8153.STOOD_DOWN
        return Promotion8153.PROMOTE_STRUCTURE
    }

    /** Pure: a verdict that latches the promotion. */
    fun promotes8153(v: Promotion8153): Boolean = v == Promotion8153.PROMOTE_PROVEN_PEAK || v == Promotion8153.PROMOTE_STRUCTURE

    // ── continuation ──────────────────────────────────────────────────────

    enum class Continuation8153 { NONE, CONTINUING, STANDING, GIVING_BACK, DISTRIBUTING, BROKEN }

    /** Pure: the verified peak has been given back to the distribution level (or the 7980 peak capture line). */
    private fun distributing8153(verifiedPeakPct: Double, grossPct: Double): Boolean =
        verifiedPeakPct.isFinite() && grossPct.isFinite() && (RunnerGrab7967.peakGiveback7980(verifiedPeakPct, grossPct) ||
            (verifiedPeakPct >= GIVEBACK_MIN_PEAK_PCT_8153 && grossPct <= verifiedPeakPct * DISTRIBUTION_KEEP_8153))

    /** Pure: a runner's state on this mark. [standing] = structure unbroken with buyers; [firming] = 2+ higher swings too. */
    fun continuation8153(plan: Plan8153, verifiedPeakPct: Double, grossPct: Double, firming: Boolean, standing: Boolean,
                         structureBroken: Boolean): Continuation8153 = when {
        plan == Plan8153.SCALP || !grossPct.isFinite() -> Continuation8153.NONE
        structureBroken -> Continuation8153.BROKEN
        distributing8153(verifiedPeakPct, grossPct) -> Continuation8153.DISTRIBUTING
        grossPct < 0.0 -> Continuation8153.NONE                    // a red runner is never held as a continuation
        verifiedPeakPct.isFinite() && verifiedPeakPct >= GIVEBACK_MIN_PEAK_PCT_8153 &&
            grossPct < verifiedPeakPct * GIVEBACK_KEEP_8153 -> Continuation8153.GIVING_BACK
        firming -> Continuation8153.CONTINUING
        standing -> Continuation8153.STANDING
        else -> Continuation8153.NONE
    }

    /** Pure: a confirmed continuation (holds soft FULL exits in the arbiter; never ranks 1-3, never a budgeted partial). */
    fun continuationConfirmed8153(c: Continuation8153): Boolean =
        c == Continuation8153.CONTINUING || c == Continuation8153.STANDING

    /**
     * Pure: a promoted / sealed runner whose continuation is confirmed refuses an ORDINARY scalp-style partial (a profit
     * lock / take-profit / harvest tranche from any path) below its first runner rung (+100%): the plan changed, the
     * scalp ladder no longer applies. Its own rungs, capital recovery (rank 4), tail / lifecycle takes, runner exits
     * and every protective exit pass; so does everything once the continuation is not confirmed.
     */
    fun scalpPartialHeld8153(plan: Plan8153, continuationConfirmed: Boolean, reason: String, grossPct: Double): Boolean {
        if (plan == Plan8153.SCALP || !continuationConfirmed) return false
        val r = reason.uppercase()
        if (r.startsWith("SPIKE_CAPTURE_7943_T") || r.startsWith("TAIL_") || r.startsWith("LIFECYCLE_") || r.startsWith("RUNNER_")) return false
        if (RunnerExitContract8136.tierOf8136(r) != RunnerExitContract8136.Tier8136.PROFIT_LOCK) return false
        return grossPct.isFinite() && grossPct < RunnerGrab7967.TIERS_7967.first().first
    }

    // ── capital recovery ──────────────────────────────────────────────────

    data class Tranche8153(val fraction: Double, val why: String)

    /**
     * Pure: the capital-recovery tranche as a fraction of the CURRENT (runtime) holding. [unrecoveredSol] = entry cost
     * less CONFIRMED net proceeds (cumulative fees counted); [grossValueSol] = the runtime holding at the executable
     * mark; [sellCostPct] = the executable sell cost; [sellableShareOfHeld] = the canonical remaining quantity over the
     * runtime quantity (capped at 1; 0 = nothing canonically held). A tranche that would have to sell more than
     * (1 - [minResidualOfHeld]) of what is really held cannot recover principal AND keep the residual it is for: no
     * tranche (the position's ordinary exits decide). The fraction is of the CURRENT holding, never clamped by the
     * share of the ORIGINAL quantity (8136 mixed the two units).
     */
    fun recoveryTranche8153(unrecoveredSol: Double, grossValueSol: Double, sellCostPct: Double, sellableShareOfHeld: Double,
                            minResidualOfHeld: Double = MIN_RESIDUAL_OF_HELD_8153): Tranche8153 {
        if (!unrecoveredSol.isFinite() || unrecoveredSol <= 0.0) return Tranche8153(0.0, "RECOVERED")
        if (!grossValueSol.isFinite() || grossValueSol <= 0.0) return Tranche8153(0.0, "NO_VALUE")
        val sellable = if (sellableShareOfHeld.isFinite()) sellableShareOfHeld.coerceIn(0.0, 1.0) else 0.0
        if (sellable <= 0.0) return Tranche8153(0.0, "NO_CANONICAL_QTY")
        val keep = 1.0 - (if (sellCostPct.isFinite()) sellCostPct.coerceIn(0.0, 90.0) else 90.0) / 100.0
        val needed = unrecoveredSol / (grossValueSol * keep)
        val residual = if (minResidualOfHeld.isFinite()) minResidualOfHeld.coerceIn(0.0, 1.0) else MIN_RESIDUAL_OF_HELD_8153
        if (needed > sellable * (1.0 - residual)) return Tranche8153(0.0, "RESIDUAL_UNFUNDED")
        return Tranche8153(needed, "TRANCHE")
    }
}
