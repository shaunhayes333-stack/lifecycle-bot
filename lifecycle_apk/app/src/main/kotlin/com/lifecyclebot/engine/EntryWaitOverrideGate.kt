package com.lifecyclebot.engine

/**
 * V5.9.495z31 / z32 — Final-Decision-Gate vs EntryAI WAIT enforcement.
 *
 * **Operator override (z32):** "we shouldn't block at 39% confidence —
 * we should WAIT to see if it changes. we block far too quickly and
 * purge tokens way too quickly. if we ignore everything we trade
 * nothing."
 *
 * Therefore: the FDG NEVER outright purges a candidate based solely
 * on EntryAI=WAIT. Three dispositions:
 *
 *   ALLOW                        — gate is satisfied, trade may
 *                                  proceed.
 *   FDG_OVERRIDE_ENTRY_WAIT      — EntryAI said WAIT but moonshot
 *                                  override or conf≥override threshold
 *                                  fires; trade proceeds with reason.
 *   FDG_DEFER_ENTRY_WAIT         — keep the token alive in the
 *                                  watchlist and re-evaluate next
 *                                  tick. NEVER purges. NOT the same
 *                                  as a block.
 *
 * Caller contract: on `FDG_DEFER_ENTRY_WAIT`, the candidate must
 * remain in the watchlist with its TTL refreshed; do NOT decrement
 * "live failure" counters; do NOT count this as a missed trade.
 */
object EntryWaitOverrideGate {

    enum class Verdict {
        ALLOW,                          // EntryAI not WAIT or risk not HIGH
        FDG_OVERRIDE_ENTRY_WAIT,        // explicit override (moonshot / high conf)
        FDG_DEFER_ENTRY_WAIT,           // observe-and-revisit (NEVER purge)
    }

    data class Result(
        val verdict: Verdict,
        val reason: String,
        val keepInWatchlist: Boolean,
    )

    /**
     * @param entryWait         true when EntryAI returned WAIT
     * @param riskHigh          true when risk=HIGH
     * @param moonshotOverride  true when MoonshotTraderAI fires explicit override
     * @param confidence        FDG confidence, 0–100
     * @param highConfThreshold confidence required to override (default 75)
     */
    fun evaluate(
        entryWait: Boolean,
        riskHigh: Boolean,
        moonshotOverride: Boolean,
        confidence: Int,
        highConfThreshold: Int = 75,
    ): Result {
        if (!entryWait || !riskHigh) {
            return Result(
                Verdict.ALLOW,
                "EntryAI=${if (entryWait) "WAIT" else "OK"} risk=${if (riskHigh) "HIGH" else "≤HIGH"}",
                keepInWatchlist = true,
            )
        }
        if (moonshotOverride) {
            return Result(
                Verdict.FDG_OVERRIDE_ENTRY_WAIT,
                "FDG_OVERRIDE_REASON=moonshot_override (EntryAI=WAIT, risk=HIGH)",
                keepInWatchlist = true,
            )
        }
        if (confidence >= highConfThreshold) {
            return Result(
                Verdict.FDG_OVERRIDE_ENTRY_WAIT,
                "FDG_OVERRIDE_REASON=conf=${confidence}≥${highConfThreshold} (EntryAI=WAIT, risk=HIGH)",
                keepInWatchlist = true,
            )
        }
        // Operator z32 directive: do NOT block / purge — defer & observe.
        return Result(
            Verdict.FDG_DEFER_ENTRY_WAIT,
            "FDG_DEFER_ENTRY_WAIT EntryAI=WAIT + risk=HIGH + conf=${confidence}<${highConfThreshold}; keeping token alive for next tick",
            keepInWatchlist = true,
        )
    }
}
