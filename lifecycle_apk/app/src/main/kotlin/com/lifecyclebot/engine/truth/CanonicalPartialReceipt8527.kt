package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import java.math.BigInteger
import kotlin.math.abs

/**
 * Read-only projection of an applied canonical slice. Requested fractions and mark PnL are not fills.
 * The constructor is private: only a same-lot, quantity-reducing canonical transition can mint this receipt.
 */
class CanonicalPartialReceipt8527 private constructor(
    val positionId: String,
    val mint: String,
    val mode: String,
    val openedAtMs: Long,
    val entryCostSol: Double,
    val originalRaw: BigInteger,
    val quantityScale: Int,
    val operationId: String,
    val preRaw: BigInteger,
    val remainingRaw: BigInteger,
    val remainingCostSol: Double,
    val soldCostSol: Double,
    val realizedPnlSol: Double,
    private val cumulativeSoldCostSol: Double,
) {
    val terminal: Boolean get() = remainingRaw.signum() == 0
    val consumedRaw: BigInteger get() = preRaw - remainingRaw
    val soldFraction: Double get() = consumedRaw.toBigDecimal()
        .divide(preRaw.toBigDecimal(), java.math.MathContext.DECIMAL128).toDouble()
    val realizedPnlPct: Double get() = if (soldCostSol > 0.0) realizedPnlSol / soldCostSol * 100.0 else 0.0

    /** The canonical authority may have booked later slices before this callback is scheduled. */
    internal fun belongsToHistory8527(now: CanonicalPositionAuthority6441.Position): Boolean {
        if (now.positionId != positionId || now.mint != mint || now.mode != mode ||
            now.openedAtMs != openedAtMs || now.originalQtyRaw != originalRaw || now.quantityScale != quantityScale ||
            !same8527(now.entryCostSol, entryCostSol) || now.originalQtyRaw.signum() <= 0 ||
            now.remainingQtyRaw.signum() < 0 ||
            now.remainingQtyRaw > remainingRaw || !now.soldCostBasisSol.isFinite() ||
            now.soldCostBasisSol + 1e-10 < cumulativeSoldCostSol || !now.realizedPnlSol.isFinite()) return false
        val cost = now.entryCostSol - now.soldCostBasisSol
        if (!cost.isFinite() || cost < -1e-10 || cost > remainingCostSol + 1e-10) return false
        return if (now.remainingQtyRaw.signum() == 0)
            now.lifecycle == CanonicalPositionAuthority6441.Lifecycle.CLOSED && abs(cost) <= 1e-10
        else cost > 0.0 && now.lifecycle in setOf(CanonicalPositionAuthority6441.Lifecycle.OPEN,
            CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED)
    }

    /** Deliver only a real settled receipt, once, even when the caller stopped waiting for an async sell. */
    class Delivery8527(private val onConfirmed: (CanonicalPartialReceipt8527) -> Boolean) {
        private val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
        fun deliver8527(receipt: CanonicalPartialReceipt8527?): Boolean {
            if (receipt == null || !delivered.compareAndSet(false, true)) return false
            return try { onConfirmed(receipt) } catch (_: Throwable) { false }
        }
    }

    /** Per-call result carrier. Only the canonical mutation may freeze its exact applied transition. */
    class Capture8527(private val delivery8527: Delivery8527? = null) {
        @Volatile var receipt: CanonicalPartialReceipt8527? = null
            private set
        @Volatile internal var afterPosition: CanonicalPositionAuthority6441.Position? = null
            private set

        /** Called by settlement only after accounting; never by partialSell while its mutation lock is held. */
        fun publishSettled8527(): Boolean = delivery8527?.deliver8527(receipt) ?: false

        internal fun recordApplied8527(before: CanonicalPositionAuthority6441.Position,
                                       after: CanonicalPositionAuthority6441.Position, operationId: String) {
            if (afterPosition != null) return
            afterPosition = after
            receipt = fromApplied8527(true, before, after, operationId,
                before.remainingQtyRaw - after.remainingQtyRaw, after.remainingQtyRaw)
        }
    }

    companion object {
        private fun same8527(a: Double, b: Double): Boolean =
            a.isFinite() && b.isFinite() && abs(a - b) <= maxOf(1e-12, maxOf(abs(a), abs(b)) * 1e-10)

        fun capture8527(ts: TokenState): CanonicalPositionAuthority6441.Position? {
            val mode = if (ts.position.isPaperPosition) "paper" else "live"
            val explicit = ts.position.positionId.trim()
            val row = if (explicit.isNotBlank()) CanonicalPositionAuthority6441.getPosition(explicit)
                else CanonicalPositionAuthority6441.openPositions().filter {
                    it.mint == ts.mint && it.mode == mode
                }.singleOrNull()
            return row?.takeIf { it.mint == ts.mint && it.mode == mode &&
                it.remainingQtyRaw.signum() > 0 && it.lifecycle in setOf(
                    CanonicalPositionAuthority6441.Lifecycle.OPEN,
                    CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED) }
        }

        fun fromApplied8527(
            applied: Boolean,
            before: CanonicalPositionAuthority6441.Position?,
            after: CanonicalPositionAuthority6441.Position?,
            operationId: String,
            confirmedConsumedRaw: BigInteger? = null,
            confirmedRemainingRaw: BigInteger? = null,
        ): CanonicalPartialReceipt8527? {
            if (!applied || before == null || after == null || before.positionId != after.positionId ||
                before.mint != after.mint || before.mode != after.mode ||
                before.openedAtMs != after.openedAtMs || before.originalQtyRaw != after.originalQtyRaw ||
                before.quantityScale != after.quantityScale || before.originalQtyRaw.signum() <= 0 ||
                before.entryCostSol <= 0.0 ||
                before.lifecycle !in setOf(CanonicalPositionAuthority6441.Lifecycle.OPEN,
                    CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED) ||
                !same8527(before.entryCostSol, after.entryCostSol) ||
                before.remainingQtyRaw.signum() <= 0 || after.remainingQtyRaw.signum() < 0 ||
                after.remainingQtyRaw >= before.remainingQtyRaw) return null
            if (confirmedConsumedRaw != null && before.remainingQtyRaw - after.remainingQtyRaw != confirmedConsumedRaw) return null
            if (confirmedRemainingRaw != null && after.remainingQtyRaw != confirmedRemainingRaw) return null
            val terminal = after.remainingQtyRaw.signum() == 0
            if (terminal && after.lifecycle != CanonicalPositionAuthority6441.Lifecycle.CLOSED) return null
            if (!terminal && after.lifecycle !in setOf(CanonicalPositionAuthority6441.Lifecycle.OPEN,
                    CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED)) return null
            val remaining = after.entryCostSol - after.soldCostBasisSol
            val soldCost = after.soldCostBasisSol - before.soldCostBasisSol
            val realized = after.realizedPnlSol - before.realizedPnlSol
            if (!remaining.isFinite() || remaining < -1e-10 || !soldCost.isFinite() || soldCost <= 0.0 ||
                !realized.isFinite() || after.mode !in setOf("paper", "live")) return null
            if (terminal && abs(remaining) > 1e-10) return null
            if (!terminal && remaining <= 0.0) return null
            return CanonicalPartialReceipt8527(
                positionId = before.positionId, mint = before.mint, mode = before.mode,
                openedAtMs = before.openedAtMs, entryCostSol = before.entryCostSol, originalRaw = before.originalQtyRaw,
                quantityScale = before.quantityScale,
                operationId = operationId.ifBlank {
                    "canonical-slice8527:${before.positionId}:${before.remainingQtyRaw}:${after.remainingQtyRaw}"
                },
                preRaw = before.remainingQtyRaw, remainingRaw = after.remainingQtyRaw,
                remainingCostSol = remaining.coerceAtLeast(0.0), soldCostSol = soldCost,
                realizedPnlSol = realized, cumulativeSoldCostSol = after.soldCostBasisSol,
            )
        }
    }
}

