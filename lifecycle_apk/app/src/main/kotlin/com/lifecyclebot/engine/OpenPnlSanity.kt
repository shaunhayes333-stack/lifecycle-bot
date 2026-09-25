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

    /**
     * V5.0.6701 — detect the exact corruption signature captured in the Meme
     * Trader screenshot: several unrelated positions simultaneously jumped by
     * ~0.93M–1.00M× while their entries were 1e-8..1e-7. That is a token-unit
     * discontinuity, not independent market alpha: the mark has moved by about
     * 10^tokenDecimals because one writer supplied UI-token pricing and another
     * supplied raw-token pricing.
     *
     * This is NOT a profit cap. It only rejects a narrow band around the token's
     * own decimal scale. A 500×, 5,000× or 50,000× coherent move remains fully
     * representable. A decimal-scale discontinuity must be re-proved on a
     * coherent basis before it can mutate PnL, peak, locks, exits or learning.
     */
    private fun tokenDecimalScaleDiscontinuity6701(ratio: Double, tokenDecimals: Int, entryPrice: Double): Boolean {
        if (!ratio.isFinite() || ratio <= 0.0) return false
        fun nearScale(decimals: Int): Boolean {
            val scale = Math.pow(10.0, decimals.toDouble())
            if (!scale.isFinite() || scale <= 0.0) return false
            val relative = ratio / scale
            return ratio >= 1_000.0 && relative in 0.50..2.00
        }
        if (tokenDecimals in 3..12) return nearScale(tokenDecimals)
        // Some early/recovered Meme rows have not hydrated tokenMap.decimals yet.
        // The two normal Solana token scales are 6 and 9. Only apply this fallback
        // to sub-micro-dollar entries so ordinary high-priced assets cannot match.
        return entryPrice < 0.000001 && (nearScale(6) || nearScale(9))
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
        tokenDecimals: Int = -1,
    ): Verdict {
        if (!entryPrice.isFinite() || entryPrice <= 0.0) return reject("ENTRY_PRICE_INVALID", entryPrice, currentPrice, context, emit, mint)
        // V5.0.7236 §MARK_IDENTITY_REPAIR consumer — if currentPrice is
        // invalid, consult MarkIdentityRepairAuthority7236 for a fresh
        // cross-source repaired value before rejecting. This is the
        // operator's directive: "correct the data to require the stop
        // to fire correctly" rather than silently skip. When a repair
        // is available the PnL evaluation proceeds against the repaired
        // value; the raw invalid input is preserved forensically via
        // the counter below.
        var currentPriceEffective7236 = currentPrice
        val markSuppressed7243 = try {
            mint.isNotBlank() &&
                com.lifecyclebot.engine.truth.MarkIdentityExecutionGate7230.isExecutionSuppressed7243(mint)
        } catch (_: Throwable) { false }
        if (markSuppressed7243) {
            val repaired7243 = try {
                com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236.getRepairedPriceIfFresh(mint)
            } catch (_: Throwable) { null }
            if (repaired7243 != null && repaired7243.isFinite() && repaired7243 > 0.0) {
                currentPriceEffective7236 = repaired7243
                try {
                    com.lifecyclebot.engine.truth.MarkIdentityExecutionGate7230.markRepairedUsable7243(mint)
                    PipelineHealthCollector.labelInc("OPEN_PNL_SUPPRESSED_MARK_REPAIRED_7243")
                } catch (_: Throwable) {}
            } else {
                try {
                    com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236.requestRepair(
                        mint, "OpenPnlSanity_mark_suppressed_7243",
                    )
                } catch (_: Throwable) {}
                return reject("MARK_IDENTITY_SUPPRESSED_7243", entryPrice, currentPrice, context, emit, mint)
            }
        }
        if (!currentPriceEffective7236.isFinite() || currentPriceEffective7236 <= 0.0) {
            val repaired7236 = try {
                if (mint.isNotBlank())
                    com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236.getRepairedPriceIfFresh(mint)
                else null
            } catch (_: Throwable) { null }
            if (repaired7236 != null && repaired7236.isFinite() && repaired7236 > 0.0) {
                currentPriceEffective7236 = repaired7236
                try {
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("OPEN_PNL_CURRENT_PRICE_REPAIRED_7236")
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "OPEN_PNL_CURRENT_PRICE_REPAIRED_7236",
                        "mint=${mint.take(10)} rawCurrent=$currentPrice " +
                            "repaired=${"%.10g".format(repaired7236)} " +
                            "src=${com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236.getRepairedSource(mint)} " +
                            "context=${context.take(96)} " +
                            "action=proceed_with_repaired_value",
                    )
                } catch (_: Throwable) {}
            } else {
                // No repair available — request one for the NEXT tick
                // and continue with the standard rejection so the
                // caller does not act on a corrupt basis.
                try {
                    if (mint.isNotBlank())
                        com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236.requestRepair(
                            mint, "OpenPnlSanity_current_price_invalid",
                        )
                } catch (_: Throwable) {}
                return reject("CURRENT_PRICE_INVALID", entryPrice, currentPrice, context, emit, mint)
            }
        }
        var ratio = currentPriceEffective7236 / entryPrice
        if (!ratio.isFinite() || ratio <= 0.0) return reject("PRICE_RATIO_INVALID", entryPrice, currentPriceEffective7236, context, emit, mint)
        var pnl = (ratio - 1.0) * 100.0
        if (!pnl.isFinite()) return reject("OPEN_PNL_NOT_FINITE", entryPrice, currentPriceEffective7236, context, emit, mint)
        if (pnl < MIN_PNL_PCT) return reject("OPEN_PNL_BELOW_TOTAL_LOSS", entryPrice, currentPriceEffective7236, context, emit, mint)

        // V5.0.6854 §ABSURD_UPSIDE_WAS_NEVER_QUARANTINED — StalePriceExitGuard
        // .isGainTrustworthy() exists to reject a gain multiple above
        // ABSURD_GAIN_MULTIPLE (1000x = +100,000%) and quarantine the mint, and it
        // had ZERO callers, so markStale() was never invoked from it and
        // anyActive() was permanently false. This authority already rejects the
        // downside impossibility one line above; the mirror-image upside
        // impossibility — a decimals/basis glitch presenting a 10^6 gain — passed
        // straight through into peak tracking, profit locks and learner rewards.
        // Same invariant, same gate, and routing it through the guard means the
        // quarantine flag finally gets set by the thing that detects the problem.
        //
        // V5.0.7298 §QUARANTINE_WITHOUT_A_REPING_IS_A_LIFE_SENTENCE. Operator:
        // the basis-wait tokens "should of never been bought … or it needs to be
        // aware and reping the price to correct". Five held positions (entries
        // ~$48k cap) sat here for the whole session: every tick rejected as
        // absurd, and nothing ever asked the feeds again, so the position could
        // neither exit nor be valued. Now an absurd multiple asks for a repair
        // (MarkIdentityRepairAuthority7236, parallel feeds first) and, once a
        // fresh repaired price exists:
        //   - repaired agrees with the mark  → independent feeds confirm the
        //     move; it is a real runner and is not capped;
        //   - repaired is a sane multiple    → the mark was the fault; PnL is
        //     evaluated on the repaired price so exits and stops can fire;
        //   - otherwise                     → rejected as before.
        if (!com.lifecyclebot.engine.sell.StalePriceExitGuard
                .isGainTrustworthy(mint, entryPrice, currentPriceEffective7236, ratio)) {
            val repairAuth7301 = com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236
            val executable7301 = try { if (mint.isNotBlank()) repairAuth7301.getExecutablePriceIfFresh7301(mint) else null } catch (_: Throwable) { null }
            if (executable7301 == null && mint.isNotBlank()) {
                try { repairAuth7301.requestExecutableQuote7301(mint, tokenDecimals) } catch (_: Throwable) {}
            }
            val repaired7298 = try {
                if (mint.isNotBlank()) repairAuth7301.getRepairedPriceIfFresh(mint) else null
            } catch (_: Throwable) { null }
            // V5.0.7301 — only an executable quote confirms an absurd multiple;
            // two price feeds agreeing with each other no longer does.
            val execAgrees7301 = executable7301 != null && executable7301.isFinite() && executable7301 > 0.0 &&
                (executable7301 / currentPriceEffective7236) in 0.60..1.67
            val execSane7301 = executable7301 != null && executable7301.isFinite() && executable7301 > 0.0 &&
                executable7301 / entryPrice <= com.lifecyclebot.engine.sell.StalePriceExitGuard.ABSURD_GAIN_MULTIPLE
            val saneRepair7298 = repaired7298 != null && repaired7298.isFinite() && repaired7298 > 0.0 &&
                repaired7298 / entryPrice <= com.lifecyclebot.engine.sell.StalePriceExitGuard.ABSURD_GAIN_MULTIPLE
            val saneReplacement7301 = when {
                execSane7301 -> executable7301
                saneRepair7298 -> repaired7298
                else -> null
            }
            when {
                execAgrees7301 -> {
                    try { PipelineHealthCollector.labelInc("OPEN_PNL_ABSURD_GAIN_CONFIRMED_BY_EXECUTABLE_QUOTE_7301") } catch (_: Throwable) {}
                }
                saneReplacement7301 != null -> {
                    currentPriceEffective7236 = saneReplacement7301
                    try {
                        PipelineHealthCollector.labelInc(if (execSane7301) "OPEN_PNL_ABSURD_GAIN_REPAIRED_BY_EXECUTABLE_QUOTE_7301" else "OPEN_PNL_ABSURD_GAIN_REPAIRED_7298")
                        com.lifecyclebot.engine.ForensicLogger.lifecycle(
                            "OPEN_PNL_ABSURD_GAIN_REPAIRED_7298",
                            "mint=${mint.take(10)} rawCurrent=$currentPrice repaired=${"%.10g".format(saneReplacement7301)} " +
                                "src=${if (execSane7301) "JUPITER_EXECUTABLE_QUOTE_7301" else repairAuth7301.getRepairedSource(mint)} " +
                                "context=${context.take(96)} action=proceed_with_repaired_value",
                        )
                    } catch (_: Throwable) {}
                    ratio = currentPriceEffective7236 / entryPrice
                    pnl = (ratio - 1.0) * 100.0
                    if (pnl < MIN_PNL_PCT) return reject("OPEN_PNL_BELOW_TOTAL_LOSS", entryPrice, currentPriceEffective7236, context, emit, mint)
                }
                else -> {
                    try {
                        if (mint.isNotBlank()) com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236.requestRepair(mint, "OpenPnlSanity_absurd_gain_7298")
                    } catch (_: Throwable) {}
                    return reject("OPEN_PNL_ABSURD_GAIN_6854", entryPrice, currentPriceEffective7236, context, emit, mint)
                }
            }
        }

        // V5.0.6701 — this must run BEFORE source/pool comparability. The defect
        // that produced the operator screenshot was precisely a new numeric mark
        // wearing stale same-source/same-pool metadata. Decimal-unit continuity is
        // an independent invariant and cannot be waived by provenance equality.
        if (tokenDecimalScaleDiscontinuity6701(ratio, tokenDecimals, entryPrice)) {
            return reject("TOKEN_DECIMAL_SCALE_DISCONTINUITY_6701", entryPrice, currentPriceEffective7236, context, emit, mint)
        }

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

    /**
     * V5.0.6907 §THE_ENGINE_ALREADY_ANSWERED_THIS.
     *
     * OPERATOR EVIDENCE (5.0.6905 screenshot + snapshot):
     *
     *   Open Positions card:  TDOF +4290.3%   KIBA +3628.4%   SPX67H +1347.1%
     *   Same snapshot:        PAPER_CROSS_BASIS_MARK_REFUSED_6895 = 1247
     *
     *   > "it should be firing partials and taking these wins.
     *   >  Profit locks aren't firing either look at the picture."
     *
     * Both statements were true at once, and that is the defect. The exit
     * engine had already refused those marks as incomparable (entry priced on
     * one feed, tick priced on another, >10x apart in a single discontinuous
     * step) and was serving `entryPrice` — so it read 0% and correctly fired
     * no partial and no profit lock. Meanwhile this authority was asked the
     * SAME question about the SAME position and answered differently, because
     * it compared `entryPrice` against the RAW tick (`ts.ref`) using its own
     * band: MAX_UNKNOWN_BASIS_RATIO = 51.0. A 43.9x basis artefact sits under
     * 51x and above 10x, so the engine refused it and the card painted it.
     *
     * Two thresholds for one question is the bug, not the value of either
     * threshold. Lowering this band to 10x would be worse — it would refuse
     * genuine 10x-50x runners on a stable feed, and runner capture is not
     * negotiable (V5.9.1358). The engine's decision is already narrower and
     * better informed than anything re-derivable here: it is source-change
     * gated, so a real runner on one feed never trips it.
     *
     * So do not re-derive. Read the answer. Executor.getActualPrice §6895 is
     * the single writer of `markRefusedAtMs6907`; it clears the stamp the
     * instant an on-basis tick arrives, so a position that regains a matching
     * feed is priceable again on that very tick and nothing is condemned
     * permanently.
     *
     * Scope note: this rejects the DISPLAY/learning basis only. Inventory,
     * cost basis, stops and the catastrophic backstop are untouched — a
     * position with an unpriceable mark is still fully exitable, which is what
     * V5.0.6835's missing-mark veto and the §6904 backstop are for.
     */
    private const val MARK_REFUSAL_FRESHNESS_MS_6907 = 120_000L

    private fun markRefusedByEngine6907(pos: Position): Boolean {
        val at = pos.markRefusedAtMs6907
        if (at <= 0L) return false
        val age = System.currentTimeMillis() - at
        return age in -5_000L..MARK_REFUSAL_FRESHNESS_MS_6907
    }

    fun inspect(ts: TokenState, context: String = "", emit: Boolean = true): Verdict {
        val p = ts.position
        if (markRefusedByEngine6907(p)) {
            return reject("MARK_REFUSED_CROSS_BASIS_6907", p.entryPrice, ts.ref, context, emit, ts.mint)
        }
        val verdict = inspect(
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
            tokenDecimals = ts.tokenMap.decimals ?: -1,
        )
        // V5.0.6701 — a rejected raw/UI decimal discontinuity may already have
        // poisoned mutable peak/high-water state before provenance caught up.
        // Self-heal only this exact failure class. We do NOT clamp a trusted
        // runner; we discard a peak that was generated from a mark proven to be
        // unit-incompatible. This also clears the stale TARGET/lock badge after
        // restart once the first canonical PnL read occurs.
        if (!verdict.ok && verdict.reason == "TOKEN_DECIMAL_SCALE_DISCONTINUITY_6701") {
            val hadPoisonedPeak = p.peakGainPct > 0.0 || p.highestPrice > p.entryPrice || p.lastRoutePrice > 0.0
            p.peakGainPct = 0.0
            p.highestPrice = p.entryPrice.coerceAtLeast(0.0)
            p.lastRoutePrice = 0.0
            p.lastRoutePriceTs = 0L
            p.lastTickFloorBreach = false
            if (hadPoisonedPeak) try {
                PipelineHealthCollector.labelInc("MEME_DECIMAL_SCALE_PEAK_SELF_HEALED_6701")
                ForensicLogger.lifecycle(
                    "MEME_DECIMAL_SCALE_PEAK_SELF_HEALED_6701",
                    "mint=${ts.mint.take(10)} sym=${ts.symbol} entry=${p.entryPrice} mark=${ts.ref} action=reset_untrusted_peak_route_mark",
                )
            } catch (_: Throwable) {}
        }
        return verdict
    }

    fun inspectPosition(pos: Position, currentPrice: Double, context: String = "", emit: Boolean = true, mint: String = ""): Verdict {
        // V5.0.6907 — same engine verdict, same authority. See
        // markRefusedByEngine6907. Checked before the cost/qty heal below so a
        // reconstructed basis cannot smuggle a refused mark back onto a card.
        if (markRefusedByEngine6907(pos)) {
            return reject("MARK_REFUSED_CROSS_BASIS_6907", pos.entryPrice, currentPrice, context, emit, mint)
        }
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

    /**
     * V5.0.7083 — reject reasons that prove the PRICE BASIS itself is wrong, as
     * opposed to the trade merely going badly.
     *
     * Deliberately excludes OPEN_PNL_BELOW_TOTAL_LOSS and OPEN_PNL_NOT_FINITE:
     * the first is a real loss that must keep teaching, and the second is a
     * transient NaN that says nothing durable about the mint.
     */
    private val LEARNING_POISON_REASONS_7083 = setOf(
        "OPEN_PNL_ABSURD_GAIN_6854",
        "TOKEN_DECIMAL_SCALE_DISCONTINUITY_6701",
        // V5.0.7091 — PRICE_BASIS_UNTRUSTED_EXTREME_RATIO was MISSING from this
        // set, and it is the reason that actually fired on the operator's book.
        // The 5.0.7088 device shows it on CARDSccUMF at ratio=280.3 and on
        // Xsv9hRk1z5 — exactly the mints whose entry price was fabricated by the
        // 1e9 seed (V5.0.7089). So the contaminated outcomes were NOT quarantined
        // and they trained the oracle:
        //
        //   Predictive oracle (§6915): evals=894 admit=0 probe=251 refuse=643
        //   ENTRY_AUTHORITY_DENY_6846  oracleEV=-0.5498 pWin=0.14 effN=8
        //   terminal by source: DEX_TRENDING... n=3 WR=0.0% avgPct=-99.9%
        //
        // 894 evaluations and ZERO admits. Those -99.9% "losses" are positions
        // booked against an invented entry, so the oracle learned that everything
        // loses and now refuses everything. V5.0.7089 stops new fabrications but
        // cannot un-teach what is already persisted — this closes the hole that
        // let it be taught, which is why both builds are needed.
        "PRICE_BASIS_UNTRUSTED_EXTREME_RATIO",
    )

    private fun reject(reason: String, entry: Double, current: Double, context: String, emit: Boolean, mint: String = ""): Verdict {
        // V5.0.6246 — DeadTokenQuarantine strike + emit-suppression. Bumps the
        // per-mint strike counter for blacklisted reasons; once STRIKE_THRESHOLD
        // is reached the mint is permanently quarantined and future rejects
        // stop emitting (kills the log flood from stuck RECOVERED_* ghosts).
        val alreadyDead = mint.isNotBlank() && try { DeadTokenQuarantine.isDead(mint) } catch (_: Throwable) { false }
        if (!alreadyDead && mint.isNotBlank()) {
            try { DeadTokenQuarantine.recordStrike(mint, reason) } catch (_: Throwable) {}
        }
        // V5.0.7083 §A DIMENSIONALLY IMPOSSIBLE MARK MUST NOT TEACH ANYTHING.
        //
        // Operator directive §7: "exclude any terminal outcome whose price basis
        // was ever tagged OPEN_PNL_ABSURD_GAIN / PRICE_BASIS_UNTRUSTED_EXTREME_
        // RATIO / METRICS_IDENTITY_BROKEN ... from StrategyExpectancy,
        // ForwardOutcomeModel, UnifiedPolicyHead, TacticSwitcher,
        // HypothesisEngine, LosingPatternMemory."
        //
        // This function already detects exactly those marks — the device report
        // shows it catching ratios of 839x, 972x, 1006x, 1019x, 1047x and 1658x,
        // and the CSV carries a terminal sell at roughly 1,176,272x. What it did
        // NOT do is tell the learners. It recorded a DeadTokenQuarantine strike,
        // which suppresses log noise and needs STRIKE_THRESHOLD hits to fire,
        // and it never touched LearningQuarantineGate6470 — the gate that
        // CanonicalTradeStream6501, PaperAccountLedger6430 and
        // EconomicPurityGate6504 all consult before an outcome is allowed to
        // teach.
        //
        // So a mark could be refused for PnL and still train the models through
        // its terminal row. That is how "WR 15.5% / PF 2.38 / EV +107.8%" can be
        // arithmetically true and economically meaningless at the same time: the
        // giant winners carrying the average are the same marks this authority
        // classifies as impossible.
        //
        // Quarantine is per MINT and immediate — one proven-impossible mark is
        // sufficient evidence about that mint's price basis, and requiring a
        // threshold would let the first few contaminated outcomes through, which
        // is precisely the ones that matter because they are the largest.
        //
        // Restricted to the BASIS-INTEGRITY reasons. A mint that merely went to
        // zero (OPEN_PNL_BELOW_TOTAL_LOSS) is a real loss and MUST keep
        // teaching — that is the most valuable lesson the bot gets, and
        // quarantining it would bias the learned set towards survivors.
        if (mint.isNotBlank() && reason in LEARNING_POISON_REASONS_7083) {
            try {
                com.lifecyclebot.engine.truth.MarkIdentityExecutionGate7230
                    .suppressMint7243(mint, "OPEN_PNL_" + reason)
                com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236
                    .requestRepair(mint, "OpenPnlSanity_rejected_basis")
                com.lifecyclebot.engine.truth.LearningQuarantineGate6470
                    .quarantineMint(mint, "PRICE_BASIS_IMPOSSIBLE_$reason")
                PipelineHealthCollector.labelInc("LEARNING_QUARANTINED_IMPOSSIBLE_BASIS_7083")
            } catch (_: Throwable) {}
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