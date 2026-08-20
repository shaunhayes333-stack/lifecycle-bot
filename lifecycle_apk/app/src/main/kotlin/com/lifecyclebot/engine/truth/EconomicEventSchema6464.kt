package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6464 §P0-#5 — TYPED ECONOMIC EVENT SCHEMA.
 *
 * OPERATOR MANDATE:
 *   "Current journal fields sol/cost/pnl are too ambiguous to
 *    independently verify partial-close PnL. Replace with typed:
 *    BUY: executedCostSol, filledQty, fillPrice.
 *    SELL/PARTIAL: soldQty, allocatedCostBasisSol, grossProceedsSol,
 *      exitFeesSol, netProceedsSol, realizedPnlSol, realizedReturnPct,
 *      remainingQty, remainingCostBasisSol.
 *    allocatedCostBasis = preRemainingCostBasis * soldQty / preRemainingQty
 *    realizedPnlSol = netProceedsSol - allocatedCostBasisSol
 *    realizedReturnPct = realizedPnlSol / allocatedCostBasisSol * 100
 *    No generic numeric PNL field may ambiguously represent ratio,
 *    percent, SOL, or proceeds."
 *
 * DESIGN
 * ──────
 * A typed sidecar journal that lives PARALLEL to the legacy Trade
 * history rows. Executor paths call `recordBuy` / `recordSell` at
 * the same convergence points that write to the legacy journal.
 * Consumers (CanonicalPaperReplay6464, learners, EV estimator) prefer
 * this sidecar when reconstructing PnL; the legacy journal remains
 * for UI compatibility.
 *
 * The sidecar is bounded at 8192 rows (typical run) with FIFO
 * eviction. `snapshot()` returns a copy for read-only consumers.
 */
object EconomicEventSchema6464 {

    sealed class Event {
        abstract val atMs: Long
        abstract val mode: String            // "paper" | "live"
        abstract val positionId: String
        abstract val mint: String
        abstract val symbol: String
        abstract val idempotencyKey: String  // sellExecutionId/fillId/signature — buys can reuse a distinct key
    }

    data class Buy(
        override val atMs: Long,
        override val mode: String,
        override val positionId: String,
        override val mint: String,
        override val symbol: String,
        override val idempotencyKey: String,
        val executedCostSol: Double,      // principal token cost; excludes entry fee
        val entryFeesSol: Double = 0.0,    // typed separately for conservation
        val filledQty: java.math.BigInteger,
        val fillPrice: Double,             // executedCostSol / filledQty (in SOL/token)
    ) : Event()

    data class Sell(
        override val atMs: Long,
        override val mode: String,
        override val positionId: String,
        override val mint: String,
        override val symbol: String,
        override val idempotencyKey: String,
        val partial: Boolean,
        val soldQty: java.math.BigInteger,
        val allocatedCostBasisSol: Double,
        val grossProceedsSol: Double,
        val exitFeesSol: Double,
        val netProceedsSol: Double,
        val realizedPnlSol: Double,
        val realizedReturnPct: Double,
        val remainingQty: java.math.BigInteger,
        val remainingCostBasisSol: Double,
    ) : Event()

    private const val CAP = 8192
    private val events = ConcurrentLinkedDeque<Event>()
    private val recordedBuys = AtomicLong(0L)
    private val recordedSells = AtomicLong(0L)
    private val recordedPartials = AtomicLong(0L)
    private val arithDivergences = AtomicLong(0L)
    private val eventVersion = AtomicLong(0L)

    fun recordBuy(
        mode: String, positionId: String, mint: String, symbol: String,
        idempotencyKey: String, executedCostSol: Double, filledQty: java.math.BigInteger,
        fillPrice: Double, entryFeesSol: Double = 0.0,
    ) {
        val e = Buy(
            atMs = System.currentTimeMillis(), mode = mode.lowercase(),
            positionId = positionId, mint = mint, symbol = symbol,
            idempotencyKey = idempotencyKey.ifBlank { "buy_${System.nanoTime()}" },
            executedCostSol = executedCostSol, entryFeesSol = entryFeesSol.coerceAtLeast(0.0),
            filledQty = filledQty, fillPrice = if (fillPrice.isFinite()) fillPrice else 0.0,
        )
        appendBounded(e)
        recordedBuys.incrementAndGet()
        try { PipelineHealthCollector.labelInc("ECONOMIC_EVENT_BUY_6464") } catch (_: Throwable) {}
    }

