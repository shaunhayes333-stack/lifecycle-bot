package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.network.JupiterApi
import com.lifecyclebot.network.SwapQuote
import java.math.BigInteger
import java.math.RoundingMode

/** Profit is executable SOL over the confirmed cost of the exact sold slice, never a USD mark or peak. */
object ProfitExitTruth8163 {
    private val refusals = java.util.concurrent.atomic.AtomicLong(0)
    fun statusLine(): String = "profitQuoteRefused8163=${refusals.get()} basis=exact_slice_SOL " + ProfitLock8363.statusLine8363()
    fun profitTaking(reason: String): Boolean {
        // V5.0.8337 — a protective exit (ranks 1-3) is never profit-taking, whatever words its name carries.
        if (try { ExitTruthArbiter8144.protective8144(reason) } catch (_: Throwable) { false }) return false
        // V5.0.8363 — a profit lock / give-back / trail / floor protects a shrinking gain: never refused by the requote test.
        if (try { ProfitLock8363.profitLock8363(reason) } catch (_: Throwable) { false }) return false
        val r = reason.uppercase()
        if (listOf("STOP_LOSS", "STRICT_SL", "CATASTROPH", "BACKSTOP", "RUG", "MANUAL", "EMERGENCY", "ORPHAN", "RECONCIL").any { r.contains(it) }) return false
        return RecoveredHoldGuard.isProfitTakingExit7931(reason) ||
            listOf("HARVEST", "RUNNER_BANK", "RUNNER_LIVE_BANK", "STALE_RUNNER_FORCE", "CAPITAL_RECOVERY", "SPIKE_CAPTURE", "RUNNER_MILESTONE", "TAIL_MILESTONE", "LIFECYCLE_PROFIT_TAKE").any { r.contains(it) }
    }

    fun sliceCost(remainingCostSol: Double, remainingRaw: BigInteger, sellRaw: BigInteger): Double? {
        if (!remainingCostSol.isFinite() || remainingCostSol <= 0.0 || remainingRaw.signum() <= 0 ||
            sellRaw.signum() <= 0 || sellRaw > remainingRaw) return null
        return remainingCostSol * sellRaw.toBigDecimal().divide(remainingRaw.toBigDecimal(), 18, RoundingMode.HALF_UP).toDouble()
    }

    private fun basis(ts: TokenState, raw: Long): Double? {
        val p = CanonicalPositionAuthority6441.getPosition(ts.position.positionId) ?: return null
        if (p.mint != ts.mint || !p.mode.equals("live", true) ||
            p.lifecycle !in setOf(CanonicalPositionAuthority6441.Lifecycle.OPEN, CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED)) return null
        return sliceCost(p.entryCostSol - p.soldCostBasisSol, p.remainingQtyRaw, BigInteger.valueOf(raw))
    }

    /** The margin covers transaction overhead in addition to the quote's venue fees and slippage. */
    fun clears(costSol: Double?, outLamports: Long, slippageBps: Int, fixedFeeSol: Double = 0.000_205): Boolean {
        if (costSol == null || !costSol.isFinite() || costSol <= 0.0 || outLamports <= 0L || slippageBps !in 0..9999) return false
        val net = outLamports / 1_000_000_000.0 * (1.0 - slippageBps / 10_000.0)
        return fixedFeeSol.isFinite() && fixedFeeSol >= 0.0 && net >= costSol * 1.015 + fixedFeeSol
    }

    /** V5.0.8189 — expected sell slippage when the lane has fewer than [MIN_SLIP_N_8189] measured sell legs. */
    private const val DEFAULT_SLIP_PCT_8189 = 1.0
    private const val MIN_SLIP_N_8189 = 5
    private const val MIN_SLIP_BPS_8189 = 50

    /**
     * V5.0.8189 — pure: the slippage (bps) a profit exit's EXPECTED proceeds are discounted by. The quote's out amount
     * already carries price impact; the cap ([capBps], the swap's minOut bound) is the worst case, not the expectation —
     * 5.0.8186 refused 16 profitable exits by treating it as one. The measured lane sell-side slippage when [n] >= 5
     * legs, else 1.0%, clamped to [0.5%, cap]. The transaction's own minOut stays at the cap.
     */
    fun expectedSlippageBps8189(measuredPct: Double?, n: Int, capBps: Int): Int {
        val pct = if (measuredPct != null && measuredPct.isFinite() && measuredPct >= 0.0 && n >= MIN_SLIP_N_8189) measuredPct else DEFAULT_SLIP_PCT_8189
        val cap = capBps.coerceIn(0, 9999)
        return (Math.round(pct * 100.0).toInt()).coerceIn(minOf(MIN_SLIP_BPS_8189, cap), cap)
    }

