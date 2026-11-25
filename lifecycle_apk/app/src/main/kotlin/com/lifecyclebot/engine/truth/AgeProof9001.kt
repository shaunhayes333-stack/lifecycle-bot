package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.9001 §THE_PROOF_IS_READ_AT_THE_COINS_OWN_AGE — owner, 25 Nov 15:04: "the runner capture needs to stop buying the
 * tops ... we can see from all of the data how runners form and why we keep missing them".
 *
 * Measured on the 25 Nov 12:01 export (aate_edge_dataset_8537: 50,586 decisions labelled at 15 minutes, 6,047 coins, 70 h):
 *  - admitted decisions on a coin under 15 minutes old: n 3,510, mean +17 % to +34 % by cap band, both halves positive;
 *  - admitted decisions on a coin an hour old or more: n 2,975 (41 % of every admission), mean -3.9 % +- 0.5, both halves
 *    negative, 2.4 % of them reach +40 %; no column of the 23,448 rows on such coins holds a cell that pays on both halves;
 *  - every lane the same way: MOONSHOT +19.7 % under 15 minutes (n 851) / -2.4 % past an hour (n 1,200), EXPRESS +16.1 /
 *    -4.4, SHITCOIN +21.7 / -6.2, CORE +15.5 / -4.8;
 *  - live, same window: 30 of 43 matched buys were on coins past their launch phase (3 won, -0.085 SOL); the 7 bought while
 *    the coin was still expanding won 5 (+0.041 SOL). 45 of the 54 graded live receipts came through SPECIFIC_EDGE_PROVEN,
 *    36 of them graded LOSS_BOUGHT_TOP.
 *
 * So the "proof" behind those buys is a record pooled over every age: a setup, timing band, Cortex cohort or runner grab
 * earns its mean on coins minutes old and it is spent on coins hours or days old. This object measures, per lane, how far
 * the admitted labels of the coin's own age band run below the lane's pooled admitted labels, and that gap comes off the
 * POOLED records at the support tests (CandidateAdmission8136.Candidate8136.pooledShortfall9001), exactly as the realised
 * live shortfall does (LiveAdmissionProof8290). It adds no refusal: a record that still clears its bar after the gap admits
 * as before, one that does not is simply not proof for this coin and the candidate goes on to the lane's ordinary checks.
 * The coin's own cell (source x lane x cap x age) carries its age and is not shifted. The pockets are not shifted either,
 * but 9003 found they do NOT carry an age: LanePocket8934 now withholds an age-blind cell in a band the lane loses in. A band that
 * pays as well as the lane has no gap; nothing is added for a band that pays more. It follows the labels, so a band that
 * starts paying loses its gap. Memory only, rebuilt from the dataset replay at start.
 */
object AgeProof9001 {
    const val MIN_BAND_N_9001 = 100
    const val MIN_LANE_N_9001 = 300
    /** One label counts for at most this much, so a single +8,000 % print cannot set a lane's mean. */
    const val LABEL_CAP_PCT_9001 = 300.0
    const val MAX_GAP_PTS_9001 = 25.0
    private const val ALL = "*"

    /** Pure: the age band of a coin [ageMin] minutes old. An unknown age claims nothing. */
    fun band9001(ageMin: Double): String = when {
        !ageMin.isFinite() || ageMin < 0.0 -> "UNKNOWN"
        ageMin < 15.0 -> "UNDER_15M"
        ageMin < 60.0 -> "15M_1H"
        ageMin < 1_440.0 -> "1H_24H"
        else -> "OVER_24H"
    }

    private class Cell9001 { var n = 0L; var sum = 0.0; var sumSq = 0.0 }
    private val cells = ConcurrentHashMap<String, Cell9001>()

    /** One labelled decision. Only an admitted, filled label is evidence about what an admission earns. */
    fun note9001(lane: String, ageMin: Double, netPct: Double, admitted: Boolean, filled: Boolean) {
        if (!admitted || !filled || !netPct.isFinite()) return
        val l = lane.trim().uppercase()
        if (l.isEmpty()) return
        val band = band9001(ageMin)
        val v = netPct.coerceIn(-100.0, LABEL_CAP_PCT_9001)
        for (k in if (band == "UNKNOWN") listOf("$l|$ALL") else listOf("$l|$ALL", "$l|$band")) {
            val c = cells.computeIfAbsent(k) { Cell9001() }
            synchronized(c) { c.n++; c.sum += v; c.sumSq += v * v }
        }
    }

    /**
     * Pure: the points to take off a pooled record for a coin of this band — the lane's pooled mean less the band's mean
     * plus one standard error, never negative, at most [MAX_GAP_PTS_9001]; 0 until both hold enough labels.
     */
    fun gap9001(laneN: Long, laneMean: Double, bandN: Long, bandMean: Double, bandSe: Double): Double {
        if (laneN < MIN_LANE_N_9001 || bandN < MIN_BAND_N_9001) return 0.0
        if (!laneMean.isFinite() || !bandMean.isFinite() || !bandSe.isFinite()) return 0.0
        return (laneMean - (bandMean + bandSe)).coerceIn(0.0, MAX_GAP_PTS_9001)
    }

