package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8365 §LOG_OPTIMAL_KELLY — the compounding share is the log-optimal Kelly fraction of the record's own return
 * distribution, not 0.25 x (mean - SE) / variance.
 *
 * 5.0.8360 audit item 1: the mean-variance form is wrong for runner payoffs. A record where 95 trades lose 10 % and 5 make
 * +500 % has a true (log-optimal) Kelly of ~0.31; the old form gave ~0.009 of equity and 0 at n=30, so every trade sat at
 * the route minimum. Now:
 *   f*   = argmax over f in [0, F_MAX] of mean(log(1 + f * r_i)) on per-trade net returns (fractions, after cost, each
 *          clipped at -0.99), found by a 60-step grid plus a golden-section refine (log growth is concave in f);
 *   frac = KELLY_FRACTION (0.25) x f* x n / (n + N0) (N0 = 30: a thin record cannot oversize), capped at 30 % of equity,
 *          and 0 unless mean - 1 SE > 0 on the same returns (the edge test is kept).
 * The record is the selected setup's LIVE realised closes once there are [LIVE_MIN_N_8365]+, else its executable label
 * record (as before). A label record is stored as moments (n, mean, variance, runner rate), so it is read as the two-point
 * distribution with those moments and the runner rate as the upside probability (exact for a runner book like 95/5).
 *
 * Sizing helpers here never raise a size by themselves: the lane position cap, pool depth cap, hard loss at the stop,
 * tradeable cash / reserve and the exposure preflight stay binding in LiveRiskPolicy7807.decide; label shares are still
 * capped by an independent live proof (8188) and revoked by fresh live losses (8296); the daily 2x-5x target never sizes.
 */
object Kelly8365 {
    const val KELLY_FRACTION_8365 = 0.25
    const val MAX_EQUITY_FRAC_8365 = 0.30
    private const val N0_8365 = 30.0
    private const val F_MAX_8365 = 1.0
    private const val CLIP_8365 = -0.99
    const val LIVE_MIN_N_8365 = 8
    private const val GRID_STEPS_8365 = 60
    /** A sub-minimum stake below this share of the route minimum is not promoted without a named 8329 reason. */
    const val ROUTE_SHARE_8365 = 0.25
    const val KELLY_TOO_SMALL_8365 = "KELLY_TOO_SMALL_FOR_ROUTE_8365"
    /** A two-point read whose loss side is milder than this is not a credible loss side (falls back to p = 0.5). */
    private const val MIN_CREDIBLE_LOSS_8365 = -0.05

    // ── pure maths ──────────────────────────────────────────────────────────

    /** Pure: weighted mean(log(1 + f * r_i)), each r_i clipped at [CLIP_8365]. */
    fun growth8365(f: Double, r: DoubleArray, w: DoubleArray?): Double {
        var s = 0.0
        var ws = 0.0
        for (k in r.indices) {
            val x = r[k]
            if (!x.isFinite()) continue
            val wk = if (w != null && k < w.size) w[k] else 1.0
            if (!wk.isFinite() || wk <= 0.0) continue
            val g = 1.0 + f * maxOf(x, CLIP_8365)
            if (g <= 0.0) return Double.NEGATIVE_INFINITY
            s += wk * kotlin.math.ln(g)
            ws += wk
        }
        return if (ws > 0.0) s / ws else 0.0
    }

