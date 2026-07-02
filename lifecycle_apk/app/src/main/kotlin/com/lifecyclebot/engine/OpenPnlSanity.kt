package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState

/**
 * V5.0.3833 — single authority for open-position PnL math.
 *
 * Open PnL/peak/lock/exit logic may not compare prices from incompatible bases
 * (synthetic PumpFun mcap/1B entry vs DEX pool quote, rounded near-zero entry,
 * unknown-source mega ratio, etc.). If the basis is not trustworthy, return an
 * untrusted verdict and let callers HOLD / show basis-wait instead of inventing
 * fake wins or simulated profit locks.
 */
object OpenPnlSanity {
    const val MAX_UNKNOWN_BASIS_PNL_PCT = 5_000.0
    private const val MAX_UNKNOWN_BASIS_RATIO = 51.0
    private const val MIN_PNL_PCT = -100.0001

    data class Verdict(
        val ok: Boolean,
        val pnlPct: Double = 0.0,
        val reason: String = "",
    )

    data class PricingTruth(
        val markPrice: Double,
        val pnlPct: Double,
        val pnlSol: Double,
        val trusted: Boolean,
        val reason: String,
        val source: String,
    )

    fun inspect(
        entryPrice: Double,
        currentPrice: Double,
        entrySource: String = "",
        currentSource: String = "",
        entryPool: String = "",
        currentPool: String = "",
        priceBasisRescaled: Boolean = false,
        context: String = "",
        emit: Boolean = true,
    ): Verdict {
        if (!entryPrice.isFinite() || entryPrice <= 0.0) return reject("ENTRY_PRICE_INVALID", entryPrice, currentPrice, context, emit)
        if (!currentPrice.isFinite() || currentPrice <= 0.0) return reject("CURRENT_PRICE_INVALID", entryPrice, currentPrice, context, emit)
        val ratio = currentPrice / entryPrice
        if (!ratio.isFinite() || ratio <= 0.0) return reject("PRICE_RATIO_INVALID", entryPrice, currentPrice, context, emit)
        val pnl = (ratio - 1.0) * 100.0
        if (!pnl.isFinite()) return reject("OPEN_PNL_NOT_FINITE", entryPrice, currentPrice, context, emit)
        if (pnl < MIN_PNL_PCT) return reject("OPEN_PNL_BELOW_TOTAL_LOSS", entryPrice, currentPrice, context, emit)

        val eSrc = entrySource.trim().uppercase()
        val cSrc = currentSource.trim().uppercase()
        val sameSource = eSrc.isNotBlank() && cSrc.isNotBlank() && eSrc == cSrc
        val samePool = entryPool.isNotBlank() && currentPool.isNotBlank() && entryPool == currentPool
        val explicitComparable = samePool || sameSource || priceBasisRescaled
        val syntheticInvolved = eSrc.contains("SYNTH") || cSrc.contains("SYNTH") || eSrc.contains("PUMP_FUN_BC") || cSrc.contains("PUMP_FUN_BC")

        if (ratio > MAX_UNKNOWN_BASIS_RATIO && (!explicitComparable || syntheticInvolved)) {
            return reject("PRICE_BASIS_UNTRUSTED_EXTREME_RATIO", entryPrice, currentPrice, context, emit)
        }
        if (pnl > MAX_UNKNOWN_BASIS_PNL_PCT && !explicitComparable) {
            return reject("UNKNOWN_PRICE_BASIS_EXTREME_PNL", entryPrice, currentPrice, context, emit)
        }
        if (pnl > MAX_UNKNOWN_BASIS_PNL_PCT && syntheticInvolved && !priceBasisRescaled) {
            return reject("SYNTHETIC_PRICE_BASIS_EXTREME_PNL", entryPrice, currentPrice, context, emit)
        }
        return Verdict(true, pnl)
    }

    fun inspect(ts: TokenState, context: String = "", emit: Boolean = true): Verdict {
        val p = ts.position
        return inspect(
            entryPrice = p.entryPrice,
            currentPrice = ts.ref,
            entrySource = p.entryPriceSource,
            currentSource = ts.lastPriceSource,
            entryPool = p.entryPoolAddress,
            currentPool = ts.lastPricePoolAddr,
            priceBasisRescaled = p.priceBasisRescaled,
            context = context.ifBlank { "${ts.symbol}/${ts.mint.take(8)}" },
            emit = emit,
        )
    }

    fun inspectPosition(pos: Position, currentPrice: Double, context: String = "", emit: Boolean = true): Verdict =
        inspect(
            entryPrice = pos.entryPrice,
            currentPrice = currentPrice,
            entrySource = pos.entryPriceSource,
            entryPool = pos.entryPoolAddress,
            priceBasisRescaled = pos.priceBasisRescaled,
            context = context,
            emit = emit,
        )

    /** V5.0.6037 — canonical open pricing truth for reports/UI/journal-facing displays.
     *  All open-position surfaces must consume this result instead of recomputing
     *  PnL with local formulas or downgrading route state independently. */
    fun pricingTruth(ts: TokenState, context: String = "", emit: Boolean = true): PricingTruth {
        val mark = ts.ref
        val verdict = inspect(ts, context = context.ifBlank { "pricing_truth/${ts.symbol}/${ts.mint.take(8)}" }, emit = emit)
        val pnlPct = if (verdict.ok) verdict.pnlPct else 0.0
        val pnlSol = if (verdict.ok) ts.position.costSol * pnlPct / 100.0 else 0.0
        val src = ts.lastPriceSource.ifBlank { "UNKNOWN" }
        return PricingTruth(mark, pnlPct, pnlSol, verdict.ok, verdict.reason, src)
    }

    private fun reject(reason: String, entry: Double, current: Double, context: String, emit: Boolean): Verdict {
        if (emit) {
            try { PipelineHealthCollector.labelInc("OPEN_PNL_BASIS_REJECTED") } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("OPEN_PNL_BASIS_REJECTED_$reason") } catch (_: Throwable) {}
            try { ForensicLogger.lifecycle("OPEN_PNL_BASIS_REJECTED", "reason=$reason context=${context.take(96)} entry=$entry current=$current ratio=${if (entry > 0.0) current / entry else 0.0}") } catch (_: Throwable) {}
        }
        return Verdict(false, reason = reason)
    }
}
