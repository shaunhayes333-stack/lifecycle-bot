package com.lifecyclebot.engine

/**
 * V5.0.8398 — the hot-bench eviction order is a data-completeness rule, not a conviction guess.
 *
 * Runtime 5.0.8397 (2 min 13 s, watchlist at the 220 cap, intake ~1.4 coins/s, ~12 coins cycled per 3 s loop):
 * every WATCHLIST_LRU_EVICT_6287 victim was 60-131 s old with processCount 0-1. The 6287 order (lowest
 * processCount first) kept the restored veterans that had been cycled dozens of times and threw out each coin the
 * moment its 60 s admission protection lapsed, after at most one evaluation. LanePlaybook's core inputs need three
 * time-ordered, >= 10 s spaced price readings (shape) plus a stamped buy-pressure and change reading; a coin cycled
 * once can never observe them, so every lane answered DATA_INCOMPLETE / SETUP_FEATURE_MISSING_8084_shape and the
 * coin was gone before the re-fetch it asked for (DataOrchestrator.refreshMissingInputs8087) could land.
 *
 * Rule: a coin is UNDECIDED until it has completed [MIN_EVALS_8398] cycles; undecided coins are not evictable
 * before [MAX_UNDECIDED_AGE_MS_8398] (the bounded ceiling keeps the cap real). Among evictable coins the oldest
 * admission goes first (true LRU), not the least-cycled. When every resident is protected the incoming coin is
 * routed to probation by the existing 7735 branch, so nothing is lost; probation promotes it on confirmation.
 *
 * Pure: no Android, no registry access, unit-tested in Aate8398WatchlistEvictionTest.
 */
object WatchlistEviction8398 {
    /** Three completed cycles: the minimum that can observe the 8084 shape input (three spaced readings). */
    const val MIN_EVALS_8398 = 3
    /** An undecided coin may hold a bench slot this long at most; past it the cap wins. */
    const val MAX_UNDECIDED_AGE_MS_8398 = 300_000L
    /** V5.0.6598 — no eviction before this admission age, so lane history can start accumulating. */
    const val MIN_ADMISSION_AGE_MS_6598 = 60_000L
    /** V5.0.6598 — a coin the pipeline touched this recently is being actively worked. */
    const val MIN_PROCESSING_IDLE_MS_6598 = 30_000L

    data class Row(
        val mint: String,
        val source: String,
        val addedAt: Long,
        val lastProcessedAt: Long,
        val processCount: Int,
        val open: Boolean,
    )

    data class Pool(val evictable: List<Row>, val protectedUndecided: Int)

    fun undecided8398(row: Row, now: Long): Boolean =
        row.processCount < MIN_EVALS_8398 && now - row.addedAt < MAX_UNDECIDED_AGE_MS_8398

    /** The evictable residents, oldest admission first. [incomingMint] is never its own victim. */
    fun pool8398(rows: Collection<Row>, now: Long, incomingMint: String): Pool {
        var protectedUndecided = 0
        val evictable = rows.asSequence()
            .filter { it.mint != incomingMint }
            .filter { !it.open }
            .filter { now - it.addedAt >= MIN_ADMISSION_AGE_MS_6598 }
            .filter { it.lastProcessedAt == 0L || now - it.lastProcessedAt >= MIN_PROCESSING_IDLE_MS_6598 }
            .filter { row -> if (undecided8398(row, now)) { protectedUndecided++; false } else true }
            .sortedWith(compareBy<Row> { it.addedAt }.thenByDescending { it.processCount })
            .toList()
        return Pool(evictable, protectedUndecided)
    }

    /** The victim: a pump.fun coin first while pump.fun holds over its share (V5.0.8029), else the oldest evictable. */
    fun victim8398(pool: Pool, pumpOverShare: Boolean, pumpFamily: (String) -> Boolean): Row? =
        (if (pumpOverShare) pool.evictable.firstOrNull { pumpFamily(it.source) } else null) ?: pool.evictable.firstOrNull()
}
