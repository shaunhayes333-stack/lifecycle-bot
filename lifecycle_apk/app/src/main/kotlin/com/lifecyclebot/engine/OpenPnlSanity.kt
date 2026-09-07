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
    // V5.0.6680 — 1000x+ remains possible in the real world, so it is not
    // clamped or declared impossible. But source-name equality alone is not
    // sufficient proof for an astronomical move. Above this ratio we require
    // immutable same-pool continuity; otherwise the correct state is basis-wait
    // until the mark is reproved. Genuine 500x moonshots remain untouched.
    private const val ASTRONOMICAL_RATIO_REPROOF_6680 = 1_001.0
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

    private fun concretePool6680(pool: String): String? {
        val p = pool.trim().uppercase()
        if (p.isBlank()) return null
        if (p == "UNKNOWN" || p == "PLACEHOLDER" || p == "SENTINEL") return null
        if (p.startsWith("MINT_ROUTE:")) return null
        return p
    }

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
        // V5.0.6636 — carry/recovery entries derived from cost/qty are
        // SOL/token (or unit-unknown), not USD/token. They preserve inventory
        // but can never authorize a numeric USD-mark PnL. Likewise, an explicit
        // invariant/quarantine stamp is terminal for display trust.
        val entryUnitUntrusted = eSrc.contains("DERIVED_CARRY") ||
            eSrc.contains("DURABLE_CARRY") || eSrc.contains("REPLAY_CARRY") ||
            eSrc.contains("RECOVERED_CARRY") || eSrc.contains("DERIVED_FROM_COST") ||
            eSrc.contains("OPEN_POSITION_DERIVED_FROM_COST_QTY") ||
            eSrc.contains("INVARIANT_BROKEN") || eSrc.contains("QUARANTINED")
        if (entryUnitUntrusted) {
            return reject("ENTRY_PRICE_UNIT_UNTRUSTED", entryPrice, currentPrice, context, emit, mint)
        }

        // V5.0.6680 — persisted sentinel entries must never become trusted merely
        // because a later real provider/pool produced a valid mark. V5.0.6658
        // blocks these values at new PAPER entry, but older persisted positions
        // can still be rehydrated; reject them at the shared PnL authority too.
        val knownSentinelEntry6680 = try {
            com.lifecyclebot.engine.truth.MarketDataProvenance6471
                .isKnownStandaloneSentinelPrice6658(entryPrice)
        } catch (_: Throwable) { false }
        if (knownSentinelEntry6680) {
            return reject("ENTRY_PRICE_SENTINEL_6680", entryPrice, currentPrice, context, emit, mint)
        }

        val sameSource = eSrc.isNotBlank() && cSrc.isNotBlank() && eSrc == cSrc
        val entryConcretePool6680 = concretePool6680(entryPool)
        val currentConcretePool6680 = concretePool6680(currentPool)
        val samePool = entryConcretePool6680 != null && currentConcretePool6680 != null &&
            entryConcretePool6680 == currentConcretePool6680

        // V5.0.6116b RESTORED BY V5.0.6680 — PATCH-ROT FIX.
        // Do NOT re-add `|| priceBasisRescaled` here. That historical flag is
        // stamped by ordinary proof/recovery paths and is not proof that the
        // CURRENT mark shares the entry basis. Reintroducing it permanently
        // waived the extreme-ratio guard and recreated phantom mega-PnL.
        val explicitComparable = samePool || sameSource
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

        // V5.0.6680 — same provider family is not immutable asset/basis proof.
        // A symbol/alias/template collision can report the same source string on
        // both sides while being thousands/millions of times apart. For >1000x
        // require the exact concrete pool to survive from entry to current mark.
        // This is a reproof requirement, not a profit cap: true same-pool moves
        // remain fully represented, and 500x moves never hit this branch.
        if (ratio > ASTRONOMICAL_RATIO_REPROOF_6680 && !samePool) {
            return reject("ASTRONOMICAL_RATIO_REQUIRES_SAME_POOL_PROOF_6680", entryPrice, currentPrice, context, emit, mint)
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
        // basis no longer waives source/pool trust; V5.0.6680 restored the 6116b
        // rule that a historical rescale flag is not current-mark proof.
        val healedEntryPrice = if (pos.entryPrice.isFinite() && pos.entryPrice > 0.0) pos.entryPrice
        // V5.0.6308 — heal threshold widened from qty>1.0 to qty>1e-9. Operator
        // emergency report showed 31,050 ENTRY_PRICE_INVALID rejects because
        // most stuck positions have decimals-adjusted qty in fractional units
        // (0.0001-0.5) — the >1.0 gate blocked the heal on those, so every
        // tick loop re-rejected the same 5-6 positions. Any positive qty is
        // valid input for costSol/qty; the resulting price is validated below.
        else if (pos.costSol > 0.0 && pos.qtyToken > 1e-9 && currentPrice > 0.0) {
            val reconstructed = pos.costSol / pos.qtyToken
            // V5.0.6308a — bound the reconstructed price to a sane SOL-per-token
            // range (< 1.0 SOL/token; tokens above that would need 1B+ market cap
            // at ~1B supply which is not a real memecoin scenario). Prevents dust
            // qty (1e-8) + small costSol from producing an astronomically inflated
            // basis that would then wildly distort every subsequent PnL calc.
            if (reconstructed.isFinite() && reconstructed > 0.0 && reconstructed < 1.0) {
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
