package com.lifecyclebot.engine.sell

object SellSafetyPolicy {
    const val pumpPortalPartialSellEnabled = false
    const val pumpPortalRescueSellEnabled = false

    /**
     * V5.9.1533 — HARD slippage ceiling for LIVE sells (operator spec item 2).
     * Normal live exits may NEVER exceed 500 bps. The legacy 800/1000bps sell ladder
     * is removed from live mode. A path that needs more must fail-closed and queue,
     * UNLESS it is an explicit emergency/catastrophe exit, which is allowed to use the
     * emergency config but MUST log SELL_EMERGENCY_SLIPPAGE_OVERRIDE. There is no
     * hidden 10% (1000bps) default for sells.
     */
    const val HARD_MAX_SELL_SLIPPAGE_BPS = 500
    const val EMERGENCY_MAX_SELL_SLIPPAGE_BPS = 9999

    fun isManualEmergency(reason: String?): Boolean {
        val r = reason.orEmpty().uppercase()
        return r.contains("MANUAL") && (r.contains("EMERGENCY") || r.contains("RUG") || r.contains("DRAIN"))
    }
    fun isHardRug(reason: String?): Boolean {
        val r = reason.orEmpty().uppercase()
        return r.contains("RUG_DRAIN") || r.contains("HARD_RUG") || r.contains("LIQUIDITY_COLLAPSE") || r.contains("CATASTROPHE")
    }

    /** An exit is allowed beyond the 500bps hard cap ONLY if it is an explicit emergency. */
    fun isEmergencyExit(reason: String?): Boolean = isHardRug(reason) || isManualEmergency(reason)

    fun classify(reason: String?): ExitReason = SellReasonClassifier.fromString(reason)

    /**
     * The effective max slippage for a LIVE sell. Clamped to HARD_MAX_SELL_SLIPPAGE_BPS
     * (500) for every non-emergency reason. Emergency exits get the emergency ceiling
     * and the caller MUST emit SELL_EMERGENCY_SLIPPAGE_OVERRIDE (see logEmergencyOverride).
     */
    fun maxSlippageBps(reason: String?): Int {
        if (isEmergencyExit(reason)) {
            logEmergencyOverride(reason)
            return EMERGENCY_MAX_SELL_SLIPPAGE_BPS
        }
        // Every normal live exit reason is hard-capped at 500bps. No 800/1000 ladder.
        return HARD_MAX_SELL_SLIPPAGE_BPS
    }

    private fun logEmergencyOverride(reason: String?) {
        try {
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "SELL_EMERGENCY_SLIPPAGE_OVERRIDE",
                "reason=${reason.orEmpty()} ceilingBps=$EMERGENCY_MAX_SELL_SLIPPAGE_BPS hardCapBps=$HARD_MAX_SELL_SLIPPAGE_BPS")
        } catch (_: Throwable) {}
    }

    fun initialSlippageBps(reason: String?): Int = when (classify(reason)) {
        ExitReason.PROFIT_LOCK, ExitReason.PARTIAL_TAKE_PROFIT, ExitReason.CAPITAL_RECOVERY -> 200
        ExitReason.STOP_LOSS, ExitReason.HARD_STOP, ExitReason.RUG_DRAIN, ExitReason.MANUAL_FULL_EXIT -> 500
        ExitReason.UNKNOWN -> 200
    }.coerceAtMost(maxSlippageBps(reason))

    /**
     * Live slippage ladder. Non-emergency reasons are capped to a 200→500 walk; the
     * 800/1000bps rungs are GONE from live mode. Emergency reasons keep the escalation
     * ladder (the override is logged via maxSlippageBps).
     */
    fun ladder(reason: String?): List<Int> {
        val max = maxSlippageBps(reason)
        val base = if (isEmergencyExit(reason)) {
            listOf(500, 1500, 3000, 5000, 7500, 9999)
        } else {
            // Hard-capped non-emergency walk. Never exceeds 500.
            listOf(200, 350, 500)
        }
        return base.map { it.coerceAtMost(max) }.distinct()
    }
}
