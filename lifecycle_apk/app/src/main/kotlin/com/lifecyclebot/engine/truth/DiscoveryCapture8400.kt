package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8400 §CAPTURED_WITH_THE_TOKEN_AT_DISCOVERY — the operator's rule: a token's market cap (and the inputs every
 * specialist grades it on) is captured when the token is discovered, not hydrated lazily after a lane has already
 * refused it as "mcap unknown".
 *
 * 5.0.8399 run: MOONSHOT NATIVE_MCAP_UNHYDRATED_DEFER=925 of 2,348 readies and MCAP_HYDRATED_FROM_CURVE_8399=0 — the
 * deferred coins reached the specialists with lastMcap=0 because their intake source carried none (HOT_CONVICTION_WARMUP
 * passes NaN, PROBATION / restore rows carry no cap, a scanner row whose aggregator had no fdv) and nothing at intake
 * resolved it from what the app already knew. This is the one resolver, in evidence order, each step an existing
 * authority (nothing invented, no new provider surface):
 *
 *  1. the row already has a cap                         -> ROW
 *  2. the curve's own reserves (frame / account read)   -> CURVE_RESERVES   (CurveReserves7951.hydrateMcap8399)
 *  3. independent price x resolved supply               -> PRICE_X_SUPPLY   (TrustedMcap8019.mcap8019, writes the row)
 *  4. an active pump curve's real SOL -> cap invariant  -> CURVE_FROM_LIQUIDITY (inverse of LiquidityTruth8037.curveRealSolFromCap8048)
 *  5. none of the above: queue the batched chain read   -> CHAIN_READ_QUEUED (OnChainDepth8038.needDepth8047; the next
 *     evaluate re-runs this and the fingerprint carries lastMcap)
 *
 * Called at intake (admitProtectedMemeIntake, every source) and as the backstop at the top of
 * SpecialistBrainBridge7542.evaluate. Labels name the resolver and, when nothing resolved, the intake source, so the
 * report shows which discovery path delivers incomplete tokens.
 */
object DiscoveryCapture8400 {
    const val ROW = "ROW"
    const val CURVE_RESERVES = "CURVE_RESERVES"
    const val PRICE_X_SUPPLY = "PRICE_X_SUPPLY"
    const val CURVE_FROM_LIQUIDITY = "CURVE_FROM_LIQUIDITY"
    const val CHAIN_READ_QUEUED = "CHAIN_READ_QUEUED"
    const val UNRESOLVED = "UNRESOLVED"

    /** pump.fun curve invariant: 30 + r virtual SOL over a 1B supply gives cap = (30 + r)^2 / 32.19 SOL (inverse of 8048). */
    private const val CURVE_K_8400 = 30.0 * 1.073

    private val resolved = AtomicLong(0)
    private val queued = AtomicLong(0)
    private val unresolved = AtomicLong(0)

    /** Pure: an active curve's cap in SOL from the real SOL buyers put in, or NaN. */
    fun curveCapSolFromRealSol8400(realSol: Double): Double =
        if (realSol.isFinite() && realSol >= 0.0) (realSol + 30.0) * (realSol + 30.0) / CURVE_K_8400 else Double.NaN

    /** Pure: the cap in USD an active curve's USD depth implies, or NaN. */
    fun curveCapUsdFromLiquidity8400(liquidityUsd: Double, solUsd: Double): Double {
        if (!(liquidityUsd.isFinite() && liquidityUsd > 0.0 && solUsd.isFinite() && solUsd > 0.0)) return Double.NaN
        val capSol = curveCapSolFromRealSol8400(liquidityUsd / solUsd)
        return if (capSol.isFinite() && capSol > 0.0) capSol * solUsd else Double.NaN
    }

    private fun activeCurve8400(mint: String): Boolean =
        (try { com.lifecyclebot.network.PumpFunDirectApi.isPumpFunMint(mint) } catch (_: Throwable) { false }) &&
            !(try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(mint) } catch (_: Throwable) { true })

    /**
     * Resolve [ts]'s market cap from what the app already knows; queue the chain read when nothing does.
     * Returns the resolver name. [where] is the caller ("INTAKE_<source>" / "BRIDGE") for the unresolved label.
     */
    fun captureMcap8400(ts: TokenState, where: String, solUsd: Double = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice): String {
        if (ts.mint.isBlank()) return UNRESOLVED
        if (ts.lastMcap.isFinite() && ts.lastMcap > 0.0) return ROW
        var how = UNRESOLVED
        try {
            val v = CurveReserves7951.hydrateMcap8399(ts, solUsd)
            if (v.isFinite() && v > 0.0) how = CURVE_RESERVES
        } catch (_: Throwable) {}
        if (how == UNRESOLVED) try {
            val v = TrustedMcap8019.mcap8019(ts)   // writes the price-implied cap back to the row when it can
            if (v.isFinite() && v > 0.0) how = PRICE_X_SUPPLY
        } catch (_: Throwable) {}
        if (how == UNRESOLVED && activeCurve8400(ts.mint)) {
            val cap = curveCapUsdFromLiquidity8400(ts.lastLiquidityUsd, solUsd)
            if (cap.isFinite() && cap > 0.0) {
                synchronized(ts) { if (!(ts.lastMcap.isFinite() && ts.lastMcap > 0.0)) ts.lastMcap = cap }
                how = CURVE_FROM_LIQUIDITY
            }
        }
        if (how == UNRESOLVED) {
            try { OnChainDepth8038.needDepth8047(ts) } catch (_: Throwable) {}
            how = if (activeCurve8400(ts.mint) || ts.mint.endsWith("pump", ignoreCase = true)) CHAIN_READ_QUEUED else UNRESOLVED
        }
        when (how) {
            CHAIN_READ_QUEUED -> queued.incrementAndGet()
            UNRESOLVED -> unresolved.incrementAndGet()
            else -> resolved.incrementAndGet()
        }
        try {
            PipelineHealthCollector.labelInc("DISCOVERY_MCAP_${how}_8400")
            if (how == UNRESOLVED || how == CHAIN_READ_QUEUED) PipelineHealthCollector.labelInc("DISCOVERY_MCAP_MISSING_8400_${where.take(40)}")
        } catch (_: Throwable) {}
        return how
    }

    fun statusLine(): String = "discoveryMcap8400 resolved=${resolved.get()} chainQueued=${queued.get()} unresolved=${unresolved.get()}"
}
