package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8136 — the live doors of RunnerExitContract8136 (the pure rules live there; this object reads TokenState,
 * the canonical position and the cost model, and counts what it did).
 *
 *  - [budget8136]          the one residual share budget every discretionary partial passes (Executor.requestPartialSell,
 *                          Executor.checkProfitLock profit lock, SpikeCapture7943 tiers).
 *  - [recoveryTranche8136] the capital-recovery tranche, net of sell costs, on capital still unrecovered by CONFIRMED
 *                          proceeds (Executor.checkProfitLock).
 *  - [noteSliceFill8136]   capture loss: a confirmed slice's realised gross against the gross that decided it
 *                          (Executor.commitVerifiedLiveSlice7835).
 */
object RunnerExitLive8136 {
    private const val DEFAULT_SELL_COST_PCT_8136 = 3.0
    /** A capture that gave up at least this many points against its decision price is labelled (fees alone are ~3-8). */
    private const val CAPTURE_LOSS_LABEL_PP_8136 = 15.0
    private const val MAX_COUNTER_KEYS_8136 = 64

    private val counts = ConcurrentHashMap<String, AtomicLong>()
    private val captures = AtomicLong(0)
    private var captureLossPpSum = 0.0
    private var captureLossPpWorst = 0.0

    /** Count [label] here and on the pipeline health board. */
    fun count8136(label: String) {
        if (counts.size < MAX_COUNTER_KEYS_8136 || counts.containsKey(label)) counts.computeIfAbsent(label) { AtomicLong(0) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {}
    }

    /** A position whose residual runner + moonbag share the budget protects. */
    private fun protectsResidual8136(ts: TokenState): Boolean =
        RunnerGrab7967.holdingRunner8018(ts) || TradeExitContract8077.longHold(ts) == true || LifecycleHold8065.holding8065(ts)

    /** The share budget: the fraction of the CURRENT holding a partial [reason] may sell (full exits pass unchanged). */
    fun budget8136(ts: TokenState, fractionOfCurrent: Double, reason: String): Double {
        if (!fractionOfCurrent.isFinite() || fractionOfCurrent <= 0.0) return 0.0
        if (fractionOfCurrent >= 1.0 - 1e-9) return fractionOfCurrent
        val reserve = try {
            if (protectsResidual8136(ts)) RunnerExitContract8136.RUNNER_RESIDUAL_SHARE_8136 else 0.0
        } catch (_: Throwable) { 0.0 }
        if (reserve <= 0.0) return fractionOfCurrent
        val remaining = ((100.0 - ts.position.partialSoldPct) / 100.0).coerceIn(0.0, 1.0)
        val allowed = RunnerExitContract8136.budgetedFraction8136(fractionOfCurrent, remaining, reserve, reason)
        if (allowed < fractionOfCurrent - 1e-9) {
            count8136(if (allowed <= 0.0) "RUNNER_SHARE_BUDGET_REFUSED_8136" else "RUNNER_SHARE_BUDGET_CLAMPED_8136")
        }
        return allowed
    }

    /** The sell-side cost of one exit leg, percent: half the measured round trip, at least the 1% platform share. */
    fun sellCostPct8136(ts: TokenState): Double = try {
        val sizeSol = ts.position.costSol
        val solUsd = WalletManager.lastKnownSolPrice
        val roundTrip = com.lifecyclebot.engine.truth.FieldManual7715.roundTripCostPct7766(
            sizeSol, if (solUsd.isFinite() && solUsd > 0.0) sizeSol * solUsd else 0.0,
            com.lifecyclebot.engine.truth.LiquidityTruth8037.hydrate8037(ts),
        )
        if (roundTrip.isFinite()) (roundTrip / 2.0).coerceIn(1.0, 25.0) else DEFAULT_SELL_COST_PCT_8136
    } catch (_: Throwable) { DEFAULT_SELL_COST_PCT_8136 }

    /**
     * Executor.checkProfitLock: the capital-recovery tranche as a fraction of the CURRENT holding, worth
     * [grossValueSol] at the executable mark. Live: the target is entry cost less the canonical position's confirmed
     * net proceeds (a spike / runner rung may already have brought most of it back); otherwise [remainingCostSol].
     * When confirmed proceeds already cover the cost the position is marked recovered FROM those proceeds and no
     * tranche is sold. 0 = no tranche.
     */
    fun recoveryTranche8136(ts: TokenState, remainingCostSol: Double, grossValueSol: Double): Double {
        val pos = ts.position
        val canon = if (pos.isPaperPosition) null else try {
            com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.getPosition(pos.positionId)
                ?.takeIf { it.mint == ts.mint && it.mode.equals("live", true) && it.entryCostSol.isFinite() && it.entryCostSol > 0.0 }
        } catch (_: Throwable) { null }
        if (canon != null && RunnerExitContract8136.capitalRecoveredConfirmed8136(canon.entryCostSol, canon.realizedProceedsSol, canon.feesSol)) {
            synchronized(ts) {
                ts.position = ts.position.copy(capitalRecovered = true, isHouseMoney = true,
                    capitalRecoveredSol = canon.realizedProceedsSol - canon.feesSol)
            }
            count8136("CAPITAL_RECOVERED_FROM_CONFIRMED_SLICES_8136")
            return 0.0
        }
        val unrecovered = if (canon != null) RunnerExitContract8136.unrecoveredSol8136(canon.entryCostSol, canon.realizedProceedsSol, canon.feesSol)
            else remainingCostSol.coerceAtLeast(0.0)
        val fraction = RunnerExitContract8136.recoveryFraction8136(unrecovered, grossValueSol, sellCostPct8136(ts))
            .coerceAtMost(((100.0 - pos.partialSoldPct) / 100.0).coerceAtLeast(0.0))
        if (fraction > 0.0) count8136("CAPITAL_RECOVERY_TRANCHE_NET_8136")
        return fraction
    }

    /** Executor.commitVerifiedLiveSlice7835: a confirmed slice of [costBasisSol] returned [proceedsSol]. */
    fun noteSliceFill8136(reason: String, proceedsSol: Double, costBasisSol: Double) {
        val decided = RunnerExitContract8136.decisionGrossOf8136(reason)
        if (!decided.isFinite() || decided <= 0.0) return
        val tier = RunnerExitContract8136.tierOf8136(reason)
        if (tier.rank <= RunnerExitContract8136.Tier8136.HARD_RISK.rank) return
        val loss = RunnerExitContract8136.captureLossPp8136(decided, proceedsSol, costBasisSol)
        if (!loss.isFinite()) return
        captures.incrementAndGet()
        synchronized(this) {
            captureLossPpSum += loss
            if (loss > captureLossPpWorst) captureLossPpWorst = loss
        }
        if (loss < CAPTURE_LOSS_LABEL_PP_8136) return
        val realised = (proceedsSol / costBasisSol - 1.0) * 100.0
        count8136("RUNNER_CAPTURE_LOSS_8136_${tier.name}")
        if (realised < 0.0) count8136("RUNNER_CAPTURE_COLLAPSED_TO_LOSS_8136")
        try {
            ForensicLogger.lifecycle("RUNNER_CAPTURE_LOSS_8136",
                "reason=${reason.take(60)} decidedPct=${decided.toInt()} realisedPct=${"%.1f".format(realised)} lossPp=${"%.1f".format(loss)}")
        } catch (_: Throwable) {}
    }

    fun statusLine8136(): String {
        val n = captures.get()
        val (sum, worst) = synchronized(this) { captureLossPpSum to captureLossPpWorst }
        val mean = if (n > 0L) "%.1f".format(sum / n) else "-"
        val top = counts.entries.sortedByDescending { it.value.get() }.take(8).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return "captures=$n meanLossPp=$mean worstPp=${"%.1f".format(worst)} residualShare=${(RunnerExitContract8136.RUNNER_RESIDUAL_SHARE_8136 * 100).toInt()}% " +
            "promote=+${RunnerExitContract8136.PROMOTE_PCT_8136.toInt()}% corroborated · [$top]"
    }
}
