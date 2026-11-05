package com.lifecyclebot.engine.truth

/**
 * V5.0.8535 §P1-12 — the missed-runner register stratified by WHERE the opportunity was lost, from the decision trace
 * (AdmissionRunnerTrace8144) and its 8144 verdict, with a bankable estimate instead of the hindsight peak.
 *
 * 8529: ~33 detected 4x+ opportunities, one held at the important moment; "WRONGLY_REFUSED" alone does not say whether
 * the coin was never seen, seen late, refused, elected out, allowed but never bought, or bought and sold early.
 *   NEVER_DETECTED   no detection stamp at all
 *   DETECTED_LATE    detected more than [LATE_MS_8535] after first seen (the run was already on)
 *   WRONGLY_REFUSED  the 8144 verdict on decision-time evidence
 *   ELECTED_OUT      a qualified lane lost the election / ownership
 *   NO_BUY_ATTEMPT   the FDG allowed and nothing downstream reached a ticket / execution
 *   FILLED_LATE      filled (downstream `FILLED@+x%`) after a large part of the run
 *   SOLD_PREMATURELY held, then sold (`SOLD@+x%`) far below the observed peak
 *   CAPTURED / CORRECT_AT_DECISION / DATA_GAP / NOT_EVALUATED  as the 8144 verdict says
 * Pure; the downstream tokens are optional evidence (absent = not attributed to those two buckets).
 */
object RunnerFunnel8535 {

    enum class Bucket { NEVER_DETECTED, DETECTED_LATE, WRONGLY_REFUSED, ELECTED_OUT, NO_BUY_ATTEMPT, FILLED_LATE, SOLD_PREMATURELY,
        CAPTURED, CORRECT_AT_DECISION, DATA_GAP, NOT_EVALUATED }

    const val LATE_MS_8535 = 5L * 60_000L
    const val FILLED_LATE_PCT_8535 = 100.0
    const val SOLD_EARLY_SHARE_8535 = 0.25

    /** Pure: the `KEY@+x%` figure in a downstream string, or NaN. */
    fun pctToken8535(downstream: String, key: String): Double {
        val i = downstream.indexOf("$key@")
        if (i < 0) return Double.NaN
        val s = downstream.substring(i + key.length + 1).takeWhile { it == '+' || it == '-' || it == '.' || it.isDigit() }
        return s.toDoubleOrNull() ?: Double.NaN
    }

    fun bucketOf8535(t: AdmissionRunnerTrace8144.Trace8144, v: AdmissionRunnerTrace8144.Verdict8144): Bucket {
        val filledAt = pctToken8535(t.downstream, "FILLED")
        val soldAt = pctToken8535(t.downstream, "SOLD")
        if (t.held || v.miss == AdmissionRunnerTrace8144.Miss8144.CAPTURED) {
            if (soldAt.isFinite() && t.peakPct.isFinite() && t.peakPct > 0.0 && soldAt < t.peakPct * SOLD_EARLY_SHARE_8535) return Bucket.SOLD_PREMATURELY
            if (filledAt.isFinite() && filledAt >= FILLED_LATE_PCT_8535) return Bucket.FILLED_LATE
            return Bucket.CAPTURED
        }
        if (t.detectedAtMs <= 0L && t.nativeAtMs <= 0L) return Bucket.NEVER_DETECTED
        val g = v.gate.uppercase()
        if (g.contains("ELECTION") || g.contains("NOT_PRIMARY") || g.contains("OWNER")) return Bucket.ELECTED_OUT
        if (t.fdgVerdict.startsWith("ALLOW") && t.downstream.isBlank()) return Bucket.NO_BUY_ATTEMPT
        return when (v.miss) {
            AdmissionRunnerTrace8144.Miss8144.WRONGLY_REFUSED -> Bucket.WRONGLY_REFUSED
            AdmissionRunnerTrace8144.Miss8144.CORRECT_AT_DECISION -> Bucket.CORRECT_AT_DECISION
            AdmissionRunnerTrace8144.Miss8144.DATA_GAP -> Bucket.DATA_GAP
            AdmissionRunnerTrace8144.Miss8144.NOT_EVALUATED ->
                if (t.detectedAtMs > 0L && t.firstSeenMs > 0L && t.detectedAtMs - t.firstSeenMs > LATE_MS_8535) Bucket.DETECTED_LATE else Bucket.NOT_EVALUATED
            AdmissionRunnerTrace8144.Miss8144.CAPTURED -> Bucket.CAPTURED
        }
    }

    /**
     * Pure: the bankable share of an observed peak on the runner ladder (RunnerExitProfile7277 shape: a quarter banked at
     * +50 %, a quarter at +100 %, a quarter at +200 %, the rest trailing 30 % below the peak), net of [costPct]. A peak
     * below the first rung banks the trailing read only. Never the hindsight peak itself.
     */
    fun bankablePct8535(peakPct: Double, costPct: Double): Double {
        if (!peakPct.isFinite() || peakPct <= 0.0) return 0.0
        val cost = costPct.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val rungs = doubleArrayOf(50.0, 100.0, 200.0)
        var banked = 0.0
        var share = 1.0
        for (r in rungs) if (peakPct >= r) { banked += 0.25 * r; share -= 0.25 }
        val trail = (1.0 + peakPct / 100.0) * 0.70 - 1.0
        banked += share * (trail * 100.0).coerceAtLeast(-100.0)
        return banked - cost
    }

    /** Pure: one summary fragment over traces: counts per bucket and the bankable SOL-equivalent (% of ticket) by bucket. */
    fun line8535(traces: List<AdmissionRunnerTrace8144.Trace8144>, costPct: Double = 4.4): String {
        if (traces.isEmpty()) return "funnel8535{runners=0}"
        val rows = traces.map { it to bucketOf8535(it, AdmissionRunnerTrace8144.classify8144(it)) }
        val counts = Bucket.values().joinToString(" ") { b -> "${b.name.lowercase()}=${rows.count { it.second == b }}" }
        val bankable = rows.filter { it.second != Bucket.CAPTURED }.sumOf { bankablePct8535(it.first.peakPct, costPct).coerceAtLeast(0.0) }
        return "funnel8535{$counts bankableMissedPctOfTicket=${"%.0f".format(bankable)} basis=ladder25/25/25+trail30 net=${"%.1f".format(costPct)}%}"
    }
}
