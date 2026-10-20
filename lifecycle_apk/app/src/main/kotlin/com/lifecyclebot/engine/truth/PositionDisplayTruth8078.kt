package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState

/**
 * V5.0.8078 — ONE PRICE, ONE BASIS, EVERY SCREEN.
 *
 * Operator, 8077 screenshots: the Open Positions card showed SI "Entry $0.001973 · 2.00K tokens · mark $0.001710
 * · +19.9%" while the detail sheet showed "Entry $0.001850 · 1.62K · -7.58%" and the engine stopped it at -7%.
 * Every surface read its own mix of TokenState.position, the buy-fill registry, locked entry metrics, the host
 * wallet tracker and ts.ref. This is the single read-only projection every position display consumes:
 *
 *   basis  the canonical position (wallet-proven entry USD/token, REMAINING raw quantity, remaining cost =
 *          entry cost - cost basis already sold) when one exists for this positionId and mode; else the runtime
 *          position, labelled as such.
 *   mark   the canonical EXIT_ECONOMIC mark (the chain/route-verified price the exit engine acts on) when fresh;
 *          otherwise the runtime mark, labelled unverified. Never mixed within one view.
 *   pnl    mark / entry - 1, validated by OpenPnlSanity on exactly the pair displayed.
 *
 * Read-only: no ledger, position or registry mutation. Safe on the UI thread (map reads only).
 */
object PositionDisplayTruth8078 {
    private const val VERIFIED_MARK_MAX_AGE_MS = 30_000L
    private val divergenceLoggedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    data class View(
        val entryUsd: Double,
        val qty: Double,
        val costSol: Double,
        val markUsd: Double,
        val markSource: String,
        val markAgeMs: Long,
        val markVerified: Boolean,
        val canonicalBasis: Boolean,
        val basisOk: Boolean,
        val basisReason: String,
        val pnlPct: Double,
        val pnlSol: Double,
        val valueSol: Double,
        val executablePnlPct: Double? = null,
        val executablePnlSol: Double? = null,
    ) {
        /** Short tag for the mark line: the operator can see which price they are reading. */
        val markTag: String get() = if (markVerified) "exit estimate" else "indicative"
    }

