package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8290 §LIVE_PROOF_MUST_SURVIVE_EXECUTION.
 *
 * Owner, 5.0.8286: "went from a near money printer to a bot that just buys losers". 19-minute live report: 4 of 4 live
 * closes lost (-9.2 .. -33.8 %), every one sized UNPROVEN at the route minimum, while the forward-label book the
 * admission read said admitted60 n17 +1.8 %, LAUNCH_LADDER_PROVEN n824 +83.5 % auth=1.00, and the best kind books were
 * OUTLIER_SUSPECT_8185 (NARR|SHITCOIN|UNKNOWN +328 %, KIND|PROJECT_SNIPER|PUMPSWAP|MC_LT10K +107 %,
 * KIND|MOONSHOT|OTHER|MC_LT10K +88.7 %). The 8187 executable offset that is meant to close the label-to-bank gap read
 * -3.5 pt: its realised half is PAIRED (an admitted decision's own label beside its own close), and 657 of 701 graded
 * decisions had lost their label (LABEL_LOST_GAP_8187), so only the cost half was ever measured. Live paid the rest.
 *
 * Two rules, both one-way (they can only stop a live buy, never start one):
 *
 *  1. An OUTLIER_SUSPECT record proves nothing for live money. 8185 reads it at the winsorised UPPER bound with the
 *     winsorised range as its error, but its mean - SE is only "pessimistic" when the excess labels are huge: a book of
 *     2,000 % labels winsorises to a positive lower bound and still "proves" (and the point-estimate tests — the runner
 *     tail, the win-rate proof, watch-first — read the upper bound itself). A record whose raw mean is impossible under
 *     the plausibility cap is a record of basis errors, not of tradeable coins.
 *
 *  2. A forward-label proof must clear the lane's REALISED shortfall as well as its own standard error. The shortfall
 *     is unpaired: the lane's admitted forward-label mean minus the mean of its recent live whole-position closes
 *     (outlier-capped), shrunk by n / (n + 5) and floored at 0. Whatever the 8187 offset already took off a book is not
 *     taken twice ([extraBeyondOffset8290]). No live closes, or closes at / above the labels: no extra (0).
 *
 * Paper is untouched (the extra is 0 outside LIVE): paper and the shadow book are where unproven setups prove out.
 */
object LiveAdmissionProof8290 {
    private const val SHRINK_K_8290 = 5.0
    const val MAX_SHORTFALL_PCT_8290 = 60.0
    /** A single close is an anecdote; two make the first measurement (the 8187 realised blend uses the same bar). */
    private const val MIN_REALISED_N_8290 = 2
    /** The most recent live closes the shortfall reads (the label book is long-run; the exit doctrine is current). */
    private const val RECENT_CLOSES_8290 = 50
    private const val CACHE_MS_8290 = 30_000L

    private val suspectRefused8290 = AtomicLong(0)
    private val shortfallRefused8290 = AtomicLong(0)
    private val cache8290 = ConcurrentHashMap<String, DoubleArray>()   // lane -> [atMs, extra, shortfall, realisedN, realisedMean, labelN, labelMean, offset]

    /**
     * Pure: the lane's unpaired label-to-bank shortfall (points): admitted forward-label mean minus realised close mean,
     * shrunk by realisedN / (realisedN + [SHRINK_K_8290]), in 0..[MAX_SHORTFALL_PCT_8290]. 0 without both records or when
     * the closes did at least as well as the labels.
     */
    fun unpairedShortfall8290(labelN: Int, labelMeanPct: Double, realisedN: Int, realisedMeanPct: Double): Double {
        if (labelN < 1 || realisedN < MIN_REALISED_N_8290 || !labelMeanPct.isFinite() || !realisedMeanPct.isFinite()) return 0.0
        val gap = labelMeanPct - realisedMeanPct
        if (!(gap > 0.0)) return 0.0
        return (gap * realisedN / (realisedN + SHRINK_K_8290)).coerceIn(0.0, MAX_SHORTFALL_PCT_8290)
    }

    /** Pure: the part of [shortfallPct] the 8187 executable offset ([execOffsetPct]) has not already taken off a book. */
    fun extraBeyondOffset8290(shortfallPct: Double, execOffsetPct: Double): Double {
        val s = if (shortfallPct.isFinite()) shortfallPct.coerceAtLeast(0.0) else 0.0
        val o = if (execOffsetPct.isFinite()) execOffsetPct.coerceAtLeast(0.0) else 0.0
        return (s - o).coerceIn(0.0, MAX_SHORTFALL_PCT_8290)
    }

    /**
     * Pure: a forward-label record proves live money — not outlier-suspect, at least [minN] labels, and its mean minus
     * [seMult] standard errors minus the lane's realised [extraPct] above zero (labels are net of the charged cost).
     */
    fun survives8290(n: Double, meanPct: Double, sePct: Double, suspect: Boolean, extraPct: Double,
                     minN: Double = 30.0, seMult: Double = 1.0): Boolean {
        // V5.0.8326 — Entry8114Mode8326's 8114 verdict: proven means mean - SE past zero on the record (no suspect flag, no shortfall).
        if (Entry8114Mode8326.legacyScope8326()) return n >= minN && meanPct.isFinite() && sePct.isFinite() && meanPct - seMult * sePct > 0.0
        if (suspect) return false
        if (!(n >= minN) || !meanPct.isFinite() || !sePct.isFinite()) return false
        val extra = if (extraPct.isFinite()) extraPct.coerceAtLeast(0.0) else 0.0
        return meanPct - seMult * sePct - extra > 0.0
    }

    /**
     * Pure (V5.0.8316): the LABEL side of the shortfall — the lane's admitted forward-label book read at its pessimistic
     * winsorised mean under the 8185 cap, bounded by its median's sign. 8314: the robust mean (+215.6 % MOONSHOT, +45.4 %
     * SHITCOIN) is a handful of runner labels; read against live closes cut by catastrophe stops within minutes it charged
     * the exit doctrine to entry selection (MOONSHOT 55.9, SHITCOIN 42.8 pt). Only aggregates are kept (n, sum, sumSq,
     * wins), so the median itself is not recoverable: [RobustLabelRead8185.lowerMean8185] is the smallest winsorised mean the
     * aggregates allow, and a book with fewer than half its labels positive has a non-positive median, so its label side is
     * at most 0. NaN without a record; the 60-pt cap ([MAX_SHORTFALL_PCT_8290]) is unchanged.
     */
    fun labelSide8316(n: Int, meanPct: Double, sePct: Double, winRate: Double, cap: Double = RobustLabelRead8185.CAP_LABEL_PCT_8185): Double {
        if (n < 1 || !meanPct.isFinite()) return Double.NaN
        val nd = n.toDouble()
        val variance = if (sePct.isFinite() && n > 1) sePct * sePct * nd else 0.0
        val sum = meanPct * nd
        val sumSq = (variance + meanPct * meanPct) * nd
        var side = minOf(meanPct, RobustLabelRead8185.lowerMean8185(nd, sum, sumSq, cap))
        if (winRate.isFinite() && winRate < 0.5) side = minOf(side, 0.0)   // the median label is not positive
        return side
    }

    /** Pure: [e] read [extraPct] points lower (mean and late mean); the record itself when there is nothing to take off. */
    fun shift8290(e: CandidateAdmission8136.Evidence8136?, extraPct: Double): CandidateAdmission8136.Evidence8136? {
        if (e == null || !extraPct.isFinite() || extraPct <= 0.0) return e
        return e.copy(meanPct = e.meanPct - extraPct,
            lateMeanPct = if (e.lateMeanPct.isFinite()) e.lateMeanPct - extraPct else e.lateMeanPct)
    }

    /** A labeler cell / kind record proves live money (rule 1 + rule 2), counted when it would have proved without them. */
    fun cellSurvives8290(st: ForwardReturnLabeler7731.CellStat?, lane: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (st == null) return false
        val ok = survives8290(st.n60.toDouble(), st.meanNet60Pct, st.stderr60Pct, st.outlierSuspect8185, laneExtra8290(lane, nowMs))
        if (!ok && LiveEdgeGate7877.cellProven8060(st.n60, st.meanNet60Pct, st.stderr60Pct)) noteRefused8290(st.outlierSuspect8185, lane)
        return ok
    }

    /** Counts a proof that stood only on a suspect record ([suspect]) or only before the realised shortfall. */
    fun noteRefused8290(suspect: Boolean, lane: String) {
        (if (suspect) suspectRefused8290 else shortfallRefused8290).incrementAndGet()
        try { PipelineHealthCollector.labelInc((if (suspect) "LIVE_PROOF_SUSPECT_REFUSED_8290_" else "LIVE_PROOF_SHORTFALL_REFUSED_8290_") + canonLane8290(lane)) } catch (_: Throwable) {}
    }

    private fun canonLane8290(raw: String): String =
        (try { CanonicalLaneIdentity6506.canonical(raw).uppercase() } catch (_: Throwable) { "" }).ifBlank { raw.trim().uppercase() }

    /** The realised shortfall (points) a forward-label proof in [laneRaw] must clear beyond the 8187 offset; 0 outside LIVE. */
    fun laneExtra8290(laneRaw: String, nowMs: Long = System.currentTimeMillis()): Double {
        if (Entry8114Mode8326.legacyScope8326()) return 0.0  // V5.0.8326 — 8114 had no realised-shortfall offset
        val live = try { com.lifecyclebot.engine.RuntimeModeAuthority.isLive() } catch (_: Throwable) { false }
        if (!live) return 0.0
        val lane = canonLane8290(laneRaw)
        cache8290[lane]?.let { if (nowMs - it[0].toLong() in 0L..CACHE_MS_8290) return it[1] }
        val row = try { measure8290(lane, nowMs) } catch (_: Throwable) { null } ?: doubleArrayOf(nowMs.toDouble(), 0.0, 0.0, 0.0, Double.NaN, 0.0, Double.NaN, 0.0)
        if (cache8290.size > 64) cache8290.clear()
        cache8290[lane] = row
        return row[1]
    }

    private fun measure8290(lane: String, nowMs: Long): DoubleArray {
        val closes = CanonicalFinalizedTradeBus6464.terminalEnvelopes7863("live")
            .filter { it.learningEligible && canonLane8290(it.lane) == lane }
            .take(RECENT_CLOSES_8290)
            .map { it.realizedReturnPct }
            .filter { it.isFinite() }
            .map { it.coerceIn(-100.0, RobustLabelRead8185.CAP_LABEL_PCT_8185) }
        val realisedMean = if (closes.isNotEmpty()) closes.average() else Double.NaN
        val label = try { ForwardReturnLabeler7731.laneDecisionStats8094(lane).first } catch (_: Throwable) { null }
        // V5.0.8316 — the label side is the admitted book's pessimistic winsorised mean bounded by its median's sign, not the robust mean.
        val labelSide = if (label == null) Double.NaN else labelSide8316(label.n60, label.meanNet60Pct, label.stderr60Pct, label.winRate60, RobustLabelRead8185.capForKey8185(label.key))
        val sf = unpairedShortfall8290(label?.n60 ?: 0, labelSide, closes.size, realisedMean)
        val off = try { com.lifecyclebot.engine.cortex.Cortex7885.executableOffset8187(lane) } catch (_: Throwable) { 0.0 }
        val extra = extraBeyondOffset8290(sf, off)
        return doubleArrayOf(nowMs.toDouble(), extra, sf, closes.size.toDouble(), realisedMean,
            (label?.n60 ?: 0).toDouble(), labelSide, off)
    }

    fun statusLine8290(): String {
        val lanes = cache8290.entries.filter { it.value[3] > 0.0 }.sortedByDescending { it.value[1] }.take(8).joinToString(" · ") { (l, r) ->
            "$l label${"%+.1f".format(r[6])}%(n${r[5].toInt()}) live${"%+.1f".format(r[4])}%(n${r[3].toInt()}) shortfall${"%.1f".format(r[2])} off${"%.1f".format(r[7])} extra${"%.1f".format(r[1])}"
        }.ifBlank { "no live closes read" }
        return "proof8290[suspectRefused=${suspectRefused8290.get()} shortfallRefused=${shortfallRefused8290.get()} $lanes]"
    }
}
