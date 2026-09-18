package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.7036 §THE_INTELLIGENCE_CIRCUIT_COMPUTES_AND_NOBODY_READS.
 *
 * ci/audit_unread_intelligence.py reports 110 modules holding 280 analysis
 * functions that nothing in the app calls. They are not broken and they are
 * not cheap: FluidLearningAI alone is 2,751 lines and 25 of its 82 query
 * functions have no reader. The engine pays to compute those answers on every
 * cycle and then throws all of them away.
 *
 * WHAT THIS IS AND IS NOT. Batch one of the operator's "wire, ignite, connect"
 * pass, restricted to READ-ONLY outputs: every call below returns a number or
 * a list and none of them can refuse a trade, resize one, or move a
 * threshold. That restriction is the point. The same audit also lists
 * shouldBlockNewBuys, blockLiveBuyReason, shouldBlockFeeBandExit and
 * isInDangerZone — thirteen risk gates whose whole purpose is to STOP trades.
 * Switching those on in the same change as this would mean that if execution
 * fell to zero afterwards, nothing in the snapshot could say which of the
 * thirteen did it. They go in their own batches, one small group at a time,
 * each with its own named counter.
 *
 * WHY SURFACING COUNTS AS WIRING. An analysis function with no reader is
 * indistinguishable from one that is wrong: nobody has ever seen its output,
 * so nobody can tell. Putting these on the snapshot is what makes the next
 * step possible — the operator and I can look at what BehaviorLearning
 * actually thinks a winning pattern is before anything is allowed to trade on
 * it. Reading first, acting second, is the order that was skipped when these
 * were built.
 *
 * EVERY CALL IS INDIVIDUALLY GUARDED. These modules were never exercised from
 * this path; one of them throwing must not cost the operator their health
 * snapshot, which is the only diagnostic surface they have.
 */
object UnreadIntelligenceSurface7036 {

    private fun pct(v: Double): String =
        if (!v.isFinite()) "—" else String.format(java.util.Locale.US, "%.1f%%", v)

    private fun <T> safe(label: String, block: () -> T): T? = try {
        block()
    } catch (_: Throwable) {
        try { PipelineHealthCollector.labelInc("UNREAD_INTEL_READ_FAILED_7036_$label") } catch (_: Throwable) {}
        null
    }

