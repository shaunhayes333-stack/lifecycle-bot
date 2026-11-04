package com.lifecyclebot.engine.cortex

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * V5.0.8325 §RANKING_OFF_THE_LOCK — the Cortex's per-lane ranking check (inversion8324) and its cache.
 *
 * 5.0.8323: assessLockWait8324 max 121 s, assess p50 1.5 s, ASYNC_DROPPED 548. The report, the panel and the executable
 * refresh ranked every lane's grade book (Spearman sorts, quintiles, ponds) while holding the Cortex monitor, at every
 * read. Now the rows are copied under the lock in one pass (CortexGradeBook8144.rankRowsByLane8325), ranked here outside
 * it, and the result is cached for [TTL_MS_8325]; assessments read only the cached verdict ([TtlCache8325.peek]).
 *
 * The ordering a lane is judged on is the one it buckets on: runner lanes the tail EV (p(run) from the voters' bins,
 * CortexRunOdds8325) once [MIN_ROWS_8325] decisions carry one, scalp lanes the calibrated edge. The verdict reads the
 * lane's most recent [RECENT_ROWS_8325] rows, so a repaired ordering is judged on its own decisions.
 *
 * Pure apart from the cache's atomics: no Android, no I/O.
 */
object CortexRank8325 {
    const val TTL_MS_8325 = 30_000L
    internal const val MIN_ROWS_8325 = 10
    internal const val RECENT_ROWS_8325 = 300

    /** A small TTL cache: one computation at a time, readers never wait (they get the last value while one runs). */
    class TtlCache8325<T : Any>(private val ttlMs: Long) {
        @Volatile private var atMs = Long.MIN_VALUE
        @Volatile private var value: T? = null
        private data class Fresh8527<T>(val atMs: Long, val value: T)
        /** Atomic evidence + timestamp: a concurrent rank refresh must not re-date an older verdict. */
        @Volatile private var fresh8527: Fresh8527<T>? = null
        private val busy = AtomicBoolean(false)
        val computes = AtomicLong(0)

        /** The cached value, never computing. */
        fun peek(): T? = value

        /** Current cache only: a stale historical rank can never authorize a new inverse trade. */
        fun peekFresh8527(nowMs: Long): T? = fresh8527?.takeIf { nowMs - it.atMs in 0L until ttlMs }?.value

        /** The cached value while fresh; else computes it once (concurrent callers get the previous value). */
        fun get(nowMs: Long, compute: () -> T): T? {
            val cur = value
            if (cur != null && nowMs - atMs in 0L until ttlMs) return cur
            if (!busy.compareAndSet(false, true)) return cur
            try {
                val v = compute()
                value = v; atMs = nowMs
                fresh8527 = Fresh8527(nowMs, v)
                computes.incrementAndGet()
                return v
            } finally { busy.set(false) }
        }

        /** Next [get] recomputes. */
        fun invalidate() { atMs = Long.MIN_VALUE; fresh8527 = null }
    }

    class LaneRank8325(val lane: String, val line: String, val short: String, val verdict: String, val rows: Int,
                       val orderedBy: String, val refuseCount8527: Int = 0, val refuseNetLowerPct8527: Double = Double.NaN,
                       val strongCount8527: Int = 0, val strongNetUpperPct8527: Double = Double.NaN)

    private fun f(v: Double) = if (v.isFinite()) "%+.2f".format(v) else "-"

    /** Modelled executable-net return percent; one standard-error interval, not a fill guarantee. */
    private fun cohortBounds8527(values: List<Double>): Pair<Double, Double> {
        if (values.size < 2) return Double.NaN to Double.NaN
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        val se = sqrt(variance / values.size)
        return (mean - se) to (mean + se)
    }

    /** Pure: one lane's ranking check from its rows (oldest first); null under [MIN_ROWS_8325] rows. */
    fun rankLane8325(lane: String, all: List<CortexGradeBook8144.RankRow8324>, runner: Boolean, pondCompact: String?): LaneRank8325? {
        if (all.size < MIN_ROWS_8325) return null
        val y = all.map { it.objective }
        val pred = all.map { it.predicted }
        val ic = CortexTailEv8324.rankIc8324(pred, y)
        val q = CortexTailEv8324.quintiles8324(pred, y)
        val tailRows = all.filter { it.tailEv.isFinite() }
        val tailIc = CortexTailEv8324.rankIc8324(tailRows.map { it.tailEv }, tailRows.map { it.objective })
        val sN = all.count { it.bucket == CortexScoreboard7885.Bucket.STRONG.ordinal }
        val rN = all.count { it.bucket == CortexScoreboard7885.Bucket.REFUSE.ordinal }
        val tN = all.count { it.admitted }
        // The ordering the lane buckets on, on its recent rows.
        val byTail = runner && tailRows.size >= MIN_ROWS_8325
        val recent = (if (byTail) tailRows else all).takeLast(RECENT_ROWS_8325)
        val oPred = recent.map { if (byTail) it.tailEv else it.predicted }
        val oY = recent.map { it.objective }
        val oIc = CortexTailEv8324.rankIc8324(oPred, oY)
        val oQ = CortexTailEv8324.quintiles8324(oPred, oY)
        // A profitable bottom fifth does not prove that ALL raw REFUSE-bucket candidates pay.
        // The operator flips the full bucket, so authority must be earned on that very bucket.
        val pairedRecent = recent.filter { (if (byTail) it.tailEv else it.predicted).isFinite() && it.objective.isFinite() }
        val eligiblePairCount = pairedRecent.size
        val refuseReturns = pairedRecent.filter { it.bucket == CortexScoreboard7885.Bucket.REFUSE.ordinal }
            .map { it.objective }
        val strongReturns = pairedRecent.filter { it.bucket == CortexScoreboard7885.Bucket.STRONG.ordinal }
            .map { it.objective }
        val refuseLower = cohortBounds8527(refuseReturns).first
        val strongUpper = cohortBounds8527(strongReturns).second
        val verdict = CortexTailEv8324.verdict8324(oIc, oQ[0], oQ[1])
        val by = if (byTail) "tailEv" else "edge"
        val line = "$lane: ic=${f(ic)} n${all.size} topQ/botQ=${CortexTailEv8324.fmt(q[0])}/${CortexTailEv8324.fmt(q[1])} " +
            "tailIc=${f(tailIc)}(n${tailRows.size}) strong/refuse=$sN/$rN take/refuse=$tN/${all.size - tN} " +
            "order8325=$by ic=${f(oIc)} n${eligiblePairCount} topQ/botQ=${CortexTailEv8324.fmt(oQ[0])}/${CortexTailEv8324.fmt(oQ[1])} refuseN=${refuseReturns.size} refuseNetLCB=${f(refuseLower)} strongN=${strongReturns.size} strongNetUCB=${f(strongUpper)} $verdict" +
            (pondCompact?.let { " pond[$it]" } ?: "")
        val short = "rank IC ($by) ${if (oIc.isFinite()) "%+.2f".format(oIc) else "-"} top/bottom fifth " +
            "${CortexTailEv8324.fmt(oQ[0])}/${CortexTailEv8324.fmt(oQ[1])} $verdict"
        return LaneRank8325(lane, line, short, verdict, eligiblePairCount, by,
            refuseReturns.size, refuseLower, strongReturns.size, strongUpper)
    }
}
