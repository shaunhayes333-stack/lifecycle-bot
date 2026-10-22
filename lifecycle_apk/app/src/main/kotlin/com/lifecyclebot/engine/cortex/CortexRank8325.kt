package com.lifecyclebot.engine.cortex

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
        private val busy = AtomicBoolean(false)
        val computes = AtomicLong(0)

        /** The cached value, never computing. */
        fun peek(): T? = value

        /** The cached value while fresh; else computes it once (concurrent callers get the previous value). */
        fun get(nowMs: Long, compute: () -> T): T? {
            val cur = value
            if (cur != null && nowMs - atMs in 0L until ttlMs) return cur
            if (!busy.compareAndSet(false, true)) return cur
            try {
                val v = compute()
                value = v; atMs = nowMs
                computes.incrementAndGet()
                return v
            } finally { busy.set(false) }
        }

        /** Next [get] recomputes. */
        fun invalidate() { atMs = Long.MIN_VALUE }
    }

    class LaneRank8325(val lane: String, val line: String, val short: String, val verdict: String, val rows: Int, val orderedBy: String)

    private fun f(v: Double) = if (v.isFinite()) "%+.2f".format(v) else "-"

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
        val verdict = CortexTailEv8324.verdict8324(oIc, oQ[0], oQ[1])
        val by = if (byTail) "tailEv" else "edge"
        val line = "$lane: ic=${f(ic)} n${all.size} topQ/botQ=${CortexTailEv8324.fmt(q[0])}/${CortexTailEv8324.fmt(q[1])} " +
            "tailIc=${f(tailIc)}(n${tailRows.size}) strong/refuse=$sN/$rN take/refuse=$tN/${all.size - tN} " +
            "order8325=$by ic=${f(oIc)} n${recent.size} topQ/botQ=${CortexTailEv8324.fmt(oQ[0])}/${CortexTailEv8324.fmt(oQ[1])} $verdict" +
            (pondCompact?.let { " pond[$it]" } ?: "")
        val short = "rank IC ($by) ${if (oIc.isFinite()) "%+.2f".format(oIc) else "-"} top/bottom fifth " +
            "${CortexTailEv8324.fmt(oQ[0])}/${CortexTailEv8324.fmt(oQ[1])} $verdict"
        return LaneRank8325(lane, line, short, verdict, recent.size, by)
    }
}
