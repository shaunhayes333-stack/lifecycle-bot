package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8424 §A_LOSING_LANE_SIZES_DOWN_IT_DOES_NOT_HALT.
 *
 * 5.0.8422 report: `realised8295[lanesLosing{MOONSHOT n3/-21.3%}]` and, in 8.5 minutes, MOONSHOT refused
 * LIVE_LANE_REALISED_LOSING_8325 504 times and ENTRY8114_LANE_REALISED_LOSING_BINDS_8329 245 times. The runner lane was shut
 * by THREE live closes — the 8418 stops that fired on single 21–25x prints and sold into fills the mark contradicted — and a
 * shut lane has no way back: it opens nothing, so no close can ever satisfy EntryEconomics8329.reopens8329 (a win AFTER the
 * shut, or the last five closes averaging >= 0). Three closes are not a record either way (SE of a three-sample mean of meme
 * returns is tens of points).
 *
 * Owner policy: small stakes trade (DIRECTIVE_LEDGER_8367 #33), size down not halt (KillSwitch SIZE_DOWN_NOT_HALT_7864), and
 * live size reads realised live closes only (#36) — a lane without a positive realised record already sizes at the
 * route-minimum stake (LiveSizingTruth8136.edgeMature8136 needs mean - SE > 0 and net SOL > 0).
 *
 * Rule: a lane whose realised live closes are losing (LaneRealisedGovernor8295: 3+, mean < 0) is in PROBE, not shut:
 *  - it opens at most ONE live position at a time (a probe admitted but not yet open holds the slot for [CLAIM_MS_8424]);
 *  - every probe is a real close on the lane's record — a win re-opens the lane (8329), a loss keeps it probing;
 *  - a lane PROVEN losing ([PROVEN_MIN_N_8424]+ closes, mean + 2 SE < 0) probes no more than once per [PROVEN_PROBE_GAP_MS_8424],
 *    measured from its last close after the shut (an open probe is already "occupied").
 * Nothing else is relaxed: hard safety, economics, data, the setup / timing / cell records and the lane's own realised
 * sizing all still bind on the probe candidate. Paper is not judged here (the realised governor is live-only).
 */
object LaneRealisedProbe8424 {
    const val PROVEN_MIN_N_8424 = 10
    const val PROVEN_PROBE_GAP_MS_8424 = 2L * 60L * 60_000L
    /** A probe admitted but not yet open holds the lane's one slot this long (the buy is in flight; a failed buy frees it). */
    const val CLAIM_MS_8424 = 90_000L
    /** Both names keep "LANE_REALISED_LOSING": LiveEdgeGate7877's shadowOnly8393 still opens the evidence shadow on them. */
    const val OCCUPIED_8424 = "LANE_REALISED_LOSING_PROBE_OCCUPIED_8424"
    private const val PROVEN_WAIT_8424 = "LANE_REALISED_LOSING_PROVEN_PROBE_WAIT_8424"

    enum class Verdict8424 { PROBE, OCCUPIED, PROVEN_WAIT }

    /** Pure: [n, mean %, SE %] of the realised returns (SE = +inf below two closes). */
    fun stats8424(returnsPct: List<Double>): DoubleArray {
        val r = returnsPct.filter { it.isFinite() }
        val n = r.size
        if (n == 0) return doubleArrayOf(0.0, Double.NaN, Double.POSITIVE_INFINITY)
        val mean = r.average()
        val se = if (n > 1) kotlin.math.sqrt(r.sumOf { (it - mean) * (it - mean) } / (n - 1) / n) else Double.POSITIVE_INFINITY
        return doubleArrayOf(n.toDouble(), mean, se)
    }

    /** Pure: proven losing — [PROVEN_MIN_N_8424]+ closes and mean + 2 SE below zero. Three closes prove nothing. */
    fun provenLosing8424(returnsPct: List<Double>): Boolean {
        val s = stats8424(returnsPct)
        return s[0] >= PROVEN_MIN_N_8424 && s[1].isFinite() && s[2].isFinite() && s[1] + 2.0 * s[2] < 0.0
    }

    /**
     * Pure: the verdict for a lane the caller found SHUT (3+ closes, mean < 0, not re-opened). [openInLane] live positions
     * open in the lane now; [claimedAgoMs] ms since a probe was admitted for ANOTHER coin (negative = none);
     * [lastCloseAgoMs] ms since the lane's last close after the shut (negative = none yet).
     */
    fun verdict8424(returnsPct: List<Double>, openInLane: Int, claimedAgoMs: Long, lastCloseAgoMs: Long): Verdict8424 {
        if (openInLane > 0 || claimedAgoMs in 0L until CLAIM_MS_8424) return Verdict8424.OCCUPIED
        if (provenLosing8424(returnsPct) && lastCloseAgoMs in 0L until PROVEN_PROBE_GAP_MS_8424) return Verdict8424.PROVEN_WAIT
        return Verdict8424.PROBE
    }

    // ── runtime ──
    private class Claim8424(val mint: String, val atMs: Long)
    private val claims = ConcurrentHashMap<String, Claim8424>()
    private val probes = AtomicLong(0)
    private val occupied = AtomicLong(0)
    private val provenWaits = AtomicLong(0)

    private fun openInLane8424(lane: String): Int = try {
        CanonicalPositionAuthority6441.openPositions().count {
            it.mode.equals("live", ignoreCase = true) && CanonicalLaneIdentity6506.desk8392(it.lane) == lane
        }
    } catch (_: Throwable) { 0 }

    private fun ownClaim8424(lane: String, mint: String, nowMs: Long): Boolean {
        val c = claims[lane] ?: return false
        return mint.isNotBlank() && c.mint == mint && nowMs - c.atMs in 0L until CLAIM_MS_8424
    }

    /**
     * EntryEconomics8329.laneRealisedRefusal8329: may [mint] enter the shut lane [lane] as its probe now? Null = the door is
     * open to it; otherwise the refusal. This READS only: the slot is claimed by [claim8424] when the FDG actually admits the
     * coin live (a candidate refused by another door must not hold the lane for 90 s). The lane's admitted probe re-asked
     * inside its claim keeps its admission (the FDG asks more than once per decision).
     * [closes] = (atMs, realised SOL, realised %) of the lane's live closes; [shutSinceMs] when the shut was first seen.
     */
    fun probe8424(lane: String, mint: String, closes: List<Triple<Long, Double, Double>>, shutSinceMs: Long, nowMs: Long): String? {
        if (ownClaim8424(lane, mint, nowMs)) return null
        val c = claims[lane]
        val claimedAgo = if (c != null && c.mint != mint) nowMs - c.atMs else -1L
        val lastClose = closes.filter { it.first > shutSinceMs }.maxOfOrNull { it.first }
        val lastCloseAgo = if (lastClose != null) nowMs - lastClose else -1L
        return when (verdict8424(closes.map { it.third }, openInLane8424(lane), claimedAgo, lastCloseAgo)) {
            Verdict8424.PROBE -> null
            Verdict8424.OCCUPIED -> { occupied.incrementAndGet(); "${OCCUPIED_8424}_$lane" }
            Verdict8424.PROVEN_WAIT -> { provenWaits.incrementAndGet(); "${PROVEN_WAIT_8424}_$lane" }
        }
    }

    /**
     * V5.0.8429 — CandidateAdmission8136: is [lane]'s one probe slot open to [mint] now (its own fresh claim, or no live
     * position open in the lane and no other coin's fresh claim)? A learned-negative record door then sizes down instead of
     * refusing. Read-only; the slot is claimed on the live admission ([claim8424]).
     */
    fun slotOpen8429(lane: String, mint: String, nowMs: Long): Boolean {
        val l = CanonicalLaneIdentity6506.desk8392(lane)
        if (ownClaim8424(l, mint, nowMs)) return true
        val c = claims[l]
        if (c != null && c.mint != mint && nowMs - c.atMs in 0L until CLAIM_MS_8424) return false
        return openInLane8424(l) == 0
    }

    /**
     * LiveEdgeGate7877.liveRefusal, on a LIVE admission: [mint] holds [lane]'s one probe slot for [CLAIM_MS_8424] while its
     * buy is in flight (an open position then keeps the lane occupied; a failed buy frees it). Cheap on an open lane: the
     * claim is only ever read while the lane is shut.
     */
    fun claim8424(lane: String, mint: String, nowMs: Long) {
        if (lane.isBlank() || mint.isBlank()) return
        val prior = claims.put(lane, Claim8424(mint, nowMs))
        if (prior?.mint == mint && nowMs - prior.atMs in 0L until CLAIM_MS_8424) return   // the same admission re-noted
        probes.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LANE_REALISED_PROBE_ADMITTED_8424_$lane") } catch (_: Throwable) {}
        try {
            com.lifecyclebot.engine.ForensicLogger.lifecycle("LANE_REALISED_PROBE_8424",
                "lane=$lane mint=${mint.take(10)} action=one_small_stake_probe_record_decides")
        } catch (_: Throwable) {}
    }

    fun statusLine8424(nowMs: Long = System.currentTimeMillis()): String {
        val live = claims.entries.filter { nowMs - it.value.atMs in 0L until CLAIM_MS_8424 }
            .joinToString(",") { "${it.key}:${it.value.mint.take(6)}" }.ifBlank { "-" }
        return "laneProbe8424[admitted=${probes.get()} occupied=${occupied.get()} provenWaits=${provenWaits.get()} claims=$live " +
            "rule=losing lane probes 1 at a time at the small stake; proven(n>=$PROVEN_MIN_N_8424,mean+2se<0) every ${PROVEN_PROBE_GAP_MS_8424 / 3_600_000L}h]"
    }

    internal fun resetForTests8424() { claims.clear(); probes.set(0); occupied.set(0); provenWaits.set(0) }
}
