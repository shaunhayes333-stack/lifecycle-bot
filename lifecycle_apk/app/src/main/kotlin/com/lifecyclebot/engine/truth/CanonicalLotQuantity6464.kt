package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6464 §P0-#3 — CANONICAL LOT QUANTITY (sell boundary is BLIND fix).
 *
 * OPERATOR MANDATE:
 *   "over-sold mints=6 (example: buyQty=35.140 sellQty=57.632). Yet
 *    runtime overSoldCandidates=0. The boundary guard is validating
 *    the wrong quantity source."
 *
 * DESIGN
 * ──────
 * Per-positionId ledger of confirmed buys, confirmed sells, and reserved
 * pending sells — the sell-side truth boundary. Every sell REQUEST must
 * pass `assertSellable(positionId, requestedQty)` BEFORE it enters the
 * executor mutation.
 *
 *   confirmedBoughtQty       — atomic, incremented on BUY fill
 *   confirmedSoldQty         — atomic, incremented on SELL fill
 *   reservedPendingSellQty   — atomic, held between decision and fill
 *
 *   sellableQty = confirmedBoughtQty - confirmedSoldQty - reservedPendingSellQty
 *
 * Invariant enforced on every mutation:
 *   confirmedSoldQty <= confirmedBoughtQty + ε   (ε = 1 raw unit)
 *
 * Mint aggregation is retained as forensic fallback only — the primary
 * key is positionId. When a sell request references a mint we resolve
 * to the newest OPEN positionId for that mint.
 */
object CanonicalLotQuantity6464 {

    data class Lot(
        val positionId: String,
        val mint: String,
        var confirmedBoughtQty: BigInteger,
        var confirmedSoldQty: BigInteger,
        var reservedPendingSellQty: BigInteger,
    ) {
        fun sellable(): BigInteger =
            (confirmedBoughtQty - confirmedSoldQty - reservedPendingSellQty).coerceAtLeast(BigInteger.ZERO)
    }

    enum class GuardResult { OK, CLAMPED_TO_SELLABLE, REJECTED_NO_LOT, REJECTED_ZERO_SELLABLE }

    data class Guard(val result: GuardResult, val allowedQty: BigInteger, val sellable: BigInteger, val reason: String)

    private val lots = ConcurrentHashMap<String, Lot>()

    private val overSellRejects = AtomicLong(0L)
    private val overSellClamps = AtomicLong(0L)
    private val invariantViolations = AtomicLong(0L)

    // ─── Confirmed fill hooks ──────────────────────────────────────────

    @Synchronized
    fun rebuildPaperFromEvents6486(source: List<EconomicEventSchema6464.Event>): Int {
        val paperIds = source.asSequence().filter { it.mode == "paper" }.map { it.positionId }.toSet()
        paperIds.forEach { lots.remove(it) }
        lots.entries.removeIf { it.key.startsWith("PAPER:CARRY6492:") }
        source.filter { it.mode == "paper" }.sortedBy { it.atMs }.forEach { e ->
            when (e) {
                is EconomicEventSchema6464.Buy -> onBuyFilled(e.positionId, e.mint, e.filledQty)
                is EconomicEventSchema6464.Sell -> onSellFilled(e.positionId, e.mint, e.soldQty)
            }
        }
        // V5.0.6492 — mirror durable replay carry into the exact active
        // positionId selected by CanonicalPositionAuthority. Without this,
        // healUnfundedPaperEntries removes carry-restored positions immediately.
        val carry6492 = try { EconomicEventSchema6464.replayCarry6489() } catch (_: Throwable) { null }
        carry6492?.perMintQty?.forEach { (mint, qtyRaw) ->
            if (mint.isBlank() || qtyRaw <= BigInteger.ZERO) return@forEach
            val pid = CanonicalPositionAuthority6441.openPositions().filter { it.mint == mint }
                .firstOrNull { it.mode == "paper" }?.positionId ?: "PAPER:CARRY6492:$mint"
            onBuyFilled(pid, mint, qtyRaw)
            try { PipelineHealthCollector.labelInc("CANONICAL_CARRY_LOT_RESTORED_6492") } catch (_: Throwable) {}
        }
        try { PipelineHealthCollector.labelInc("CANONICAL_PAPER_LOTS_REBUILT_6486") } catch (_: Throwable) {}
        return paperIds.size
    }