/** A native book is a projection, never an execution or quantity authority. */
object NativePartialProjection8527 {
    /** Amounts to project now; the actual receipt remains an immutable historical slice. */
    data class Projection(
        val remainingRaw: BigInteger,
        val remainingCostSol: Double,
        val soldFraction: Double,
        val cumulativeSoldFraction: Double,
        val soldCostSol: Double,
        val realizedPnlSol: Double,
        val realizedPnlPct: Double,
    ) { val terminal: Boolean get() = remainingRaw.signum() == 0 }

    private class BookKey(val book: MutableMap<String, *>, val mint: String) {
        override fun equals(other: Any?): Boolean = other is BookKey && book === other.book && mint == other.mint
        override fun hashCode(): Int = 31 * System.identityHashCode(book) + mint.hashCode()
    }
    private class Lineage(
        val positionId: String, val mode: String, var current: Any,
        var projectedRaw: BigInteger, var projectedSoldCost: Double, var projectedPnl: Double,
    ) { val receipts = HashSet<String>() }
    private val lineages = java.util.concurrent.ConcurrentHashMap<BookKey, Lineage>()

    fun <P : Any> capture8527(
        book: MutableMap<String, P>,
        ts: TokenState,
        paperOf: (P) -> Boolean,
        update: (P, Projection) -> P,
        applied: (P, P?, Projection) -> Unit = { _, _, _ -> },
    ): (CanonicalPartialReceipt8527?) -> Boolean {
        val mint = ts.mint
        val isPaper = ts.position.isPaperPosition
        val canonical = CanonicalPartialReceipt8527.capture8527(ts)
        val key = BookKey(book, mint)
        val lineage = synchronized(book) {
            val expected = book[mint]
            if (canonical == null || expected == null || paperOf(expected) != isPaper) null
            else {
                val prior = lineages[key]
                if (prior != null && prior.positionId == canonical.positionId && prior.mode == canonical.mode &&
                    prior.current === expected) prior
                else Lineage(canonical.positionId, canonical.mode, expected, canonical.remainingQtyRaw,
                    canonical.soldCostBasisSol, canonical.realizedPnlSol).also { lineages[key] = it }
            }
        }
        // Closed/replaced native projections do not keep a process-lifetime mint map.
        if (lineages.size > 4_096) lineages.entries.forEach { (k, v) ->
            synchronized(k.book) { if (k.book[k.mint] !== v.current) lineages.remove(k, v) }
        }
        return fun(receipt: CanonicalPartialReceipt8527?): Boolean {
            if (lineage == null || receipt == null || receipt.positionId != lineage.positionId ||
                receipt.mint != mint || receipt.mode != lineage.mode) return false
            // Canonical -> native is the only nested-lock order. No network/provider work occurs here.
            return CanonicalPositionAuthority6441.projectConfirmedSlice8527(receipt) { current ->
                synchronized(book) {
                    val previous = book[mint]
                    if (previous == null && current.remainingQtyRaw.signum() == 0 &&
                        lineages[key] === lineage && receipt.operationId !in lineage.receipts) {
                        // The native terminal owner already booked/released this exact lot.
                        lineage.receipts.add(receipt.operationId)
                        lineages.remove(key, lineage)
                        true
                    } else if (previous == null || lineages[key] !== lineage || previous !== lineage.current ||
                        paperOf(previous) != isPaper || receipt.operationId in lineage.receipts ||
                        current.remainingQtyRaw.signum() == 0) false
                    else {
                        val consumed = lineage.projectedRaw - current.remainingQtyRaw
                        val fraction = if (lineage.projectedRaw.signum() > 0 && consumed.signum() >= 0)
                            consumed.toBigDecimal().divide(lineage.projectedRaw.toBigDecimal(),
                                java.math.MathContext.DECIMAL128).toDouble() else 0.0
                        val soldCost = (current.soldCostBasisSol - lineage.projectedSoldCost).coerceAtLeast(0.0)
                        val pnl = current.realizedPnlSol - lineage.projectedPnl
                        val projection = Projection(current.remainingQtyRaw,
                            (current.entryCostSol - current.soldCostBasisSol).coerceAtLeast(0.0),
                            fraction.coerceIn(0.0, 1.0),
                            (1.0 - current.remainingQtyRaw.toBigDecimal().divide(current.originalQtyRaw.toBigDecimal(),
                                java.math.MathContext.DECIMAL128).toDouble()).coerceIn(0.0, 1.0), soldCost, pnl,
                            if (soldCost > 0.0) pnl / soldCost * 100.0 else receipt.realizedPnlPct)
                        val next = update(previous, projection)
                        book[mint] = next
                        lineage.current = next
                        lineage.projectedRaw = current.remainingQtyRaw
                        lineage.projectedSoldCost = current.soldCostBasisSol
                        lineage.projectedPnl = current.realizedPnlSol
                        lineage.receipts.add(receipt.operationId)
                        applied(previous, next, projection)
                        true
                    }
                }
            }
        }
    }

