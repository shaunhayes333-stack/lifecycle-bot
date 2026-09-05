package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.TradeHistoryStore
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6677 — idempotent typed-event -> durable-journal convergence repair.
 *
 * The economic sidecar is durable and contains the immutable receipt for every
 * canonical paper BUY / SELL / PARTIAL. Historical caller-side patch stacks
 * allowed some canonical mutations to reach EconomicEventSchema6464 without a
 * matching TradeHistoryStore row (most visibly partials=9 vs journal partials=0).
 *
 * This repair does NOT recalculate economics from current prices or token state.
 * It projects only the exact typed receipt, and only when no exact or equivalent
 * journal row already exists. Existing legacy rows are never deleted or doubled.
 */
object CanonicalJournalProjectionRepair6677 {

    data class Result(
        val typedEvents: Int,
        val projected: Int,
        val exactAlreadyPresent: Int,
        val legacyEquivalentPresent: Int,
        val skippedInsufficientProof: Int,
    )

    private val running = AtomicBoolean(false)
    private val lastScheduledAtMs = AtomicLong(0L)
    private const val MIN_SCHEDULE_GAP_MS = 2_000L

    /**
     * UI surfaces may call this safely. All journal I/O and canonical settlement
     * runs off the main thread; the next normal account refresh observes the
     * repaired durable state.
     */
    fun scheduleRepair6677() {
        val now = System.currentTimeMillis()
        val prior = lastScheduledAtMs.get()
        if (now - prior < MIN_SCHEDULE_GAP_MS) return
        if (!lastScheduledAtMs.compareAndSet(prior, now)) return
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                // Repair the known CryptoAlt sentinel-entry corruption first so
                // its neutral terminal receipt is part of the typed event set
                // projected below.
                try { CanonicalSentinelEntryRepair6677.repairOpenPaperCryptoAltSentinels() } catch (_: Throwable) {}
                repairMissingPaperProjections6677()
            } catch (t: Throwable) {
                try {
                    PipelineHealthCollector.labelInc("CANONICAL_JOURNAL_REPAIR_FAILED_6677")
                    ForensicLogger.lifecycle(
                        "CANONICAL_JOURNAL_REPAIR_FAILED_6677",
                        "error=${t.javaClass.simpleName}:${t.message?.take(160)}",
                    )
                } catch (_: Throwable) {}
            } finally {
                running.set(false)
            }
        }, "aate-journal-repair-6677").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun repairMissingPaperProjections6677(): Result {
        val typed = try {
            EconomicEventSchema6464.snapshot()
                .filter { it.mode.equals("paper", true) }
                .sortedBy { it.atMs }
        } catch (_: Throwable) { emptyList() }
        if (typed.isEmpty()) return Result(0, 0, 0, 0, 0)

        val journal = try {
            TradeHistoryStore.getAllValidTradesSnapshot(limit = 20_000).toMutableList()
        } catch (_: Throwable) { mutableListOf() }
        val positions = try {
            (CanonicalPositionAuthority6441.openPositions() + CanonicalPositionAuthority6441.closedPositions())
                .associateBy { it.positionId }
        } catch (_: Throwable) { emptyMap() }
        val buysByPosition = typed.filterIsInstance<EconomicEventSchema6464.Buy>()
            .groupBy { it.positionId }

        var projected = 0
        var exact = 0
        var legacyEquivalent = 0
        var skipped = 0

        fun approx(a: Double, b: Double, absTol: Double = 1e-8): Boolean {
            if (!a.isFinite() || !b.isFinite()) return false
            val tol = maxOf(absTol, maxOf(kotlin.math.abs(a), kotlin.math.abs(b)) * 1e-7)
            return kotlin.math.abs(a - b) <= tol
        }

        fun scaleFor(positionId: String, fallback: Int): Int {
            val fromPos = positions[positionId]?.quantityScale
            if (fromPos != null && fromPos in 0..18) return fromPos
            return fallback.coerceIn(0, 18)
        }

        fun tokenQty(raw: BigInteger, scale: Int): Double = try {
            raw.toBigDecimal().movePointLeft(scale.coerceIn(0, 18)).toDouble()
        } catch (_: Throwable) { 0.0 }

        fun sellGross(row: Trade): Double =
            if (row.economicEventId.isNotBlank()) row.grossProceedsSol
            else row.grossProceedsSol.takeIf { it.isFinite() && it > 0.0 } ?: row.sol

        fun equivalentLegacyBuy(e: EconomicEventSchema6464.Buy): Boolean = journal.any { row ->
            row.economicEventId.isBlank() &&
                row.mode.equals("paper", true) && row.side.equals("BUY", true) &&
                row.positionId == e.positionId &&
                approx(row.sol, e.executedCostSol) && approx(row.feeSol, e.entryFeesSol) &&
                (row.entryRawQty <= BigInteger.ZERO || row.entryRawQty == e.filledQty)
        }

        fun equivalentLegacySell(e: EconomicEventSchema6464.Sell): Boolean = journal.any { row ->
            val expectedSide = if (e.partial) "PARTIAL_SELL" else "SELL"
            row.economicEventId.isBlank() &&
                row.mode.equals("paper", true) && row.side.equals(expectedSide, true) &&
                row.positionId == e.positionId &&
                approx(sellGross(row), e.grossProceedsSol) &&
                approx(row.soldCostBasisSol, e.allocatedCostBasisSol) &&
                approx(row.feeSol, e.exitFeesSol) &&
                (row.canonicalConsumedRaw <= BigInteger.ZERO || row.canonicalConsumedRaw == e.soldQty)
        }

        typed.forEach { event ->
            if (event.idempotencyKey.isBlank() || event.positionId.isBlank()) {
                skipped++
                return@forEach
            }
            if (journal.any { it.economicEventId == event.idempotencyKey }) {
                exact++
                return@forEach
            }

            when (event) {
                is EconomicEventSchema6464.Buy -> {
                    if (equivalentLegacyBuy(event)) {
                        legacyEquivalent++
                        try { PipelineHealthCollector.labelInc("CANONICAL_TYPED_BUY_LEGACY_EQUIVALENT_6677") } catch (_: Throwable) {}
                        return@forEach
                    }
                    if (event.executedCostSol <= 0.0 || event.filledQty <= BigInteger.ZERO) {
                        skipped++
                        return@forEach
                    }
                    val pos = positions[event.positionId]
                    val scale = scaleFor(event.positionId, event.quantityScale)
                    val qty = tokenQty(event.filledQty, scale)
                    val entryPrice = event.fillPrice.takeIf { it.isFinite() && it > 0.0 }
                        ?: pos?.entryPriceUsd?.takeIf { it.isFinite() && it > 0.0 }
                        ?: 0.0
                    val row = Trade(
                        side = "BUY", mode = "paper", sol = event.executedCostSol,
                        price = entryPrice.coerceAtLeast(0.000000000001), ts = event.atMs,
                        reason = "TYPED_ECONOMIC_BUY_PROJECTION_REPAIR_6677",
                        feeSol = event.entryFeesSol.coerceAtLeast(0.0),
                        tradingMode = pos?.lane ?: "CANONICAL_REPLAY",
                        tradingModeEmoji = "🪙", mint = event.mint,
                        proofState = "PAPER_SIMULATED", positionId = event.positionId,
                        entryTsMs = pos?.openedAtMs ?: event.atMs,
                        entryPriceSnapshot = entryPrice.coerceAtLeast(0.000000000001),
                        entryQtyToken = qty, entryCostSol = event.executedCostSol,
                        entryDecimals = scale, remainingQtyToken = qty,
                        entryRawQty = event.filledQty, remainingRawQty = event.filledQty,
                        tokenDecimals = scale,
                        entryPriceSource = pos?.entryPriceSource.orEmpty(),
                        entryPoolAddress = pos?.entryPoolAddress.orEmpty(),
                        economicEventId = event.idempotencyKey,
                    )
                    TradeHistoryStore.recordTrade(row)
                    journal.add(row)
                    projected++
                    try { PipelineHealthCollector.labelInc("CANONICAL_MISSING_BUY_JOURNAL_PROJECTED_6677") } catch (_: Throwable) {}
                }

                is EconomicEventSchema6464.Sell -> {
                    if (equivalentLegacySell(event)) {
                        legacyEquivalent++
                        try { PipelineHealthCollector.labelInc("CANONICAL_TYPED_SELL_LEGACY_EQUIVALENT_6677") } catch (_: Throwable) {}
                        return@forEach
                    }
                    if (event.soldQty <= BigInteger.ZERO || event.allocatedCostBasisSol <= 0.0 ||
                        !event.grossProceedsSol.isFinite() || event.grossProceedsSol < 0.0 ||
                        !event.exitFeesSol.isFinite() || event.exitFeesSol < 0.0
                    ) {
                        skipped++
                        return@forEach
                    }
                    val pos = positions[event.positionId]
                    val seedBuy = buysByPosition[event.positionId]?.firstOrNull()
                    val fallbackScale = seedBuy?.quantityScale ?: seedBuy?.tokenDecimals ?: 9
                    val scale = scaleFor(event.positionId, fallbackScale)
                    val soldQty = tokenQty(event.soldQty, scale)
                    val remainingQty = tokenQty(event.remainingQty, scale)
                    val entryPrice = pos?.entryPriceUsd?.takeIf { it.isFinite() && it > 0.0 }
                        ?: seedBuy?.fillPrice?.takeIf { it.isFinite() && it > 0.0 }
                        ?: 0.0
                    val basis = event.allocatedCostBasisSol
                    val gross = event.grossProceedsSol
                    val fee = event.exitFeesSol
                    val realized = gross - basis - fee
                    val pnlPct = if (basis > 0.0) realized * 100.0 / basis else 0.0
                    val exitPrice = if (soldQty > 0.0) gross / soldQty else entryPrice
                    val parsedSequence = event.idempotencyKey.substringAfterLast(':').toLongOrNull() ?: 0L
                    val originalRaw = pos?.originalQtyRaw
                        ?: seedBuy?.filledQty
                        ?: event.soldQty.add(event.remainingQty)
                    val row = Trade(
                        side = if (event.partial) "PARTIAL_SELL" else "SELL",
                        mode = "paper", sol = gross,
                        price = exitPrice.coerceAtLeast(0.000000000001), ts = event.atMs,
                        reason = "TYPED_ECONOMIC_SELL_PROJECTION_REPAIR_6677",
                        pnlSol = realized, pnlPct = pnlPct, feeSol = fee,
                        netPnlSol = realized,
                        tradingMode = pos?.lane ?: "CANONICAL_REPLAY",
                        tradingModeEmoji = "🪙", mint = event.mint,
                        proofState = "PAPER_SIMULATED", positionId = event.positionId,
                        entryTsMs = pos?.openedAtMs ?: seedBuy?.atMs ?: event.atMs,
                        entryPriceSnapshot = entryPrice.coerceAtLeast(0.000000000001),
                        // Keep the heuristic legacy qty×price PnL repair out of
                        // typed receipt recovery. Raw quantity fields below are
                        // authoritative; zero display entry qty is intentional.
                        entryQtyToken = 0.0,
                        entryCostSol = basis, entryDecimals = scale,
                        soldQtyToken = soldQty, remainingQtyToken = remainingQty,
                        entryRawQty = originalRaw,
                        canonicalConsumedRaw = event.soldQty,
                        remainingRawQty = event.remainingQty,
                        tokenDecimals = scale,
                        operationId = event.idempotencyKey,
                        partialSequence = parsedSequence,
                        soldCostBasisSol = basis,
                        grossProceedsSol = gross,
                        economicEventId = event.idempotencyKey,
                    )
                    TradeHistoryStore.recordTrade(row)
                    journal.add(row)
                    projected++
                    try {
                        PipelineHealthCollector.labelInc(
                            if (event.partial) "CANONICAL_MISSING_PARTIAL_JOURNAL_PROJECTED_6677"
                            else "CANONICAL_MISSING_SELL_JOURNAL_PROJECTED_6677"
                        )
                    } catch (_: Throwable) {}
                }
            }
        }

        if (projected > 0 || legacyEquivalent > 0 || skipped > 0) {
            try {
                ForensicLogger.lifecycle(
                    "CANONICAL_JOURNAL_PROJECTION_REPAIR_6677",
                    "typed=${typed.size} projected=$projected exact=$exact legacyEquivalent=$legacyEquivalent skipped=$skipped action=project_missing_exact_receipts_no_delete_no_recalc",
                )
            } catch (_: Throwable) {}
        }
        return Result(typed.size, projected, exact, legacyEquivalent, skipped)
    }
}