    // superseded by V5.0.8463 — the lane's mark-based sell-side median (CostLedger7962.sellSidePct8189) is the market's move
    // during the exit, not slippage: one lane's rug-drain sells discounted every later profit exit in it. Expected slippage
    // is the wallet's realised sell fills against their quotes (RealisedSlip8418), 1.0% until 5 sells measured it.
    @Suppress("UNUSED_PARAMETER")
    private fun expectedSlip8189(ts: TokenState, capBps: Int): Int {
        val m = try { com.lifecyclebot.engine.RealisedSlip8418.sellReceipt8463() } catch (_: Throwable) { null }
        return expectedSlippageBps8189(m?.first, m?.second ?: 0, capBps)
    }

    fun preflight(ts: TokenState, reason: String, raw: Long, jupiter: JupiterApi, releaseLocks: Boolean = true): Boolean {
        if (!ts.position.isPaperPosition && ProfitLock8363.profitLock8363(reason) && !ExitTruthArbiter8144.protective8144(reason)) ProfitLock8363.notePassed8363()  // V5.0.8363
        if (ts.position.isPaperPosition || !profitTaking(reason)) return true
        PipelineHealthCollector.labelInc("PROFIT_EXIT_TRUTH_CHECK_DIRECT_8168")
        val cost = basis(ts, raw)
        if (cost == null) return refused(ts, "MISSING_CONFIRMED_SLICE_BASIS", releaseLocks)
        val slip = com.lifecyclebot.engine.sell.SellSafetyPolicy.maxSlippageBps(reason)
        val quote = try { jupiter.getQuote(inputMint = ts.mint, outputMint = JupiterApi.SOL_MINT, amountRaw = raw, slippageBps = slip) }
            catch (_: Exception) { null }
        if (quote != null && !com.lifecyclebot.engine.truth.HeldHotMarkAuthority7419.quoteMatchesHolding(quote, ts.mint, BigInteger.valueOf(raw)))
            return refused(ts, "QUOTE_IDENTITY_MISMATCH", releaseLocks)
        val outLamports = preflightProceeds(quote, ts.mint, raw) {
            // This is an estimate only; the selected venue still validates the signed route.
            val position = CanonicalPositionAuthority6441.getPosition(ts.position.positionId)
            position?.let { com.lifecyclebot.engine.truth.HeldHotMarkAuthority7419.venueReserveSellEstimate(
                ts.mint, BigInteger.valueOf(raw), it.quantityScale, WalletManager.lastKnownSolPrice) }
        } ?: return refused(ts, "EXECUTABLE_QUOTE_UNAVAILABLE", releaseLocks)
        if (quote == null) PipelineHealthCollector.labelInc("PROFIT_EXIT_VENUE_RESERVE_ESTIMATE_8253")
        reconcileMark8237(ts, raw, outLamports)
        return if (clears(cost, outLamports, expectedSlip8189(ts, slip), CostMeter8151.sellLegFixedSol8151())) true else refused(ts, "EXECUTABLE_SOL_BELOW_SLICE_COST", releaseLocks)
    }

    internal fun preflightProceeds(quote: SwapQuote?, mint: String, raw: Long,
        venue: () -> com.lifecyclebot.engine.truth.HeldHotMarkAuthority7419.SellEstimate?): Long? {
        if (raw <= 0L) return null
        if (quote != null) return quote.outAmount.takeIf {
            com.lifecyclebot.engine.truth.HeldHotMarkAuthority7419.quoteMatchesHolding(quote, mint, BigInteger.valueOf(raw))
        }
        val estimate = venue() ?: return null
        if (!estimate.proceedsSol.isFinite() || estimate.proceedsSol <= 0.0) return null
        return try { java.math.BigDecimal.valueOf(estimate.proceedsSol).movePointRight(9)
            .setScale(0, RoundingMode.DOWN).longValueExact().takeIf { it > 0L } } catch (_: ArithmeticException) { null }
    }

    /** Recheck the actual route at every retry, so an earlier quote cannot authorize a later losing fill. */
    fun acceptQuote(ts: TokenState, reason: String, quote: SwapQuote, slip: Int): Boolean {
        if (ts.position.isPaperPosition || !profitTaking(reason)) return true
        PipelineHealthCollector.labelInc("PROFIT_EXIT_TRUTH_CHECK_QUOTE_8168")
        if (quote.inputMint != ts.mint || quote.outputMint != JupiterApi.SOL_MINT) return refused(ts, "QUOTE_IDENTITY_MISMATCH")
        reconcileMark8237(ts, quote.inAmount, quote.outAmount)
        return if (clears(basis(ts, quote.inAmount), quote.outAmount, expectedSlip8189(ts, slip), CostMeter8151.sellLegFixedSol8151())) true else refused(ts, "REQUOTE_BELOW_SLICE_COST")
    }