    internal fun resetForTest8527() { lineages.clear() }
}

/** Accounting read for an already journaled slice. No market mark, requested share, or remaining-position basis. */
object TradeSliceEconomics8527 {
    data class Economics(val costBasisSol: Double?, val netPnlSol: Double, val returnPct: Double?)
    fun read(trade: com.lifecyclebot.data.Trade): Economics {
        // entryCostSol may be the original whole lot on legacy partial rows. trade.sol is the sold notional.
        val candidates = if (trade.side.equals("PARTIAL_SELL", true))
            sequenceOf(trade.soldCostBasisSol, trade.sol)
        else sequenceOf(trade.soldCostBasisSol, trade.entryCostSol, trade.sol)
        val cost = candidates.firstOrNull { it.isFinite() && it > 0.0 }
        val explicitNet = trade.netPnlSol != 0.0 ||
            (trade.positionId.isNotBlank() && trade.soldCostBasisSol.isFinite() && trade.soldCostBasisSol > 0.0 &&
                (trade.canonicalConsumedRaw.signum() > 0 || trade.operationId.isNotBlank() ||
                    trade.proofState == "LIVE_FINALIZED"))
        // Wallet-delta live proceeds already include network costs. Do not subtract feeSol again.
        val net = if (explicitNet) trade.netPnlSol else trade.pnlSol
        val pct = if (cost != null && net.isFinite()) net / cost * 100.0 else null
        return Economics(cost, net, pct)
    }
}
