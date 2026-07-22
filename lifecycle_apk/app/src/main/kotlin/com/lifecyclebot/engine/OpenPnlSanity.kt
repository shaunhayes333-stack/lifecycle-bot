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
        mint: String = "",
    ): Verdict {
        if (!entryPrice.isFinite() || entryPrice <= 0.0) return reject("ENTRY_PRICE_INVALID", entryPrice, currentPrice, context, emit, mint)
        if (!currentPrice.isFinite() || currentPrice <= 0.0) return reject("CURRENT_PRICE_INVALID", entryPrice, currentPrice, context, emit, mint)
        val ratio = currentPrice / entryPrice
        if (!ratio.isFinite() || ratio <= 0.0) return reject("PRICE_RATIO_INVALID", entryPrice, currentPrice, context, emit, mint)
        val pnl = (ratio - 1.0) * 100.0
        if (!pnl.isFinite()) return reject("OPEN_PNL_NOT_FINITE", entryPrice, currentPrice, context, emit, mint)
        if (pnl < MIN_PNL_PCT) return reject("OPEN_PNL_BELOW_TOTAL_LOSS", entryPrice, currentPrice, context, emit, mint)

        val eSrc = entrySource.trim().uppercase()
        val cSrc = currentSource.trim().uppercase()
        val sameSource = eSrc.isNotBlank() && cSrc.isNotBlank() && eSrc == cSrc
        val samePool = entryPool.isNotBlank() && currentPool.isNotBlank() && entryPool == currentPool
        val explicitComparable = samePool || sameSource || priceBasisRescaled
        val syntheticInvolved = eSrc.contains("SYNTH") || cSrc.contains("SYNTH") || eSrc.contains("PUMP_FUN_BC") || cSrc.contains("PUMP_FUN_BC")

        if (ratio > MAX_UNKNOWN_BASIS_RATIO && (!explicitComparable || syntheticInvolved)) {
            return reject("PRICE_BASIS_UNTRUSTED_EXTREME_RATIO", entryPrice, currentPrice, context, emit, mint)
        }
        if (pnl > MAX_UNKNOWN_BASIS_PNL_PCT && !explicitComparable) {
            return reject("UNKNOWN_PRICE_BASIS_EXTREME_PNL", entryPrice, currentPrice, context, emit, mint)
        }
        if (pnl > MAX_UNKNOWN_BASIS_PNL_PCT && syntheticInvolved && !priceBasisRescaled) {
            return reject("SYNTHETIC_PRICE_BASIS_EXTREME_PNL", entryPrice, currentPrice, context, emit, mint)
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
            mint = ts.mint,
        )
    }

    fun inspectPosition(pos: Position, currentPrice: Double, context: String = "", emit: Boolean = true, mint: String = ""): Verdict {
        // V5.0.6050 — ENTRY_PRICE_INVALID auto-heal (operator ask 2026-07-03).
        // Report V5.0.6049 showed repeating OPEN_PNL_BASIS_REJECTED reason=
        // ENTRY_PRICE_INVALID because some positions have entryPrice=0 despite
        // having valid costSol + qtyToken (persistence race or force-load). If
        // we can reconstruct entryPrice = costSol / qtyToken (in SOL-per-token
        // terms), we heal the basis and let inspect() proceed. The reconstructed
        // basis is marked priceBasisRescaled=true so downstream trust guards
        // treat it as authoritative for the sanity check but not for banked-win
        // verification (RealPriceLock still gates real harvests).
        val healedEntryPrice = if (pos.entryPrice.isFinite() && pos.entryPrice > 0.0) pos.entryPrice
        // V5.0.6308 — heal threshold widened from qty>1.0 to qty>1e-9. Operator
        // emergency report showed 31,050 ENTRY_PRICE_INVALID rejects because
        // most stuck positions have decimals-adjusted qty in fractional units
        // (0.0001-0.5) — the >1.0 gate blocked the heal on those, so every
        // tick loop re-rejected the same 5-6 positions. Any positive qty is
        // valid input for costSol/qty; the resulting price is validated below.
        else if (pos.costSol > 0.0 && pos.qtyToken > 1e-9 && currentPrice > 0.0) {
            val reconstructed = pos.costSol / pos.qtyToken
            if (reconstructed.isFinite() && reconstructed > 0.0) {
                try { PipelineHealthCollector.labelInc("ENTRY_PRICE_HEALED_FROM_COST_QTY_6308") } catch (_: Throwable) {}
                reconstructed
            } else pos.entryPrice
        } else pos.entryPrice
        return inspect(
            entryPrice = healedEntryPrice,
            currentPrice = currentPrice,
            entrySource = pos.entryPriceSource,
            entryPool = pos.entryPoolAddress,
            priceBasisRescaled = pos.priceBasisRescaled || (healedEntryPrice != pos.entryPrice),
            context = context,
            emit = emit,
            mint = mint,
        )
    }

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

    private fun reject(reason: String, entry: Double, current: Double, context: String, emit: Boolean, mint: String = ""): Verdict {
        // V5.0.6246 — DeadTokenQuarantine strike + emit-suppression. Bumps the
        // per-mint strike counter for blacklisted reasons; once STRIKE_THRESHOLD
        // is reached the mint is permanently quarantined and future rejects
        // stop emitting (kills the log flood from stuck RECOVERED_* ghosts).
        val alreadyDead = mint.isNotBlank() && try { DeadTokenQuarantine.isDead(mint) } catch (_: Throwable) { false }
        if (!alreadyDead && mint.isNotBlank()) {
            try { DeadTokenQuarantine.recordStrike(mint, reason) } catch (_: Throwable) {}
        }
        val silentEmit = emit && !alreadyDead
        if (silentEmit) {
            try { PipelineHealthCollector.labelInc("OPEN_PNL_BASIS_REJECTED") } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("OPEN_PNL_BASIS_REJECTED_$reason") } catch (_: Throwable) {}
            try { ForensicLogger.lifecycle("OPEN_PNL_BASIS_REJECTED", "reason=$reason context=${context.take(96)} entry=$entry current=$current ratio=${if (entry > 0.0) current / entry else 0.0}") } catch (_: Throwable) {}
        } else if (emit && alreadyDead) {
            try { PipelineHealthCollector.labelInc("OPEN_PNL_BASIS_REJECTED_SUPPRESSED_DEAD_TOKEN") } catch (_: Throwable) {}
        }
        return Verdict(false, reason = reason)
    }
}
