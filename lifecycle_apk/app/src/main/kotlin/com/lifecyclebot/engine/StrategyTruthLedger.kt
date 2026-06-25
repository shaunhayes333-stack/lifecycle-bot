package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import kotlin.math.abs

/**
 * V5.0.4151 — STRATEGY TRUTH LEDGER / DEDUPER.
 *
 * Produces one clean terminal strategy outcome per real bot-opened position.
 * This is the canonical strategy-learning read filter: inventory recovery,
 * duplicate terminal rows, partial exits, zero-basis rows and bad entry rows are
 * excluded from strategy WR/PnL while still remaining available to raw journal /
 * inventory accounting surfaces.
 */
object StrategyTruthLedger {
    const val VERSION = "V5.0.4151_STRATEGY_TRUTH_LEDGER"

    data class Audit(
        val cleaned: Int,
        val deduped: Int,
        val recoveryExcluded: Int,
        val partialNotTerminal: Int,
        val badEntryExcluded: Int,
    )

    data class Result(
        val rows: List<Trade>,
        val audit: Audit,
    )

    fun cleanedTerminalRows(rawRows: List<Trade>, limit: Int = rawRows.size): List<Trade> =
        clean(rawRows, limit).rows

    fun clean(rawRows: List<Trade>, limit: Int = rawRows.size): Result {
        if (rawRows.isEmpty()) return Result(emptyList(), Audit(0, 0, 0, 0, 0))
        val newestFirst = rawRows.sortedByDescending { it.ts }
        val seenTerminalKeys = LinkedHashSet<String>()
        val seenGenerationKeys = LinkedHashSet<String>()
        val out = ArrayList<Trade>(limit.coerceAtLeast(1))
        var deduped = 0
        var recovery = 0
        var partial = 0
        var badEntry = 0

        for (row in newestFirst) {
            if (out.size >= limit.coerceAtLeast(1)) break
            val side = row.side.trim().uppercase()
            if (side == "PARTIAL_SELL") {
                // A partial can be terminal only when wallet amount is zero. In
                // current schema remainingQtyToken is the best persisted proxy.
                if (row.remainingQtyToken > 0.000000001) {
                    partial++
                    inc("STRATEGY_PARTIAL_NOT_TERMINAL")
                    continue
                }
            } else if (side != "SELL") {
                continue
            }

            if (isRecoveryInventory(row)) {
                recovery++
                inc("STRATEGY_RECOVERY_EXCLUDED")
                continue
            }
            if (!hasValidEntryBasis(row)) {
                badEntry++
                inc("STRATEGY_BAD_ENTRY_EXCLUDED")
                continue
            }

            val terminalKey = terminalKey(row)
            val generationKey = generationKey(row)
            if (!seenTerminalKeys.add(terminalKey) || !seenGenerationKeys.add(generationKey)) {
                deduped++
                inc("STRATEGY_TERMINAL_DEDUPED")
                continue
            }

            inc("STRATEGY_CLEAN_TERMINAL_ROWS")
            out += normalizedStrategyRow(row)
        }
        return Result(out, Audit(out.size, deduped, recovery, partial, badEntry))
    }

    fun isRecoveryInventory(t: Trade): Boolean {
        val hay = listOf(t.tradingMode, t.reason, t.proofState, t.entryPriceSource, t.positionId)
            .joinToString("|")
            .uppercase()
        return hay.contains("WALLET_RECOVERED") ||
            hay.contains("OPEN_RESTORED") ||
            hay.contains("ADOPTED_FROM_WALLET") ||
            hay.contains("RECOVERED_") ||
            hay.contains("RESTORED_") ||
            hay.contains("INVENTORY_RECON")
    }

    fun inventoryRecoveryRows(rawRows: List<Trade>): List<Trade> =
        rawRows.filter { isRecoveryInventory(it) }

    fun hasValidEntryBasis(t: Trade): Boolean {
        val entrySol = when {
            t.entryCostSol > 0.0 && t.entryCostSol.isFinite() -> t.entryCostSol
            t.sol > 0.0 && t.sol.isFinite() -> t.sol
            else -> 0.0
        }
        val entryPrice = when {
            t.entryPriceSnapshot > 0.0 && t.entryPriceSnapshot.isFinite() -> t.entryPriceSnapshot
            t.price > 0.0 && t.price.isFinite() -> t.price
            else -> 0.0
        }
        return entrySol > 0.0 && entryPrice > 0.0 && t.mint.isNotBlank()
    }

    fun strategyLaneFor(t: Trade): String = if (isRecoveryInventory(t)) {
        "RECOVERY_INVENTORY"
    } else try {
        TradeHistoryStore.normalizeTradeModeName(t.tradingMode).ifBlank { "STANDARD" }
    } catch (_: Throwable) {
        t.tradingMode.ifBlank { "STANDARD" }.uppercase()
    }

    private fun normalizedStrategyRow(t: Trade): Trade {
        val lane = strategyLaneFor(t)
        return if (lane != t.tradingMode) t.copy(tradingMode = lane) else t
    }

    private fun terminalKey(t: Trade): String {
        val mode = t.mode.ifBlank { "unknown" }.uppercase()
        val mint = t.mint.ifBlank { "unknown" }
        val buySig = t.positionId.ifBlank { "pos:${t.entryTsMs.takeIf { it > 0L } ?: t.ts}" }
        val sellSig = t.sig.ifBlank { "" }
        return if (sellSig.isNotBlank()) {
            "$mode|$mint|$buySig|$sellSig"
        } else {
            val bucket = terminalCloseTimeBucket(t.ts)
            "$mode|$mint|$buySig|${t.reason.take(48)}|$bucket"
        }
    }

    private fun generationKey(t: Trade): String {
        val mode = t.mode.ifBlank { "unknown" }.uppercase()
        val mint = t.mint.ifBlank { "unknown" }
        val pos = t.positionId.ifBlank { "entry:${t.entryTsMs.takeIf { it > 0L } ?: t.ts}" }
        return "$mode|$mint|$pos"
    }

    private fun terminalCloseTimeBucket(ts: Long): Long = if (ts > 0L) ts / 60_000L else 0L

    private fun inc(label: String) {
        try { PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {}
    }

    fun auditLine(limit: Int = 2_500): String = try {
        val raw = TradeHistoryStore.getRecentValidClosedTradesRaw(limit = limit, includePartials = true)
        val result = clean(raw, limit)
        val inv = inventoryRecoveryRows(raw)
        val invPnl = inv.sumOf { it.netPnlSol.takeIf { v -> abs(v) > 0.0 } ?: it.pnlSol }
        "StrategyTruthLedger: clean=${result.audit.cleaned} deduped=${result.audit.deduped} recovered=${result.audit.recoveryExcluded} partialNonTerminal=${result.audit.partialNotTerminal} badEntry=${result.audit.badEntryExcluded} inventory=${inv.size} inventoryPnl=${"%+.4f".format(invPnl)}"
    } catch (_: Throwable) { "StrategyTruthLedger: unavailable" }
}