    private fun stat(k: String): Triple<Long, Double, Double>? {
        val c = cells[k] ?: return null
        return synchronized(c) {
            if (c.n <= 1L) null else {
                val mean = c.sum / c.n
                val varc = (c.sumSq / c.n - mean * mean).coerceAtLeast(0.0)
                Triple(c.n, mean, Math.sqrt(varc / c.n))
            }
        }
    }

    /** The gap for [lane] at [ageMin] on the labels seen so far. */
    fun gapPts9001(lane: String, ageMin: Double): Double {
        val l = lane.trim().uppercase()
        val band = band9001(ageMin)
        if (band == "UNKNOWN") return 0.0
        val all = stat("$l|$ALL") ?: return 0.0
        val own = stat("$l|$band") ?: return 0.0
        return gap9001(all.first, all.second, own.first, own.second, own.third)
    }

    /** Pure: a band the lane is measured to lose in — [MIN_BAND_N_9001]+ admitted labels whose mean plus one SE is under zero. */
    fun loses9003(bandN: Long, bandMean: Double, bandSe: Double): Boolean =
        bandN >= MIN_BAND_N_9001 && bandMean.isFinite() && bandSe.isFinite() && bandMean + bandSe < 0.0

    /** Does [lane] lose, on its own admitted labels, in the age band of a coin [ageMin] minutes old? Unknown age: no claim. */
    fun bandLoses9003(lane: String, ageMin: Double): Boolean {
        val band = band9001(ageMin)
        if (band == "UNKNOWN") return false
        val own = stat("${lane.trim().uppercase()}|$band") ?: return false
        return loses9003(own.first, own.second, own.third)
    }

    /** Pure: a band the lane is measured to pay in — [MIN_BAND_N_9001]+ admitted labels whose mean less one SE is above zero. */
    fun pays9004(bandN: Long, bandMean: Double, bandSe: Double): Boolean =
        bandN >= MIN_BAND_N_9001 && bandMean.isFinite() && bandSe.isFinite() && bandMean - bandSe > 0.0

    /** Does [lane] pay, on its own admitted labels, in the age band of a coin [ageMin] minutes old? Unknown age: no claim. */
    fun bandPays9004(lane: String, ageMin: Double): Boolean {
        val band = band9001(ageMin)
        if (band == "UNKNOWN") return false
        val own = stat("${lane.trim().uppercase()}|$band") ?: return false
        return pays9004(own.first, own.second, own.third)
    }

    /** CandidateAdmission8136.candidateFor8136: the gap a LIVE candidate carries, counted by band when it is not zero. */
    fun gapFor9001(ts: TokenState, lane: String, live: Boolean): Double {
        if (!live) return 0.0
        val age = try { com.lifecyclebot.engine.TokenMetricStageRouter.snapshot(ts).ageMin } catch (_: Throwable) { Double.NaN }
        val gap = gapPts9001(lane, age)
        if (gap > 0.0) try { PipelineHealthCollector.labelInc("AGE_PROOF_GAP_9001_${band9001(age)}") } catch (_: Throwable) {}
        return gap
    }

    /** The coin's age in minutes as the admission reads it (NaN when unknown). */
    fun ageOf9004(ts: TokenState, nowMs: Long): Double =
        try { CanonicalTokenBirthTime7440.resolvedAgeMinutes(ts, nowMs) ?: Double.NaN } catch (_: Throwable) { Double.NaN }

    /** Report line: per lane, each band's admitted labels and the gap it carries. */
    fun statusLine9001(): String {
        val lanes = cells.keys.map { it.substringBefore('|') }.distinct().sortedByDescending { stat("$it|$ALL")?.first ?: 0L }.take(8)
        val body = lanes.joinToString(" · ") { l ->
            val all = stat("$l|$ALL")
            val bands = listOf("UNDER_15M", "15M_1H", "1H_24H", "OVER_24H").mapNotNull { b ->
                val s = stat("$l|$b") ?: return@mapNotNull null
                val g = if (all == null) 0.0 else gap9001(all.first, all.second, s.first, s.second, s.third)
                "$b=n${s.first}/${"%+.1f".format(s.second)}%${if (g > 0.0) "/gap${"%.1f".format(g)}" else ""}"
            }.joinToString(" ")
            "$l[all=n${all?.first ?: 0}/${"%+.1f".format(all?.second ?: 0.0)}% $bands]"
        }.ifBlank { "-" }
        return "ageProof9001{seed9005=$seed9005 $body rule=admitted labels, capped +${LABEL_CAP_PCT_9001.toInt()}%; gap = lane mean - (band mean + SE), " +
            "off setup / timing / Cortex / runner-grab records at the support tests; cell and pockets unshifted; n>=$MIN_LANE_N_9001 lane, >=$MIN_BAND_N_9001 band}"
    }

    @Volatile private var seed9005 = "pending"
    /** EdgeModel8537: how the record was filled at start (seeded:used/seen, replayed, no-dataset, failed). */
    fun noteSeed9005(status: String) { seed9005 = status }

    internal fun resetForTest9001() { cells.clear(); seed9005 = "pending" }
}