    /** Pure: the full (log-optimal) Kelly fraction f* in [0, fMax]: grid of [GRID_STEPS_8365], then golden-section refine. */
    fun fullKelly8365(r: DoubleArray, w: DoubleArray? = null, fMax: Double = F_MAX_8365): Double {
        if (r.isEmpty() || !fMax.isFinite() || fMax <= 0.0) return 0.0
        var bestI = 0
        var best = growth8365(0.0, r, w)
        for (i in 1..GRID_STEPS_8365) {
            val g = growth8365(fMax * i / GRID_STEPS_8365, r, w)
            if (g > best) { best = g; bestI = i }
        }
        var lo = fMax * maxOf(0, bestI - 1) / GRID_STEPS_8365
        var hi = fMax * minOf(GRID_STEPS_8365, bestI + 1) / GRID_STEPS_8365
        val phi = (kotlin.math.sqrt(5.0) - 1.0) / 2.0
        var a = hi - phi * (hi - lo)
        var b = lo + phi * (hi - lo)
        var ga = growth8365(a, r, w)
        var gb = growth8365(b, r, w)
        for (k in 0 until 60) {
            if (ga < gb) { lo = a; a = b; ga = gb; b = lo + phi * (hi - lo); gb = growth8365(b, r, w) }
            else { hi = b; b = a; gb = ga; a = hi - phi * (hi - lo); ga = growth8365(a, r, w) }
        }
        val f = ((lo + hi) / 2.0).coerceIn(0.0, fMax)
        return if (growth8365(f, r, w) > 0.0) f else 0.0
    }

    /** Pure: the sample-size shrink n / (n + N0). */
    fun sampleShrink8365(n: Double): Double = if (n.isFinite() && n > 0.0) n / (n + N0_8365) else 0.0

    /** Pure: the sized share of a full Kelly [fStar] on a record of [n] with [mean] and [se] (same unit): 0 unless mean - SE > 0. */
    fun sizedFrac8365(fStar: Double, n: Double, mean: Double, se: Double): Double {
        if (!fStar.isFinite() || fStar <= 0.0 || !n.isFinite() || n < 2.0 || !mean.isFinite() || !se.isFinite()) return 0.0
        if (mean - se <= 0.0) return 0.0
        return (KELLY_FRACTION_8365 * fStar * sampleShrink8365(n)).coerceIn(0.0, MAX_EQUITY_FRAC_8365)
    }

    /** One record's Kelly read (diagnostics + the share). */
    data class Share8365(val src: String, val n: Int, val fStar: Double, val frac: Double, val nShrink: Double) {
        companion object { val NONE = Share8365("none", 0, 0.0, 0.0, 0.0) }
    }

    /** Pure: the share of per-trade net returns [returnsPct] (%, after cost; e.g. live realised closes). */
    fun shareFromReturnsPct8365(returnsPct: List<Double>, src: String = "live"): Share8365 {
        val r = returnsPct.filter { it.isFinite() }.map { maxOf(it / 100.0, CLIP_8365) }.toDoubleArray()
        val n = r.size
        if (n < 2) return Share8365(src, n, 0.0, 0.0, sampleShrink8365(n.toDouble()))
        val mean = r.sum() / n
        var ss = 0.0
        for (x in r) ss += (x - mean) * (x - mean)
        val se = kotlin.math.sqrt(ss / (n - 1) / n)
        val fStar = fullKelly8365(r)
        return Share8365(src, n, fStar, sizedFrac8365(fStar, n.toDouble(), mean, se), sampleShrink8365(n.toDouble()))
    }

    /** Pure: [up, down] of the two-point distribution with [mean] and [variance] (fractions) and upside probability [p]. */
    fun twoPoint8365(mean: Double, variance: Double, p: Double): DoubleArray? {
        if (!mean.isFinite() || !variance.isFinite() || variance <= 0.0 || !p.isFinite() || p <= 0.0 || p >= 1.0) return null
        val d = kotlin.math.sqrt(variance / (p * (1.0 - p)))
        return doubleArrayOf(mean + (1.0 - p) * d, mean - p * d)
    }

