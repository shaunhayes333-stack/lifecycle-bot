package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6460 §P0 — PAPER POSITION DUST INVARIANT (source-level normalizer).
 *
 * Operator: PAPER_FORCED_ROW_CLEARED_DUST qty=0.0 cost=0.05. Fix at
 * source — after every fill/partial/full close mutation, callers must
 * normalize via `enforce(remainingQty, remainingCost)`:
 *
 *   if remainingQty <= qtyEpsilon:
 *       remainingQty=0, remainingCost=0, lifecycle=CLOSED
 *
 * Emits PAPER_POSITION_DUST_NORMALIZED_6460 with the delta so operator
 * can see the source mutation that would have left the ghost row.
 */
object PaperPositionDustInvariant6460 {
    private const val QTY_EPSILON = 1e-9
    private val normalized = AtomicLong(0L)

    data class Result(val remainingQty: Double, val remainingCostSol: Double, val closed: Boolean)

    fun enforce(remainingQty: Double, remainingCostSol: Double, mint: String = ""): Result {
        val q = if (remainingQty.isFinite()) remainingQty else 0.0
        val c = if (remainingCostSol.isFinite()) remainingCostSol.coerceAtLeast(0.0) else 0.0
        return if (q <= QTY_EPSILON) {
            if (c > 0.0) {
                normalized.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "PAPER_POSITION_DUST_NORMALIZED_6460",
                        "mint=${mint.take(10)} qty=${"%.9f".format(q)} costCleared=${"%.6f".format(c)}",
                    )
                    PipelineHealthCollector.labelInc("PAPER_POSITION_DUST_NORMALIZED_6460")
                } catch (_: Throwable) {}
            }
            Result(0.0, 0.0, closed = true)
        } else Result(q, c, closed = false)
    }

    fun statusLine(): String = "normalized=${normalized.get()}"
}
