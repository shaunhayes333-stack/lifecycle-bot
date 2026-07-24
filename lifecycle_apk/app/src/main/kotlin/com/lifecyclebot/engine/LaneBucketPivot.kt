package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LANE BUCKET PIVOT — V5.0.6240
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Operator directive:
 *   "ensure its pivoting away from toxic buckets to the proven winners within
 *    those lanes. all layers and traders need to do as designed. bluechip,
 *    standard, quality, moonshot, cashgen/treasury, core, v3.... everything.
 *    ensure the stack is intelligently pivoting correctly."
 *
 * DESIGN — bucket-granular, cross-layer, systemic pivot:
 *   • Every lane has some SCORE-BAND buckets that empirically win and some
 *     that empirically bleed (e.g. MOONSHOT|S41-60: 46L/12W μ-51%,
 *     TREASURY|S0-10: 18L/1W μ-24%). The bot must trim exposure in the
 *     bleeders and press in the proven winners — WITHIN the same lane.
 *   • This module reads the two existing sources of truth
 *       - LosingPatternMemory (danger buckets: ≥8L / ≥20 samples / ≥75% loss)
 *       - LiveWinDNAStore (winning fingerprint corpus with lane×setup×route)
 *     and produces a single advisory size shape per (lane, score) pair that
 *     every layer (BLUECHIP, STANDARD, QUALITY, MOONSHOT, CASHGEN, TREASURY,
 *     V3_CORE, edge engine, LLM personality) can multiply into its own sizing.
 *   • NEVER a hard gate. Never blocks a trade. Fail-open on every read.
 *
 * PIVOT SHAPE (range 0.25 .. 1.35):
 *   • 0.25  — CONFIRMED_TOXIC : (lane, band) is a danger bucket AND has
 *             ≥10 losses. Trim to a quarter probe.
 *   • 0.50  — DANGER          : lane×band is in LosingPatternMemory but has
 *             fewer than 10 losses (early warning).
 *   • 0.85  — CAUTIOUS        : lane×band has no data yet AND lane has
 *             overall negative EV (bleeder lane, no proven buckets).
 *   • 1.00  — NEUTRAL         : default, no signal either way.
 *   • 1.15  — PROVEN_WINNER   : (lane, band) matches an entry recorded in
 *             LiveWinDNAStore with an average PnL >= +25%.
 *   • 1.35  — DEEP_WINNER     : (lane, band) matches a WinDNA row with
 *             average PnL >= +100% (a compounder-tier bucket).
 *
 * REPORT: statusLine() and reportBlock() surface the pivots being applied
 * so the operator sees every cycle which lane×buckets are being trimmed and
 * which are being pressed.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object LaneBucketPivot {

    private const val BUCKET_WIDTH = 20   // matches ScoreExpectancyTracker: S0-19, S20-39 …

    data class PivotShape(
        val mult: Double,
        val reason: String,
        val bucket: String,          // e.g. "MOONSHOT|S41-60"
    )

    // per-lane × per-bucket last-seen shape (for report + telemetry)
    private val recentShapes = ConcurrentHashMap<String, PivotShape>()

    /** The single call every layer/trader makes to get its size-shape advisory. */
    fun sizeShape(lane: String, score: Int): PivotShape {
        val laneU = lane.uppercase()
        val band = bandFor(score)
        val bucket = "$laneU|$band"
        val shape = compute(laneU, score, band, bucket)
        recentShapes[bucket] = shape
        return shape
    }

    /** Convenience alias — just the multiplier. */
    fun sizeMult(lane: String, score: Int): Double = sizeShape(lane, score).mult

    /** Log the pivot decision on a hot-path entry so it appears in forensic logs. */
    fun logPivotOnEntry(lane: String, score: Int, mint: String, symbol: String) {
        val shape = sizeShape(lane, score)
        val tag = when {
            shape.mult <= 0.30 -> "PIVOT_CONFIRMED_TOXIC_6240"
            shape.mult <= 0.55 -> "PIVOT_DANGER_6240"
            shape.mult <= 0.90 -> "PIVOT_CAUTIOUS_6240"
            shape.mult >= 1.30 -> "PIVOT_DEEP_WINNER_6240"
            shape.mult >= 1.10 -> "PIVOT_PROVEN_WINNER_6240"
            else -> null
        }
        if (tag != null) {
            try {
                ForensicLogger.lifecycle(tag, "sym=$symbol lane=$lane band=${bandFor(score)} score=$score mult=${"%.2f".format(shape.mult)} reason=${shape.reason}")
            } catch (_: Throwable) {}
        }
    }

    fun statusLine(): String {
        if (recentShapes.isEmpty()) return "V5.0.6240_LANE_BUCKET_PIVOT: no pivots emitted yet"
        val toxic = recentShapes.values.count { it.mult <= 0.55 }
        val cautious = recentShapes.values.count { it.mult in 0.56..0.99 }
        val proven = recentShapes.values.count { it.mult >= 1.10 }
        return "V5.0.6240_LANE_BUCKET_PIVOT: buckets=${recentShapes.size} pressed=$proven trimmed=$toxic cautious=$cautious"
    }

    /**
     * V5.0.6249 — HARD VETO for proven-toxic buckets.
     *
     * Operator (2026-07-13, V5.0.6247 report): "winrates dropped off badly
     * in paper. under 50% and rolling at 17%".
     *
     * Report showed 7 LosingPatternMemory buckets with 14-72 losses each
     * (MOONSHOT|S41-60 72L/99 72.7%, SHITCOIN|S0-10 25L/34 73%, TREASURY|
     * S0-10 20L/21 95%, EXPRESS|S0-10 14L/14 100%, BLUECHIP|S0-10 17L/21
     * 81%, PROJECT_SNIPER|S0-19 45L/53 85%). LaneBucketPivot trimmed
     * exactly ONE (PRESALE_SNIPE) because everything else was covered by
     * `asymmetric_runner_exempt_6068` in LiveStrategyTuner which held
     * MOONSHOT/SHITCOIN at size×=1.00 despite 17-19% WR.
     *
     * Doctrine says LaneBucketPivot never blocks — but the trims are
     * being ignored by exempt tactics. shouldVeto is a NEW harder gate
     * the caller MUST honour: when a bucket has ≥15 losses AND
     * ≥60% loss rate AND mean PnL ≤ -15%, we block new BUYs entirely.
     * Not advisory. Real-money buckets keep bleeding otherwise.
     */
    fun shouldVeto(lane: String, score: Int): Pair<Boolean, String> {
        val laneU = lane.uppercase()
        val s = try { LosingPatternMemory.stats(laneU, score) } catch (_: Throwable) { return false to "" }
        val sample = s.sample
        val losses = s.losses
        if (sample < 20) return false to ""
        val lossRate = if (sample > 0) losses.toDouble() / sample else 0.0
        val meanPnl = s.meanPnl
        val catastrophic = losses >= 15 && lossRate >= 0.60 && meanPnl <= -15.0
        if (!catastrophic) return false to ""
        val band = bandFor(score)
        val reason = "TOXIC_BUCKET_HARD_VETO_6249 bucket=${laneU}|$band losses=$losses sample=$sample lossRate=${"%.0f".format(lossRate * 100.0)}% meanPnl=${"%.0f".format(meanPnl)}%"
        try {
            ForensicLogger.lifecycle("TOXIC_BUCKET_HARD_VETO_6249", reason)
            PipelineHealthCollector.labelInc("TOXIC_BUCKET_HARD_VETO_6249")
            PipelineHealthCollector.labelInc("TOXIC_BUCKET_HARD_VETO_${laneU}_${band}")
        } catch (_: Throwable) {}
        return true to reason
    }

    fun reportBlock(): String {
        if (recentShapes.isEmpty()) return "  (no lane×bucket pivots observed yet)"
        val sb = StringBuilder()
        val trimmed = recentShapes.values.filter { it.mult < 1.0 }.sortedBy { it.mult }.take(6)
        val pressed = recentShapes.values.filter { it.mult > 1.0 }.sortedByDescending { it.mult }.take(6)
        if (trimmed.isNotEmpty()) {
            sb.appendLine("  Pivoting AWAY from (trim size, don't veto):")
            trimmed.forEach { sb.appendLine("    ${it.bucket.padEnd(28)} mult=${"%.2f".format(it.mult)}  ${it.reason}") }
        }
        if (pressed.isNotEmpty()) {
            sb.appendLine("  Pivoting TOWARD (press size on proven winners):")
            pressed.forEach { sb.appendLine("    ${it.bucket.padEnd(28)} mult=${"%.2f".format(it.mult)}  ${it.reason}") }
        }
        return sb.toString().trimEnd()
    }

    // ─── internals ────────────────────────────────────────────────────────────

    private fun bandFor(score: Int): String {
        val s = score.coerceIn(0, 100)
        val lo = (s / BUCKET_WIDTH) * BUCKET_WIDTH
        val hi = lo + BUCKET_WIDTH - 1
        return "S$lo-$hi"
    }

    private fun compute(laneU: String, score: Int, band: String, bucket: String): PivotShape {
        // ── Read danger stats from LosingPatternMemory (per-bucket) ─────────
        val dangerStats = try {
            LosingPatternMemory.stats(laneU, score)
        } catch (_: Throwable) { null }

        // V5.0.6284 — NET-EV COUNTERWEIGHT. Before honoring 'isDangerous',
        // also read the WINNER histogram for this bucket. If the bucket has
        // strong winners that outweigh the losses in aggregate PnL, treat
        // it as a proven winner not a danger. Memecoin distributions have
        // massive asymmetry — 1 winner covers 10-20 dust rugs — and the
        // prior code discarded that entire pattern.
        val (winnerAvg, winnerCount) = winnerMeanForBucket(laneU, score)
        val bucketNetEv = if (dangerStats != null) {
            (winnerCount * winnerAvg) + (dangerStats.losses * dangerStats.meanPnl)
        } else Double.NaN

        if (dangerStats != null && dangerStats.isDangerous && (bucketNetEv.isNaN() || bucketNetEv <= 0.0)) {
            val losses = dangerStats.losses
            val mult = if (losses >= 10) 0.25 else 0.50
            val tag = if (mult <= 0.30) "CONFIRMED_TOXIC" else "DANGER"
            return PivotShape(mult, "$tag losses=$losses wins=${dangerStats.wins} μ=${"%.0f".format(dangerStats.meanPnl)}%", bucket)
        }
        if (dangerStats != null && dangerStats.isDangerous && bucketNetEv > 0.0) {
            // Danger stats say bleeder but winner side proves net-positive.
            // Log the override so operators can audit and keep at NEUTRAL 1.0.
            try {
                ForensicLogger.lifecycle(
                    "LANE_BUCKET_PIVOT_NET_EV_POSITIVE_OVERRIDE_6284",
                    "bucket=$bucket losses=${dangerStats.losses} wins=$winnerCount winnerAvg=${"%.1f".format(winnerAvg)}% loserMean=${"%.1f".format(dangerStats.meanPnl)}% netEV=${"%.1f".format(bucketNetEv)}% action=allow_neutral",
                )
                PipelineHealthCollector.labelInc("LANE_BUCKET_PIVOT_NET_EV_POSITIVE_OVERRIDE_6284")
            } catch (_: Throwable) {}
            return PivotShape(1.0, "NET_EV_POSITIVE_OVERRIDE wins=$winnerCount μ=${"%.0f".format(winnerAvg)}%", bucket)
        }

        // ── Read winning buckets from LiveWinDNAStore ────────────────────────
        if (winnerCount >= 2 && winnerAvg >= 100.0) {
            return PivotShape(1.35, "DEEP_WINNER wins=$winnerCount μ=${"%.0f".format(winnerAvg)}%", bucket)
        }
        if (winnerCount >= 2 && winnerAvg >= 25.0) {
            return PivotShape(1.15, "PROVEN_WINNER wins=$winnerCount μ=${"%.0f".format(winnerAvg)}%", bucket)
        }

        // ── Cautious tilt when lane has overall negative expectancy ──────────
        val laneBleeder = try {
            LaneExpectancyDamper.sizeMultiplier(laneU) < 0.90
        } catch (_: Throwable) { false }
        if (laneBleeder) {
            return PivotShape(0.85, "CAUTIOUS lane-bleeder overall", bucket)
        }

        return PivotShape(1.0, "NEUTRAL no signal", bucket)
    }

    private fun normaliseBucketKey(k: String): String {
        val parts = k.split("|")
        return if (parts.size >= 2) "${parts[0].uppercase()}|${parts[1]}" else k.uppercase()
    }

    private fun winnerMeanForBucket(laneU: String, score: Int): Pair<Double, Int> {
        // V5.0.6244 — allow backfilled rows (entryScore=0) to count for the
        // lane-level average. Previously the score>0 filter starved the
        // PROVEN_WINNER / DEEP_WINNER branch, so LaneBucketPivot was
        // trim-only (report showed pressed=0 across the whole session).
        //
        // V5.0.6357 — REMOVED whole-lane fallback for the PROVEN/DEEP branches.
        //   Operator (V5.0.6308 emergency dump, WR -20%):
        //     LIFECYCLE/PIVOT_DEEP_WINNER_6240  sym=MAY lane=STANDARD
        //     band=S0-19 score=0 mult=1.35 reason=DEEP_WINNER wins=121 μ=244%
        //   Every score=0 pump.fun token was getting 1.35× upsizing because
        //   the whole-lane fallback pooled STANDARD lane's 121 wins @ 244%
        //   (mostly from S40-60 band tokens) into the S0-19 bucket. That
        //   upsized garbage entries and drove the WR drop.
        //   Fix: require ≥2 winner-DNA rows IN THE SAME BAND. Whole-lane rows
        //   remain as CONTEXT for the score>0 case (via the >0 filter inside
        //   bandRows), never as a substitute for band-specific evidence.
        return try {
            val rows = LiveWinDNAStore.topByPnl(500)   // memoised snapshot
            val laneRows = rows.filter { it.lane.uppercase() == laneU || it.phase.uppercase() == laneU }
            val bandRows = laneRows.filter { r ->
                val s = r.entryScore
                s > 0 && (s / BUCKET_WIDTH) * BUCKET_WIDTH == (score / BUCKET_WIDTH) * BUCKET_WIDTH
            }
            if (bandRows.size < 2) 0.0 to 0
            else bandRows.map { it.pnlPct }.average() to bandRows.size
        } catch (_: Throwable) { 0.0 to 0 }
    }
}
