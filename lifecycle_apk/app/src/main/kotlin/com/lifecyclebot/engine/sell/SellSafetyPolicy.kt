package com.lifecyclebot.engine.sell

object SellSafetyPolicy {
    const val pumpPortalPartialSellEnabled = false
    const val pumpPortalRescueSellEnabled = false

    fun isManualEmergency(reason: String?): Boolean {
        val r = reason.orEmpty().uppercase()
        return r.contains("MANUAL") && (r.contains("EMERGENCY") || r.contains("RUG") || r.contains("DRAIN"))
    }
    fun isHardRug(reason: String?): Boolean {
        val r = reason.orEmpty().uppercase()
        return r.contains("RUG_DRAIN") || r.contains("HARD_RUG") || r.contains("LIQUIDITY_COLLAPSE") || r.contains("CATASTROPHE")
    }
    fun classify(reason: String?): ExitReason = SellReasonClassifier.fromString(reason)
    fun maxSlippageBps(reason: String?): Int = when (classify(reason)) {
        ExitReason.PROFIT_LOCK, ExitReason.PARTIAL_TAKE_PROFIT -> 500
        ExitReason.CAPITAL_RECOVERY -> 800
        ExitReason.STOP_LOSS, ExitReason.HARD_STOP -> 1200
        ExitReason.RUG_DRAIN -> if (isHardRug(reason) || isManualEmergency(reason)) 9999 else 1200
        ExitReason.MANUAL_FULL_EXIT -> if (isManualEmergency(reason)) 9999 else 1200
        ExitReason.UNKNOWN -> 500
    }
    fun initialSlippageBps(reason: String?): Int = when (classify(reason)) {
        ExitReason.PROFIT_LOCK, ExitReason.PARTIAL_TAKE_PROFIT, ExitReason.CAPITAL_RECOVERY -> 200
        ExitReason.STOP_LOSS, ExitReason.HARD_STOP, ExitReason.RUG_DRAIN, ExitReason.MANUAL_FULL_EXIT -> 500
        ExitReason.UNKNOWN -> 200
    }.coerceAtMost(maxSlippageBps(reason))
    fun ladder(reason: String?): List<Int> {
        val max = maxSlippageBps(reason)
        val base = when {
            max > 1200 && (isHardRug(reason) || isManualEmergency(reason)) -> listOf(500, 1500, 3000, 5000, 7500, 9999)
            classify(reason) == ExitReason.CAPITAL_RECOVERY -> listOf(200, 500, 800)
            classify(reason) == ExitReason.STOP_LOSS || classify(reason) == ExitReason.HARD_STOP -> listOf(500, 800, 1200)
            else -> listOf(200, 500)
        }
        return base.map { it.coerceAtMost(max) }.distinct()
    }
}