    /**
     * V5.0.8080 §PEAK_IS_CHAIN_VERIFIED. Operator: "the peak should be real. all pricing is meant to be block chain
     * verified data." A LIVE position's peak (position.peakGainPct, which arms every profit lock, give-back and
     * lifecycle promotion) may ratchet only from the fresh chain/route-verified EXIT_ECONOMIC mark, validated by
     * OpenPnlSanity on that exact pair; a provider/runtime tick that runs ahead of it never writes the peak.
     * PAPER has no chain mark and ratchets from its market price as before.
     * Returns the pnl that was eligible to ratchet (verified for LIVE), or null when LIVE has no verified mark.
     */
    fun ratchetPeak8080(ts: TokenState, candidatePnlPct: Double, writer: String): Double? {
        val pos = ts.position
        if (!pos.isOpen || !candidatePnlPct.isFinite()) return null
        if (pos.isPaperPosition) {
            try { PositionExcursion8082.observe(pos.positionId, candidatePnlPct, "PAPER_MARK_$writer") } catch (_: Throwable) {}
            if (candidatePnlPct > pos.peakGainPct) pos.peakGainPct = candidatePnlPct
            return candidatePnlPct
        }
        val v = try { view(ts) } catch (_: Throwable) { null }
        if (v == null || !v.markVerified || !v.basisOk || !v.pnlPct.isFinite()) {
            if (candidatePnlPct > pos.peakGainPct) try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PEAK_UNVERIFIED_MARK_REFUSED_8080_$writer")
            } catch (_: Throwable) {}
            return null
        }
        if (candidatePnlPct > v.pnlPct + 5.0 && candidatePnlPct > pos.peakGainPct) try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PEAK_TICK_AHEAD_OF_VERIFIED_MARK_8080_$writer")
        } catch (_: Throwable) {}
        if (v.pnlPct > pos.peakGainPct && !peakStepCorroborated8128(ts.mint, pos.peakGainPct, v.pnlPct, v.markUsd)) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PEAK_UNCORROBORATED_JUMP_REFUSED_8128_$writer") } catch (_: Throwable) {}
            requestCorroboration8128(ts.mint)
            return null
        }
        try { PositionExcursion8082.observe(pos.positionId, v.pnlPct, "VERIFIED_${v.markSource}") } catch (_: Throwable) {}
        if (v.pnlPct > pos.peakGainPct) pos.peakGainPct = v.pnlPct
        return v.pnlPct
    }

    /**
     * V5.0.8128 — a peak may not more than double in one ratchet past +100% on one mark alone (SAC: one tick from
     * ~0% to +1988%, ~23x the coin's real price). Such a step needs the mark within 25% of a price two independent
     * feeds agreed on in the last few minutes. A real run climbs through many ratchets, each under the bar.
     */
    fun peakStepCorroborated8128(mint: String, oldPeakPct: Double, newPeakPct: Double, markUsd: Double): Boolean {
        if (!jumpNeedsCorroboration8128(oldPeakPct, newPeakPct)) return true
        val agreed = try { com.lifecyclebot.network.ParallelMarkFanout7088.corroboratedPrice8128(mint) } catch (_: Throwable) { null }
        return markAgrees8128(markUsd, agreed)
    }

    /** Pure: the step from [oldPeakPct] to [newPeakPct] more than doubles the position's value multiple, past +100%. */
    fun jumpNeedsCorroboration8128(oldPeakPct: Double, newPeakPct: Double): Boolean =
        newPeakPct > 100.0 && (1.0 + newPeakPct / 100.0) > 2.0 * (1.0 + oldPeakPct.coerceAtLeast(0.0) / 100.0)

    /** Pure: [markUsd] sits within 25% of the corroborated price. */
    fun markAgrees8128(markUsd: Double, agreedUsd: Double?): Boolean =
        agreedUsd != null && agreedUsd > 0.0 && markUsd > 0.0 && kotlin.math.abs(markUsd / agreedUsd - 1.0) <= 0.25

    /**
     * V5.0.8133 — the 8128 rule for every other high/peak writer (position highestPrice, exit trackers, shadow and
     * forward-return labels, call sweeps): a step from [oldHighUsd] to [candidateUsd] that more than doubles the
     * multiple over [basisUsd] past +100% is accepted only when [candidateUsd] agrees with a two-feed price.
     * A refused step asks every feed once and the writer keeps its old high; a real run passes on the next agreeing mark.
     */
    fun highStepOk8133(mint: String, basisUsd: Double, oldHighUsd: Double, candidateUsd: Double, writer: String): Boolean {
        if (!(basisUsd > 0.0) || !candidateUsd.isFinite() || candidateUsd <= oldHighUsd) return true
        val oldPct = (maxOf(oldHighUsd, basisUsd) / basisUsd - 1.0) * 100.0
        val newPct = (candidateUsd / basisUsd - 1.0) * 100.0
        if (peakStepCorroborated8128(mint, oldPct, newPct, candidateUsd)) return true
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PEAK_UNCORROBORATED_JUMP_REFUSED_8133_$writer") } catch (_: Throwable) {}
        requestCorroboration8128(mint)
        return false
    }

    /** V5.0.8133 — the high a writer may keep: [candidateUsd] when it is a corroborated (or ordinary) step, else [oldHighUsd]. */
    fun acceptedHigh8133(mint: String, basisUsd: Double, oldHighUsd: Double, candidateUsd: Double, writer: String): Double =
        if (highStepOk8133(mint, basisUsd, oldHighUsd, candidateUsd, writer)) maxOf(oldHighUsd, candidateUsd) else oldHighUsd

    private val corroborationAskedAt8128 =java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Ask every feed for this mint once (at most every 20 s), off the caller's thread. */
    private fun requestCorroboration8128(mint: String) {
        val now = System.currentTimeMillis()
        if (now - (corroborationAskedAt8128[mint] ?: 0L) < 20_000L) return
        corroborationAskedAt8128[mint] = now
        if (corroborationAskedAt8128.size > 500) corroborationAskedAt8128.entries.removeIf { now - it.value > 600_000L }
        Thread({ try { com.lifecyclebot.network.ParallelMarkFanout7088.resolve7088(listOf(mint)) } catch (_: Throwable) {} }, "peak-corroborate-8128").start()
    }

    /** Net value of the actual quoted quantity, never a spot-price percentage applied to SOL basis. */
    internal fun executableNetPnl(costSol: Double, qty: Double, quoteUsd: Double, solUsd: Double, fixedFeeSol: Double): Double? {
        if (!costSol.isFinite() || costSol <= 0.0 || !qty.isFinite() || qty <= 0.0 ||
            !quoteUsd.isFinite() || quoteUsd <= 0.0 || !solUsd.isFinite() || solUsd <= 0.0 ||
            !fixedFeeSol.isFinite() || fixedFeeSol < 0.0) return null
        return (EconomicUnitInvariant7061.usdToSol(qty * quoteUsd, solUsd) - fixedFeeSol - costSol)
            .takeIf { it.isFinite() }
    }

    fun view(ts: TokenState, nowMs: Long = System.currentTimeMillis()): View {
        val pos = ts.position
        val mode = if (pos.isPaperPosition) "PAPER" else "LIVE"
        val canon = try {
            pos.positionId.takeIf { it.isNotBlank() }?.let { CanonicalPositionAuthority6441.getPosition(it) }
                ?.takeIf {
                    it.mode.equals(mode, true) && it.mint == ts.mint &&
                        (it.lifecycle == CanonicalPositionAuthority6441.Lifecycle.OPEN ||
                            it.lifecycle == CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED) &&
                        it.remainingQtyRaw.signum() > 0 && it.quantityScale in 0..18 && it.entryPriceUsd > 0.0
                }
        } catch (_: Throwable) { null }

        val entryUsd: Double
        val qty: Double
        val costSol: Double
        if (canon != null) {
            entryUsd = canon.entryPriceUsd
            qty = canon.remainingQtyRaw.toBigDecimal().movePointLeft(canon.quantityScale).toDouble()
            costSol = (canon.entryCostSol - canon.soldCostBasisSol).coerceAtLeast(0.0)
            noteDivergence(ts, pos.entryPrice, entryUsd, pos.qtyToken, qty)
        } else {
            entryUsd = pos.entryPrice
            qty = pos.qtyToken
            costSol = pos.costSol
        }

        val verified = try {
            CanonicalPriceMarkRegistry6522.get(ts.mint, CanonicalMarkPurpose6570.EXIT_ECONOMIC)
                ?.takeIf { nowMs - it.timestampMs in -5_000L..VERIFIED_MARK_MAX_AGE_MS }
                ?.let { m -> m.priceUsd.value.toDouble().takeIf { it.isFinite() && it > 0.0 }?.let { Triple(it, m.source, nowMs - m.timestampMs) } }
        } catch (_: Throwable) { null }
        // A spot/locked-pool mark remains useful context, but only a quantity-bound
        // sell estimate may be labelled executable or arm a profit peak.
        val solanaPosition = canon?.let { HeldHotMarkAuthority7419.solanaPricedClass7960(it.assetClass, it.mint) }
            ?: !ts.mint.contains('|')
        val exitQuote = canon?.takeIf { solanaPosition }?.let { HeldExitQuote8189.take8189(ts.mint, it.remainingQtyRaw, it.quantityScale, nowMs) }
        val exitPnl = exitQuote?.let { executableNetPnl(costSol, qty, it.priceUsd,
            com.lifecyclebot.engine.WalletManager.lastKnownSolPrice, com.lifecyclebot.engine.CostMeter8151.sellLegFixedSol8151()) }
        val markUsd = exitQuote?.priceUsd ?: verified?.first ?: ts.ref
        val markSource = if (exitQuote != null) "HELD_QUANTITY_EXIT_ESTIMATE" else verified?.second ?: ts.lastPriceSource
        val markAge = exitQuote?.let { nowMs - it.observedAtMs } ?: verified?.third ?: if (ts.lastPriceUpdate > 0L) nowMs - ts.lastPriceUpdate else -1L

        val verdict = try {
            com.lifecyclebot.engine.OpenPnlSanity.inspect(
                entryPrice = entryUsd, currentPrice = markUsd,
                entrySource = canon?.entryPriceSource ?: pos.entryPriceSource,
                currentSource = markSource,
                entryPool = canon?.entryPoolAddress ?: pos.entryPoolAddress,
                currentPool = ts.lastPricePoolAddr,
                priceBasisRescaled = pos.priceBasisRescaled,
                context = "PositionDisplayTruth8078/${ts.symbol}/${ts.mint.take(8)}",
                emit = false, mint = ts.mint, tokenDecimals = ts.tokenMap.decimals ?: -1,
            )
        } catch (_: Throwable) { null }
        val ok = verdict?.ok == true && costSol > 0.0 && qty > 0.0
        val pct = if (ok) verdict!!.pnlPct else 0.0
        val pnlSol = if (ok) costSol * pct / 100.0 else 0.0
        return View(
            entryUsd = entryUsd, qty = qty, costSol = costSol,
            markUsd = markUsd, markSource = markSource, markAgeMs = markAge, markVerified = if (solanaPosition) exitPnl != null else verified != null,
            canonicalBasis = canon != null, basisOk = ok, basisReason = verdict?.reason.orEmpty(),
            pnlPct = if (ok && exitPnl != null) exitPnl / costSol * 100.0 else pct,
            pnlSol = if (ok) exitPnl ?: pnlSol else 0.0,
            valueSol = if (ok) costSol + (exitPnl ?: pnlSol) else costSol,
            executablePnlPct = exitPnl?.let { it / costSol * 100.0 }, executablePnlSol = exitPnl,
        )
    }

    /** The runtime position drifted from the canonical one: surfaced so the engine basis can be repaired at source. */
    private fun noteDivergence(ts: TokenState, runtimeEntry: Double, canonEntry: Double, runtimeQty: Double, canonQty: Double) {
        val entryOff = runtimeEntry > 0.0 && kotlin.math.abs(runtimeEntry / canonEntry - 1.0) > 0.02
        val qtyOff = runtimeQty > 0.0 && canonQty > 0.0 && kotlin.math.abs(runtimeQty / canonQty - 1.0) > 0.02
        if (!entryOff && !qtyOff) return
        val now = System.currentTimeMillis()
        val last = divergenceLoggedAt[ts.mint] ?: 0L
        if (now - last < 60_000L) return
        divergenceLoggedAt[ts.mint] = now
        if (divergenceLoggedAt.size > 500) divergenceLoggedAt.entries.removeIf { now - it.value > 600_000L }
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("DISPLAY_RUNTIME_BASIS_DIVERGES_8078")
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "DISPLAY_RUNTIME_BASIS_DIVERGES_8078",
                "mint=${ts.mint.take(10)} sym=${ts.symbol} runtimeEntry=$runtimeEntry canonEntry=$canonEntry runtimeQty=$runtimeQty canonQty=$canonQty",
            )
        } catch (_: Throwable) {}
    }
}
