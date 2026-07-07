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
        // V5.0.6116 — REMOVED priceBasisRescaled from explicitComparable.
        // Root bug: priceBasisRescaled is stamped true at ordinary live-buy proof
        // confirmation (Executor LIVE_PROOF_COST_BASIS) and wallet-rehydration
        // recovery (BotService HOST_WALLET_TRACKER_REHYDRATED) — NOT only at the
        // genuine cross-source rebase event it was designed for. Because the flag
        // never resets, once ANY of those routine events fires (virtually every
        // live position, at buy time), explicitComparable became permanently true
        // for that position's entire remaining life — waiving the extreme-ratio
        // (51x) and extreme-pnl (5000%) numeric safety net forever. Any later
        // entryPrice corruption (decimals bug, residual-cost-basis-after-partials
        // shrink, stale mcap pivot) then displayed as a fictional giant "gain%"
        // in Open Positions (operator report: ANSEM shown +1000% unrealized,
        // then closed at real -3.7%/-0.11 SOL — the 1000% was never real).
        // Fix: comparability now requires a genuine same-source or same-pool
        // match against the CURRENT live tick. Real trustworthy live positions
        // naturally regain sameSource once ROUTE_LOCK_SELF_HEAL upgrades
        // entryPriceSource to match the live route (see Executor.kt), so this
        // does not punish legitimate winners — it only removes the permanent
        // bypass that let corrupted bases paint fake extreme gains as trusted.
        val explicitComparable = samePool || sameSource
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

    fun inspectPosition(pos: Position, currentPrice: Double, context: String = "", emit: Boolean = true): Verdict {
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
        else if (pos.costSol > 0.0 && pos.qtyToken > 1.0 && currentPrice > 0.0) {
            val reconstructed = pos.costSol / pos.qtyToken
            if (reconstructed.isFinite() && reconstructed > 0.0) {
                try { PipelineHealthCollector.labelInc("ENTRY_PRICE_HEALED_FROM_COST_QTY_6050") } catch (_: Throwable) {}
                reconstructed
            } else pos.entryPrice
        }
        // V5.0.6195 — RECOVERED-ZOMBIE HEAL. Report 2026-07-08 shows
        // RECOVERED_2xKQg4 with entryPrice=0 AND costSol=0 AND current=0
        // spamming OPEN_PNL_BASIS_REJECTED every tick forever. The position
        // came from wallet reconciliation with no bot-side entry data. Rather
        // than let it starve a slot, fall back to highestPrice (which the
        // wallet reconciler may have populated from any recent quote) or to
        // 1e-9 as a last-resort synthetic basis. Synthetic basis lets PnL
        // resolve to a small neutral figure so downstream stale-position
        // sweep logic (Executor.DEAD_TOKEN_NO_PRICE_EXIT at pos age >= 15min)
        // can finally free the slot instead of forever rejecting.
        else if (pos.highestPrice.isFinite() && pos.highestPrice > 0.0) {
            try { PipelineHealthCollector.labelInc("ENTRY_PRICE_HEALED_FROM_HIGHEST_6195") } catch (_: Throwable) {}
            pos.highestPrice
        } else if (currentPrice.isFinite() && currentPrice > 0.0) {
            try { PipelineHealthCollector.labelInc("ENTRY_PRICE_HEALED_FROM_CURRENT_6195") } catch (_: Throwable) {}
            currentPrice
        } else pos.entryPrice
        return inspect(
            entryPrice = healedEntryPrice,
            currentPrice = currentPrice,
            entrySource = pos.entryPriceSource,
            entryPool = pos.entryPoolAddress,
            priceBasisRescaled = pos.priceBasisRescaled || (healedEntryPrice != pos.entryPrice),
            context = context,
            emit = emit,
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

    private fun reject(reason: String, entry: Double, current: Double, context: String, emit: Boolean): Verdict {
        if (emit) {
            try { PipelineHealthCollector.labelInc("OPEN_PNL_BASIS_REJECTED") } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("OPEN_PNL_BASIS_REJECTED_$reason") } catch (_: Throwable) {}
            try { ForensicLogger.lifecycle("OPEN_PNL_BASIS_REJECTED", "reason=$reason context=${context.take(96)} entry=$entry current=$current ratio=${if (entry > 0.0) current / entry else 0.0}") } catch (_: Throwable) {}
        }
        return Verdict(false, reason = reason)
    }
}
