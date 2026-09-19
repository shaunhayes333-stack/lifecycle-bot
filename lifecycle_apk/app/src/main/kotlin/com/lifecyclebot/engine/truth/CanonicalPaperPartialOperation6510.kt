package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.TradeHistoryStore
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** V5.0.6510 — one claim, one canonical paper partial economic mutation. */
object CanonicalPaperPartialOperation6510 {
    enum class TierState6613 { NONE, REQUESTED, QUANTITY_RESERVED, EXECUTING, CONFIRMED, ACCOUNTED, COMPLETE }
    private val tierStates6613 = ConcurrentHashMap<String, TierState6613>()
    private val tierUpdatedAt6613 = ConcurrentHashMap<String, Long>()

    private fun normalizedTier6613(reason: String): String {
        val r = reason.uppercase()
        return when {
            r.contains("PROTECTIVE_PEAK") -> "PROTECTIVE_PEAK_25"
            r.contains("CAPITAL_RECOVERY") -> "CAPITAL_RECOVERY"
            r.contains("RAPID_INSTANT") -> "RAPID_INSTANT_PROFIT"
            r.contains("ULTRA_RUNNER") -> "ULTRA_RUNNER"
            r.contains("WALLET_GROWTH_HARVEST") -> "WALLET_GROWTH_HARVEST"
            r.contains("RUNNER_RUNG") -> "RUNNER_RUNG_${Regex("[0-9]+").find(r)?.value ?: "GENERIC"}"
            r.contains("PROFIT_LOCK") -> "PROFIT_LOCK_${Regex("[0-9]+").find(r)?.value ?: "GENERIC"}"
            r.startsWith("PARTIAL_") -> r.substringBefore("PCT") + "PCT"
            else -> r.replace(Regex("[0-9]+(?:\\.[0-9]+)?"), "N").take(80)
        }
    }

    private val sequences = ConcurrentHashMap<String, AtomicLong>()
    private val requestSequences = ConcurrentHashMap<String, Long>()

    private fun nextSequence(positionId: String, requestKey: String): Long {
        return requestSequences.computeIfAbsent("$positionId|$requestKey") {
            val seq = sequences.computeIfAbsent(positionId) {
                // Restart-safe seed: persisted economic events are authoritative.
                val prior = EconomicEventSchema6464.snapshot().count { e ->
                    e is EconomicEventSchema6464.Sell && e.positionId == positionId && e.partial
                }.toLong()
                AtomicLong(prior)
            }
            seq.incrementAndGet()
        }
    }

    data class Receipt(
        val applied: Boolean, val duplicate: Boolean, val reason: String,
        val positionId: String, val operationId: String, val partialSequence: Long,
        val preQty: BigInteger, val soldQty: BigInteger, val postQty: BigInteger,
        val preCost: Double, val soldCostBasis: Double, val postCost: Double,
        val grossProceeds: Double, val fees: Double, val realizedPnl: Double,
    )

    /**
     * V5.0.7029 §PROCEEDS_THAT_NOTHING_COULD_RECONSTRUCT.
     *
     * How far grossProceeds may sit from quantity x price before this refuses.
     *
     * The band is deliberately enormous. Fees, slippage, a stale SOL/USD rate
     * and a mark taken a few seconds before the book moves are all legitimate
     * reasons for the two figures to disagree, and on a thin memecoin pool
     * they can disagree by a lot. What is NOT legitimate is disagreeing by two
     * orders of magnitude, which is what a currency-unit defect produces: the
     * operator's four worst partials were out by 133x, 134x, 139x and 145x,
     * i.e. by the SOL/USD rate, drifting the way that rate drifts.
     *
     * 25x passes everything a market can do and catches everything a unit
     * error can do. It is a UNIT check wearing a magnitude threshold, not a
     * cap on gains — the runner doctrine (V5.9.1358, never cap, never
     * throttle) is untouched, because a genuine 1000x runner has proceeds
     * that reconstruct perfectly from its own quantity and its own price.
     */
    /**
     * V5.0.7068 — WAS 25.0.
     *
     * This compares claimed SOL proceeds against soldQty x markUsd / solUsd.
     * Correctly computed proceeds agree with that to floating point; it is an
     * arithmetic identity between three numbers the caller is holding, not a
     * market judgement. A 25x band meant a sale could claim twenty-five times
     * what its own quantity and price produce and still commit. That is not a
     * safety margin, it is permission.
     *
     * EconomicUnitInvariant7061 runs the same reconstruction at 1% at the
     * bridge and reported rejected=0 on the device, so the priced path is
     * already clean and this tightening should be a no-op for correct data —
     * which is the point. It is the second gate, and a second gate set twenty
     * times wider than the first is not a gate.
     */
    private const val PROCEEDS_RECONSTRUCTION_BAND_7029 = 1.10

