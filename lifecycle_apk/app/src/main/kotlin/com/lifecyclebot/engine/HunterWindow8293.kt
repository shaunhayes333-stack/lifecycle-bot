package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.MarketSweep7297
import com.lifecyclebot.engine.market.OpportunityRoute8293

/**
 * V5.0.8293 — the opportunity ranking shapes what is evaluated first.
 *
 * 8286: the opportunity intelligence's leaders (JONATHAN BREAKOUT_EXPANSION 90-99, Alpenglow, PHP) were never bought.
 * Once a leader was on the watchlist it waited for the 128-mint rotating priority window and the cycle slice like any
 * cold-tail token. It now rides both the way a hand-off target does: inside the window, and in the cycle slice when its
 * last evaluation is at least [REVISIT_MS_8293] old — at most [OpportunityRoute8293.MAX_WINDOW_TARGETS_8293] leaders.
 * Ordering only: the lane scorers, FDG, sizing and every safety gate still decide.
 */
object HunterWindow8293 {
    private const val REVISIT_MS_8293 = 15_000L

    /** BotService priority window: the hand-off targets plus the watched opportunity leaders among [all]. */
    fun windowTargets8293(all: Collection<String>, nowMs: Long = System.currentTimeMillis()): List<String> =
        (HandoffPriority8190.windowTargets8190(all, nowMs) + leaders8293(all, nowMs, 0L)).distinct()

    /** BotService.selectOrderedMintsForCycle: the due hand-off targets plus the leaders due a re-evaluation. */
    fun dueTargets8293(otherMints: List<String>, nowMs: Long = System.currentTimeMillis()): List<String> {
        val leaders = leaders8293(otherMints, nowMs, REVISIT_MS_8293)
        if (leaders.isNotEmpty()) try { PipelineHealthCollector.labelAdd("OPPORTUNITY_TOP_CYCLE_SLOT_8293", leaders.size.toLong()) } catch (_: Throwable) {}
        return (HandoffPriority8190.dueTargets8190(otherMints, nowMs) + leaders).distinct()
    }

    private fun leaders8293(pool: Collection<String>, nowMs: Long, revisitMs: Long): List<String> = try {
        val top = MarketSweep7297.topOpportunities8293()
        if (top.isEmpty() || pool.isEmpty()) emptyList()
        else OpportunityRoute8293.select8293(top, pool.toHashSet(), { m ->
            try { GlobalTradeRegistry.getEntry(m)?.lastProcessedAt ?: 0L } catch (_: Throwable) { 0L }
        }, nowMs, revisitMs)
    } catch (_: Throwable) { emptyList() }
}
