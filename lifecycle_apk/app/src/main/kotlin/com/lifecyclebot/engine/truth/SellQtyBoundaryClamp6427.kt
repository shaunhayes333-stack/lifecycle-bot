package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.math.BigInteger

/**
 * V5.0.6427 §I — SELL-QTY BOUNDARY CLAMP.
 *
 * OPERATOR (V5.0.6424 spec):
 * "cumulativeSoldQty > originalQty + dustTolerance"  MUST NEVER HAPPEN.
 *  V5.0.6422 dump reported 36 mints over-sold, e.g. EX38km buy=13015
 *  sell=13081.
 *
 * DESIGN
 * ──────
 * Per-position live tally of buy vs sell quantity. clamp(...) returns
 * the largest qty that still respects the invariant plus a reason. If
 * the caller is asking to sell more than we own, we clamp and emit a
 * forensic event; we NEVER return an over-sell value.
 *
 * Uses a small in-memory ledger keyed by positionId so that the clamp
 * decision does not need to hit the SQLite journal on the hot path.
 * The ledger self-repairs on registerBuy(...) which is called from
 * paperBuy/liveBuy.
 */
object SellQtyBoundaryClamp6427 {

    private const val DUST_TOLERANCE = 1e-6

    private data class Slot(
        val bought: AtomicLong = AtomicLong(0L),
        val sold: AtomicLong = AtomicLong(0L),
    )

    // Store qty as fixed-point picoUnits (1e-12 precision) to keep the
    // atomic-long invariant without floating drift on partial sells.
    private const val PICO_UNIT: Long = 1_000_000L  // 6 decimals worth of precision

    private fun toPico(qty: Double): Long =
        if (!qty.isFinite() || qty <= 0.0) 0L
        else (qty * PICO_UNIT).toLong().coerceAtLeast(0L)

    private fun fromPico(p: Long): Double = p.toDouble() / PICO_UNIT

    private val ledger = ConcurrentHashMap<String, Slot>()

    private data class RawSlot(var original: BigInteger, var remaining: BigInteger)
    data class RawAdmission(val allowed: Boolean, val requested: BigInteger, val remaining: BigInteger, val reason: String)
    private val rawLedger = ConcurrentHashMap<String, RawSlot>()
    private val rawAdmits = AtomicLong(0L)
    private val rawRejects = AtomicLong(0L)

    /** V5.0.6498 — rebuild boundary truth from canonical active inventory. */
    fun syncAuthoritativeRaw(positionId: String, originalQtyRaw: BigInteger, remainingQtyRaw: BigInteger) {
        if (positionId.isBlank() || originalQtyRaw <= BigInteger.ZERO || remainingQtyRaw < BigInteger.ZERO || remainingQtyRaw > originalQtyRaw) return
        rawLedger.compute(positionId) { _, old ->
            if (old == null) RawSlot(originalQtyRaw, remainingQtyRaw)
            else synchronized(old) { old.original = originalQtyRaw; old.remaining = remainingQtyRaw; old }
        }
    }

    /** Admission is read-only; quantity is committed only after canonical position mutation succeeds. */
    fun admitRaw(positionId: String, requestedSellQtyRaw: BigInteger, mint: String, symbol: String): RawAdmission {
        if (positionId.isBlank() || requestedSellQtyRaw <= BigInteger.ZERO) {
            rawRejects.incrementAndGet()
            return RawAdmission(false, requestedSellQtyRaw, BigInteger.ZERO, "INVALID_REQUEST")
        }
        val slot = rawLedger[positionId] ?: run {
            rawRejects.incrementAndGet()
            try { PipelineHealthCollector.labelInc("SELL_QTY_BOUNDARY_UNKNOWN_POSITION_6498") } catch (_: Throwable) {}
            return RawAdmission(false, requestedSellQtyRaw, BigInteger.ZERO, "UNKNOWN_POSITION")
        }
        synchronized(slot) {
            if (requestedSellQtyRaw > slot.remaining) {
                rawRejects.incrementAndGet()
                try {
                    ForensicLogger.lifecycle("SELL_QTY_BOUNDARY_REJECTED_6498", "positionId=$positionId requested=$requestedSellQtyRaw remaining=${slot.remaining} mint=${mint.take(10)} sym=$symbol")
                    PipelineHealthCollector.labelInc("SELL_QTY_BOUNDARY_REJECTED_6498")
                } catch (_: Throwable) {}
                return RawAdmission(false, requestedSellQtyRaw, slot.remaining, "REQUEST_EXCEEDS_CANONICAL_REMAINING")
            }
            rawAdmits.incrementAndGet()
            try { PipelineHealthCollector.labelInc("SELL_QTY_BOUNDARY_ADMITTED_6498") } catch (_: Throwable) {}
            return RawAdmission(true, requestedSellQtyRaw, slot.remaining, "ADMITTED")
        }
    }

