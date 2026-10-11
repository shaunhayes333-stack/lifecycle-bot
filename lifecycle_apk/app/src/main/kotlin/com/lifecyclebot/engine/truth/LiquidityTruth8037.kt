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

    /** Pure: the value to write when the tick has no liquidity but an observation does, else null. */
    fun fill8037(tickUsd: Double, observedUsd: Double): Double? =
        if (!(tickUsd > 0.0) && observedUsd.isFinite() && observedUsd > 0.0) observedUsd else null

    /** The liquidity every decision should read for [ts]; fills the tick field when it is missing. */
    fun hydrate8037(ts: TokenState): Double {
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
        val v = ts.lastLiquidityUsd
        if (!(v > 0.0)) {
            stillUnknown.incrementAndGet()
            // V5.0.8039 — unknown depth: read the coin's curve from the chain in the next batch.
            try { OnChainDepth8038.needCurve8039(ts.mint) } catch (_: Throwable) {}
        }
        return if (v.isFinite() && v > 0.0) v else 0.0
    }

    fun statusLine(): String =
        "chainApplied=${chainApplied.get()} hydrated=${hydrated.get()} stillUnknown=${stillUnknown.get()} rule=chain vaults first (§8038), then tick liquidity 0 -> observed (token map, curve reserves); never lowered, never invented"
}
