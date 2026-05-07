package com.lifecyclebot.engine

/**
 * V5.9.495z31 — Hot-path lane separation flags.
 *
 * Operator brief:
 *   Lane 1: MemeTrader hot path
 *   Lane 2: CryptoUniverse live route/execution path
 *   Lane 3: Position monitoring
 *   Lane 4: MarketsTrader (stocks/commodities/metals/forex/ETFs)
 *   Lane 5: Background scans (Gecko, dyn registry, RugCheck retries)
 *   Lane 6: Personality / LLM / lab
 *
 *   "Lane 1 and Lane 2 must never wait on lanes 4–6."
 *
 * This object is an additive companion to LiveExecutionGate. Callers
 * in MemeTrader / CryptoUniverseTrader test `mayProceedHotPath()`;
 * callers in lanes 4–6 test `mustYieldToHot()` and skip their cycle
 * when the hot lanes are busy.
 */
object HotPathLaneGate {

    enum class Lane {
        MEME,
        CRYPTO_UNIVERSE,
        POSITION_MONITOR,
        MARKETS,
        BACKGROUND,
        PERSONALITY_LLM,
    }

    @Volatile private var hotBusy: Boolean = false
    @Volatile private var lastHotTickMs: Long = 0L

    fun markHotTick() {
        hotBusy = true
        lastHotTickMs = System.currentTimeMillis()
    }

    fun markHotIdle() {
        hotBusy = false
    }

    /** Lanes 1–2 always proceed. */
    fun mayProceedHotPath(): Boolean = true

    /** Lanes 4–6 yield while a hot tick has happened in the last N ms. */
    fun mustYieldToHot(yieldWindowMs: Long = 2_000L): Boolean {
        if (hotBusy) return true
        return (System.currentTimeMillis() - lastHotTickMs) < yieldWindowMs
    }

    fun shouldSkipBackgroundScans(): Boolean = mustYieldToHot()

    fun shouldSkipMarkets(): Boolean = mustYieldToHot(yieldWindowMs = 1_000L)

    fun shouldSkipPersonalityLLM(): Boolean = mustYieldToHot(yieldWindowMs = 5_000L)
}
