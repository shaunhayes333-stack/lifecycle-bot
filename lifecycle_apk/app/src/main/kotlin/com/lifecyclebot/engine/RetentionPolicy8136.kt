package com.lifecyclebot.engine

/**
 * V5.0.8136 — pressure-aware retention for the memory guard.
 *
 * 8127 reached 511/512 MB after four hard trims that pruned no token row: the 8025/8027 rule only frees a row 10 min
 * after intake and only above 300 rows, so in a young session (or with fewer than 300 heavy rows) fresh candidate
 * allocations were never freed under pressure. Under HARD/CRITICAL pressure rows are now chosen by relevance, not age:
 * the oldest-touched rows that are not open, not an active admission (prefetched prospect / sealed FDG verdict) and not
 * critical evidence (runner ticket, balance-proof wait, wallet-tracked) go first; at CRITICAL (or after a hard trim that
 * did not recover headroom) watched-but-idle rows go too. Pure.
 */
object RetentionPolicy8136 {

    enum class Pressure { NORMAL, SOFT, HARD, CRITICAL }

    const val SOFT_FRAC_8136 = 0.75
    const val HARD_FRAC_8136 = 0.88
    const val CRITICAL_FRAC_8136 = 0.94
    /** A hard trim + GC that freed less than this share of the heap (and left it above HARD) did not help. */
    const val MEANINGFUL_RECOVERY_FRAC_8136 = 0.03
    const val MAX_ESCALATION_8136 = 3

    data class Row(
        val mint: String,
        val watched: Boolean,
        val open: Boolean,
        val admission: Boolean,
        val critical: Boolean,
        val addedAtMs: Long,
        val lastTouchMs: Long,
    )

    /** Pure: pressure at heap fraction [frac]; an unanswered escalation lifts HARD to CRITICAL. */
    fun pressure8136(frac: Double, escalation: Int = 0): Pressure {
        if (!frac.isFinite()) return Pressure.NORMAL
        val base = when {
            frac >= CRITICAL_FRAC_8136 -> Pressure.CRITICAL
            frac >= HARD_FRAC_8136 -> Pressure.HARD
            frac >= SOFT_FRAC_8136 -> Pressure.SOFT
            else -> Pressure.NORMAL
        }
        return if (escalation > 0 && base == Pressure.HARD) Pressure.CRITICAL else base
    }

    /** Pure: token rows kept at this pressure. NORMAL/SOFT keep the 8025 target. */
    fun keepTarget8136(p: Pressure): Int = when (p) {
        Pressure.NORMAL, Pressure.SOFT -> MemoryGuard7977.KEEP_TOKENS_8025
        Pressure.HARD -> 160
        Pressure.CRITICAL -> 90
    }

    /** Pure: minimum row age before it may go. Under HARD/CRITICAL pressure age is no protection. */
    fun minAgeMs8136(p: Pressure): Long = when (p) {
        Pressure.NORMAL, Pressure.SOFT -> 10L * 60_000L
        Pressure.HARD, Pressure.CRITICAL -> 0L
    }

    /** Pure: open positions, active admissions and critical evidence are never pruned. */
    fun protected8136(r: Row): Boolean = r.open || r.admission || r.critical

    /** Pure: rows to free, least relevant first (unwatched before watched, then oldest touch). */
    fun victims8136(rows: List<Row>, p: Pressure, nowMs: Long): List<Row> {
        val excess = rows.size - keepTarget8136(p)
        if (excess <= 0) return emptyList()
        val minAge = minAgeMs8136(p)
        return rows.asSequence()
            .filter { !protected8136(it) && nowMs - it.addedAtMs >= minAge && (!it.watched || p == Pressure.CRITICAL) }
            .sortedWith(compareBy<Row>({ it.watched }, { it.lastTouchMs }))
            .take(excess)
            .toList()
    }

    /** Pure: did a hard trim recover meaningful headroom? */
    fun recovered8136(fracBefore: Double, fracAfter: Double): Boolean {
        if (!fracBefore.isFinite() || !fracAfter.isFinite()) return true
        return fracAfter < HARD_FRAC_8136 || fracBefore - fracAfter >= MEANINGFUL_RECOVERY_FRAC_8136
    }

    /** Pure: escalation after a hard trim (0 = recovered; climbs while trims keep failing). */
    fun nextEscalation8136(prev: Int, recovered: Boolean): Int =
        if (recovered) 0 else (prev + 1).coerceAtMost(MAX_ESCALATION_8136)

    /** Pure: chart tapes kept at this pressure (null = the reader's own bound applies). */
    fun chartTapeCap8136(p: Pressure): Int? = when (p) {
        Pressure.NORMAL, Pressure.SOFT -> null
        Pressure.HARD -> 400
        Pressure.CRITICAL -> 150
    }

    /** Pure: resident token-archive rows at this pressure. */
    fun archiveCap8136(p: Pressure): Int = when (p) {
        Pressure.NORMAL, Pressure.SOFT -> 1_000
        Pressure.HARD -> 600
        Pressure.CRITICAL -> 250
    }
}
