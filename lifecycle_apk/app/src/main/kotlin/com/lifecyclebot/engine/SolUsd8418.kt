package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EconomicUnitInvariant7061
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8418 — the one SOL/USD rate execution and sizing may use.
 *
 * Owner item 10: execution and sizing used `lastKnownSolPrice ?: 150.0` (and 85/100/104/130
 * cousins). A constant is not an observation: at a real SOL of $240 a "$150" fallback sizes
 * a USD bridge leg 37% small, and at $90 it sizes it 67% large. The live rate is
 * [WalletManager.freshSolPrice8163] (observed, at most 5 minutes old). When it is unknown or
 * stale, sizing and execution REFUSE with a named counter `SOL_USD_UNKNOWN_REFUSED_8418_<stage>`;
 * they never substitute a number. Display-only callers show "-" / "$—".
 *
 * Owner item 7: the unit chain is SOL size × SOL/USD = USD notional, and USD notional /
 * asset price = asset quantity ([EconomicUnitInvariant7061.solToUsd8418] /
 * [EconomicUnitInvariant7061.usdToAssetQty8418]). Never SOL size × the asset's own price.
 */
object SolUsd8418 {
    private val refusals = ConcurrentHashMap<String, AtomicLong>()

    /** The live, observed SOL/USD rate, or null when unknown or stale. */
    fun liveOrNull(nowMs: Long = System.currentTimeMillis()): Double? {
        val p = try { WalletManager.freshSolPrice8163(nowMs) } catch (_: Throwable) { 0.0 }
        return if (p.isFinite() && p > 0.0) p else null
    }

    /** USD notional of [sizeSol] at [solUsd]; null when either is unknown. */
    fun usdNotional(sizeSol: Double, solUsd: Double?): Double? {
        if (solUsd == null) return null
        val v = EconomicUnitInvariant7061.solToUsd8418(sizeSol, solUsd)
        return if (v.isFinite()) v else null
    }

    /** Asset quantity bought by [notionalUsd] at [assetPriceUsd]; null when unknown. */
    fun assetQuantity(notionalUsd: Double?, assetPriceUsd: Double): Double? {
        if (notionalUsd == null) return null
        val v = EconomicUnitInvariant7061.usdToAssetQty8418(notionalUsd, assetPriceUsd)
        return if (v.isFinite()) v else null
    }

    /** USD notional of [sizeSol] at the live rate, or a named refusal (null) at [stage]. */
    fun liveUsdNotionalOrRefuse(sizeSol: Double, stage: String, nowMs: Long = System.currentTimeMillis()): Double? {
        val usd = usdNotional(sizeSol, liveOrNull(nowMs))
        if (usd == null) refuse(stage)
        return usd
    }

    /** The live rate, or a named refusal (null) at [stage]. */
    fun liveOrRefuse(stage: String, nowMs: Long = System.currentTimeMillis()): Double? {
        val p = liveOrNull(nowMs)
        if (p == null) refuse(stage)
        return p
    }

    fun refuse(stage: String) {
        val label = "SOL_USD_UNKNOWN_REFUSED_8418_$stage"
        refusals.computeIfAbsent(label) { AtomicLong() }.incrementAndGet()
        try { PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {}
        try { ErrorLogger.warn("SolUsd8418", "$label — SOL/USD unknown or stale; refusing to size/execute (no constant substituted)") } catch (_: Throwable) {}
    }

    fun refusalCount(stage: String): Long = refusals["SOL_USD_UNKNOWN_REFUSED_8418_$stage"]?.get() ?: 0L
}
