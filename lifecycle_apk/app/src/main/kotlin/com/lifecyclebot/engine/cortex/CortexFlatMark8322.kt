package com.lifecyclebot.engine.cortex

/**
 * V5.0.8322 §FLAT_MARK — a five-minute forward label read on a mark that never moved.
 *
 * 5.0.8319: labels rebased8076[up=99 down=859] — the re-base counted an UNCHANGED first mark as "down", and most
 * re-bases were exactly that: the decision price and the first mark were the same registry / batch value. A refused coin
 * off the watchlist whose later marks repeat the same cached price reads gross exactly 0 at five minutes and books
 * "lost the round trip" (net = -cost) into every voter, bucket, setup x verdict and veto book. That is not a return: no
 * price other than the entry was ever observed. Such a label is kept as an OBSERVED grade record and counted
 * (LABEL_HYGIENE_FLAT_MARK_8322, FORWARD_LABEL_FLAT_MARK_8322); it grades no authority book. Nothing is invented: a coin
 * whose mark moved by any amount, up or down, is graded exactly as before. Pure: no Android, no I/O.
 */
object CortexFlatMark8322 {
    /** Pure: the label saw no price other than its entry — gross exactly 0, no peak above it and no dip below it. */
    fun flatMark8322(grossPct: Double, peakPct: Double, minPct: Double): Boolean =
        grossPct == 0.0 && peakPct == 0.0 && minPct == 0.0

    /** Pure: a flat label is kept out of the books only when it would otherwise have graded them (counted once). */
    fun observedOnly8322(flat: Boolean, authority: Boolean, lateFirstPrice: Boolean, lateRead: Boolean): Boolean =
        flat && authority && !lateFirstPrice && !lateRead

    /** Pure: the re-base direction of a first mark against the decision price: 1 up, -1 down, 0 unchanged (a flat mark). */
    fun rebaseDirection8322(entryPx: Double, firstPx: Double): Int = when {
        firstPx > entryPx -> 1
        firstPx < entryPx -> -1
        else -> 0
    }
}
