package com.lifecyclebot.engine.truth

/**
 * V5.0.8535 §P0-01 — the off-watch label pricer's schedule and provider-cause taxonomy, pure.
 *
 * The 8529 run priced 187 of 655 due label marks. Structurally: a mint was re-asked no sooner than 30 s after its last
 * ask whatever its window (a 1-minute label has a 60 s grace, so at most two asks); the batch was ordered by rank and
 * observation age, not by how soon each window closes; the Jupiter rung covered at most 50 of a 300-mint batch; and a
 * label that closed unpriced said only noSource / late — never whether the provider refused (429), failed, timed out,
 * or simply does not list the coin.
 *
 * Here: a mint's retry gap is a third of what is left of ITS window (never under [MIN_RETRY_MS], never over
 * [BASE_RETRY_MS]); the ready list is ordered soonest-closing first; and every unpriced mint carries the last provider
 * cause the batch learned about it, which the terminal records beside the unavailable reason.
 */
object OffWatchSchedule8535 {

    const val BASE_RETRY_MS = 30_000L
    const val MIN_RETRY_MS = 5_000L

    /** What the providers said about a mint the batch could not price. */
    enum class Cause { PRICED, NOT_LISTED, RATE_LIMIT, UNAVAILABLE, NO_SOURCE }

    data class Due(val mint: String, val windowEndMs: Long)

    /** Pure: the gap before [windowEndMs]'s mint is re-asked — a third of what is left of its window, bounded. */
    fun retryGapMs(windowEndMs: Long?, nowMs: Long): Long {
        if (windowEndMs == null || windowEndMs <= nowMs) return BASE_RETRY_MS
        return ((windowEndMs - nowMs) / 3L).coerceIn(MIN_RETRY_MS, BASE_RETRY_MS)
    }

    /**
     * Pure: the mints ready to ask now (not asked inside their own retry gap), soonest-closing window first, each mint
     * once; and how many were deferred because they were asked recently.
     */
    fun ready(due: List<Due>, attemptAt: (String) -> Long?, nowMs: Long): Pair<List<String>, Int> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        var deferred = 0
        for (d in due.sortedBy { it.windowEndMs }) {
            if (!seen.add(d.mint)) continue
            val at = attemptAt(d.mint)
            if (at != null && nowMs - at in 0L until retryGapMs(d.windowEndMs, nowMs)) deferred++ else out += d.mint
        }
        return out to deferred
    }

    /**
     * Pure: the cause for a mint after a DexScreener rung. [refused] = our own limiter refused the call (re-asked next
     * pass, not a provider verdict); [answered] = the call returned; [listed] = the answer priced the mint.
     */
    fun dexCause(refused: Boolean, answered: Boolean, listed: Boolean): Cause = when {
        listed -> Cause.PRICED
        refused -> Cause.RATE_LIMIT
        !answered -> Cause.UNAVAILABLE
        else -> Cause.NOT_LISTED
    }

    /** Pure: a later rung's verdict never downgrades PRICED; an answered NOT_LISTED stands over an earlier UNAVAILABLE. */
    fun merge(prior: Cause?, next: Cause): Cause = when {
        prior == Cause.PRICED || next == Cause.PRICED -> Cause.PRICED
        prior == null || prior == Cause.NO_SOURCE -> next
        next == Cause.NOT_LISTED -> Cause.NOT_LISTED
        prior == Cause.NOT_LISTED -> prior
        else -> next
    }
}
