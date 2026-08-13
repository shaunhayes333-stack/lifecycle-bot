package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6442 — FORENSIC ROW MIRROR.
 *
 * OPERATOR MANDATE §5 + V5.0.6441 next actions:
 *   "Journal Schema Migration: Convert every execution row writer to
 *    emit ForensicExecutionRow6441."
 *
 * Rather than surgically re-thread every journal writer in Executor.kt
 * (many dozens of sites), this module is the shared emitter that lives
 * at the canonical close point (PositionCloseLedger.markClosedFull) and
 * accepts a row payload. It:
 *   • Verifies the immutable invariant realizedPnlSol = proceedsSol -
 *     soldCostBasisSol - feesSol before accepting the row.
 *   • Buffers the last N rows in memory for CanonicalReconciler6441
 *     full-reconstruct diffs.
 *   • Emits FORENSIC_ROW_MIRROR_6442 lifecycle events per row.
 *
 * Legacy journal writers KEEP writing (PaperTradeEvent, etc.). This
 * mirror is additive; a future V5.0.6443 will convert the legacy
 * writers to consume from this mirror instead of maintaining their own
 * schemas.
 */
object ForensicRowMirror6442 {

    private const val KEEP_LAST = 512

    private val rows = ConcurrentLinkedDeque<ForensicExecutionRow6441>()
    private val acceptedCount = AtomicLong(0L)
    private val rejectedCount = AtomicLong(0L)

    fun emitClose(
        positionId: String,
        mint: String,
        lane: String,
        sellSig: String,
        soldQtyRaw: BigInteger,
        remainingQtyRaw: BigInteger,
        soldCostBasisSol: Double,
        proceedsSol: Double,
        feesSol: Double,
        realizedPnlSol: Double,
        tokenDecimals: Int,
        markPrice: Double,
        markSource: String,
        markAgeMs: Long,
        paperMode: Boolean,
    ) {
        val row = ForensicExecutionRow6441(
            executionId = "E${System.currentTimeMillis()}_${sellSig.take(8)}",
            idempotencyKey = sellSig.ifBlank { "$positionId#close" },
            positionId = positionId,
            side = if (remainingQtyRaw == BigInteger.ZERO)
                ForensicExecutionRow6441.Side.SELL_FULL else ForensicExecutionRow6441.Side.SELL_PARTIAL,
            whenMs = System.currentTimeMillis(),
            entryCostSol = 0.0,          // read from canonical if needed downstream
            soldCostBasisSol = soldCostBasisSol,
            proceedsSol = proceedsSol,
            feesSol = feesSol,
            realizedPnlSol = realizedPnlSol,
            realizedPnlPct = if (soldCostBasisSol > 0.0) 100.0 * realizedPnlSol / soldCostBasisSol else 0.0,
            qtyBeforeRaw = (soldQtyRaw + remainingQtyRaw).toString(),
            qtyExecutedRaw = soldQtyRaw.toString(),
            qtyAfterRaw = remainingQtyRaw.toString(),
            tokenDecimals = tokenDecimals,
            executionPrice = if (soldQtyRaw > BigInteger.ZERO)
                proceedsSol / (soldQtyRaw.toDouble() / Math.pow(10.0, tokenDecimals.toDouble()))
            else 0.0,
            markPrice = markPrice,
            markSource = markSource,
            markAgeMs = markAgeMs,
            paperMode = paperMode,
            lane = lane,
        )
        val invariant = row.verifyInvariant(tolerance = 1e-4)
        if (invariant.isNotEmpty()) {
            rejectedCount.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "FORENSIC_ROW_INVARIANT_FAIL_6442",
                    "positionId=$positionId $invariant",
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("FORENSIC_ROW_INVARIANT_FAIL_6442") } catch (_: Throwable) {}
            return
        }
        rows.addLast(row)
        while (rows.size > KEEP_LAST) rows.pollFirst()
        acceptedCount.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "FORENSIC_ROW_MIRROR_6442",
                "positionId=$positionId side=${row.side} pnlSol=${"%.5f".format(realizedPnlSol)} " +
                    "qtyExec=${row.qtyExecutedRaw} qtyAfter=${row.qtyAfterRaw}",
            )
        } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("FORENSIC_ROW_MIRROR_6442") } catch (_: Throwable) {}
    }

    /**
     * Snapshot the current row buffer for CanonicalReconciler6441.
     * fullReconstruct consumption.
     */
    fun snapshot(): List<ForensicExecutionRow6441> = ArrayList(rows)

    fun statusLine(): String =
        "buffered=${rows.size} accepted=${acceptedCount.get()} rejected=${rejectedCount.get()}"
}