    fun onBuyFilled(positionId: String, mint: String, filledQty: BigInteger) {
        if (positionId.isBlank() || filledQty <= BigInteger.ZERO) return
        val lot = lots.compute(positionId) { _, cur ->
            (cur ?: Lot(positionId, mint, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO)).also {
                it.confirmedBoughtQty = it.confirmedBoughtQty + filledQty
            }
        } ?: return
        checkInvariant(lot, "onBuyFilled")
    }

    fun onSellFilled(positionId: String, mint: String, filledQty: BigInteger) {
        if (positionId.isBlank() || filledQty <= BigInteger.ZERO) return
        // V5.0.6470 §P0 — LOT QUANTITY INVARIANT AT THE SOURCE.
        //
        // Operator dump 6469 showed 28 CANONICAL_LOT_INVARIANT_VIOLATION_6464
        // firings driven by "onSellFilled bought=0 sold=7489167031711" — a
        // phantom lot got materialised by a SELL when no matching BUY had
        // ever recorded against that positionId (position-ID mismatch or
        // orphan generation).
        //
        // We now REJECT any sell against a non-existent or bought=0 lot.
        // The mutation is quarantined at the entry point instead of being
        // laundered into a lot with bought=0 that will trip the invariant
        // check after the fact.
        val existing = lots[positionId]
        if (existing == null || existing.confirmedBoughtQty <= BigInteger.ZERO) {
            invariantViolations.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "CANONICAL_LOT_SELL_QUARANTINED_6470",
                    "reason=NO_MATCHING_BUY positionId=${positionId.take(16)} mint=${mint.take(10)} " +
                        "filledQty=$filledQty existingBought=${existing?.confirmedBoughtQty ?: "null"}",
                )
                PipelineHealthCollector.labelInc("CANONICAL_LOT_SELL_QUARANTINED_6470")
                // Notify the learning quarantine gate so downstream learners
                // will drop any finalized event referencing this positionId.
                LearningQuarantineGate6470.quarantinePositionId(
                    positionId = positionId,
                    reason = "LOT_INVARIANT_NO_MATCHING_BUY",
                )
            } catch (_: Throwable) {}
            return
        }
        // Additional invariant: sold + filledQty must not exceed bought.
        if (existing.confirmedSoldQty + filledQty > existing.confirmedBoughtQty + BigInteger.ONE) {
            invariantViolations.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "CANONICAL_LOT_SELL_QUARANTINED_6470",
                    "reason=OVERSELL positionId=${positionId.take(16)} mint=${mint.take(10)} " +
                        "bought=${existing.confirmedBoughtQty} sold=${existing.confirmedSoldQty} filled=$filledQty",
                )
                PipelineHealthCollector.labelInc("CANONICAL_LOT_SELL_QUARANTINED_6470")
                LearningQuarantineGate6470.quarantinePositionId(
                    positionId = positionId,
                    reason = "LOT_INVARIANT_OVERSELL",
                )
            } catch (_: Throwable) {}
            return
        }
        val lot = lots.compute(positionId) { _, cur ->
            (cur ?: Lot(positionId, mint, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO)).also {
                it.confirmedSoldQty = it.confirmedSoldQty + filledQty
                // release reservation (never past zero)
                it.reservedPendingSellQty = (it.reservedPendingSellQty - filledQty).coerceAtLeast(BigInteger.ZERO)
            }
        } ?: return
        checkInvariant(lot, "onSellFilled")
    }

    fun hasFundedOpenLot6485(positionId: String): Boolean {
        val lot = lots[positionId] ?: return false
        return lot.confirmedBoughtQty > BigInteger.ZERO && lot.sellable() > BigInteger.ZERO
    }

    fun abortBuy6485(positionId: String) {
        if (positionId.isNotBlank()) lots.remove(positionId)
    }

    /** Reserve qty before an executor sell mutation (pre-fill). Returns actual reserved qty. */
    fun reserveForSell(positionId: String, mint: String, requestedQty: BigInteger): Guard {
        if (positionId.isBlank() || requestedQty <= BigInteger.ZERO) {
            return Guard(GuardResult.REJECTED_NO_LOT, BigInteger.ZERO, BigInteger.ZERO, "blank_position_or_zero_qty")
        }
        val lot = lots[positionId] ?: run {
            // No lot record — accept but log; mint aggregation is fallback.
            return Guard(GuardResult.REJECTED_NO_LOT, BigInteger.ZERO, BigInteger.ZERO, "no_lot_for_position")
        }
        val sellable = lot.sellable()
        if (sellable <= BigInteger.ZERO) {
            overSellRejects.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "CANONICAL_LOT_SELL_REJECTED_6464",
                    "positionId=${positionId.take(16)} mint=${mint.take(10)} requestedQty=$requestedQty sellable=$sellable " +
                        "bought=${lot.confirmedBoughtQty} sold=${lot.confirmedSoldQty} reserved=${lot.reservedPendingSellQty}",
                )
                PipelineHealthCollector.labelInc("CANONICAL_LOT_SELL_REJECTED_6464")
            } catch (_: Throwable) {}
            return Guard(GuardResult.REJECTED_ZERO_SELLABLE, BigInteger.ZERO, sellable, "zero_sellable")
        }
        val allowed = if (requestedQty > sellable) sellable else requestedQty
        if (allowed < requestedQty) {
            overSellClamps.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "CANONICAL_LOT_SELL_CLAMPED_6464",
                    "positionId=${positionId.take(16)} mint=${mint.take(10)} requestedQty=$requestedQty allowed=$allowed sellable=$sellable",
                )
                PipelineHealthCollector.labelInc("CANONICAL_LOT_SELL_CLAMPED_6464")
            } catch (_: Throwable) {}
        }
        lots.compute(positionId) { _, cur ->
            (cur ?: lot).also { it.reservedPendingSellQty = it.reservedPendingSellQty + allowed }
        }
        val resultKind = if (allowed < requestedQty) GuardResult.CLAMPED_TO_SELLABLE else GuardResult.OK
        return Guard(resultKind, allowed, sellable, "ok")
    }

    /** Release reservation without a fill (e.g., sell rejected pre-executor). */
    fun releaseReservation(positionId: String, qty: BigInteger) {
        if (positionId.isBlank() || qty <= BigInteger.ZERO) return
        lots.compute(positionId) { _, cur ->
            cur?.also {
                it.reservedPendingSellQty = (it.reservedPendingSellQty - qty).coerceAtLeast(BigInteger.ZERO)
            }
        }
    }

    fun sellable(positionId: String): BigInteger =
        lots[positionId]?.sellable() ?: BigInteger.ZERO

    fun purge(positionId: String) { lots.remove(positionId) }

    private fun checkInvariant(lot: Lot, site: String) {
        if (lot.confirmedSoldQty > lot.confirmedBoughtQty + BigInteger.ONE) {
            invariantViolations.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "CANONICAL_LOT_INVARIANT_VIOLATION_6464",
                    "site=$site positionId=${lot.positionId.take(16)} mint=${lot.mint.take(10)} " +
                        "bought=${lot.confirmedBoughtQty} sold=${lot.confirmedSoldQty}",
                )
                PipelineHealthCollector.labelInc("CANONICAL_LOT_INVARIANT_VIOLATION_6464")
            } catch (_: Throwable) {}
        }
    }

    fun statusLine(): String =
        "lots=${lots.size} overSellRejects=${overSellRejects.get()} overSellClamps=${overSellClamps.get()} " +
            "invariantViolations=${invariantViolations.get()}"

    internal fun resetForTest() {
        lots.clear()
        overSellRejects.set(0L); overSellClamps.set(0L); invariantViolations.set(0L)
    }
}
