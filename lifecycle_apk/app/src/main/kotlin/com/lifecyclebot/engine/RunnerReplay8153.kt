package com.lifecyclebot.engine

/**
 * V5.0.8153 — replay one position's price path through the live exit order (pure: no Android, no runtime state; the
 * clock is the mark index). Runtime ledgers live on the device; this is the tooling that says what the 8153 rules
 * bank on a given path, so a claim about runner capture is measured on a replay, not assumed.
 *
 * Per mark, in the order the live tick runs them: the verified peak ratchets (only from the chain-verified mark),
 * the plan is read and a promotion earned BEFORE any ladder is chosen (RunnerPlay8018 -> RunnerPlan8153), every
 * overlay offers its intent (runner structure / give-back / hard stop, the scalp stop and lock, the one rung the
 * RunnerRungBook8153 lets request), ExitTruthArbiter8144 decides once with the plan basis and the live continuation,
 * and only a landed fill confirms a rung and books its proceeds (once per signature). Units: entry cost 1.0 SOL.
 */
object RunnerReplay8153 {

    /**
     * One print: [px] the print the overlays decide on, [execPx] what a sale fills at, [verifiedPx] the chain-verified
     * corroborated mark (null = none on this print), the structure read, and [sellFails] (a sale sent here never lands).
     */
    data class Mark8153(
        val px: Double,
        val execPx: Double = px,
        val verifiedPx: Double? = execPx,
        val firming: Boolean = false,
        val standing: Boolean = firming,
        val structureBroken: Boolean = false,
        val emergency: Boolean = false,
        val sellFails: Boolean = false,
        val chartTop: Boolean = false,
    )

    data class Result8153(
        val plan: RunnerPlan8153.Plan8153,
        val promotedAtMark: Int,
        val promotion: RunnerPlan8153.Promotion8153?,
        val verifiedPeakPct: Double,
        val proceeds: Double,
        val residualShare: Double,
        /** The share still held on the print that set the verified peak (what rode the run). */
        val shareAtPeak: Double,
        val residualValue: Double,
        val net: Double,
        val bookedProceeds: Double,
        val scalpConfirmed: Int,
        val runnerConfirmed: Int,
        val exits: List<String>,
        val rejected: Int,
        val superseded: Int,
        val capitalRecovered: Boolean,
        val continuationHolds: Int,
        val book: RunnerRungBook8153,
    )

    private const val KEY_8153 = "REPLAY8153|1"

    private class State8153(val book: RunnerRungBook8153) {
        var share = 1.0
        var shareAtPeak = 1.0
        var proceeds = 0.0
        var peak = 0.0
        var latched = false
        var promotedAt = -1
        var firstVerdict: RunnerPlan8153.Promotion8153? = null
        var recovered = false
        var rejected = 0
        var superseded = 0
        var holds = 0
        var plan = RunnerPlan8153.Plan8153.SCALP
        val exits = mutableListOf<String>()
    }

    /** Replay [path] (path[0] is the entry print) for a live meme-lane position sealed as [sealedStyle]. */
    fun replay8153(path: List<Mark8153>, sealedStyle: String, sellCostPct: Double = 3.0, markMs: Long = 1_000L,
                   book: RunnerRungBook8153 = RunnerRungBook8153()): Result8153 {
        val st = State8153(book)
        val entry = path.firstOrNull()?.px ?: 0.0
        var lastExec = entry
        if (entry > 0.0) for (i in 1 until path.size) {
            if (st.share <= 1e-9) break
            val m = path[i]
            lastExec = m.execPx
            if (!stepMark8153(st, m, i, entry, sealedStyle, sellCostPct, i * markMs)) break
        }
        val keep = 1.0 - sellCostPct.coerceIn(0.0, 90.0) / 100.0
        val residualValue = if (entry > 0.0) st.share * lastExec / entry * keep else 0.0
        return Result8153(st.plan, st.promotedAt, st.firstVerdict, st.peak, st.proceeds, st.share, st.shareAtPeak, residualValue,
            st.proceeds + residualValue - 1.0, book.bankedProceedsSol8153(KEY_8153),
            book.confirmedTier8153(KEY_8153, RunnerRungBook8153.LADDER_SCALP_8153),
            book.confirmedTier8153(KEY_8153, RunnerRungBook8153.LADDER_RUNNER_8153),
            st.exits.toList(), st.rejected, st.superseded, st.recovered, st.holds, book)
    }