    /**
     * One block for the operator snapshot. Deliberately compact: this is a
     * first look at answers nobody has ever seen, not a dashboard.
     */
    fun report(): String {
        val sb = StringBuilder()
        sb.append("===== UNREAD INTELLIGENCE (V5.0.7036 — first readers) =====\n")

        safe("EXIT_PROFITABLE_RATE") {
            com.lifecyclebot.engine.ExitIntelligence.getProfitableRate()
        }?.let { sb.append("  ExitIntelligence.profitableRate:   ").append(pct(it)).append("\n") }

        // V5.0.7038 — BotBrain.getBlendedWinRate() is NOT readable from here and
        // that is not an oversight to fix later. BotBrain is a `class`, not an
        // `object`: BotService constructs one locally (BotService:6635) and no
        // singleton is exposed, so there is no instance for a reporting surface
        // to reach. 7036 called it statically and the build failed on
        // "Unresolved reference: getBlendedWinRate".
        //
        // My mistake was the grep: `^object X|^class X` matched, and I did not
        // look at WHICH branch matched. Third ownership error of this session
        // after 7017 (read a field off the wrong type) and 7032 (used a
        // parameter in a different function than the one declaring it), so
        // ci/static_call_check.py now fails the build on it instead of me
        // promising to be more careful.

        safe("BEHAVIOUR_GOOD") {
            com.lifecyclebot.engine.BehaviorLearning.getTopGoodPatterns(3)
        }?.let { list ->
            sb.append("  BehaviorLearning top GOOD (n=").append(list.size).append("):\n")
            for (p in list) {
                sb.append("    ").append(p.signature.take(44))
                    .append("  n=").append(p.occurrences)
                    .append(" W/L=").append(p.wins).append("/").append(p.losses)
                    .append(" avgPnl=").append(
                        if (p.occurrences > 0) String.format(java.util.Locale.US, "%+.1f%%", p.totalPnlPct / p.occurrences)
                        else "—"
                    ).append("\n")
            }
        }

        safe("BEHAVIOUR_BAD") {
            com.lifecyclebot.engine.BehaviorLearning.getTopBadPatterns(3)
        }?.let { list ->
            sb.append("  BehaviorLearning top BAD (n=").append(list.size).append("):\n")
            for (p in list) {
                sb.append("    ").append(p.signature.take(44))
                    .append("  n=").append(p.occurrences)
                    .append(" W/L=").append(p.wins).append("/").append(p.losses)
                    .append(" avgPnl=").append(
                        if (p.occurrences > 0) String.format(java.util.Locale.US, "%+.1f%%", p.totalPnlPct / p.occurrences)
                        else "—"
                    ).append("\n")
            }
        }

        safe("MOMENTUM_STRONG") {
            com.lifecyclebot.engine.MomentumPredictorAI.getStrongMomentumTokens()
        }?.let { list ->
            sb.append("  MomentumPredictorAI.strongMomentum: ").append(list.size).append(" tokens")
            if (list.isNotEmpty()) {
                sb.append(" [").append(list.take(5).joinToString(",") { it.symbol.take(10) }).append("]")
            }
            sb.append("\n")
        }

        safe("COLLECTIVE_WIN_PATTERNS") {
            com.lifecyclebot.collective.CollectiveLearning.getHighWinPatterns().size
        }?.let { sb.append("  CollectiveLearning.highWinPatterns: ").append(it).append("\n") }

        safe("COLLECTIVE_LOSS_PATTERNS") {
            com.lifecyclebot.collective.CollectiveLearning.getHighLossPatterns().size
        }?.let { sb.append("  CollectiveLearning.highLossPatterns:").append(it).append("\n") }

        // V5.0.7036 — risk exposure by canonical asset class. CanonicalLedger6387
        // has computed this since 6387 and nothing has ever asked: the count that
        // decides whether the bot is over-exposed was available and unread.
        safe("RISK_EXPOSURE") {
            CanonicalLedger6387.canonicalRiskPositionIds().size to
                CanonicalLedger6387.canonicalRiskPositionMints().size
        }?.let { (ids, mints) ->
            sb.append("  CanonicalLedger6387 risk exposure:  positions=").append(ids)
                .append(" distinctMints=").append(mints).append("\n")
        }

        // ── V5.0.7037, batch 2 ────────────────────────────────────────────
        // Tuning constants and learned curves the engine computes and never
        // consults. Each is a pure function of its arguments, so calling it
        // here cannot disturb anything: we are asking what it WOULD say.

        safe("WEEKLY_DD_SIZE_MULT") {
            // WeeklyGrowthMode6393.sizeMultiplierFromDrawdown — the drawdown
            // ladder that was supposed to shrink size as the week goes against
            // us. Probed at the operator's actual current drawdown.
            val dd = com.lifecyclebot.engine.truth.CanonicalCapitalAuthority6450
                .snapshot().let { snap ->
                    val eq = snap.totalEquitySol
                    if (eq > 0.0) ((snap.realizedPnlSol / eq) * 100.0).coerceAtMost(0.0) else 0.0
                }
            WeeklyGrowthMode6393.sizeMultiplierFromDrawdown(kotlin.math.abs(dd)) to dd
        }?.let { (mult, dd) ->
            sb.append("  WeeklyGrowthMode6393 @dd=").append(pct(kotlin.math.abs(dd)))
                .append(": sizeMult=").append(String.format(java.util.Locale.US, "%.2fx", mult)).append("\n")
        }

        safe("PEAK_TRAIL_CURVE") {
            // EarlyEntryScout6390.trailPctForPeakGain — the peak-gain trail
            // curve. Printed at four points so the shape is visible rather
            // than asserted.
            listOf(25.0, 100.0, 500.0, 2000.0).map { it to EarlyEntryScout6390.trailPctForPeakGain(it) }
        }?.let { pts ->
            sb.append("  EarlyEntryScout6390 trail curve:    ")
                .append(pts.joinToString(" ") { (peak, trail) ->
                    "+" + peak.toInt() + "%→" + String.format(java.util.Locale.US, "%.0f%%", trail)
                }).append("\n")
        }

        safe("EDGE_VETO_STICKY") {
            com.lifecyclebot.engine.EdgeLearning.getVetoStickyMinutes()
        }?.let { sb.append("  EdgeLearning.vetoStickyMinutes:    ").append(it).append("m\n") }

        safe("CURRICULUM_HOLD") {
            com.lifecyclebot.v3.scoring.EducationSubLayerAI.getCurriculumHoldStats()
        }?.let { m ->
            sb.append("  EducationSubLayerAI curriculum:    ").append(m.size).append(" layers")
            if (m.isNotEmpty()) {
                val worst = m.entries.minByOrNull { it.value.third }
                if (worst != null) {
                    sb.append("  weakest=").append(worst.key.take(22))
                        .append(" acc=").append(pct(worst.value.third * 100.0))
                }
            }
            sb.append("\n")
        }

        safe("FEE_ADJUSTED_TP") {
            // FluidLearningAI.getFeeAdjustedTakeProfit — what the raw TP
            // becomes once round-trip fees are priced in. Probed at the
            // 25% meme runner default on a typical 0.1 SOL / $50k-liq fill.
            com.lifecyclebot.v3.scoring.FluidLearningAI.getFeeAdjustedTakeProfit(25.0, 0.1, 50_000.0)
        }?.let {
            sb.append("  FluidLearningAI feeAdjTP(25%):     ").append(pct(it)).append("\n")
        }

        sb.append("  Read: first readers for outputs the engine has been computing\n")
        sb.append("        and discarding. Advisory only — nothing here gates a trade.\n")
        return sb.toString()
    }
}
