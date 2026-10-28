package com.lifecyclebot.engine.cortex

/**
 * V5.0.8291 §LABELS_MATURE — which unpriced forward-label observations the off-watch mark batch asks for first.
 *
 * 5.0.8286: Cortex graded=710 against lost=701 (LABEL_LOST_GAP_8187=657, LABEL_LOST_BASIS_8187=44), and the timing cortex
 * missed its window on 977 of 1058 samples. A refused coin leaves the watchlist, so its 5-minute label is priced only by the
 * off-watch batch (ForwardReturnLabeler7731.fetchOffWatchMarks7737: 50 mints per 20 s, a mint re-asked after 90 s). That
 * batch was ordered by decision time alone: every unpriced observation from age 1 minute on (its 1-minute slot never booked
 * while unpriced) competed equally, so the oldest coins — many already dead, many only at their 1-minute read — took the
 * batch while observations INSIDE their 5-minute window (5:00..7:30, the only marks that book the label; a GAP is filed at
 * 7:30) waited behind them. The batch capacity (~150 mints a minute) is far under the demand (~4 asks per observation over
 * its 6.5 unpriced minutes), so the order decides which labels mature.
 *
 * Now the batch asks in rank order, oldest first within a rank:
 *   0  inside the 5-minute window (the label's last chance; the timing cortex's window opens at the same minute);
 *   1  the entry mark inside the fill-latency re-base window (without it the label is LATE_FIRST_PRICE, observed only);
 *   2  from half the horizon to the horizon (a read there books the label as LAST_MARK when the window brings none);
 *   3  everything else (the 1-minute slot, the 60-minute read, progressive checkpoints).
 * No mark is invented: this only changes which REAL marks are fetched first.
 */
object CortexLabelFeed8291 {
    /** Larger than any epoch-millisecond due time, so a rank always outranks a due time. */
    private const val RANK_SPAN_8291 = 10_000_000_000_000L

    /** Pure: the off-watch rank of an unpriced observation [ageMs] old (see the class comment). */
    fun offWatchRank8291(ageMs: Long, done60: Boolean, firstMarkDue: Boolean, horizonMs: Long, graceMs: Long): Int = when {
        !done60 && ageMs >= horizonMs && ageMs <= horizonMs + graceMs -> 0
        firstMarkDue -> 1
        !done60 && ageMs >= horizonMs / 2 && ageMs < horizonMs -> 2
        else -> 3
    }

    /** Pure: the sort key of an observation of [rank] due at [dueMs] (ascending = asked first). */
    fun offWatchKey8291(rank: Int, dueMs: Long): Long = rank.coerceIn(0, 9).toLong() * RANK_SPAN_8291 + dueMs

    // ── V5.0.8418 §EVERY_LABEL_GETS_ITS_MARK ──────────────────────────────────────────────────────────────────────────────
    // 5.0.8416 (3.7 h live): observed=8349 booked60=1532 lostMark=6669; offWatch priced=2700 in 3.7 h while the DexScreener
    // pair lane (120 calls/min, 30 mints a call) ran ~20 calls a minute. An unpriced observation inside a horizon window (or
    // [LABEL_LEAD_MS_8418] before it) now registers with KeylessMarks8359, whose pass puts it on the pair lane after held and
    // shadow mints and on the keyless curve read after held and shadow curves. The windows are the labeler's own (the
    // re-base window, then each open horizon to its Field-Manual grace, graceFor7946); nothing here widens them.

    /** How long before a horizon an unpriced observation starts asking (one lane round trip and a curve pass fit in it). */
    const val LABEL_LEAD_MS_8418 = 30_000L

    /**
     * Pure (V5.0.8418): the wall-clock end of the mark window an unpriced observation decided at [atMs] is in (or within
     * [leadMs] of) at [ageMs], else 0. [firstMarkDue]: the re-base window, which ends at [rebaseMaxMs]. [horizonsMs] /
     * [gracesMs]: the horizons still open (a negative horizon is closed) and each one's grace. The soonest window wins.
     */
    fun windowEnd8418(atMs: Long, ageMs: Long, firstMarkDue: Boolean, rebaseMaxMs: Long, horizonsMs: LongArray,
                      gracesMs: LongArray, leadMs: Long): Long {
        if (firstMarkDue && ageMs <= rebaseMaxMs) return atMs + rebaseMaxMs
        var best = 0L
        for (i in horizonsMs.indices) {
            val h = horizonsMs[i]
            if (h < 0L || i >= gracesMs.size) continue
            val g = gracesMs[i].coerceAtLeast(0L)
            if (ageMs >= h - leadMs && ageMs <= h + g) {
                val end = atMs + h + g
                if (best == 0L || end < best) best = end
            }
        }
        return best
    }

    /** Pure (V5.0.8418): the family of a label mark's source — CURVE (account read), TAPE (curve print), DEX (pair / aggregator / loop). */
    fun markFamily8418(source: String?): String {
        val s = source.orEmpty().uppercase()
        return when {
            s == "CURVE_ACCOUNT" || s.startsWith("PUMP_CURVE") -> "CURVE"
            s.startsWith("CURVE_") -> "TAPE"
            else -> "DEX"
        }
    }

    /**
     * Pure (V5.0.8418): a 5-minute read that closed unavailable is LATE when a mark was seen for it (accepted at some point,
     * a rejected / unaccepted mark, or one only after the window) and NO_SOURCE when no source ever priced it.
     */
    fun lostLate8418(markAfterWindow: Boolean, everAccepted: Boolean, anyMarkSeen: Boolean): Boolean =
        markAfterWindow || everAccepted || anyMarkSeen

    /** Pure (V5.0.8418): labelMarks8418[due=.. priced{dex=..,curve=..,tape=..}, lost{noSource=..,late=..}] + the routing counters. */
    fun labelMarksLine8418(due: Long, dex: Long, curve: Long, tape: Long, noSource: Long, late: Long, route: LongArray?): String {
        val r = route ?: LongArray(0)
        fun at(i: Int): Long = if (i < r.size) r[i] else 0L
        return "labelMarks8418[due=$due, priced{dex=$dex,curve=$curve,tape=$tape}, lost{noSource=$noSource,late=$late}, " +
            "route{noted=${at(0)},laneQueued=${at(1)},laneMarks=${at(2)},curvePasses=${at(3)},curveMarks=${at(4)}}]"
    }
}
