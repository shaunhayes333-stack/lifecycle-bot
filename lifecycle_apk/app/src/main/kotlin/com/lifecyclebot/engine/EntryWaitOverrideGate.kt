package com.lifecyclebot.engine

/**
 * V5.9.495z31 — Final-Decision-Gate vs EntryAI WAIT enforcement.
 *
 * Operator-reported gate conflict from logs:
 *
 *   "Entry Score: 64 → WAIT | risk=HIGH | Strong buy pressure,
 *    High volume activity, RSI overbought"
 *   "Decision: GMAR quality=B | edge=C | conf=39% | penalty=1.0 |
 *    shouldTrade=true"
 *
 * Hard rule:
 *   - If EntryAI returned WAIT and risk=HIGH, the FDG must NOT trade
 *     unless an explicit moonshot override or a high-confidence
 *     override is present.
 *   - When the FDG overrides anyway, it must log the exact reason.
 */
object EntryWaitOverrideGate {

    enum class Verdict {
        ALLOW,                       // EntryAI was not WAIT, or risk was not HIGH
        FDG_BLOCK_ENTRY_WAIT,        // blocked by this gate
        FDG_OVERRIDE_ENTRY_WAIT      // explicit override (caller must supply reason)
    }

    data class Result(
        val verdict: Verdict,
        val reason: String,
    )

    /**
     * @param entryWait        true when EntryAI returned WAIT
     * @param riskHigh         true when risk=HIGH
     * @param moonshotOverride true when MoonshotTraderAI fires explicit override
     * @param confidence       FDG confidence, 0–100
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
            return Result(Verdict.ALLOW, "EntryAI=${if (entryWait) "WAIT" else "OK"} risk=${if (riskHigh) "HIGH" else "≤HIGH"}")
        }
        if (moonshotOverride) {
            return Result(Verdict.FDG_OVERRIDE_ENTRY_WAIT,
                "FDG_OVERRIDE_REASON=moonshot_override (EntryAI=WAIT, risk=HIGH)")
        }
        if (confidence >= highConfThreshold) {
            return Result(Verdict.FDG_OVERRIDE_ENTRY_WAIT,
                "FDG_OVERRIDE_REASON=conf=${confidence}≥${highConfThreshold} (EntryAI=WAIT, risk=HIGH)")
        }
        return Result(Verdict.FDG_BLOCK_ENTRY_WAIT,
            "FDG_BLOCK_ENTRY_WAIT EntryAI=WAIT + risk=HIGH + conf=${confidence}<${highConfThreshold} + no_moonshot_override")
    }
}
