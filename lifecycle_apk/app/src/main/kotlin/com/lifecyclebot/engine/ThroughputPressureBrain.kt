package com.lifecyclebot.engine

/**
 * V5.0.6143 — ThroughputPressureBrain.
 *
 * Local-only pressure signal for the 500–1000 trades/day doctrine. This is not
 * a hard gate and never calls network/LLM. It reads persisted 24h trade count
 * and returns a bounded intake/size pressure multiplier so the bot can widen
 * qualified intake when projected volume is mechanically too low.
 */
object ThroughputPressureBrain {
    data class Verdict(
        val projectedTradesPerDay: Int,
        val intakeMultiplier: Double,
        val sizeMultiplier: Double,
        val underTarget: Boolean,
        val reason: String,
    )

    fun current(): Verdict {
        val trades24h = try { TradeHistoryStore.getTradeCount24h() } catch (_: Throwable) { 0 }
        val projected = trades24h.coerceAtLeast(0)
        val intake = when {
            projected >= 1000 -> 1.00
            projected >= 700 -> 1.02
            projected >= 500 -> 1.05
            projected >= 300 -> 1.10
            projected >= 150 -> 1.16
            projected >= 75 -> 1.22
            else -> 1.28
        }
        val size = when {
            projected >= 1000 -> 1.00
            projected >= 500 -> 1.01
            projected >= 300 -> 1.03
            projected >= 150 -> 1.05
            else -> 1.07
        }
        return Verdict(
            projectedTradesPerDay = projected,
            intakeMultiplier = intake.coerceIn(1.0, 1.28),
            sizeMultiplier = size.coerceIn(1.0, 1.07),
            underTarget = projected < 500,
            reason = "projectedTradesPerDay=$projected target=500_1000 intakeMult=${"%.2f".format(intake)} sizeMult=${"%.2f".format(size)} underTarget=${projected < 500}",
        )
    }
}
