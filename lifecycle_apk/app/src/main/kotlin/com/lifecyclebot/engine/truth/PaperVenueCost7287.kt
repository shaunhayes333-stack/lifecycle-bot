package com.lifecyclebot.engine.truth

/**
 * V5.0.7287 §A PAPER TRADE PAYS WHAT THE VENUE CHARGES.
 *
 * Operator: "the fees needs to be investigated correctly. go to the internet
 * research the correct average." The paper ledger read realized +0.95 SOL
 * against fees 2.72 SOL over 574 operations. Measured against the code, one
 * dust pump.fun round trip was charged, before any price move:
 *
 *   buy   +5% on price (liquidity tier) and 0.5% off the tokens AND 0.5% as
 *         a separate cash debit — the same fee twice
 *   sell  -5% on price (liquidity tier) and 1.6% plus a LEARNED slippage
 *         term (up to 8%) — slippage charged twice, once as the tier and
 *         once as the learned term the tier already models
 *
 * about 12-20% per round trip. What the venues publish (Sept 2026):
 *
 *   pump.fun bonding curve     1.25% per trade (protocol + creator)
 *   PumpSwap                   0.20% LP + 0.05% protocol, plus a creator
 *                              fee of up to 0.95% under a $300k cap that
 *                              tapers to 0.05% past $20M
 *   Raydium / Meteora          about 0.25%
 *   Jupiter routing            no platform fee on standard swaps
 *   network                    5,000 lamports base; priority ~0.0002 SOL
 *                              typical, spiking on hot launches; tips
 *                              0.0001-0.001 SOL
 *
 * This object prices each leg from those numbers and from the one thing a
 * simulation must compute rather than look up: price impact, which is the
 * clip against the pool's SOL-side depth (constant product,
 * impact = clip / (depth + clip)). The fixed leg is 0.000805 SOL a side,
 * the same number FeeAwareSizeFloor7277 sizes against (0.00161 a round
 * trip), so the floor and the ledger now agree.
 *
 * The app's own trading fee (Executor.MEME_TRADING_FEE_PERCENT, 0.5% a
 * side, the operator's fee-split revenue) is a real live cost and is added
 * by the caller, not here.
 */
object PaperVenueCost7287 {
    enum class Venue { BONDING_CURVE, PUMPSWAP, AMM }

    /** Priority fee + tip + base fee, per side. Matches FeeAwareSizeFloor7277. */
    const val FIXED_SOL_PER_SIDE = 0.000805

    private const val CURVE_FEE_PCT = 1.25
    private const val PUMPSWAP_BASE_FEE_PCT = 0.25
    private const val AMM_FEE_PCT = 0.25

    /** A pump.fun curve graduates near 85 real SOL; its reported USD liquidity stays under this. */
    private const val CURVE_LIQUIDITY_CEILING_USD = 15_000.0

    /** A fresh curve holds ~30 virtual SOL on its SOL side before any buys. */
    private const val CURVE_VIRTUAL_SOL = 30.0

    private const val IMPACT_UNKNOWN_DEPTH_PCT = 2.0
    private const val IMPACT_CAP_PCT = 15.0

    private fun isPumpMint(mint: String): Boolean =
        mint.endsWith("pump") || try {
            com.lifecyclebot.network.PumpCurveKeys7269.keyFor(mint) != null
        } catch (_: Throwable) { false }

    fun venue(mint: String, liquidityUsd: Double): Venue = when {
        !isPumpMint(mint) -> Venue.AMM
        liquidityUsd > 0.0 && liquidityUsd < CURVE_LIQUIDITY_CEILING_USD -> Venue.BONDING_CURVE
        liquidityUsd <= 0.0 -> Venue.BONDING_CURVE
        else -> Venue.PUMPSWAP
    }

    /** PumpSwap's dynamic creator fee by market cap. */
    private fun pumpSwapCreatorFeePct(mcapUsd: Double): Double = when {
        mcapUsd <= 0.0 -> 0.95
        mcapUsd < 300_000.0 -> 0.95
        mcapUsd < 1_000_000.0 -> 0.50
        mcapUsd < 20_000_000.0 -> 0.20
        else -> 0.05
    }

    /** The venue's own trading fee on one side, in percent of notional. */
    fun venueFeePct(mint: String, liquidityUsd: Double, mcapUsd: Double): Double =
        when (venue(mint, liquidityUsd)) {
            Venue.BONDING_CURVE -> CURVE_FEE_PCT
            Venue.PUMPSWAP -> PUMPSWAP_BASE_FEE_PCT + pumpSwapCreatorFeePct(mcapUsd)
            Venue.AMM -> AMM_FEE_PCT
        }

    /**
     * Price impact of one side, in percent: the clip against the pool's
     * SOL-side depth. Reported liquidity is both sides, so the SOL side is
     * half of it; a curve never has less than its virtual reserve.
     */
    fun impactPct(mint: String, clipSol: Double, liquidityUsd: Double, solUsd: Double): Double {
        if (!clipSol.isFinite() || clipSol <= 0.0) return 0.0
        if (!solUsd.isFinite() || solUsd <= 0.0) return IMPACT_UNKNOWN_DEPTH_PCT
        val v = venue(mint, liquidityUsd)
        val sideSol = if (liquidityUsd.isFinite() && liquidityUsd > 0.0) {
            EconomicUnitInvariant7061.usdToSol(liquidityUsd / 2.0, solUsd).takeIf { it.isFinite() } ?: 0.0
        } else 0.0
        val depthSol = if (v == Venue.BONDING_CURVE) maxOf(sideSol, CURVE_VIRTUAL_SOL) else sideSol
        if (depthSol <= 0.0) return IMPACT_UNKNOWN_DEPTH_PCT
        return (clipSol / (depthSol + clipSol) * 100.0).coerceIn(0.0, IMPACT_CAP_PCT)
    }
}
