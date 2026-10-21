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
}
