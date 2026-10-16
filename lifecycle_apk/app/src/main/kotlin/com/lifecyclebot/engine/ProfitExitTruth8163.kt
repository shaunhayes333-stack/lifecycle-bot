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

    fun preflight(ts: TokenState, reason: String, raw: Long, jupiter: JupiterApi, releaseLocks: Boolean = true): Boolean {
        if (ts.position.isPaperPosition || !profitTaking(reason)) return true
        val cost = basis(ts, raw)
        if (cost == null) return refused(ts, "MISSING_CONFIRMED_SLICE_BASIS", releaseLocks)
        val slip = com.lifecyclebot.engine.sell.SellSafetyPolicy.maxSlippageBps(reason)
        val quote = try { jupiter.getQuote(inputMint = ts.mint, outputMint = JupiterApi.SOL_MINT, amountRaw = raw, slippageBps = slip) }
            catch (_: Exception) { return refused(ts, "EXECUTABLE_QUOTE_UNAVAILABLE", releaseLocks) }
        return if (clears(cost, quote.outAmount, slip, CostMeter8151.sellLegFixedSol8151())) true else refused(ts, "EXECUTABLE_SOL_BELOW_SLICE_COST", releaseLocks)
    }

    /** Recheck the actual route at every retry, so an earlier quote cannot authorize a later losing fill. */
    fun acceptQuote(ts: TokenState, reason: String, quote: SwapQuote, slip: Int): Boolean {
        if (ts.position.isPaperPosition || !profitTaking(reason)) return true
        if (quote.inputMint != ts.mint || quote.outputMint != JupiterApi.SOL_MINT) return refused(ts, "QUOTE_IDENTITY_MISMATCH")
        return if (clears(basis(ts, quote.inAmount), quote.outAmount, slip, CostMeter8151.sellLegFixedSol8151())) true else refused(ts, "REQUOTE_BELOW_SLICE_COST")
    }

    fun acceptProceeds8168(ts: TokenState, reason: String, raw: Long, outLamports: Long, slip: Int): Boolean {
        if (ts.position.isPaperPosition || !profitTaking(reason)) return true
        return if (clears(basis(ts, raw), outLamports, slip, CostMeter8151.sellLegFixedSol8151())) true
        else refused(ts, "ROUTE_PROCEEDS_BELOW_SLICE_COST", releaseLocks = false)
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
