package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8037 — one liquidity number for every decision.
 *
 * 5.0.8036 log (VOCAT, ranked #1 of 296): the V3 scorer saw liq=$8,471 (TokenMapAuthority.observedLiquidityUsd —
 * the tick, then the discovery token map, then curve reserves), while the lane scorers, the Field Manual card and
 * the net-edge gate read the raw tick field ts.lastLiquidityUsd, which is 0 for pump.fun curve coins and fresh pools.
 * The same coin was "$8.5K deep" to one brain and "$0, cost 24%, exit cannot be costed" to the rest:
 * SHITCOIN NATIVE_LIQ_TOO_LOW=1,607 · EXPRESS NATIVE_LIQ_TOO_LOW=1,570 · MOONSHOT "Low liquidity $0" · net edge
 * lastCost=23.5% refusing 80 coins · first sight refused 76.
 *
 * [hydrate8037] fills a missing (<= 0) tick liquidity with the observed value before a decision reads it. It never
 * lowers or overrides a real tick value with an API number, and never invents depth: an unobserved coin stays 0 and
 * its own gates (ZERO_LIQUIDITY, Field Manual "no priced liquidity") still decide it. V5.0.8038: a fresh read of the
 * pool's vaults on chain (OnChainDepth8038) replaces any API figure, up or down — the chain is the depth.
 */
object LiquidityTruth8037 {
    private val hydrated = AtomicLong(0)
    private val stillUnknown = AtomicLong(0)
    private val chainApplied = AtomicLong(0)
    private val curveRealApplied8046 = AtomicLong(0)

    /** Pure: the value to write when the tick has no liquidity but an observation does, else null. */
    fun fill8037(tickUsd: Double, observedUsd: Double): Double? =
        if (!(tickUsd > 0.0) && observedUsd.isFinite() && observedUsd > 0.0) observedUsd else null

    /** The liquidity every decision should read for [ts]; fills the tick field when it is missing. */
    fun hydrate8037(ts: TokenState): Double {
        // V5.0.8046 — an active pump.fun curve's liquidity is the SOL buyers actually put in, not its 30 SOL of virtual
        // reserves: 5.0.8043 MOONSHOT read DOGO-type launches as liq=$2.6-3.1K at a $3-4K cap while the wallet showed
        // $1-2 (nobody had bought). Set up or down — this is the curve's own account.
        // V5.0.8173 — a stale curve reading is checked against a newer executed price and re-read from chain (LaunchCohort8173).
        val curveReal = try { LaunchCohort8173.curveRealSolFor8173(ts) } catch (_: Throwable) { null }
        if (curveReal != null) {
            val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
            if (solUsd.isFinite() && solUsd > 0.0) {
                val usd = curveReal * solUsd
                if (ts.lastLiquidityUsd != usd) { ts.lastLiquidityUsd = usd; curveRealApplied8046.incrementAndGet() }
                // V5.0.8048 — the token map keeps the same truth (it held the intake estimate, mcap x 0.85: BOWNARROW $2,608
                // in the map while V3 read $0), so every reader of the map agrees with the curve's own account.
                try { ts.tokenMap.liquidityUsd = usd } catch (_: Throwable) {}
                return usd
            }
        }
        // V5.0.8038 — a fresh on-chain read of the pool's vaults (OnChainDepth8038) is the depth, ahead of any API figure.
        val chain = try { OnChainDepth8038.chainDepthUsd8038(ts.mint) } catch (_: Throwable) { Double.NaN }
        if (chain.isFinite() && chain > 0.0) {
            if (ts.lastLiquidityUsd != chain) {
                try { OnChainDepth8038.noteApi8038(ts.mint, ts.lastLiquidityUsd) } catch (_: Throwable) {}
                ts.lastLiquidityUsd = chain; chainApplied.incrementAndGet()
            }
            return chain
        }
        val obs = try { com.lifecyclebot.engine.TokenMapAuthority.observedLiquidityUsd(ts) } catch (_: Throwable) { 0.0 }
        fill8037(ts.lastLiquidityUsd, obs)?.let { ts.lastLiquidityUsd = it; hydrated.incrementAndGet() }
        // V5.0.8164 — an ACTIVE pump.fun curve with a priced cap but no reserve frame inside the curve TTL: its real SOL
        // follows from the cap by the curve's own invariant ([curveRealSolFromCap8048]), so it is derived, not invented.
        // 5.0.8162 fresh run: SHITCOIN NATIVE_LANE_LIQ_UNHYDRATED_DEFER=1,633 and EXPRESS 1,606 on curve coins whose
        // cap every scorer already used, and forward labels on the same coins costed at the 20% unknown-depth impact.
        if (!(ts.lastLiquidityUsd > 0.0)) curveDepthFromCap8164(ts)?.let { ts.lastLiquidityUsd = it; curveCapDerived8164.incrementAndGet() }
        val v = ts.lastLiquidityUsd
        if (!(v > 0.0)) {
            stillUnknown.incrementAndGet()
            // V5.0.8039 — unknown depth: read the coin's curve from the chain in the next batch.
            // V5.0.8047 — and its pool's vaults when it names one (graduated / other venues have no curve).
            try { OnChainDepth8038.needDepth8047(ts) } catch (_: Throwable) {}
        }
        return if (v.isFinite() && v > 0.0) v else 0.0
    }

