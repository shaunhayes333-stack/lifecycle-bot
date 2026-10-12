package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.EntryStrategySnapshot6450

/** Exit overlays follow the immutable trade style, never the scanner's current lane or style. */
object TradeExitContract8077 {
    internal fun longHoldStyle(style: String): Boolean? {
        val normalized = style.trim().uppercase()
        if (normalized.isBlank() || normalized == "UNKNOWN") return null
        val selected = AgenticStyleRouter.Style.values().firstOrNull {
            it.name == normalized
        } ?: return null
        return selected in longStyles8077
    }

    private val longStyles8077 = setOf(AgenticStyleRouter.Style.DIAMOND_HANDS_RUNNER,
            AgenticStyleRouter.Style.BREAKOUT_RUNNER, AgenticStyleRouter.Style.SWING_HOLD,
            AgenticStyleRouter.Style.WHALE_ACCUMULATION_HOLD, AgenticStyleRouter.Style.MAINSTREAM_CRYPTO_SWING,
            AgenticStyleRouter.Style.WHALE_FOLLOW, AgenticStyleRouter.Style.REACCUMULATION)

    fun longHold(ts: TokenState): Boolean? {
        val snap = ts.position.positionId.takeIf { it.isNotBlank() }?.let {
            EntryStrategySnapshot6450.snapshot(it)
        }?.takeIf { it.mint == ts.mint && it.entryTimestampMs == ts.position.entryTime }
        val sealedStyle = snap?.entryStyle?.takeIf { it.isNotBlank() } ?: ts.position.entryPolicySnapshot
            .split(';').firstOrNull { it.startsWith("style=") }?.substringAfter('=') ?: ""
        return longHoldStyle(sealedStyle)
    }
}
