package com.lifecyclebot.engine

/**
 * V5.9.495z31 — Tier state separation.
 *
 * Operator-reported bug: dashboard shows TIER 5 with 4352 trades but
 * win-rate 33% with active streak guards. The current label "TIER 5"
 * implies "ready" when the bot is actually held back by profitability
 * guards.
 *
 * Split tier into two facets:
 *   - TIER_COUNT_UNLOCKED — pure trade-count milestone (informational)
 *   - PROFITABILITY_LOCKED — true if WR < target or guards active
 *   - STREAK_GUARD_ACTIVE — streak guard separately surfaced
 *
 * UI / pipeline read these to render and gate.
 */
object TierState {

    enum class Status {
        TIER_COUNT_UNLOCKED,
        PROFITABILITY_LOCKED,
        STREAK_GUARD_ACTIVE,
        READY,
    }

    data class Snapshot(
        val tierCount: Int,
        val tradeCount: Int,
        val winRatePct: Double,
        val targetWrPct: Double,
        val streakBlocks: Int,
        val statuses: Set<Status>,
    ) {
        val isReady: Boolean get() = Status.READY in statuses
        val displayLabel: String get() = when {
            isReady                                  -> "TIER $tierCount · READY"
            Status.STREAK_GUARD_ACTIVE in statuses   -> "TIER $tierCount · STREAK_GUARD_ACTIVE"
            Status.PROFITABILITY_LOCKED in statuses  -> "TIER $tierCount · PROFITABILITY_LOCKED (WR ${"%.1f".format(winRatePct)}% < ${"%.1f".format(targetWrPct)}%)"
            else                                     -> "TIER $tierCount · TIER_COUNT_UNLOCKED"
        }
    }

    fun evaluate(
        tierCount: Int,
        tradeCount: Int,
        winRatePct: Double,
        targetWrPct: Double,
        streakBlocks: Int,
    ): Snapshot {
        val statuses = mutableSetOf<Status>()
        statuses += Status.TIER_COUNT_UNLOCKED
        if (winRatePct < targetWrPct) statuses += Status.PROFITABILITY_LOCKED
        if (streakBlocks > 0) statuses += Status.STREAK_GUARD_ACTIVE
        if (Status.PROFITABILITY_LOCKED !in statuses && Status.STREAK_GUARD_ACTIVE !in statuses) {
            statuses += Status.READY
        }
        return Snapshot(
            tierCount = tierCount,
            tradeCount = tradeCount,
            winRatePct = winRatePct,
            targetWrPct = targetWrPct,
            streakBlocks = streakBlocks,
            statuses = statuses,
        )
    }
}