    /**
     * Pure (V5.0.8048): the SOL buyers have put into a pump.fun curve, from its market cap in SOL. The curve holds
     * 30 + r virtual SOL against 30 x 1.073B / (30 + r) virtual tokens over a 1B supply, so cap = (30 + r)^2 / 32.19.
     * Replaces the intake guesses (cap x 0.85, virtual reserves x a hard-coded $170) that fed every gate a fake depth.
     */
    /** V5.0.8164 — USD depth of an active (not graduated) pump.fun curve from its trusted cap, or null. */
    private fun curveDepthFromCap8164(ts: TokenState): Double? {
        val mint = ts.mint
        if (mint.isBlank()) return null
        if (!(try { com.lifecyclebot.network.PumpFunDirectApi.isPumpFunMint(mint) } catch (_: Throwable) { false })) return null
        if (try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(mint) } catch (_: Throwable) { true }) return null
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        val trusted = try { TrustedMcap8019.mcap8019(ts) } catch (_: Throwable) { Double.NaN }
        val capUsd = curveCapUsd8317(mint, trusted, ts.lastPrice)
        if (!(trusted > 0.0) && capUsd.isFinite()) capFromPrice8317.incrementAndGet()
        return curveDepthUsd8164(capUsd, solUsd)
    }

    private val capFromPrice8317 = AtomicLong(0)

    /**
     * Pure (V5.0.8317): the cap an active curve's depth is derived from — the trusted cap when it is priced; else, for a
     * *pump mint with an observed price, price x the curve's fixed 1e9 supply (TrustedMcap8019.PUMP_SUPPLY_8019). 8314:
     * TrustedMcap8019.mcap8019 returned the row cap (0) when the price source was not independent, so a brand-new curve
     * priced only by its own snapshot read $0 of depth — under the $500 live floor — just as it started running. Any other
     * mint, or no price: NaN (nothing is derived). The depth itself still follows the curve invariant ([curveDepthUsd8164]).
     */
    fun curveCapUsd8317(mint: String, trustedCapUsd: Double, lastPriceUsd: Double): Double = when {
        trustedCapUsd.isFinite() && trustedCapUsd > 0.0 -> trustedCapUsd
        mint.endsWith("pump", ignoreCase = true) && lastPriceUsd.isFinite() && lastPriceUsd > 0.0 -> lastPriceUsd * TrustedMcap8019.PUMP_SUPPLY_8019
        else -> Double.NaN
    }

    /**
     * Pure (V5.0.8164): the curve's real SOL in USD from a cap in USD. Null when the price or cap is unknown, or the cap
     * is past what an active curve can carry ([MAX_ACTIVE_CURVE_CAP_SOL_8164]: the curve completes near 85 real SOL).
     */
    fun curveDepthUsd8164(capUsd: Double, solUsd: Double): Double? {
        if (!(capUsd.isFinite() && capUsd > 0.0 && solUsd.isFinite() && solUsd > 0.0)) return null
        val capSol = EconomicUnitInvariant7061.usdToSol(capUsd, solUsd)
        if (capSol > MAX_ACTIVE_CURVE_CAP_SOL_8164) return null
        val real = curveRealSolFromCap8048(capSol)
        return if (real > 0.0) real * solUsd else null
    }

    private const val MAX_ACTIVE_CURVE_CAP_SOL_8164 = 411.0   // (30 + 85)^2 / 32.19: the curve completes at ~85 real SOL
    private val curveCapDerived8164 = AtomicLong(0)

    fun curveRealSolFromCap8048(capSol: Double): Double =
        if (capSol.isFinite() && capSol > 0.0) (kotlin.math.sqrt(capSol * 30.0 * 1.073) - 30.0).coerceAtLeast(0.0) else 0.0

    fun statusLine(): String =
        "curveReal=${curveRealApplied8046.get()} curveCap8164=${curveCapDerived8164.get()} capFromPrice8317=${capFromPrice8317.get()} chainApplied=${chainApplied.get()} hydrated=${hydrated.get()} stillUnknown=${stillUnknown.get()} rule=chain vaults first (§8038), then tick liquidity 0 -> observed (token map, curve reserves); never lowered, never invented"
}