    fun acceptProceeds8168(ts: TokenState, reason: String, raw: Long, outLamports: Long, slip: Int): Boolean {
        if (ts.position.isPaperPosition || !profitTaking(reason)) return true
        PipelineHealthCollector.labelInc("PROFIT_EXIT_TRUTH_CHECK_ROUTE_8168")
        reconcileMark8237(ts, raw, outLamports)
        return if (clears(basis(ts, raw), outLamports, expectedSlip8189(ts, slip), CostMeter8151.sellLegFixedSol8151())) true
        else refused(ts, "ROUTE_PROCEEDS_BELOW_SLICE_COST", releaseLocks = false)
    }

    /**
     * V5.0.8237 §A_DISPROVEN_MARK_STOPS_HERE — the executable quote is the only price a profit exit can bank; when the
     * mark the position was displayed, peaked and (nearly) learnt at sits far above it, the mark is disproven: the
     * position's price and peak are re-based to the quote so the screen, the trailing peak and the learner stop reading
     * a gain nobody could sell. 8236 forensic (YEARN): +1909%/+2000% displayed, +3.5% executable, 20 of 21 profit quotes
     * refused against slice cost — the quote proved the mark wrong 20 times and nothing acted on it.
     */
    private const val MARK_DISPROOF_RATIO_8237 = 1.5

    /** Pure: the USD price a quote actually states for the slice, or NaN. */
    fun quoteImpliedUsd8237(outLamports: Long, rawIn: Long, decimals: Int, solUsd: Double): Double {
        if (outLamports <= 0L || rawIn <= 0L || decimals !in 0..18 || !(solUsd > 0.0) || !solUsd.isFinite()) return Double.NaN
        val tokens = rawIn / Math.pow(10.0, decimals.toDouble())
        return (outLamports / 1e9 * solUsd) / tokens
    }

    /** Pure: is the mark more than [MARK_DISPROOF_RATIO_8237] above the executable price? */
    fun markDisproven8237(markUsd: Double, quoteUsd: Double): Boolean =
        markUsd.isFinite() && quoteUsd.isFinite() && markUsd > 0.0 && quoteUsd > 0.0 && markUsd / quoteUsd > MARK_DISPROOF_RATIO_8237

    private fun reconcileMark8237(ts: TokenState, rawIn: Long, outLamports: Long) {
        try {
            val dec = com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.firstOpenForMint(ts.mint)?.tokenDecimals?.takeIf { it in 0..18 } ?: return
            val q = quoteImpliedUsd8237(outLamports, rawIn, dec, WalletManager.lastKnownSolPrice)
            if (!markDisproven8237(ts.lastPrice, q)) return
            val ratio = ts.lastPrice / q
            ts.lastPrice = q
            ts.lastPriceSource = "EXEC_QUOTE_8237"
            ts.lastPriceUpdate = System.currentTimeMillis()
            val floor = maxOf(q, ts.position.entryPrice)
            if (ts.position.highestPrice > floor) ts.position.highestPrice = floor
            PipelineHealthCollector.labelInc("MARK_DISPROVEN_BY_QUOTE_8237")
            if (ForensicEmitRateLimiter6356.shouldEmit("MARK_DISPROVEN_8237", ts.mint))
                ForensicLogger.lifecycle("MARK_DISPROVEN_BY_QUOTE_8237", "mint=${ts.mint} markOverQuote=${"%.2f".format(ratio)}x quoteUsd=$q peakRebasedTo=$floor")
        } catch (_: Throwable) {}
    }

    private fun refused(ts: TokenState, why: String, releaseLocks: Boolean = true): Boolean {
        // Both preflight and retry rejection occur before a new broadcast. Do not strand a close lease.
        if (releaseLocks && TradeVerifier.activeSellSig(ts.mint) == null) {
            com.lifecyclebot.engine.sell.SellExecutionLocks.release(ts.mint)
            com.lifecyclebot.engine.sell.CloseLease.release(ts.mint, "PROFIT_QUOTE_REFUSED_8163")
            HostWalletTokenTracker.clearSellInFlight(ts.mint, "PROFIT_QUOTE_REFUSED_8163")
            TradeVerifier.endSell(ts.mint)
        }
        refusals.incrementAndGet()
        PipelineHealthCollector.labelInc("PROFIT_EXIT_TRUTH_REFUSED_8163_$why")
        PipelineHealthCollector.labelInc("PROFIT_EXIT_REFUSED_8163_$why")  // V5.0.8363
        // V5.0.8363 — a refusal is not a sign / send failure: the spine books it as PROFIT_EXIT_REFUSED_8163_<why>.
        try { com.lifecyclebot.engine.sell.SellRouteSpine8144.noteRefused8363(ts.mint, "PROFIT_EXIT_REFUSED_8163_$why") } catch (_: Throwable) {}
        if (ForensicEmitRateLimiter6356.shouldEmit("PROFIT_EXIT_TRUTH_8163", ts.mint))
            ForensicLogger.lifecycle("PROFIT_EXIT_TRUTH_REFUSED_8163", "mint=${ts.mint} reason=$why action=refresh_profit_evidence_structural_exits_remain_available")
        return false
    }
}
