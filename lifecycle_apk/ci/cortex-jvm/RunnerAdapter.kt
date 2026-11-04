package com.lifecyclebot.engine

/** Exit orchestration is outside this pure-model harness; accidental calls fail instead of granting authority. */
object LifecycleHold8065 {
    fun lifecycleLane8065(lane: String): Boolean = error("Pure Cortex tests must not decide lifecycle exit ownership")
}
object RunnerTrail8419 {
    fun runnerLaneAt8468(mode: String, owner: String, peakPct: Double): Boolean =
        error("Pure Cortex tests must not decide a live position's runner exit contract")
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