    fun commitRaw(positionId: String, soldQtyRaw: BigInteger, terminal: Boolean): Boolean {
        val slot = rawLedger[positionId] ?: return false
        synchronized(slot) {
            if (soldQtyRaw <= BigInteger.ZERO || soldQtyRaw > slot.remaining) return false
            slot.remaining -= soldQtyRaw
            if (terminal || slot.remaining == BigInteger.ZERO) rawLedger.remove(positionId, slot)
            return true
        }
    }

    fun registerBuy(positionId: String, qty: Double) {
        if (positionId.isBlank() || !qty.isFinite() || qty <= 0.0) return
        val slot = ledger.computeIfAbsent(positionId) { Slot() }
        slot.bought.addAndGet(toPico(qty))
    }

    /**
     * Returns the clamped sell qty. If the caller asked to sell more
     * than remaining, clamps to remaining and emits a forensic event.
     * If the position isn't registered, fail-open (return requested)
     * to avoid breaking legacy code paths.
     */
    fun clamp(positionId: String, requestedSellQty: Double, mint: String, symbol: String): Double {
        if (positionId.isBlank() || !requestedSellQty.isFinite() || requestedSellQty <= 0.0) {
            return requestedSellQty.coerceAtLeast(0.0)
        }
        val slot = ledger[positionId] ?: return requestedSellQty  // fail-open
        val boughtPico = slot.bought.get()
        val soldPico = slot.sold.get()
        val remainingPico = (boughtPico - soldPico).coerceAtLeast(0L)
        val remaining = fromPico(remainingPico)
        if (remaining <= DUST_TOLERANCE) {
            try {
                ForensicLogger.lifecycle(
                    "SELL_QTY_CLAMP_ZERO_6427",
                    "positionId=$positionId requested=${"%.6f".format(requestedSellQty)} bought=${"%.6f".format(fromPico(boughtPico))} sold=${"%.6f".format(fromPico(soldPico))} mint=${mint.take(10)} sym=$symbol",
                )
                PipelineHealthCollector.labelInc("SELL_QTY_CLAMP_ZERO_6427")
            } catch (_: Throwable) {}
            return 0.0
        }
        if (requestedSellQty <= remaining + DUST_TOLERANCE) {
            // Within tolerance — commit the sell and return as-is.
            slot.sold.addAndGet(toPico(requestedSellQty.coerceAtMost(remaining)))
            return requestedSellQty
        }
        // Over-sell attempt — clamp.
        slot.sold.addAndGet(remainingPico)
        try {
            ForensicLogger.lifecycle(
                "SELL_QTY_CLAMP_OVERSELL_6427",
                "positionId=$positionId requested=${"%.6f".format(requestedSellQty)} clamped=${"%.6f".format(remaining)} bought=${"%.6f".format(fromPico(boughtPico))} sold_prior=${"%.6f".format(fromPico(soldPico))} mint=${mint.take(10)} sym=$symbol",
            )
            PipelineHealthCollector.labelInc("SELL_QTY_CLAMP_OVERSELL_6427")
        } catch (_: Throwable) {}
        return remaining
    }

    fun remainingQty(positionId: String): Double {
        val slot = ledger[positionId] ?: return 0.0
        val remainingPico = (slot.bought.get() - slot.sold.get()).coerceAtLeast(0L)
        return fromPico(remainingPico)
    }

    fun statusLine(): String {
        val overSoldCount = ledger.values.count { it.sold.get() > it.bought.get() }
        return "positions=${rawLedger.size} admits=${rawAdmits.get()} rejects=${rawRejects.get()} legacyPositions=${ledger.size} overSoldCandidates=$overSoldCount"
    }

    internal fun resetForTest() { ledger.clear(); rawLedger.clear(); rawAdmits.set(0L); rawRejects.set(0L) }
}
