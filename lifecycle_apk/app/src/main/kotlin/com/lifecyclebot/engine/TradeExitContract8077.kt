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

    // V5.0.8104 — owner: every live meme buy rides the runner ladder from entry. 5.0.8098: a MOONSHOT trade entered as
    // CHART_BREAKOUT / PUMP_GRADUATION_SNIPE / ... was not a long-hold style, so the runner ladder, its soft-exit hold
    // and the moonbag were all off and the learned pop-fade plan sold +24/+46/+70 with a 10-minute clock (runner
    // promoted=0, moonbag held=0; Nihilism +1547%, RAM +1110%, SCREAM +1133% missed). CASHGEN stays a scalp lane.
    private val runnerFromEntryLanes8104 = setOf("MOONSHOT", "SHITCOIN", "EXPRESS", "PROJECT_SNIPER")

    /** Pure: does a live position in [lane] ride the runner ladder from its entry? */
    fun runnerFromEntryLane8104(lane: String?, paper: Boolean): Boolean =
        !paper && com.lifecyclebot.engine.truth.LiveRiskPolicy7807.canonicalLane(lane) in runnerFromEntryLanes8104

    fun runnerFromEntry8104(ts: TokenState): Boolean =
        ts.position.isOpen && ts.position.entryTime > 0L && runnerFromEntryLane8104(ts.position.tradingMode, ts.position.isPaperPosition)

    fun longHold(ts: TokenState): Boolean? {
        val snap = ts.position.positionId.takeIf { it.isNotBlank() }?.let {
            EntryStrategySnapshot6450.snapshot(it)
        }?.takeIf { it.mint == ts.mint && it.entryTimestampMs == ts.position.entryTime }
        val sealedStyle = snap?.entryStyle?.takeIf { it.isNotBlank() } ?: ts.position.entryPolicySnapshot
            .split(';').firstOrNull { it.startsWith("style=") }?.substringAfter('=') ?: ""
        return longHoldStyle(sealedStyle)
    }
}
