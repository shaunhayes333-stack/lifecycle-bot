package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.LiveTradeLogStore
import java.math.BigInteger

/**
 * V5.9.495z39 — Forensic labels for sell finalization.
 *
 * Operator spec item 9: replace optimistic "Profit lock landed |
 * +275.65%" with chain-based fields:
 *
 *   SELL_LANDED
 *   requestedSellUi=...
 *   actualConsumedUi=...
 *   remainingUi=...
 *   entrySolSpent=...
 *   proportionalCostBasis=...
 *   solReceived=...
 *   realizedPnlSol=...
 *   realizedPnlPct=...
 *   sellAmountViolation=true/false
 *   slippageUsedBps=...
 *   exitReason=...
 *   positionStatus=...
 */
object SellForensicsWriter {

    fun writeSellLanded(
        intent: SellIntent,
        finalState: TxMetaSellFinalizer.FinalState,
        actualConsumedRaw: BigInteger,
        remainingRaw: BigInteger,
        solReceived: Double,
        pnl: RealizedPnLCalculator.Result,
        slippageUsedBps: Int,
        sellAmountViolation: Boolean,
        decimals: Int,
        traderTag: String = "MEME",
    ) {
        val key = "SELL_LANDED_${intent.symbol}_${System.currentTimeMillis()}"
        val actualUi = actualConsumedRaw.toBigDecimal().movePointLeft(decimals).toDouble()
        val remainUi = remainingRaw.toBigDecimal().movePointLeft(decimals).toDouble()
        val payload = buildString {
            append("SELL_LANDED ${intent.symbol}\n")
            append("  requestedSellUi=${"%.4f".format(intent.requestedSellUi)}\n")
            append("  actualConsumedUi=${"%.4f".format(actualUi)}\n")
            append("  remainingUi=${"%.4f".format(remainUi)}\n")
            append("  entrySolSpent=${"%.6f".format(intent.entrySolSpent)}\n")
            append("  proportionalCostBasis=${"%.6f".format(pnl.proportionalCostBasisSol)}\n")
            append("  solReceived=${"%.6f".format(solReceived)}\n")
            append("  realizedPnlSol=${"%.6f".format(pnl.realizedPnlSol)}\n")
            append("  realizedPnlPct=${if (pnl.degenerate) "n/a" else "%.2f%%".format(pnl.realizedPnlPct)}\n")
            append("  sellAmountViolation=$sellAmountViolation\n")
            append("  slippageUsedBps=$slippageUsedBps\n")
            append("  exitReason=${intent.reason}\n")
            append("  positionStatus=$finalState")
        }
        ErrorLogger.info("SellForensics", payload)
        try {
            LiveTradeLogStore.log(
                tradeKey = key,
                mint = intent.mint,
                symbol = intent.symbol,
                side = "SELL",
                phase = if (sellAmountViolation)
                    LiveTradeLogStore.Phase.WARNING
                else if (pnl.isProfit)
                    LiveTradeLogStore.Phase.SELL_VERIFY_SOL_RETURNED
                else
                    LiveTradeLogStore.Phase.INFO,
                message = payload,
                solAmount = solReceived,
                traderTag = traderTag,
            )
        } catch (_: Throwable) { /* best-effort */ }
    }
}
