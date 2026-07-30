package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * V5.0.6397 — ADAPTIVE FLOOR BRAIN.
 *
 * The V5.0.6396 canonical scale (BASELINE=15, CAUTION=17, TIGHTENED=20,
 * ABSOLUTE_MIN=12, ABSOLUTE_MAX=22) defines the *envelope*.  This module
 * makes the actual live floor *fluid* within that envelope, guided by:
 *
 *   • Scanner heat            — how many hydrated hard-safety-passed
 *                               candidates the scanner is producing per
 *                               unit time.  Hot markets pull the floor
 *                               UP toward saturating executions; cold
 *                               markets pull DOWN toward participation.
 *   • Score distribution      — the p45..p60 window of hard-safety-passed
 *                               executable candidates (baseline should
 *                               sit near median).
 *   • Lane learning (EV/WR)   — a lane demonstrating positive expectancy
 *                               may relax by up to POSITIVE_LANE_MAX_RELAX
 *                               points.
 *   • Governor tier           — BASELINE / CAUTION / SOFT_TIGHT / RECOVERY
 *                               / TIGHTENED / HOLD contributes the base
 *                               tier floor (drives absolute anchoring).
 *   • SuperAGI advice         — the SuperAGI adaptive controller may push
 *                               a signed advisory delta (bounded).
 *   • SSI advice              — same, distinct channel.
 *   • LLM advice              — same, distinct channel.
 *
 * The recommendation is always CLAMPED through
 * LiveEntryThresholdAuthority6396.clampFloor(...) — legacy 55/56 remain
 * mechanically unreachable.
 *
 * The brain is thread-safe, side-effect-free (except counter emits) and
 * a snapshot may be built at any time.  Callers (LiveEntrySafetyHold,
 * FinalDecisionGate score-floor gate) refresh at their natural cadence
 * — the brain does not tick on its own.
 */
object AdaptiveFloorBrain6397 {

    /** Maximum signed delta any single advisory channel may push. */
    const val ADVISORY_MAX_DELTA: Int = 3
    /** Maximum absolute delta the combined advisory stack may apply. */
    const val ADVISORY_COMBINED_CAP: Int = 5
    /** Time after which stale advisories are ignored. */
    const val ADVISORY_STALE_MS: Long = 5 * 60_000L

    /** Positive-expectancy lane relaxation (mirrors 6396 rule; max 2 pts). */
    const val LANE_RELAX_MAX: Int = LiveEntryThresholdAuthority6396.POSITIVE_LANE_MAX_RELAX

    /** Scanner heat quantiles — hot/very hot pull the floor up. */
    const val SCANNER_HEAT_HOT: Double = 0.65     // >65th pct of window heat
    const val SCANNER_HEAT_VERY_HOT: Double = 0.90

    /** Minimum lane trades for lane-EV bias to activate. */
    const val LANE_TRADES_FOR_BIAS: Int = 8

    // -------- ADVISORY CHANNELS --------------------------------------------

    enum class AdvisoryChannel { SUPER_AGI, SSI, LLM }

    data class Advisory(
        val channel: AdvisoryChannel,
        val signedDelta: Int,           // clamped to [-ADVISORY_MAX_DELTA, +ADVISORY_MAX_DELTA]
        val reason: String,
        val issuedAtMs: Long = System.currentTimeMillis(),
    )

    private val advisories = ConcurrentHashMap<AdvisoryChannel, Advisory>()

    /** Post an advisory. Deltas beyond ADVISORY_MAX_DELTA are clamped. */
    fun postAdvisory(channel: AdvisoryChannel, delta: Int, reason: String,
                     nowMs: Long = System.currentTimeMillis()) {
        val clamped = delta.coerceIn(-ADVISORY_MAX_DELTA, ADVISORY_MAX_DELTA)
        advisories[channel] = Advisory(channel, clamped, reason, nowMs)
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ADAPTIVE_FLOOR_ADVISORY_${channel.name}_6397") } catch (_: Throwable) {}
    }

    fun clearAdvisory(channel: AdvisoryChannel) { advisories.remove(channel) }

    fun activeAdvisories(nowMs: Long = System.currentTimeMillis()): List<Advisory> =
        advisories.values.filter { nowMs - it.issuedAtMs <= ADVISORY_STALE_MS }.toList()

    // -------- LANE LEARNING FEED -------------------------------------------

    data class LaneStat(
        val lane: String,
        val trades: Int,
        val winRatePct: Double,
        val expectancySol: Double,
        val updatedAtMs: Long = System.currentTimeMillis(),
    )
    private val laneStats = ConcurrentHashMap<String, LaneStat>()

    fun postLaneStat(stat: LaneStat) { laneStats[stat.lane.uppercase()] = stat }
    fun laneStat(lane: String): LaneStat? = laneStats[lane.uppercase()]

    // -------- SCANNER HEAT FEED --------------------------------------------

    /**
     * Rolling scanner heat — callers push a 0.0..1.0 percentile relative to
     * a recent window (e.g. hydrated_all sample count vs its own p90).
     */
    private val scannerHeat = AtomicReference(0.0)
    fun postScannerHeat(pct01: Double) { scannerHeat.set(pct01.coerceIn(0.0, 1.0)) }
    fun scannerHeat(): Double = scannerHeat.get()

    // -------- BRAIN RECOMMENDATION -----------------------------------------

    data class BrainInputs(
        val governorTier: LiveEntryThresholdAuthority6396.GovernorTier,
        val lane: String,
        val scannerHeatPct01: Double = scannerHeat(),
        val nowMs: Long = System.currentTimeMillis(),
    )