    /**
     * Pure: the share of a moments-only record ([meanPct], [variancePct2], [n]) read as its two-point distribution with
     * upside probability [upsideP] (the runner rate when known; 0.5 otherwise or when the loss side is not credible).
     */
    fun shareFromMoments8365(meanPct: Double, variancePct2: Double, n: Double, upsideP: Double = 0.5, src: String = "label"): Share8365 {
        val nInt = if (n.isFinite() && n > 0.0) n.toInt() else 0
        if (!meanPct.isFinite() || !variancePct2.isFinite() || variancePct2 <= 0.0 || !n.isFinite() || n < 2.0)
            return Share8365(src, nInt, 0.0, 0.0, sampleShrink8365(n))
        val sePct = kotlin.math.sqrt(variancePct2 / n)
        val m = meanPct / 100.0
        val v = variancePct2 / 10_000.0
        var p = if (upsideP.isFinite() && upsideP > 0.0 && upsideP < 1.0) upsideP.coerceIn(0.02, 0.5) else 0.5
        var tp = twoPoint8365(m, v, p)
        if (tp != null && tp[1] > MIN_CREDIBLE_LOSS_8365 && p < 0.5) { p = 0.5; tp = twoPoint8365(m, v, p) }
        if (tp == null) return Share8365(src, nInt, 0.0, 0.0, sampleShrink8365(n))
        val fStar = fullKelly8365(tp, doubleArrayOf(p, 1.0 - p))
        return Share8365(src, nInt, fStar, sizedFrac8365(fStar, n, meanPct, sePct), sampleShrink8365(n))
    }

    /** Pure: the share's ratio when the record's mean is lowered by [extraCostPct] (re-netting at a dearer size), in [0, 1]. */
    fun netRatio8365(meanPct: Double, variancePct2: Double, n: Double, extraCostPct: Double): Double {
        if (!extraCostPct.isFinite() || extraCostPct <= 0.0) return 1.0
        val base = shareFromMoments8365(meanPct, variancePct2, n).frac
        if (!(base > 0.0)) return 0.0
        val net = shareFromMoments8365(meanPct - extraCostPct, variancePct2, n).frac
        return (net / base).coerceIn(0.0, 1.0)
    }

    /**
     * Pure (item 3): ONE uncertainty shrink — the deepest active shrink factor, not the product of all of them. Factors
     * above 1 are not shrinks and are ignored here (growth is applied separately); non-finite factors are ignored.
     */
    fun oneShrink8365(vararg factors: Double): Double {
        var m = 1.0
        for (f in factors) if (f.isFinite() && f >= 0.0 && f < m) m = f
        return m
    }

    /** Pure: [factors] combined as min(shrinks) x product(boosts): stacked size-downs count once, boosts are kept. */
    fun shrinkThenBoost8365(vararg factors: Double): Double {
        var boost = 1.0
        for (f in factors) if (f.isFinite() && f > 1.0) boost *= f
        return oneShrink8365(*factors) * boost
    }

    /** Pure: the factor that turns a size already multiplied by all of [factors] into one multiplied by [shrinkThenBoost8365]. */
    fun undoStackedShrink8365(vararg factors: Double): Double {
        var product = 1.0
        for (f in factors) if (f.isFinite() && f >= 0.0) product *= f
        if (!(product > 0.0)) return 1.0
        return (shrinkThenBoost8365(*factors) / product).coerceAtLeast(1.0)
    }

    /** Pure (item 2): is a sub-minimum [stakeSol] at least [ROUTE_SHARE_8365] of the route minimum [routeMinSol]? */
    fun stakeNearRoute8365(stakeSol: Double, routeMinSol: Double): Boolean =
        stakeSol.isFinite() && routeMinSol.isFinite() && routeMinSol > 0.0 && stakeSol >= ROUTE_SHARE_8365 * routeMinSol

    // ── runtime: which record sizes the setup ──────────────────────────────

    private val liveCache8365 = ConcurrentHashMap<String, Pair<Long, List<Double>>>()

    private fun primarySetup8365(tag: String): String = tag.substringBefore('#').substringBefore(';').trim()

