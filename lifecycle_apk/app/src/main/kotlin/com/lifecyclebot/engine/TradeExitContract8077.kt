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

    // V5.0.8136 — the lane only says a lane CAN host a runner. Whether THIS position rides the runner ladder, its
    // soft-exit deferral, the moonbag and the no-pop-fade-clock rule is its immutable sealed setup or a PROVEN
    // promotion (RunnerExitContract8136.runnerBasis8136): a QUICK_FLIP scalp bought in MOONSHOT no longer holds 6 h.
    fun runnerFromEntry8104(ts: TokenState): Boolean =
        ts.position.isOpen && ts.position.entryTime > 0L && runnerFromEntryLane8104(ts.position.tradingMode, ts.position.isPaperPosition) &&
            runnerBasis8136(ts) != RunnerExitContract8136.RunnerBasis8136.NONE

    /**
     * V5.0.8136 — why [ts] rides the runner contract: its sealed entry setup, or proof (position.peakGainPct ratchets
     * only from the chain-verified mark and refuses an uncorroborated jump, PositionDisplayTruth8078 8080/8128).
     */
    fun runnerBasis8136(ts: TokenState): RunnerExitContract8136.RunnerBasis8136 =
        RunnerExitContract8136.runnerBasis8136(laneHostsRunners = true, sealedStyle = sealedStyle8136(ts), corroboratedPeakPct = ts.position.peakGainPct)

    /** V5.0.8136 — the style sealed at entry (the entry snapshot for this exact position, else the policy snapshot). */
    fun sealedStyle8136(ts: TokenState): String {
        val snap = ts.position.positionId.takeIf { it.isNotBlank() }?.let {
            EntryStrategySnapshot6450.snapshot(it)
        }?.takeIf { it.mint == ts.mint && it.entryTimestampMs == ts.position.entryTime }
        return snap?.entryStyle?.takeIf { it.isNotBlank() } ?: ts.position.entryPolicySnapshot
            .split(';').firstOrNull { it.startsWith("style=") }?.substringAfter('=') ?: ""
    }

    fun longHold(ts: TokenState): Boolean? = longHoldStyle(sealedStyle8136(ts))
}
