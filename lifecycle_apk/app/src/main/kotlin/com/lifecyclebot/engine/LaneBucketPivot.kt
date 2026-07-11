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

        if (dangerStats != null && dangerStats.isDangerous) {
            val losses = dangerStats.losses
            val mult = if (losses >= 10) 0.25 else 0.50
            val tag = if (mult <= 0.30) "CONFIRMED_TOXIC" else "DANGER"
            return PivotShape(mult, "$tag losses=$losses wins=${dangerStats.wins} μ=${"%.0f".format(dangerStats.meanPnl)}%", bucket)
        }

        // ── Read winning buckets from LiveWinDNAStore ────────────────────────
        val (winnerAvg, winnerCount) = winnerMeanForBucket(laneU, score)
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
        // LiveWinDNAStore doesn't currently store entry-score directly for all
        // rows (backfill rows have score=0). Best-effort: aggregate by lane
        // only when score matches an occupied band; otherwise fall through to
        // lane-level average for the band.
        return try {
            val rows = LiveWinDNAStore.topByPnl(500)   // whole corpus
            val laneRows = rows.filter { it.lane.uppercase() == laneU || it.phase.uppercase() == laneU }
            val bandRows = laneRows.filter { r ->
                val s = r.entryScore
                s > 0 && (s / BUCKET_WIDTH) * BUCKET_WIDTH == (score / BUCKET_WIDTH) * BUCKET_WIDTH
            }
            val useRows = if (bandRows.size >= 2) bandRows else laneRows
            if (useRows.size < 2) 0.0 to 0
            else useRows.map { it.pnlPct }.average() to useRows.size
        } catch (_: Throwable) { 0.0 to 0 }
    }
}
