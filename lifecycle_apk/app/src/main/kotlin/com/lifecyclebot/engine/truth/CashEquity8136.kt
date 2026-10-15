package com.lifecyclebot.engine.truth

/**
 * V5.0.8136 §CASH_EQUITY_SPLIT — the live capital, in its separate parts.
 *
 * 5.0.8127: a 24h peak of 1.0777 SOL against ~0.40 SOL of real equity (SAC's phantom +1988% mark) cut every
 * live size by drawdown, and a deposit / withdrawal moved the "daily P&L" exactly as a trade would. One marked
 * number cannot answer three different questions, so the parts are kept apart:
 *
 *   liquidSol               SOL in the wallet
 *   reservedTxSol           SOL held back for fees / rent (never spendable on an order)
 *   openPrincipalSol        confirmed cost basis still deployed in open live positions (wallet-held rows only)
 *   validatedUnrealisedSol  the open positions' mark, only where the mark passed OpenPnlSanity (signed)
 *   settledRealisedSol      settled, chain-proven realised live P&L (report)
 *   netExternalFlowSol      deposits (+) / withdrawals (-) that no bot trade explains
 *
 * Rules (pure, below):
 *   - risk equity (peaks, drawdown, the anti-reward-hack high) = liquid + principal + an unrealised LOSS that has
 *     held for two reads. An unrealised GAIN never counts until it is sold: a transient or phantom mark can
 *     neither create a peak nor mask a real drawdown, and a one-read crash mark cannot invent one.
 *   - trading P&L = change in risk equity minus external flows: a deposit is not profit, a withdrawal not a loss.
 *   - tradeable = liquid - reserved: the reserve never funds an order.
 * Paper never enters any of it.
 */
data class CashEquity8136(
    val liquidSol: Double,
    val reservedTxSol: Double,
    val openPrincipalSol: Double,
    val validatedUnrealisedSol: Double,
    val settledRealisedSol: Double,
    val netExternalFlowSol: Double,
)

object CashEquityBook8136 {

    private fun fin(v: Double): Double = if (v.isFinite()) v else 0.0
    private fun pos(v: Double): Double = if (v.isFinite()) v.coerceAtLeast(0.0) else 0.0

    /** Pure: SOL an order may spend (liquid less the tx reserve). */
    fun tradeableSol8136(e: CashEquity8136): Double = (pos(e.liquidSol) - pos(e.reservedTxSol)).coerceAtLeast(0.0)

    /** Pure: the equity peaks and drawdown are measured on — unrealised gains excluded, validated losses in. */
    fun riskEquitySol8136(e: CashEquity8136): Double =
        pos(e.liquidSol) + (pos(e.openPrincipalSol) + minOf(0.0, fin(e.validatedUnrealisedSol))).coerceAtLeast(0.0)

    /** Pure: the display / marked equity (validated gains included). Never a peak or a sizing input. */
    fun markedEquitySol8136(e: CashEquity8136): Double =
        pos(e.liquidSol) + (pos(e.openPrincipalSol) + fin(e.validatedUnrealisedSol)).coerceAtLeast(0.0)

    /** Pure: trading P&L since [baselineRiskEquitySol] — the change external flows do not explain. */
    fun tradingPnlSol8136(baselineRiskEquitySol: Double, e: CashEquity8136): Double =
        riskEquitySol8136(e) - fin(baselineRiskEquitySol) - fin(e.netExternalFlowSol)

    /**
     * Pure: the unrealised loss a drawdown may count — the SMALLER loss of this read and the previous one, so a
     * one-read crash mark is not a drawdown; a loss that holds for two reads is. No previous read: this one counts.
     */
    fun confirmedUnrealisedLoss8136(unrealisedNowSol: Double, unrealisedPrevSol: Double): Double {
        val now = minOf(0.0, fin(unrealisedNowSol))
        if (!unrealisedPrevSol.isFinite()) return now
        return maxOf(now, minOf(0.0, unrealisedPrevSol))
    }

    /**
     * Pure: the equity a drawdown is measured on, from the MARKED equity [markedEquitySol] and its unrealised part:
     * marked less this read's whole unrealised P&L, plus only the confirmed unrealised loss.
     */
    fun drawdownEquitySol8136(markedEquitySol: Double, unrealisedNowSol: Double, unrealisedPrevSol: Double): Double {
        if (!markedEquitySol.isFinite() || markedEquitySol <= 0.0) return markedEquitySol
        return (markedEquitySol - fin(unrealisedNowSol) + confirmedUnrealisedLoss8136(unrealisedNowSol, unrealisedPrevSol))
            .coerceAtLeast(0.0)
    }

    /**
     * Pure: today's P&L percent against [dailyStartSol], net of the external flows booked since the day started
     * ([flowSinceStartSol], deposits +). A deposit does not read as a gain, a withdrawal not as a daily loss.
     */
    fun dailyPnlPct8136(equityNowSol: Double, dailyStartSol: Double, flowSinceStartSol: Double): Double {
        if (!equityNowSol.isFinite() || !dailyStartSol.isFinite() || dailyStartSol <= 0.0) return 0.0
        return (equityNowSol - dailyStartSol - fin(flowSinceStartSol)) / dailyStartSol * 100.0
    }

    /** Pure: a high-water mark after a confirmed external flow (deposit +): moved by the flow, never below zero. */
    fun shiftedHigh8136(priorHighSol: Double, flowSol: Double): Double =
        if (!priorHighSol.isFinite() || priorHighSol <= 0.0) priorHighSol else (priorHighSol + fin(flowSol)).coerceAtLeast(0.0)

    /** One status fragment with every part named (CapitalDrawdown7948.line7948). */
    fun line8136(e: CashEquity8136): String =
        "equity8136=[liquid=${f(e.liquidSol)} reservedTx=${f(e.reservedTxSol)} openPrincipal=${f(e.openPrincipalSol)} " +
            "validatedUpnl=${"%+.4f".format(fin(e.validatedUnrealisedSol))} settledRealised=${"%+.4f".format(fin(e.settledRealisedSol))} " +
            "netExternal=${"%+.4f".format(fin(e.netExternalFlowSol))} risk=${f(riskEquitySol8136(e))} marked=${f(markedEquitySol8136(e))} " +
            "tradeable=${f(tradeableSol8136(e))}]"

    private fun f(v: Double): String = if (v.isFinite()) "%.4f".format(v) else "-"
}
