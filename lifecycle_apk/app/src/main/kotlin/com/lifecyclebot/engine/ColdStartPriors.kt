package com.lifecyclebot.engine

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🌱 COLD START PRIORS — V5.0.6008
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Operator directive 2026-07-02:
 *   *"you're still tuning the bot at its current state. correct behaviour
 *    needs to be from the start and first trade so a new user gets a decent
 *    bot from trade 1"*
 *
 * ROOT CAUSE (RCA):
 * MOONSHOT is the only lane that wins from trade 1 (58% WR, +0.096 SOL)
 * because it has PERMISSIVE hardcoded defaults with no learned-behavior
 * gates. Every other lane has RESTRICTIVE floors that were tuned to
 * protect experienced users (V5.9.1307 CashGen "wrong pond" fix at
 * $25K liq, QUALITY/BLUECHIP WR-recovery boosts, danger-bucket guards).
 * These floors need >=5-30 trades of history to make sense. A fresh
 * install has zero history, so the compounders reject every candidate
 * for the first 30-50 trades and only MOONSHOT trades.
 *
 * FIX:
 * When `totalLifetimeTrades < 30`, use PERMISSIVE floors (MOONSHOT-style)
 * for the compounder lanes. Once the AGI stack has real data, the normal
 * learned floors take over. This gives every fresh install a decent
 * MOONSHOT-tier experience from trade 1 across ALL lanes, not just
 * MOONSHOT itself.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object ColdStartPriors {
    const val VERSION = "V5.0.6008_COLD_START_PRIORS"

    /** Lane graduates from cold-start priors once lifetime SELL count crosses this. */
    const val COLD_START_TRADE_THRESHOLD = 30

    @Volatile private var cachedTotal: Long = -1L
    @Volatile private var cachedAtMs: Long = 0L
    private const val TOTAL_TTL_MS = 15_000L

    /** True while the runtime is in cold-start mode (< 30 lifetime trades). */
    fun isColdStart(): Boolean {
        val now = System.currentTimeMillis()
        if (cachedTotal < 0L || (now - cachedAtMs) > TOTAL_TTL_MS) {
            cachedTotal = try {
                TradeHistoryStore.getStatsCached().totalStoredTrades.toLong()
            } catch (_: Throwable) { 0L }
            cachedAtMs = now
        }
        return cachedTotal < COLD_START_TRADE_THRESHOLD
    }

    /**
     * Cold-start liquidity floor (USD) per lane. MOONSHOT-style permissive
     * defaults that let winners flow in. Once >=30 trades accumulated, each
     * lane's normal learned floor takes over.
     */
    fun coldStartLiquidityFloor(lane: String): Double = when (lane.uppercase()) {
        "MOONSHOT"          -> 2_000.0
        "SHITCOIN"          -> 3_000.0
        "EXPRESS"           -> 3_000.0
        "STANDARD"          -> 5_000.0
        "TREASURY",
        "CASHGEN"           -> 8_000.0
        "QUALITY",
        "BLUECHIP"          -> 10_000.0
        else                -> 5_000.0
    }

    /**
     * Cold-start score floor per lane. Permissive defaults so tokens have
     * a fair shot before the learned score-band system accumulates data.
     */
    fun coldStartScoreFloor(lane: String): Int = when (lane.uppercase()) {
        "MOONSHOT"          -> 20
        "SHITCOIN"          -> 15
        "EXPRESS"           -> 20
        "STANDARD"          -> 25
        "TREASURY",
        "CASHGEN"           -> 25
        "QUALITY"           -> 30
        "BLUECHIP"          -> 30
        else                -> 20
    }

    /**
     * Apply cold-start override to a learned liquidity floor. During
     * cold-start returns the permissive floor; after cold-start returns
     * the caller's learned floor as-is.
     */
    fun applyLiquidityFloor(lane: String, learnedFloor: Double): Double {
        return if (isColdStart()) minOf(learnedFloor, coldStartLiquidityFloor(lane))
        else learnedFloor
    }

    fun statusLine(): String {
        val total = try { TradeHistoryStore.getStatsCached().totalStoredTrades } catch (_: Throwable) { 0 }
        val phase = if (isColdStart()) "COLD_START" else "GRADUATED"
        return "$VERSION phase=$phase lifetimeTrades=$total threshold=$COLD_START_TRADE_THRESHOLD"
    }
}