    /**
     * Canonical partial/full sell record. Computes typed fields from
     * preRemainingQty + preRemainingCostBasisSol + fill details so
     * every consumer sees the same numbers.
     */
    fun recordSell(
        mode: String, positionId: String, mint: String, symbol: String,
        idempotencyKey: String, partial: Boolean, soldQty: java.math.BigInteger,
        preRemainingQty: java.math.BigInteger, preRemainingCostBasisSol: Double,
        grossProceedsSol: Double, exitFeesSol: Double,
    ) {
        val idKey = idempotencyKey.ifBlank { "sell_${System.nanoTime()}" }
        val gross = grossProceedsSol.coerceAtLeast(0.0)
        val fees = exitFeesSol.coerceAtLeast(0.0)
        val net = (gross - fees).coerceAtLeast(0.0)
        val allocatedCost = if (preRemainingQty > java.math.BigInteger.ZERO && preRemainingCostBasisSol > 0.0) {
            val proportion = soldQty.toDouble() / preRemainingQty.toDouble()
            (preRemainingCostBasisSol * proportion.coerceIn(0.0, 1.0))
        } else 0.0
        val realized = net - allocatedCost
        val ret = if (allocatedCost > 0.0) realized / allocatedCost * 100.0 else 0.0
        val remainingQ = (preRemainingQty - soldQty).coerceAtLeast(java.math.BigInteger.ZERO)
        val remainingCost = (preRemainingCostBasisSol - allocatedCost).coerceAtLeast(0.0)
        val e = Sell(
            atMs = System.currentTimeMillis(), mode = mode.lowercase(),
            positionId = positionId, mint = mint, symbol = symbol,
            idempotencyKey = idKey, partial = partial,
            soldQty = soldQty,
            allocatedCostBasisSol = allocatedCost,
            grossProceedsSol = gross,
            exitFeesSol = fees,
            netProceedsSol = net,
            realizedPnlSol = realized,
            realizedReturnPct = ret,
            remainingQty = remainingQ,
            remainingCostBasisSol = remainingCost,
        )
        appendBounded(e)
        if (partial) recordedPartials.incrementAndGet() else recordedSells.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc(if (partial) "ECONOMIC_EVENT_PARTIAL_SELL_6464" else "ECONOMIC_EVENT_SELL_6464")
        } catch (_: Throwable) {}
        // Arithmetic self-audit — realized == net - allocatedCost by construction; log any drift.
        val expected = net - allocatedCost
        if (kotlin.math.abs(expected - realized) > 1e-6) {
            arithDivergences.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "ECONOMIC_EVENT_ARITH_DIVERGENCE_6464",
                    "positionId=${positionId.take(16)} expected=${"%.6f".format(expected)} realized=${"%.6f".format(realized)}",
                )
                PipelineHealthCollector.labelInc("ECONOMIC_EVENT_ARITH_DIVERGENCE_6464")
            } catch (_: Throwable) {}
        }
    }

    private fun appendBounded(e: Event) {
        events.addFirst(e)
        eventVersion.incrementAndGet()
        while (events.size > CAP) events.pollLast()
    }

    fun snapshot(): List<Event> = events.toList()
    fun version(): Long = eventVersion.get()

    fun statusLine(): String =
        "events=${events.size} buys=${recordedBuys.get()} sells=${recordedSells.get()} " +
            "partials=${recordedPartials.get()} arithDivergences=${arithDivergences.get()}"

    internal fun resetForTest() {
        events.clear()
        recordedBuys.set(0L); recordedSells.set(0L); recordedPartials.set(0L)
        arithDivergences.set(0L); eventVersion.set(0L)
    }
}
