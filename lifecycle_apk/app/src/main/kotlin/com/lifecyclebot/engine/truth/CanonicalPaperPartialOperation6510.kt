package com.lifecyclebot.engine.truth

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** V5.0.6510 — one claim, one canonical paper partial economic mutation. */
object CanonicalPaperPartialOperation6510 {
    data class Receipt(
        val applied: Boolean, val duplicate: Boolean, val reason: String,
        val positionId: String, val operationId: String, val partialSequence: Long,
        val preQty: BigInteger, val soldQty: BigInteger, val postQty: BigInteger,
        val preCost: Double, val soldCostBasis: Double, val postCost: Double,
        val grossProceeds: Double, val fees: Double, val realizedPnl: Double,
    )

    fun commit(positionId: String, mint: String, symbol: String, fraction: Double,
               grossProceeds: Double, fees: Double, exitReason: String): Receipt {
        val pre = CanonicalPositionAuthority6441.getPosition(positionId)
            ?: return empty(positionId, "", 0L, "UNKNOWN_POSITION")
        if (pre.mode != "paper" || fraction <= 0.0 || fraction > 1.0 || pre.remainingQtyRaw <= BigInteger.ZERO)
            return empty(positionId, "", 0L, "INVALID_PARTIAL")
        val sequence = kotlin.math.abs(exitReason.hashCode().toLong()).coerceAtLeast(1L)
        val operationId = "$positionId:$sequence"
        val soldRaw = pre.remainingQtyRaw.toBigDecimal().multiply(BigDecimal.valueOf(fraction))
            .setScale(0, RoundingMode.HALF_UP).toBigInteger().coerceIn(BigInteger.ONE, pre.remainingQtyRaw)
        val preCost = (pre.entryCostSol - pre.soldCostBasisSol).coerceAtLeast(0.0)
        val soldBasis = (preCost * soldRaw.toBigDecimal().divide(pre.remainingQtyRaw.toBigDecimal(), 18, RoundingMode.HALF_UP).toDouble()).coerceIn(0.0, preCost)
        val r = CanonicalPaperTerminalBridge6469.finalizeSell(
            positionId, mint, symbol, pre.openedAtMs, operationId, soldRaw, pre.remainingQtyRaw,
            preCost, grossProceeds.coerceAtLeast(0.0), soldBasis, fees.coerceAtLeast(0.0), pre.lane,
            exitReason, soldRaw >= pre.remainingQtyRaw, directPositionMutation6486 = true,
        )
        val post = CanonicalPositionAuthority6441.getPosition(positionId) ?: pre
        return Receipt(r.applied, !r.applied && r.reason.contains("DUPLICATE", true), r.reason,
            positionId, operationId, sequence, pre.remainingQtyRaw, soldRaw, post.remainingQtyRaw,
            preCost, soldBasis, (post.entryCostSol - post.soldCostBasisSol).coerceAtLeast(0.0),
            grossProceeds, fees, grossProceeds - soldBasis - fees)
    }

    private fun empty(pid: String, op: String, seq: Long, reason: String) = Receipt(false, false, reason, pid, op, seq,
        BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
}
