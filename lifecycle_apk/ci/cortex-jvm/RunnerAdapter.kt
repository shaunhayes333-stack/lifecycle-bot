package com.lifecyclebot.engine

/** Standalone runner-lane service adapter; production exits are not tested. */
object RunnerExitProfile7277 {
    private val runnerKeys = arrayOf(
        "MOONSHOT", "SHITCOIN", "MEME", "EXPRESS", "MANIPULATED", "MANIP",
        "PRESALE", "PROJECT_SNIPER", "DIP_HUNTER", "INSIDER_SHARK", "COPY_TRADE",
        "WHALE_FOLLOW", "CORE",
    )
    fun isRunnerLane(lane: String?): Boolean =
        lane?.trim()?.uppercase()?.let { value -> runnerKeys.any { value.contains(it) } } ?: false
}

/** Only the desk vocabulary; no service snapshot, admission or execution path. */
object ToolkitSignalSheet {
    enum class Setup {
        NONE, DIAMOND_HANDS_RUNNER, DEGEN_MICRO_SNIPE, PUMP_GRADUATION_SNIPE,
        CHART_BREAKOUT, CHART_PULLBACK_RECLAIM, WHALE_ACCUMULATION_HOLD,
        EXHAUSTION_QUICK_FLIP, MAINSTREAM_CRYPTO_SWING, VOLUME_IGNITION_SCALP,
        SMART_WALLET_COPY_FOLLOW, NARRATIVE_SOCIAL_IGNITION, LIQUIDITY_DEPTH_QUALITY,
        PANIC_REVERSION_BOUNCE, ARB_FLOW_IMBALANCE, MEV_PROTECTED_ENTRY,
        REENTRY_RECOVERY, CASHFLOW_SCALP, CYCLIC_COMPOUND, REGIME_DEFENSIVE_PROBE,
    }
}
