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
        val forensicExcluded: Int = 0,
    )

    data class Result(
        val rows: List<Trade>,
        val audit: Audit,
    )

    fun cleanedTerminalRows(rawRows: List<Trade>, limit: Int = rawRows.size): List<Trade> =
        clean(rawRows, limit).rows

    fun clean(rawRows: List<Trade>, limit: Int = rawRows.size): Result {
        if (rawRows.isEmpty()) return Result(emptyList(), Audit(0, 0, 0, 0, 0, 0))
        val newestFirst = rawRows.sortedByDescending { it.ts }
        val seenTerminalKeys = LinkedHashSet<String>()
        val seenGenerationKeys = LinkedHashSet<String>()
        val seenMintCloseWindows = LinkedHashMap<String, Long>()
        val out = ArrayList<Trade>(limit.coerceAtLeast(1))
        var deduped = 0
        var recovery = 0
        var partial = 0
        var badEntry = 0
        var forensic = 0

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
            val forensicReject = forensicRejectReason(row)
            if (forensicReject != null) {
                forensic++
                inc("STRATEGY_FORENSIC_EXCLUDED_${forensicReject}")
                continue
            }

            val terminalKey = terminalKey(row)
            val generationKey = generationKey(row)
            val mintWindowKey = mintCloseWindowKey(row)
            val priorCloseTs = seenMintCloseWindows[mintWindowKey]
            val sameMintCloseDuplicate = priorCloseTs != null && row.ts > 0L && kotlin.math.abs(priorCloseTs - row.ts) <= SAME_MINT_TERMINAL_DEDUP_WINDOW_MS
            if (!seenTerminalKeys.add(terminalKey) || !seenGenerationKeys.add(generationKey) || sameMintCloseDuplicate) {
                deduped++
                inc("STRATEGY_TERMINAL_DEDUPED")
                if (sameMintCloseDuplicate) inc("STRATEGY_MINT_CLOSE_WINDOW_DEDUPED_4494")
                continue
            }
            if (row.ts > 0L) seenMintCloseWindows[mintWindowKey] = row.ts

            inc("STRATEGY_CLEAN_TERMINAL_ROWS")
            out += normalizedStrategyRow(row)
        }
        return Result(out, Audit(out.size, deduped, recovery, partial, badEntry, forensic))
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

    // V5.0.4502 — forensic money contract. Strategy truth must not count
    // large live PnL rows unless price, SOL basis, and wallet/proof finality line
    // up. Raw rows stay in the journal; this only prevents unaudited money from
    // becoming strategy-clean PnL/WR and poisoning decisions.
    private fun forensicRejectReason(t: Trade): String? {
        val side = t.side.trim().uppercase()
        if (side != "SELL" && side != "PARTIAL_SELL") return null
        val mode = t.mode.trim().uppercase()
        val live = mode == "LIVE"
        val proof = t.proofState.trim().uppercase()
        val basis = t.entryCostSol.takeIf { it.isFinite() && it > 0.0 } ?: return "MISSING_ENTRY_COST_BASIS"
        val realized = when {
            t.netPnlSol.isFinite() && t.netPnlSol != 0.0 -> t.netPnlSol
            t.pnlSol.isFinite() -> t.pnlSol
            else -> return "PNL_SOL_NAN"
        }
        val proceeds = basis + realized
        if (!proceeds.isFinite() || proceeds < -0.000001) return "NEGATIVE_PROCEEDS"
        val pctFromSol = (realized / basis) * 100.0
        if (!pctFromSol.isFinite() || kotlin.math.abs(pctFromSol - t.pnlPct) > 50.0) return "PNL_SOL_PERCENT_MISMATCH"
        if (live && proof.isBlank()) return "MISSING_LIVE_PROOF"
        val largePnl = kotlin.math.abs(realized) >= 0.25 || kotlin.math.abs(t.pnlPct) >= 1000.0
        val walletFinal = proof.contains("FINAL") || proof.contains("BALANCE") || proof.contains("TX_PARSE") || proof.contains("OWNER_DELTA")
        if (live && largePnl && !walletFinal) return "LARGE_PNL_NOT_WALLET_FINAL"
        return null
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

    private const val SAME_MINT_TERMINAL_DEDUP_WINDOW_MS = 5L * 60_000L

    private fun mintCloseWindowKey(t: Trade): String {
        val mode = t.mode.ifBlank { "unknown" }.uppercase()
        val mint = t.mint.ifBlank { "unknown" }
        return "$mode|$mint"
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
