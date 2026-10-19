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
    fun statusLine(): String = "profitQuoteRefused8163=${refusals.get()} basis=exact_slice_SOL"
    fun profitTaking(reason: String): Boolean {
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

    /** Measured quote-to-wallet shortfall includes fixed fees; the fallback estimate charges those separately. */
    internal fun clearsQuoted8239(cost: Double?, outLamports: Long, capBps: Int, measuredShortfallPct: Double?, measuredN: Int,
                         fixedFeeSol: Double): Boolean {
        val measured = measuredN >= 5 && measuredShortfallPct?.let { it.isFinite() && it >= 0.0 } == true
        if (capBps !in 0..9999) return false
        // A transaction slippage cap does not cap network fees. Measured quote-to-wallet total shortfall
        // must retain those costs even when it exceeds the price-slippage cap.
        val discount = if (measured) Math.round(maxOf(0.5, measuredShortfallPct!!) * 100.0).toInt().coerceIn(0, 9999)
            else expectedSlippageBps8189(null, 0, capBps)
        return clears(cost, outLamports, discount, if (measured) 0.0 else fixedFeeSol)
    }

    private fun clearsRoute8239(ts: TokenState, raw: Long, outLamports: Long, slip: Int, route: String = "JUPITER"): Boolean {
        val m = try { com.lifecyclebot.engine.truth.QuoteFillCost8239.measured(ts.position.tradingMode, outLamports / 1e9, route) } catch (_: Throwable) { null }
        return clearsQuoted8239(basis(ts, raw), outLamports, slip, m?.first, m?.second ?: 0, CostMeter8151.sellLegFixedSol8151())
    }

    fun basisPreflight8239(ts: TokenState, reason: String, raw: Long, releaseLocks: Boolean = true): Boolean {
        if (ts.position.isPaperPosition || !profitTaking(reason)) return true
        return basis(ts, raw) != null || refused(ts, "MISSING_CONFIRMED_SLICE_BASIS", releaseLocks)
    }

    private fun captureQuote8239(ts: TokenState, raw: Long, outLamports: Long, route: String, observedAtMs: Long) {
        if (ts.position.isPaperPosition) return
        com.lifecyclebot.engine.truth.QuoteFillCost8239.capture(com.lifecyclebot.engine.truth.QuoteFillCost8239.Reference(
            ts.position.positionId, ts.mint,
            com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(ts.position.tradingMode).uppercase(),
            raw, outLamports / 1e9, route, observedAtMs))
    }

    fun preflight(ts: TokenState, reason: String, raw: Long, jupiter: JupiterApi, releaseLocks: Boolean = true): Boolean {
        if (ts.position.isPaperPosition || !profitTaking(reason)) return true
        PipelineHealthCollector.labelInc("PROFIT_EXIT_TRUTH_CHECK_DIRECT_8168")
        val cost = basis(ts, raw)
        if (cost == null) return refused(ts, "MISSING_CONFIRMED_SLICE_BASIS", releaseLocks)
        val slip = com.lifecyclebot.engine.sell.SellSafetyPolicy.maxSlippageBps(reason)
        val quote = try { jupiter.getQuote(inputMint = ts.mint, outputMint = JupiterApi.SOL_MINT, amountRaw = raw, slippageBps = slip) }
            catch (_: Exception) { return refused(ts, "EXECUTABLE_QUOTE_UNAVAILABLE", releaseLocks) }
        reconcileMark8237(ts, raw, quote.outAmount, quote.observedAtMs8239)
        return if (clearsRoute8239(ts, raw, quote.outAmount, slip)) true else refused(ts, "EXECUTABLE_SOL_BELOW_SLICE_COST", releaseLocks)
    }

    /** Recheck the actual route at every retry, so an earlier quote cannot authorize a later losing fill. */
    fun acceptQuote(ts: TokenState, reason: String, quote: SwapQuote, slip: Int, releaseLocks: Boolean = true): Boolean {
        if (ts.position.isPaperPosition) return true
        if (profitTaking(reason)) {
            PipelineHealthCollector.labelInc("PROFIT_EXIT_TRUTH_CHECK_QUOTE_8168")
            if (quote.inputMint != ts.mint || quote.outputMint != JupiterApi.SOL_MINT) return refused(ts, "QUOTE_IDENTITY_MISMATCH", releaseLocks)
            if (System.currentTimeMillis() - quote.observedAtMs8239 !in 0L..10_000L) return refused(ts, "QUOTE_STALE", releaseLocks)
            reconcileMark8237(ts, quote.inAmount, quote.outAmount, quote.observedAtMs8239)
            if (!clearsRoute8239(ts, quote.inAmount, quote.outAmount, slip)) return refused(ts, "REQUOTE_BELOW_SLICE_COST", releaseLocks)
        }
        if (quote.inputMint == ts.mint && quote.outputMint == JupiterApi.SOL_MINT)
            captureQuote8239(ts, quote.inAmount, quote.outAmount, "JUPITER", quote.observedAtMs8239)
        return true
    }

    fun acceptProceeds8168(ts: TokenState, reason: String, raw: Long, outLamports: Long, slip: Int,
                          route: String = "RAYDIUM", observedAtMs: Long = System.currentTimeMillis()): Boolean {
        if (ts.position.isPaperPosition) return true
        if (profitTaking(reason)) {
            PipelineHealthCollector.labelInc("PROFIT_EXIT_TRUTH_CHECK_ROUTE_8168")
            if (System.currentTimeMillis() - observedAtMs !in 0L..10_000L) return refused(ts, "NATIVE_QUOTE_STALE", releaseLocks = false)
            reconcileMark8237(ts, raw, outLamports, observedAtMs)
            if (!clearsRoute8239(ts, raw, outLamports, slip, route)) return refused(ts, "ROUTE_PROCEEDS_BELOW_SLICE_COST", releaseLocks = false)
        }
        captureQuote8239(ts, raw, outLamports, route, observedAtMs)
        return true
    }

    /** An exact full-holding quote can correct the current liquidation valuation. It cannot disprove an earlier peak:
     * market movement, size impact and route differences may all explain a lower current quote. Historical corrections
     * require an identified invalid observation or a proven entry-basis repair (RunnerEvidence8239). */
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

    internal fun reconcileMark8237(ts: TokenState, rawIn: Long, outLamports: Long, observedAtMs: Long) {
        try {
            val canonical = CanonicalPositionAuthority6441.getPosition(ts.position.positionId)?.takeIf {
                it.mint == ts.mint && it.mode.equals("live", true) && it.tokenDecimals in 0..18 &&
                    it.remainingQtyRaw == BigInteger.valueOf(rawIn)
            } ?: return // A partial-slice unit price must not revalue a differently sized remaining holding.
            val now = System.currentTimeMillis()
            if (now - observedAtMs !in 0L..10_000L) return
            val q = quoteImpliedUsd8237(outLamports, rawIn, canonical.tokenDecimals, WalletManager.freshSolPrice8163(now))
            if (!markDisproven8237(ts.lastPrice, q)) return
            val ratio = ts.lastPrice / q
            val published = com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522.publishRepairedExitEconomic7418(
                ts.mint, q, "EXEC_QUOTE_8237", verifiedIdentity7424 = true, observedAtMs7884 = observedAtMs)
            if (!published) return
            ts.lastPrice = q
            ts.lastPriceSource = "EXEC_QUOTE_8237"
            ts.lastPriceUpdate = observedAtMs
            // highestPrice / PositionExcursion / runner opportunity retain their original historical evidence.
            PipelineHealthCollector.labelInc("MARK_DISPROVEN_BY_QUOTE_8237")
            if (ForensicEmitRateLimiter6356.shouldEmit("MARK_DISPROVEN_8237", ts.mint))
                ForensicLogger.lifecycle("MARK_DISPROVEN_BY_QUOTE_8237", "mint=${ts.mint} markOverQuote=${"%.2f".format(ratio)}x quoteUsd=$q historicalPeak=retained sourceAt=$observedAtMs")
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
        if (ForensicEmitRateLimiter6356.shouldEmit("PROFIT_EXIT_TRUTH_8163", ts.mint))
            ForensicLogger.lifecycle("PROFIT_EXIT_TRUTH_REFUSED_8163", "mint=${ts.mint} reason=$why action=refresh_profit_evidence_structural_exits_remain_available")
        return false
    }
}