    /**
     * V5.0.7066 — ceiling on proceeds-to-basis for a basis-derived partial.
     *
     * The expression is `soldBasis x (1 + pnlPct/100)`, so this cap is a cap on
     * (1 + pnlPct/100) — i.e. it refuses a partial claiming more than a 1000x
     * return on the slice it sold. That is not a runner clamp: a cross-asset
     * paper position is leverage-bounded and cannot legitimately print 1000x on
     * a single rung, and the memecoin path (which CAN) does not come through
     * here. It exists to catch a corrupted pnlPct, which is the only way this
     * arithmetic can go wrong.
     */
    private const val BASIS_DERIVED_MAX_RATIO_7066 = 1000.0

    fun commit(positionId: String, mint: String, symbol: String, fraction: Double,
               grossProceeds: Double, fees: Double, exitReason: String,
               // V5.0.7029 — the mark that produced grossProceeds, and the rate
               // needed to put it in the same currency as the ledger. Both zero
               // means the caller cannot supply them and the check is skipped;
               // the counter below still says so, so a silently unchecked
               // commit path cannot hide.
               markPriceUsd7029: Double = 0.0,
               solUsd7029: Double = 0.0,
               // V5.0.7066 §MY_GUARD_WAS_THE_WRONG_SHAPE_FOR_THIS_ASSET_CLASS.
               //
               // True when the caller derived grossProceeds as
               // `remainingBasisSol x fraction x (1 + pnlPct/100)` — SOL times
               // a dimensionless ratio, which is SOL by construction and has no
               // token-quantity-times-USD-price form to reconstruct.
               //
               // The cross-asset traders (Forex, CryptoAlt, Metals, Tokenized
               // Stock) work this way: a position holds a CONTRACT COUNT, not a
               // token balance, and its `currentPrice` is a quote in the
               // instrument's own denomination, unrelated to its SOL cost by
               // any supply ratio. The operator's own log shows it —
               // `perps: entry=2.3947539 qty=1.000 cost=1.0000` — where
               // 1 x 2.3947539 / solUsd is 0.021 SOL against a 1.0 SOL cost.
               //
               // My V5.0.7056 guard demanded the memecoin reconstruction from
               // ALL of them and refused every one it could not perform:
               // PARTIAL_PROCEEDS_UNRECONSTRUCTABLE_7056_NO_PRICE fired 512
               // times against 26 partials that completed. A 95% refusal rate
               // on profit-taking, caused by applying a token-shaped invariant
               // to instruments that have no token in them.
               //
               // These commits are NOT unchecked. They are checked by the
               // invariant that actually applies to them — see below.
               basisDerivedProceeds7066: Boolean = false): Receipt {
        val pre = CanonicalPositionAuthority6441.getPosition(positionId)
            ?: return empty(positionId, "", 0L, "UNKNOWN_POSITION")
        if (pre.mode != "paper" || fraction <= 0.0 || fraction > 1.0 || pre.remainingQtyRaw <= BigInteger.ZERO)
            return empty(positionId, "", 0L, "INVALID_PARTIAL")
        // V5.0.6613 — canonical entitlement is position + original lot qty +
        // normalized tier. Changing peak text cannot manufacture a new partial;
        // a real top-up changes originalQtyRaw and explicitly rearms a new lot epoch.
        val tier = normalizedTier6613(exitReason)
        val tierKey = "$positionId|${pre.originalQtyRaw}|$tier"
        val priorState = tierStates6613.putIfAbsent(tierKey, TierState6613.REQUESTED)
        if (priorState != null) {
            return empty(positionId, "", 0L, "PARTIAL_TIER_${priorState.name}").copy(duplicate = true)
        }
        tierUpdatedAt6613[tierKey] = System.currentTimeMillis()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PAPER_PARTIAL_CLOSE_REQUESTED") } catch (_: Throwable) {}
        // V5.0.6566 — operation identity is position-local and monotonic.
        val sequence = nextSequence(positionId, tierKey)
        val operationId = "$positionId:$sequence"
        tierStates6613[tierKey] = TierState6613.QUANTITY_RESERVED
        val soldRaw = pre.remainingQtyRaw.toBigDecimal().multiply(BigDecimal.valueOf(fraction))
            .setScale(0, RoundingMode.HALF_UP).toBigInteger().coerceIn(BigInteger.ONE, pre.remainingQtyRaw)
        val preCost = (pre.entryCostSol - pre.soldCostBasisSol).coerceAtLeast(0.0)
        val soldBasis = (preCost * soldRaw.toBigDecimal().divide(pre.remainingQtyRaw.toBigDecimal(), 18, RoundingMode.HALF_UP).toDouble()).coerceIn(0.0, preCost)
        // V5.0.7029 §PROCEEDS_THAT_NOTHING_COULD_RECONSTRUCT.
        //
        // grossProceeds arrives here as an opaque SOL figure. Every ledger
        // downstream — the paper capital authority, EconomicEventSchema, the
        // journal, the replay — then records THAT number, so they all agree
        // with each other no matter what it is. That is why the operator's
        // snapshot could report `CONSERVATION Δ -0.000000`, `qtyMismatch=0`
        // and `arithDivergences=0` over twelve partials carrying 46.59 SOL of
        // profit that no price in the same export supports. Conservation
        // proves the ledgers agree; it has never proved the originating
        // economics were right, and nothing else was checking.
        //
        // A sale has an independent identity: quantity x price. Reconstruct it
        // here, at the one boundary every paper partial passes through, and
        // refuse a figure that cannot be explained by the position's own
        // quantity and its own mark. This is the check whose absence made the
        // defect invisible rather than merely present.
        run {
            if (markPriceUsd7029 > 0.0 && solUsd7029 > 0.0 && grossProceeds > 0.0) {
                val soldQty7029 = try {
                    soldRaw.toBigDecimal().movePointLeft(pre.quantityScale.coerceIn(0, 18)).toDouble()
                } catch (_: Throwable) { 0.0 }
                val expectedSol7029 = soldQty7029 * (markPriceUsd7029 / solUsd7029)
                if (soldQty7029 > 0.0 && expectedSol7029 > 0.0) {
                    val ratio7029 = grossProceeds / expectedSol7029
                    if (ratio7029 > PROCEEDS_RECONSTRUCTION_BAND_7029 ||
                        ratio7029 < 1.0 / PROCEEDS_RECONSTRUCTION_BAND_7029
                    ) {
                        try {
                            com.lifecyclebot.engine.PipelineHealthCollector
                                .labelInc("PARTIAL_PROCEEDS_UNRECONSTRUCTABLE_7029")
                            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                                "PARTIAL_PROCEEDS_UNRECONSTRUCTABLE_7029",
                                "positionId=$positionId mint=${mint.take(10)} symbol=$symbol " +
                                    "claimedSol=${"%.6f".format(grossProceeds)} " +
                                    "qty=${"%.6f".format(soldQty7029)} markUsd=$markPriceUsd7029 " +
                                    "solUsd=${"%.2f".format(solUsd7029)} " +
                                    "reconstructedSol=${"%.6f".format(expectedSol7029)} " +
                                    "ratio=${"%.1f".format(ratio7029)}x " +
                                    "band=${PROCEEDS_RECONSTRUCTION_BAND_7029}x " +
                                    "action=refuse_commit_proceeds_not_explained_by_qty_times_price",
                            )
                        } catch (_: Throwable) {}
                        // Release the tier so a later, well-priced attempt at
                        // the same ladder step is not permanently locked out by
                        // this refusal.
                        tierStates6613.remove(tierKey)
                        return empty(positionId, "", 0L, "PROCEEDS_UNRECONSTRUCTABLE_7029")
                    }
                }
            } else {
                // V5.0.7056 §5 — FAIL CLOSED. This branch used to count
                // "PARTIAL_PROCEEDS_UNCHECKED_7029" and then fall through and
                // commit the sale anyway. That is a fail-OPEN guard: whenever
                // the mark or the SOL rate was unavailable, the one check that
                // could refuse an impossible proceeds figure was skipped and
                // the money moved regardless. V5.0.7042 measured how often that
                // happened — PROCEEDS_SOL_UNCONVERTIBLE_7029 fired 163 times in
                // a 161-second window — so the escape hatch was the normal
                // path, not the rare one. It is the leak that let 108 partial
                // rows above |1000%| contribute ~+599 SOL to a ledger whose
                // clean canonical performance was -1.3565 SOL.
                //
                // The operator's directive §5 is explicit: an unreconstructible
                // partial is quarantined, never credited. Mine was the code
                // doing the opposite.
                // V5.0.7066 — the basis-derived path is checked here, by the
                // invariant that fits it, instead of being refused for failing
                // one that never could.
                //
                //     grossProceeds = (remainingBasisSol x fraction) x (1 + pnl%)
                //
                // SOL x ratio = SOL. There is no unit crossing available in
                // that expression, which is precisely why it needs a different
                // check rather than no check: what CAN go wrong is the ratio
                // being absurd or the slice not matching the position, so that
                // is what is verified. EconomicUnitInvariant7061's §6 pro-rata
                // and conservation checks then run on it at the commit
                // boundary exactly as they do for every other sale.
                if (basisDerivedProceeds7066) {
                    val remainingBasis7066 = (pre.entryCostSol - pre.soldCostBasisSol).coerceAtLeast(0.0)
                    val impliedRatio7066 = if (soldBasis > 0.0) grossProceeds / soldBasis else -1.0
                    val sane7066 = grossProceeds.isFinite() && grossProceeds >= 0.0 &&
                        soldBasis.isFinite() && soldBasis > 0.0 &&
                        soldBasis <= remainingBasis7066 + 1e-9 &&
                        impliedRatio7066.isFinite() && impliedRatio7066 >= 0.0 &&
                        impliedRatio7066 <= BASIS_DERIVED_MAX_RATIO_7066
                    if (sane7066) {
                        try {
                            com.lifecyclebot.engine.PipelineHealthCollector
                                .labelInc("PARTIAL_PROCEEDS_BASIS_DERIVED_VALIDATED_7066")
                        } catch (_: Throwable) {}
                        // Fall through to commit — checked, just not by the
                        // token-quantity reconstruction.
                        return@run
                    }
                    try {
                        com.lifecyclebot.engine.PipelineHealthCollector
                            .labelInc("PARTIAL_PROCEEDS_BASIS_DERIVED_REFUSED_7066")
                        com.lifecyclebot.engine.ForensicLogger.lifecycle(
                            "PARTIAL_PROCEEDS_BASIS_DERIVED_REFUSED_7066",
                            "positionId=$positionId mint=${mint.take(10)} symbol=$symbol " +
                                "claimedSol=${"%.6f".format(grossProceeds)} " +
                                "soldBasis=${"%.6f".format(soldBasis)} " +
                                "remainingBasis=${"%.6f".format(remainingBasis7066)} " +
                                "impliedRatio=${"%.4g".format(impliedRatio7066)} " +
                                "cap=${BASIS_DERIVED_MAX_RATIO_7066}x " +
                                "action=refuse_basis_slice_or_ratio_not_credible",
                        )
                    } catch (_: Throwable) {}
                    tierStates6613.remove(tierKey)
                    return empty(positionId, "", 0L, "PROCEEDS_BASIS_DERIVED_REFUSED_7066")
                }
                try {
                    com.lifecyclebot.engine.PipelineHealthCollector
                        .labelInc("PARTIAL_PROCEEDS_UNRECONSTRUCTABLE_7056_NO_PRICE")
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "PARTIAL_PROCEEDS_UNRECONSTRUCTABLE_7056_NO_PRICE",
                        "positionId=$positionId mint=${mint.take(10)} symbol=$symbol " +
                            "claimedSol=${"%.6f".format(grossProceeds)} " +
                            "markUsd=$markPriceUsd7029 solUsd=$solUsd7029 " +
                            "action=refuse_commit_cannot_reconstruct_qty_times_price",
                    )
                } catch (_: Throwable) {}
                // Release the tier exactly as the priced-refusal branch does, so
                // a later attempt with a usable mark is not locked out.
                tierStates6613.remove(tierKey)
                return empty(positionId, "", 0L, "PROCEEDS_UNPRICED_7056")
            }
        }
        tierStates6613[tierKey] = TierState6613.EXECUTING
        val r = CanonicalPaperTerminalBridge6469.finalizeSell(
            positionId, mint, symbol, pre.openedAtMs, operationId, soldRaw, pre.remainingQtyRaw,
            preCost, grossProceeds.coerceAtLeast(0.0), soldBasis, fees.coerceAtLeast(0.0), pre.lane,
            exitReason, soldRaw >= pre.remainingQtyRaw, directPositionMutation6486 = true,
            // V5.0.7032 — the two figures the 7029 guard above already
            // reconstructs this sale from, recorded onto the durable event so
            // the same check can be run on it a month from now. Their absence
            // on older rows is what makes those rows unreconstructible.
            exitPriceUsd7032 = markPriceUsd7029,
            solUsdAtExit7032 = solUsd7029,
        )
        val post = CanonicalPositionAuthority6441.getPosition(positionId) ?: pre
        if (r.applied) {
            // V5.0.6677 — JOURNAL THE SAME IMMUTABLE PARTIAL RECEIPT HERE.
            //
            // Before this repair CanonicalPaperPartialOperation6510 mutated the
            // canonical position + paper ledger + EconomicEventSchema, then
            // returned without writing TradeHistoryStore. That manufactured the
            // exact split seen in forensics: typed `partials>0` while journal
            // replay reported `partials=0`, which forced UnifiedAccountSnapshot
            // into ACCOUNT_UNAVAILABLE. Do not add another caller-side patch;
            // the mutation source owns its matching durable projection.
            val scale = r.tokenDecimals.takeIf { it in 0..18 }
                ?: pre.quantityScale.coerceIn(0, 18)
            fun tokenQty(raw: BigInteger): Double = try {
                raw.toBigDecimal().movePointLeft(scale).toDouble()
            } catch (_: Throwable) { 0.0 }
            val basis = r.soldCostBasisSol
            val gross = r.grossProceedsSol
            val fee = r.feesSol
            val realizedNet = gross - basis - fee
            val pnlPct = if (basis > 0.0) realizedNet * 100.0 / basis else 0.0
            val soldQtyToken = tokenQty(r.canonicalConsumedRaw)
            val exitPrice = if (soldQtyToken > 0.0) gross / soldQtyToken else pre.entryPriceUsd

            TradeHistoryStore.recordTrade(
                Trade(
                    side = if (r.postRemainingRaw <= BigInteger.ZERO) "SELL" else "PARTIAL_SELL",
                    mode = "paper",
                    sol = gross,
                    price = exitPrice,
                    ts = System.currentTimeMillis(),
                    reason = exitReason,
                    pnlSol = realizedNet,
                    pnlPct = pnlPct,
                    feeSol = fee,
                    netPnlSol = realizedNet,
                    tradingMode = pre.lane,
                    tradingModeEmoji = "🪙",
                    mint = pre.mint,
                    proofState = "PAPER_SIMULATED",
                    positionId = pre.positionId,
                    entryTsMs = pre.openedAtMs,
                    entryPriceSnapshot = pre.entryPriceUsd,
                    // This is a modern typed receipt; raw fields are the quantity
                    // authority. Keep display entryQty at zero so 6373D's legacy
                    // qty×USD-price-vs-SOL-basis heuristic cannot rewrite this
                    // canonical receipt's PnL. soldQtyToken remains populated for UI.
                    entryQtyToken = 0.0,
                    entryCostSol = basis,
                    entryDecimals = scale,
                    soldQtyToken = soldQtyToken,
                    remainingQtyToken = tokenQty(r.postRemainingRaw),
                    entryRawQty = pre.originalQtyRaw,
                    canonicalConsumedRaw = r.canonicalConsumedRaw,
                    remainingRawQty = r.postRemainingRaw,
                    tokenDecimals = scale,
                    operationId = r.economicEventId,
                    partialSequence = sequence,
                    soldCostBasisSol = basis,
                    grossProceedsSol = gross,
                    economicEventId = r.economicEventId,
                )
            )
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PAPER_PARTIAL_JOURNAL_PROJECTED_6677")
            } catch (_: Throwable) {}

            tierStates6613[tierKey] = TierState6613.CONFIRMED
            tierStates6613[tierKey] = TierState6613.ACCOUNTED
            tierStates6613[tierKey] = TierState6613.COMPLETE
            tierUpdatedAt6613[tierKey] = System.currentTimeMillis()
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PAPER_PARTIAL_CLOSE_DONE") } catch (_: Throwable) {}
        } else {
            // Failed operations release entitlement and reserved quantity for a clean retry.
            tierStates6613.remove(tierKey)
            tierUpdatedAt6613.remove(tierKey)
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PAPER_PARTIAL_CLOSE_FAILED|${r.reason.take(80)}") } catch (_: Throwable) {}
        }
        return Receipt(r.applied, !r.applied && r.reason.contains("DUPLICATE", true), r.reason,
            positionId, operationId, sequence, pre.remainingQtyRaw, soldRaw, post.remainingQtyRaw,
            preCost, soldBasis, (post.entryCostSol - post.soldCostBasisSol).coerceAtLeast(0.0),
            grossProceeds, fees, grossProceeds - soldBasis - fees)
    }

    fun tierState6613(positionId: String, originalQtyRaw: BigInteger, reason: String): TierState6613 =
        tierStates6613["$positionId|$originalQtyRaw|${normalizedTier6613(reason)}"] ?: TierState6613.NONE

    internal fun resetForTest6613() { tierStates6613.clear(); tierUpdatedAt6613.clear(); sequences.clear(); requestSequences.clear() }

    private fun empty(pid: String, op: String, seq: Long, reason: String) = Receipt(false, false, reason, pid, op, seq,
        BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
}