    data class BrainRecommendation(
        val baseFloor: Int,
        val laneDelta: Int,
        val scannerDelta: Int,
        val distributionDelta: Int,
        val advisoryDelta: Int,
        val proposedFloor: Int,        // pre-clamp
        val finalFloor: Int,           // clamped through 6396 authority
        val notes: List<String>,
    )

    /**
     * Compute a fluid floor recommendation. The caller assembles this into
     * a full EntryThresholdSnapshot via LiveEntryThresholdAuthority6396
     * (which does the final clamp again as a defense-in-depth).
     */
    fun recommend(inp: BrainInputs): BrainRecommendation {
        val notes = mutableListOf<String>()
        val base = LiveEntryThresholdAuthority6396.baseFloorFor(inp.governorTier)
        notes += "base=${inp.governorTier.name}:${base}"

        // (a) Distribution bias — pull toward the p50 of hard-safety-passed
        //     samples so we sit inside the natural score curve.
        val hs = ScoreDistributionHistogram6396.percentiles(
            ScoreDistributionHistogram6396.Bucket.HARD_SAFETY_PASSED)
        val distributionDelta: Int = if (hs.samples >= ScoreDistributionHistogram6396.MIN_SAMPLES_FOR_ADAPT) {
            val target = hs.median.roundToInt()
            val diff = (target - base).coerceIn(-2, 2)    // gentle nudge, ±2 pts
            notes += "dist_target=${target}_diff=${diff}"
            diff
        } else {
            notes += "dist_insufficient_samples(${hs.samples})"
            0
        }

        // (b) Lane learning bias — positive expectancy relaxes down to
        //     POSITIVE_LANE (13), negative expectancy tightens up.
        val laneDelta: Int = laneStats[inp.lane.uppercase()]?.let { st ->
            if (st.trades < LANE_TRADES_FOR_BIAS) {
                notes += "lane_insufficient_trades(${st.trades})"; 0
            } else if (st.expectancySol > 0.0 && st.winRatePct >= 55.0) {
                notes += "lane_pos_ev_relax_${LANE_RELAX_MAX}"; -LANE_RELAX_MAX
            } else if (st.expectancySol < 0.0 && st.winRatePct < 40.0) {
                notes += "lane_neg_ev_tighten_+2"; +2
            } else {
                notes += "lane_neutral"; 0
            }
        } ?: run { notes += "lane_no_stats"; 0 }

        // (c) Scanner heat bias — very hot scanner → +1 to +2 (more
        //     selective).  Cool scanner → -1 (participate).
        val scannerDelta: Int = when {
            inp.scannerHeatPct01 >= SCANNER_HEAT_VERY_HOT -> { notes += "scanner_very_hot+2"; +2 }
            inp.scannerHeatPct01 >= SCANNER_HEAT_HOT     -> { notes += "scanner_hot+1"; +1 }
            inp.scannerHeatPct01 <= 0.20                 -> { notes += "scanner_cool-1"; -1 }
            else -> { notes += "scanner_neutral"; 0 }
        }

        // (d) Advisory stack — SUPER_AGI + SSI + LLM sum, capped.
        val active = activeAdvisories(inp.nowMs)
        val advisoryRaw = active.sumOf { it.signedDelta }
        val advisoryDelta = advisoryRaw.coerceIn(-ADVISORY_COMBINED_CAP, ADVISORY_COMBINED_CAP)
        if (active.isNotEmpty()) notes += "advisory(${active.joinToString(",") { "${it.channel.name}${if (it.signedDelta>=0) "+" else ""}${it.signedDelta}" }})=${advisoryDelta}"

        val proposed = base + distributionDelta + laneDelta + scannerDelta + advisoryDelta
        val finalFloor = LiveEntryThresholdAuthority6396.clampFloor(proposed)
        if (finalFloor != proposed) notes += "CLAMPED_${proposed}->${finalFloor}"

        return BrainRecommendation(
            baseFloor = base, laneDelta = laneDelta, scannerDelta = scannerDelta,
            distributionDelta = distributionDelta, advisoryDelta = advisoryDelta,
            proposedFloor = proposed, finalFloor = finalFloor, notes = notes,
        )
    }

    /** Convenience — recommend + build the canonical snapshot in one step. */
    fun snapshotFor(
        decisionId: String,
        mint: String,
        lane: String,
        rawScore: Double,
        effectiveScore: Double,
        governorTier: LiveEntryThresholdAuthority6396.GovernorTier,
        metricEpoch: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): LiveEntryThresholdAuthority6396.EntryThresholdSnapshot {
        val rec = recommend(BrainInputs(governorTier, lane, scannerHeat(), nowMs))
        // Feed the individual deltas into the canonical snapshot so
        // downstream inspectors can see exactly which channel moved the
        // floor.  6396 clamps again as defense-in-depth.
        return LiveEntryThresholdAuthority6396.snapshot(
            decisionId = decisionId, mint = mint, lane = lane,
            rawScore = rawScore, effectiveScore = effectiveScore,
            governorTier = governorTier,
            governorDelta = rec.scannerDelta,       // scanner_heat lives in the governor slot
            laneDelta = rec.laneDelta,
            personalityDelta = rec.distributionDelta,
            volatilityDelta = rec.advisoryDelta,    // advisories occupy the volatility slot
            metricEpoch = metricEpoch,
        )
    }

    internal fun clearAllForTest() {
        advisories.clear(); laneStats.clear(); scannerHeat.set(0.0)
    }
}
