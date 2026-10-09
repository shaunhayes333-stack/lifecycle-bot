package com.lifecyclebot.engine.truth

/**
 * V5.0.7948 — when a shadow-book position closes.
 *
 * 5.0.7947: shadow book 9 opens, 0 closes. Executor.checkShadowPositions skipped
 * every position whose token was no longer in the loop's token states or had no
 * loop price, and its 30-minute timeout was checked only AFTER a price was found.
 * The shadow book opens on refusals (security guard, exposure cap) — exactly the
 * tokens that leave the watchlist — so those positions never priced, never timed
 * out, and were lost unclosed at the next restart (the book is in memory and the
 * sessions run ~24 minutes, shorter than the old timeout).
 *
 * Now: the mark falls back to the forward label's chain (registry, off-watch
 * batch), the timeout fires on the last observed mark when nothing prices the
 * token now, and the timeout is 15 minutes so a session closes what it opens.
 */
object ShadowBookExit7948 {
    const val TIMEOUT_MIN_7948 = 15L
    const val TAKE_PROFIT_PCT_7948 = 50.0

    /**
     * Pure: the exit reason for a shadow position, or null to keep holding.
     * [pnlPct] is null when there is no mark at all (neither now nor last seen);
     * then only the timeout can close it — and only with a mark to book at,
     * which the caller supplies (no mark at the timeout: dropped as lost).
     */
    fun exitReason7948(pnlPct: Double?, holdMin: Long, stopLossPct: Double): String? = when {
        pnlPct != null && pnlPct.isFinite() && stopLossPct > 0.0 && pnlPct <= -stopLossPct -> "stop_loss"
        pnlPct != null && pnlPct.isFinite() && pnlPct >= TAKE_PROFIT_PCT_7948 -> "take_profit"
        holdMin >= TIMEOUT_MIN_7948 -> "timeout_${TIMEOUT_MIN_7948}min"
        else -> null
    }
}