    /** One mark. Returns false when the position is gone. */
    private fun stepMark8153(st: State8153, m: Mark8153, i: Int, entry: Double, style: String, cost: Double, nowMs: Long): Boolean {
        val gross = (m.px / entry - 1.0) * 100.0
        m.verifiedPx?.let { v ->
            val vg = (v / entry - 1.0) * 100.0
            if (vg.isFinite() && vg < RunnerPlan8153.BAD_UNITS_PCT_8153 && vg > st.peak) { st.peak = vg; st.shareAtPeak = st.share }
        }
        val ev = RunnerPlan8153.Evidence8153(laneHostsRunners = true, sealedStyle = style, sealedStrategy = "",
            promotedLatched = st.latched, verifiedPeakPct = st.peak)
        // The promotion is earned before any ladder is chosen (RunnerPlay8018.onMark8018 runs first in the tick).
        val verdict = RunnerPlan8153.promotion8153(RunnerPlan8153.PromotionInput8153(RunnerPlan8153.plan8153(ev), true,
            st.latched, st.peak, gross, m.firming, m.structureBroken, nowMs, false))
        if (st.firstVerdict == null && verdict != RunnerPlan8153.Promotion8153.NOT_YET) st.firstVerdict = verdict
        if (RunnerPlan8153.promotes8153(verdict) && !st.latched) {
            st.latched = true; st.promotedAt = i; st.book.latchPromotion8153(KEY_8153)
        }
        st.plan = RunnerPlan8153.plan8153(ev.copy(promotedLatched = st.latched))
        val runner = st.plan != RunnerPlan8153.Plan8153.SCALP
        val cont = RunnerPlan8153.continuation8153(st.plan, st.peak, gross, m.firming, m.standing, m.structureBroken)
        val intents = overlayIntents8153(st, m, gross, runner)
        val rung = rungIntent8153(st, m, gross, entry, runner, cost, nowMs)
        if (rung != null) intents += rung.second
        val inp = ExitTruthArbiter8144.Inputs8144(sealedStrategy = "", entryStyle = style, laneHostsRunners = true,
            confirmedPeakPct = st.peak, capitalRecoveredConfirmed = st.recovered, pnlPct = gross, sellCostPct = cost,
            remainingShare = st.share, continuationConfirmed = RunnerPlan8153.continuationConfirmed8153(cont),
            planBasis8153 = RunnerPlan8153.basis8153(st.plan))
        val d = ExitTruthArbiter8144.decide8144(intents, inp)
        val act = d.act
        if (inp.continuationConfirmed && intents.any { softFull8153(it) && it.reason != act?.reason }) st.holds++
        if (rung != null && act?.reason != rung.second.reason) {
            // Beaten (or refused) on this tick: the rung goes back — never consumed.
            st.superseded++
            st.book.revert8153(KEY_8153, RunnerRungBook8153.Revert8153.SUPERSEDED, nowMs)
        }
        if (act == null) return true
        val rungTier = if (rung != null && act.reason == rung.second.reason) rung.first else 0
        val ladder = if (runner) RunnerRungBook8153.LADDER_RUNNER_8153 else RunnerRungBook8153.LADDER_SCALP_8153
        if (m.sellFails || m.execPx <= 0.0) {
            // No fill, no proceeds: a rejected tranche goes back with a backoff; the position is unchanged.
            st.rejected++
            if (rungTier > 0) st.book.revert8153(KEY_8153, RunnerRungBook8153.Revert8153.REJECTED, nowMs)
            st.exits += "UNFILLED:" + act.reason
            return true
        }
        if (rungTier > 0) {
            st.book.advance8153(KEY_8153, RunnerRungBook8153.Stage8153.TICKET_ACCEPTED, nowMs)
            st.book.advance8153(KEY_8153, RunnerRungBook8153.Stage8153.BROADCAST, nowMs)
        }
        val frac = act.fraction.coerceIn(0.0, 1.0)
        val sold = st.share * frac
        val fill = sold * m.execPx / entry * (1.0 - cost.coerceIn(0.0, 90.0) / 100.0)
        st.proceeds += fill
        st.share -= sold
        // The fill is booked once per signature, however many doors report it (slice commit + chain-partial callback).
        st.book.confirm8153(KEY_8153, ladder, rungTier, "sig8153-$i", fill, sold)
        st.book.confirm8153(KEY_8153, ladder, rungTier, "sig8153-$i", fill, sold)
        st.recovered = RunnerExitContract8136.capitalRecoveredConfirmed8136(1.0, st.proceeds, 0.0)
        st.exits += act.reason
        return st.share > 1e-9
    }

