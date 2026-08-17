package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6459 §P0 — SELL QUANTITY BOUNDARY (positionId-scope idempotent).
 *
 * Operator: over-sold mints=7, doubleConfirm=46, existing guard checks
 * wrong scope. Fix at source: keyed by positionId + sellExecutionId.
 *
 *   sellableQty = confirmedBoughtQty - confirmedSoldQty - reservedPendingSellQty
 *   clamp: requestedSellQty <= sellableQty
 *   idempotent: duplicate sellExecutionId => no second qty/PnL mutation.
 *
 * Callers use `admitSell(positionId, sellExecutionId, requestedQty)` which
 * returns clamped qty or 0.0 (rejected).
 */
object SellQuantityBoundary6459 {
    private data class Slot(
        val positionId: String,
        var confirmedBought: Double,
        var confirmedSold: Double,
        var reservedPending: Double,
        val consumedExecutionIds: MutableSet<String>,
    )
    private val slots = ConcurrentHashMap<String, Slot>() // key: positionId
    private val admits = AtomicLong(0L)
    private val rejects = AtomicLong(0L)
    private val duplicates = AtomicLong(0L)
    private val oversellPrevented = AtomicLong(0L)

    fun recordBuyFill(positionId: String, qtyToken: Double) {
        if (positionId.isBlank() || qtyToken <= 0.0) return
        slots.compute(positionId) { _, cur ->
            val s = cur ?: Slot(positionId, 0.0, 0.0, 0.0, mutableSetOf())
            s.confirmedBought += qtyToken
            s
        }
    }

    /** Returns the clamped qty admitted for this sellExecutionId (0.0 = rejected). */
    fun admitSell(positionId: String, sellExecutionId: String, requestedQtyToken: Double): Double {
        if (positionId.isBlank() || sellExecutionId.isBlank() || requestedQtyToken <= 0.0) {
            rejects.incrementAndGet(); return 0.0
        }
        val s = slots[positionId] ?: run { rejects.incrementAndGet(); return 0.0 }
        synchronized(s) {
            if (s.consumedExecutionIds.contains(sellExecutionId)) {
                duplicates.incrementAndGet()
                try { PipelineHealthCollector.labelInc("SELL_QTY_BOUNDARY_DUPLICATE_6459") } catch (_: Throwable) {}
                return 0.0
            }
            val sellable = (s.confirmedBought - s.confirmedSold - s.reservedPending).coerceAtLeast(0.0)
            val clamped = kotlin.math.min(requestedQtyToken, sellable)
            if (clamped < requestedQtyToken) {
                oversellPrevented.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "SELL_QTY_BOUNDARY_OVERSELL_PREVENTED_6459",
                        "pid=${positionId.take(12)} exec=${sellExecutionId.take(12)} " +
                            "requested=${"%.6f".format(requestedQtyToken)} sellable=${"%.6f".format(sellable)}",
                    )
                    PipelineHealthCollector.labelInc("SELL_QTY_BOUNDARY_OVERSELL_PREVENTED_6459")
                } catch (_: Throwable) {}
            }
            if (clamped <= 0.0) { rejects.incrementAndGet(); return 0.0 }
            s.consumedExecutionIds += sellExecutionId
            s.confirmedSold += clamped
            admits.incrementAndGet()
            return clamped
        }
    }

    fun statusLine(): String = "positions=${slots.size} admits=${admits.get()} " +
        "rejects=${rejects.get()} duplicates=${duplicates.get()} oversellPrevented=${oversellPrevented.get()}"

    internal fun resetForTest() { slots.clear(); admits.set(0); rejects.set(0); duplicates.set(0); oversellPrevented.set(0) }
}