    /** The selected setup's LIVE realised net returns (%) in [lane]: clean whole-position closes attributed by their entry tag. */
    private fun liveSetupReturnsPct8365(lane: String, setup: String, nowMs: Long = System.currentTimeMillis()): List<Double> {
        val key = LiveRiskPolicy7807.canonicalLane(lane)
        val ck = "$key|$setup"
        liveCache8365[ck]?.let { (at, v) -> if (nowMs - at in 0L..30_000L) return v }
        val v = try {
            CanonicalFinalizedTradeBus6464.terminalEnvelopes7863("live")
                .filter { it.learningEligible && LiveRiskPolicy7807.canonicalLane(it.lane) == key }
                .take(200)
                .filter { e ->
                    val snap = try { EntryStrategySnapshot6450.latestFor8085(e.mint, e.lane) } catch (_: Throwable) { null }
                    snap != null && snap.entryTimestampMs <= e.atMs && primarySetup8365(snap.cortexSetupTag8085) == setup
                }
                .map { it.realizedReturnPct }
        } catch (_: Throwable) { emptyList() }
        if (liveCache8365.size > 128) liveCache8365.clear()
        liveCache8365[ck] = nowMs to v
        return v
    }

    /**
     * The selected setup's share: its LIVE realised closes when there are [LIVE_MIN_N_8365]+, else its label record
     * [labelStat] ([mean %, variance %², n], as LanePlaybook7907.provenSetupStat8057) read with the setup's runner rate.
     */
    fun setupShare8365(ts: com.lifecyclebot.data.TokenState, lane: String, labelStat: DoubleArray?, nowMs: Long = System.currentTimeMillis()): Share8365 {
        val l = LiveRiskPolicy7807.canonicalLane(lane)
        val setup = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.selectedSetup8094(ts, l, nowMs) } catch (_: Throwable) { null }
        val live = if (setup.isNullOrBlank()) emptyList() else liveSetupReturnsPct8365(l, setup, nowMs)
        val share = if (live.size >= LIVE_MIN_N_8365) shareFromReturnsPct8365(live, "live")
        else if (labelStat == null || labelStat.size < 3) Share8365("label", 0, 0.0, 0.0, 0.0)
        else {
            val runner = if (setup.isNullOrBlank()) Double.NaN else try {
                com.lifecyclebot.engine.cortex.LanePlaybook7907.firedSetupRecords8136(ts, l, nowMs)
                    .firstOrNull { it.first == setup }?.second?.getOrNull(3) ?: Double.NaN
            } catch (_: Throwable) { Double.NaN }
            shareFromMoments8365(labelStat[0], labelStat[1], labelStat[2], runner, "label")
        }
        noteShare8365(l, share)
        return share
    }

    // ── report: size8365[lane: src= n= f*= frac= shrink= size= route= decision=] ──

    private class Row8365 {
        @Volatile var share: Share8365 = Share8365.NONE
        @Volatile var unc: Double = 1.0
        @Volatile var size: Double = 0.0
        @Volatile var route: Double = 0.0
        @Volatile var decision: String = "-"
    }

    private val rows8365 = ConcurrentHashMap<String, Row8365>()

    private fun noteShare8365(lane: String, share: Share8365) {
        rows8365.computeIfAbsent(lane) { Row8365() }.share = share
    }

    /** LiveRiskPolicy7807.record: the decision's size, route minimum, one uncertainty shrink and verdict for [lane]. */
    fun noteDecision8365(lane: String, sizeSol: Double, routeMinSol: Double, uncertaintyShrink: Double, decision: String) {
        val r = rows8365.computeIfAbsent(LiveRiskPolicy7807.canonicalLane(lane)) { Row8365() }
        r.size = if (sizeSol.isFinite()) sizeSol else 0.0
        r.route = if (routeMinSol.isFinite()) routeMinSol else 0.0
        r.unc = if (uncertaintyShrink.isFinite()) uncertaintyShrink else 1.0
        r.decision = decision
    }

    fun statusLine8365(): String {
        val body = rows8365.entries.sortedBy { it.key }.joinToString(" ") { (l, r) ->
            val s = r.share
            "size8365[$l: src=${s.src} n=${s.n} f*=${"%.3f".format(s.fStar)} frac=${"%.4f".format(s.frac)} " +
                "shrink=${"%.2f".format(s.nShrink)}/${"%.2f".format(r.unc)} size=${"%.4f".format(r.size)} route=${"%.4f".format(r.route)} decision=${r.decision}]"
        }
        return body.ifBlank { "size8365[none yet]" }
    }
}
