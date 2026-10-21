package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8298 §RESTORE_THE_8114_DOORS — the profitability regression audit (lifecycle_apk/AUDIT_REGRESSION_8298.md).
 *
 * The operator's profitable session was builds 8113–8114 (379ad2fa0, 15 Oct 2026: ~40 % wins, cash doubled —
 * audits/repair8198/8114-comparison.md). Between 8201 and 8256 a candidate-specific ADMIT (CandidateAdmission8136,
 * graded on forward LABELS) was allowed to stand down, one by one, the learned doors that selected the 8114 entries:
 *   8201 timing / picked-setup / tactic negatives (LiveEdgeGate7877), 8243 the spike-chase rule and the fresh-launch
 *   stops-first prior (FDG), 8244 the cell proof ladder (FDG), 8256 the lane shape bins and the watch-first negative
 *   cell. And 8169 widened every loss backstop (fresh meme 9 % -> the ordinary stop up to 25 %, hard floor 15 % -> up
 *   to 30 %, MOONSHOT's first-12-minute stop 15 % -> up to 25 %).
 * 5.0.8286 live: 4/4 closes lost (-33.8 %, -16.7 %, -10.6 %, -9.2 %) while the same label books read +83..+330 %;
 * the admitted cohort's own forward return was +1.8 % (n17), below a ~6 % round trip.
 *
 * The labels that justified each stand-down are not executable outcomes. So each stand-down, and each widened backstop,
 * is now EARNED by the only economic authority there is: the lane's realised whole-position LIVE closes on the canonical
 * bus, mature and positive (LiveSizingTruth8136.edgeMature8136: 10+ clean closes, mean - SE > 0, net SOL > 0). Until a
 * lane has earned it, the 8114 doors and backstops bind. Nothing is reset, nothing is fabricated, no hard-safety rule is
 * touched; this only re-applies refusals and tighter loss backstops that 8114 applied.
 */
object RegressionRestore8298 {
    /** 8114's Executor risk-check hard floor for a meme position in its first five minutes (%). */
    const val FRESH_MEME_BACKSTOP_PCT_8298 = 9.0
    /** 8114's hard floor (rapid monitor and Executor risk check) and MOONSHOT's first-12-minute stop (%). */
    const val BACKSTOP_PCT_8298 = 15.0
    private const val CACHE_MS_8298 = 20_000L

    /** Pure: may the 8201–8256 stand-downs / 8169 widened backstops apply? Only on a mature, positive realised record. */
    fun standDownEarned8298(m: LiveSizingTruth8136.EdgeMaturity8136?): Boolean =
        m != null && LiveSizingTruth8136.edgeMature8136(m)

    /**
     * Pure: the backstop magnitude (positive %) — the widened 8169 value when [earned], else never deeper than the 8114
     * value [base8114]. A non-finite widened value falls back to the 8114 value.
     */
    fun backstopMag8298(widened8169: Double, base8114: Double, earned: Boolean): Double =
        if (!widened8169.isFinite() || widened8169 <= 0.0) base8114
        else if (earned) widened8169 else minOf(widened8169, base8114)

    private val cache8298 = ConcurrentHashMap<String, Pair<Long, Boolean>>()
    private val restored8298 = ConcurrentHashMap<String, AtomicLong>()

    /** The lane's clean live whole-position closes on the canonical bus (null on a fault: not earned). */
    private fun laneRealised8298(lane: String): LiveSizingTruth8136.EdgeMaturity8136? = try {
        val key = canon(lane)
        val envs = CanonicalFinalizedTradeBus6464.terminalEnvelopes7863("live")
            .filter { it.learningEligible && canon(it.lane) == key }
        LiveSizingTruth8136.maturityOf8136(envs.map { it.realizedReturnPct }, envs.map { it.realizedPnlSol })
    } catch (_: Throwable) { null }

    /** Has [lane] earned the stand-downs on its realised live closes? Cached [CACHE_MS_8298]; false on any fault. */
    fun specificStandDownEarned8298(lane: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val key = canon(lane)
        cache8298[key]?.let { (at, v) -> if (nowMs - at in 0L..CACHE_MS_8298) return v }
        val v = try { standDownEarned8298(laneRealised8298(key)) } catch (_: Throwable) { false }
        if (cache8298.size > 64) cache8298.clear()
        cache8298[key] = nowMs to v
        return v
    }

    /** Count one door / backstop the restore kept binding ([door] e.g. CHASE, SHAPE, CELL_PROOF, FRESH_MEME_BACKSTOP). */
    fun note8298(door: String, lane: String) {
        try {
            val k = "${door}_${canon(lane)}".take(64)
            restored8298.computeIfAbsent(k) { AtomicLong(0) }.incrementAndGet()
            PipelineHealthCollector.labelInc("REGRESSION_RESTORE_8298_$k")
        } catch (_: Throwable) {}
    }

    /**
     * FDG (cellProofBlock7731 / freshLaunchBlock7737): may a candidate-specific ADMIT stand down the learned refusal
     * [raw] (8244 cell proof ladder, 8243 fresh-launch stops-first prior)? True when there is nothing to stand down or the
     * lane earned it; otherwise the refusal binds as in 8114 and is counted.
     */
    fun restoredDoorStandsDown8298(raw: String?, lane: String): Boolean {
        if (raw == null) return true
        if (specificStandDownEarned8298(lane)) return true
        note8298(raw.substringBefore("_7").take(32), lane)
        return false
    }

    /**
     * The position's backstop (positive %) for [lane]: 8169's [widened8169] if the lane earned it, else the 8114 [base8114].
     * Counts a restored (tightened) backstop.
     */
    fun backstopFor8298(lane: String, widened8169: Double, base8114: Double, door: String): Double {
        val earned = specificStandDownEarned8298(lane)
        val out = backstopMag8298(widened8169, base8114, earned)
        if (widened8169.isFinite() && out < widened8169 - 1e-9) note8298(door, lane)
        return out
    }

    fun statusLine8298(): String =
        "restore8298[earned=${cache8298.entries.filter { it.value.second }.joinToString(",") { it.key }.ifBlank { "none" }} " +
            "kept=${restored8298.entries.sortedByDescending { it.value.get() }.take(12).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}]"

    private fun canon(lane: String?): String =
        try { CanonicalLaneIdentity6506.canonical(lane).uppercase().ifBlank { lane?.trim()?.uppercase().orEmpty() } }
        catch (_: Throwable) { lane?.trim()?.uppercase().orEmpty() }
}
