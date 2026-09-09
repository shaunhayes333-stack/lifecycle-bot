package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.TradeHistoryStore
import java.math.BigInteger
import kotlin.math.abs

/**
 * V5.0.6706 — repair a missing durable journal terminal leg from the exact typed
 * canonical SELL receipt. No price, quantity, cost basis or PnL is invented.
 *
 * Runtime-smoke 5.0.6705 ended with journalPositions=105 vs canonicalPositions=82,
 * open-basis delta ~=1.01 SOL and matching cash/quantity deltas. The old
 * orphan-lot repair only handled positions that had disappeared from canonical
 * storage; CLOSED canonical positions are intentionally retained, so their
 * missing journal SELL could remain an open journal lot forever.
 *
 * This repair is idempotent by EconomicEventSchema6464.idempotencyKey and only
 * writes when the exact terminal receipt has no canonical journal counterpart.
 */
object CanonicalJournalTerminalRepair6706 {

    private fun display(raw: BigInteger, scale: Int): Double = try {
        raw.toBigDecimal().movePointLeft(scale.coerceIn(0, 18)).toDouble()
    } catch (_: Throwable) { 0.0 }

    fun repairMissingTerminalLegs(): Int {
        val rows = try { TradeHistoryStore.getAllValidTradesSnapshot(limit = 20_000) }
        catch (_: Throwable) { return 0 }
        val typed = try {
            EconomicEventSchema6464.snapshot()
                .filterIsInstance<EconomicEventSchema6464.Sell>()
                .filter { it.mode.equals("paper", true) }
                .sortedBy { it.atMs }
        } catch (_: Throwable) { return 0 }
        if (typed.isEmpty()) return 0

        val positions = try {
            (CanonicalPositionAuthority6441.openPositions() + CanonicalPositionAuthority6441.closedPositions())
                .associateBy { it.positionId }
        } catch (_: Throwable) { emptyMap() }
        val typedBuys = try {
            EconomicEventSchema6464.snapshot()
                .filterIsInstance<EconomicEventSchema6464.Buy>()
                .filter { it.mode.equals("paper", true) }
                .groupBy { it.positionId }
        } catch (_: Throwable) { emptyMap() }

        val existingEventIds = rows.asSequence()
            .filter { it.mode.equals("paper", true) && it.economicEventId.isNotBlank() }
            .map { it.economicEventId }
            .toHashSet()
        val rowsByPosition = rows.filter { it.mode.equals("paper", true) && it.positionId.isNotBlank() }
            .groupBy { it.positionId }
        val sequenceByPosition = mutableMapOf<String, Long>()
        var repaired = 0

        for (sell in typed) {
            val seq = (sequenceByPosition[sell.positionId] ?: 0L) + 1L
            sequenceByPosition[sell.positionId] = seq
            val eventId = sell.idempotencyKey
            if (eventId.isBlank() || eventId in existingEventIds) continue

            val positionRows = rowsByPosition[sell.positionId].orEmpty()
            val alreadyRepresented = positionRows.any { row ->
                val terminalSide = row.side.equals("SELL", true) || row.side.equals("PARTIAL_SELL", true)
                if (!terminalSide) return@any false
                // Old CryptoAlt display rows are intentionally ignored by
                // JournalEconomicReplay6619 and therefore are not canonical proof.
                if (row.economicEventId.isBlank() && row.tradingMode.contains("CryptoAlt", true)) return@any false
                val sideMatches = row.side.equals(if (sell.partial) "PARTIAL_SELL" else "SELL", true)
                val rawMatches = row.canonicalConsumedRaw > BigInteger.ZERO && row.canonicalConsumedRaw == sell.soldQty
                val grossMatches = row.grossProceedsSol.isFinite() &&
                    abs(row.grossProceedsSol - sell.grossProceedsSol) <= 1e-7
                val timeMatches = abs(row.ts - sell.atMs) <= 15_000L
                sideMatches && ((rawMatches && grossMatches) || (timeMatches && grossMatches))
            }
            if (alreadyRepresented) continue

            val pos = positions[sell.positionId]
            val buys = typedBuys[sell.positionId].orEmpty().sortedBy { it.atMs }
            val firstBuy = buys.firstOrNull()
            val scale = (pos?.quantityScale ?: firstBuy?.quantityScale ?: firstBuy?.tokenDecimals ?: 9).coerceIn(0, 18)
            val originalRaw = pos?.originalQtyRaw
                ?: buys.fold(BigInteger.ZERO) { acc, b -> acc + b.filledQty }
            if (originalRaw <= BigInteger.ZERO || sell.soldQty <= BigInteger.ZERO) continue
            val soldDisplay = display(sell.soldQty, scale)
            val remainingDisplay = display(sell.remainingQty, scale)
            val originalDisplay = display(originalRaw, scale)
            val entryPrice = when {
                pos?.entryPriceUsd?.isFinite() == true && pos.entryPriceUsd > 0.0 -> pos.entryPriceUsd
                firstBuy?.fillPrice?.isFinite() == true && firstBuy.fillPrice > 0.0 -> firstBuy.fillPrice
                else -> 0.0
            }
            val exitPrice = if (soldDisplay > 0.0) sell.grossProceedsSol / soldDisplay else entryPrice
            if (!exitPrice.isFinite() || exitPrice <= 0.0) continue
            val lane = pos?.lane ?: positionRows.firstOrNull()?.tradingMode ?: "STANDARD"
            val mint = sell.mint.ifBlank { pos?.mint.orEmpty() }
            val symbol = sell.symbol.ifBlank { pos?.symbol.orEmpty() }
            if (mint.isBlank()) continue
            val entryCost = buys.sumOf { it.executedCostSol.coerceAtLeast(0.0) }
                .takeIf { it > 0.0 } ?: pos?.entryCostSol?.coerceAtLeast(0.0) ?: 0.0
            val entryTs = firstBuy?.atMs ?: pos?.openedAtMs ?: sell.atMs

            try {
                PaperEconomicAtomicCommit6632.stampLedger(
                    eventId, mint,
                    if (sell.partial) PaperEconomicAtomicCommit6632.Side.PARTIAL_SELL
                    else PaperEconomicAtomicCommit6632.Side.SELL,
                    "CanonicalJournalTerminalRepair6706.exactTypedReceipt",
                )
                TradeHistoryStore.recordTrade(
                    Trade(
                        side = if (sell.partial) "PARTIAL_SELL" else "SELL",
                        mode = "paper",
                        sol = sell.grossProceedsSol,
                        price = exitPrice,
                        ts = sell.atMs,
                        reason = "CANONICAL_TERMINAL_PROJECTION_6706",
                        pnlSol = sell.realizedPnlSol,
                        pnlPct = sell.realizedReturnPct,
                        feeSol = sell.exitFeesSol,
                        netPnlSol = sell.realizedPnlSol - sell.exitFeesSol,
                        tradingMode = lane,
                        mint = mint,
                        proofState = "PAPER_SIMULATED",
                        positionId = sell.positionId,
                        entryTsMs = entryTs,
                        entryPriceSnapshot = entryPrice.takeIf { it > 0.0 } ?: exitPrice,
                        entryQtyToken = originalDisplay,
                        entryCostSol = entryCost,
                        entryDecimals = scale,
                        soldQtyToken = soldDisplay,
                        remainingQtyToken = remainingDisplay,
                        entryRawQty = originalRaw,
                        canonicalConsumedRaw = sell.soldQty,
                        remainingRawQty = sell.remainingQty,
                        tokenDecimals = scale,
                        partialSequence = seq,
                        soldCostBasisSol = sell.allocatedCostBasisSol,
                        grossProceedsSol = sell.grossProceedsSol,
                        economicEventId = eventId,
                    )
                )
                existingEventIds += eventId
                repaired++
                PipelineHealthCollector.labelInc("CANONICAL_TERMINAL_JOURNAL_REPAIRED_6706")
                ForensicLogger.lifecycle(
                    "CANONICAL_TERMINAL_JOURNAL_REPAIRED_6706",
                    "positionId=${sell.positionId.take(24)} mint=${mint.take(10)} side=${if (sell.partial) "PARTIAL" else "FULL"} raw=${sell.soldQty} basis=${sell.allocatedCostBasisSol} gross=${sell.grossProceedsSol} eventId=${eventId.take(48)}",
                )
            } catch (_: Throwable) {}
        }
        if (repaired > 0) {
            try { TradeHistoryStore.awaitDurableJournalBoundary6669() } catch (_: Throwable) {}
        }
        return repaired
    }
}
