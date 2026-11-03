package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CurveReserves7951
import com.lifecyclebot.network.PairInfo
import com.lifecyclebot.network.PumpCurveKeys7269
import com.lifecyclebot.network.RaydiumMintPools8128

/** Liquidity supplied to safety must be a dated observation of this venue, never a cap-derived estimate. */
object SafetyLiquidity8522 {
    const val MAX_POOL_AGE_MS = 120_000L
    const val MAX_CURVE_AGE_MS = 30_000L

    data class Observation(val usd: Double, val observedAtMs: Long, val poolAddress: String, val source: String)

    internal fun choose(current: Observation?, fallback: Observation?, selectedPool: String,
                        nowMs: Long, maxAgeMs: Long = MAX_POOL_AGE_MS): Observation? {
        fun valid(row: Observation?): Boolean = row != null && row.usd.isFinite() && row.usd >= 0.0 &&
            row.observedAtMs > 0L && nowMs - row.observedAtMs in -5_000L..maxAgeMs &&
            row.poolAddress.isNotBlank() && (selectedPool.isBlank() || row.poolAddress == selectedPool)
        // A current low/zero observation must not be hidden by a higher historical reading.
        return when {
            valid(current) -> current
            valid(fallback) -> fallback
            else -> null
        }
    }

    internal fun fromPair(pair: PairInfo, mint: String): Observation? {
        if (pair.candle.synthetic || pair.pairAddress.isBlank() ||
            pair.tokenAddress.ifBlank { pair.baseTokenAddress } != mint) return null
        return Observation(pair.liquidity, pair.candle.ts, pair.pairAddress, "DEXSCREENER_POOL")
    }

    fun observed(ts: TokenState, pair: PairInfo, nowMs: Long = System.currentTimeMillis()): Observation? {
        val ordinaryPool = !pair.candle.synthetic && pair.pairAddress.isNotBlank() &&
            !pair.dexId.equals("pumpfun", true) && !pair.dexId.equals("pump.fun", true)
        if (!ordinaryPool) {
            val curve = try { CurveReserves7951.accountObservation8522(ts.mint, nowMs, MAX_CURVE_AGE_MS) } catch (_: Throwable) { null }
            val solUsd = try { WalletManager.freshSolPrice8163() } catch (_: Throwable) { 0.0 }
            if (curve != null && solUsd.isFinite() && solUsd > 0.0) {
                return Observation(curve.realSol * solUsd, curve.observedAtMs,
                    try { PumpCurveKeys7269.keyFor(ts.mint).orEmpty() } catch (_: Throwable) { "" }, "CURVE_ACCOUNT")
            }
        }
        val selectedPool = pair.pairAddress.ifBlank {
            ts.lastPricePoolAddr.ifBlank { ts.tokenMap.poolAddress.ifBlank { ts.tokenMap.pairAddress } }
        }
        val current = fromPair(pair, ts.mint)
        choose(current, null, selectedPool, nowMs)?.let { return it }
        val ray = try { RaydiumMintPools8128.observedForSafety8522(ts.mint, nowMs, MAX_POOL_AGE_MS) } catch (_: Throwable) { null }
        val fallback = ray?.let { (pool, at) -> Observation(pool.tvlUsd, at, pool.poolId, "RAYDIUM_POOL") }
        return choose(null, fallback, selectedPool, nowMs)
    }
}