    private fun softFull8153(i: ExitTruthArbiter8144.Intent8144): Boolean {
        val r = ExitTruthArbiter8144.rankOf8144(i.reason)
        return i.fraction >= 1.0 - 1e-9 && (r == ExitTruthArbiter8144.Rank8144.PROFIT_PARTIAL || r == ExitTruthArbiter8144.Rank8144.TIME_BASED)
    }

    /** The runner / scalp overlays' own exits on this print (rapidMark: chart reader, heldExit7967, the scalp stops). */
    private fun overlayIntents8153(st: State8153, m: Mark8153, g: Double, runner: Boolean): MutableList<ExitTruthArbiter8144.Intent8144> {
        val out = mutableListOf<ExitTruthArbiter8144.Intent8144>()
        val gi = g.toInt()
        if (m.emergency) out += ExitTruthArbiter8144.Intent8144("LIQUIDITY_DRAIN_EXIT_8153_${gi}PCT", 1.0)
        if (runner) {
            if (g <= -35.0) out += ExitTruthArbiter8144.Intent8144("RUNNER_HARD_STOP_7967_${gi}PCT", 1.0)
            if (RunnerGrab7967.peakGiveback7980(st.peak, g)) out += ExitTruthArbiter8144.Intent8144("RUNNER_PEAK_CAPTURE_7980_${st.peak.toInt()}PK_${gi}PCT", 1.0)
            else if (RunnerGrab7967.fromEntryGiveback8104(st.peak, g)) out += ExitTruthArbiter8144.Intent8144("RUNNER_FROM_ENTRY_GIVEBACK_8104_${st.peak.toInt()}PK_${gi}PCT", 1.0)
            if (m.structureBroken) out += ExitTruthArbiter8144.Intent8144("RUNNER_STRUCTURE_BREAK_7967_1M_${gi}PCT", 1.0)
        } else {
            if (g <= -20.0) out += ExitTruthArbiter8144.Intent8144("STRICT_SL_-20_${gi}PCT", 1.0)
            if (st.peak >= 40.0 && g <= st.peak * 0.5) out += ExitTruthArbiter8144.Intent8144("TICK_PROFIT_LOCK_peak${st.peak.toInt()}_${gi}PCT", 1.0)
        }
        // The chart reader's top call on this print (a soft full exit): a confirmed runner continuation holds it.
        if (m.chartTop) out += ExitTruthArbiter8144.Intent8144("CHART_CAPTURE_TOP_7950_TOP_${gi}PCT", 1.0)
        return out
    }

    /**
     * The one rung this print may request (RunnerRungBook8153: nothing pending, tier above the confirmed one, outside a
     * rejection's backoff). A runner's first rung is the capital-recovery tranche (RunnerPlan8153.recoveryTranche8153).
     */
    private fun rungIntent8153(st: State8153, m: Mark8153, g: Double, entry: Double, runner: Boolean, cost: Double,
                               nowMs: Long): Pair<Int, ExitTruthArbiter8144.Intent8144>? {
        val tiers = if (runner) RunnerGrab7967.TIERS_7967 else SpikeCapture7943.TIERS
        val ladder = if (runner) RunnerRungBook8153.LADDER_RUNNER_8153 else RunnerRungBook8153.LADDER_SCALP_8153
        val reached = SpikeCapture7943.tierReached(g, tiers)
        val done = st.book.confirmedTier8153(KEY_8153, ladder)
        // A basis in the wrong units is not a price (SpikeCapture7943.onMark refuses it the same way).
        if (!g.isFinite() || g > RunnerPlan8153.BAD_UNITS_PCT_8153) return null
        if (reached <= done || st.book.pending8153(KEY_8153) != null) return null
        val gi = g.toInt()
        var reason = if (runner && done >= 1) "RUNNER_MILESTONE_8136_T${reached}_${gi}PCT" else "SPIKE_CAPTURE_7943_T${reached}_${gi}PCT"
        var frac = SpikeCapture7943.crossedTierFraction8079(tiers, done, reached)
        if (runner && done == 0) {
            val t = RunnerPlan8153.recoveryTranche8153(RunnerExitContract8136.unrecoveredSol8136(1.0, st.proceeds, 0.0),
                st.share * m.execPx / entry, cost, 1.0)
            if (t.fraction > 0.0) { reason = "CAPITAL_RECOVERY_8136_T${reached}_${gi}PCT"; frac = t.fraction }
        }
        if (frac <= 0.0 || !st.book.request8153(KEY_8153, ladder, reached, reason, nowMs)) return null
        return reached to ExitTruthArbiter8144.Intent8144(reason, frac)
    }
}
