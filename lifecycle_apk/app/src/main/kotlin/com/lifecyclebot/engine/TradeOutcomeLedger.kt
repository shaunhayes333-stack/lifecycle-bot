package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.data.Trade
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1100-pre — canonical execution/learning idempotency ledger.
 *
 * PositionId: runtimeGeneration + mode + mint + openedAtMs + primaryLane.
 * TradeOutcomeId: positionId + CLOSE.
 *
 * Only a successful recordClose() means downstream strategy learning may run.
 * Partial sells and orphan closes are explicitly excluded from closed-trade
 * learning counts.
 */
object TradeOutcomeLedger {
    data class OpenRecord(
        val positionId: String,
        val runtimeGeneration: Long,
        val mode: String,
        val mint: String,
        val openedAtMs: Long,
        val primaryLane: String,
    )

    data class CloseVerdict(
        val accepted: Boolean,
        val positionId: String,
        val outcomeId: String,
        val reason: String,
        val orphan: Boolean = false,
        val partial: Boolean = false,
    )

    private val opens = ConcurrentHashMap<String, OpenRecord>()
    private val closes = ConcurrentHashMap<String, CloseVerdict>()
    private val duplicateOpenSuppressed = AtomicLong(0L)
    private val duplicateCloseSuppressed = AtomicLong(0L)
    private val orphanCloseSuppressed = AtomicLong(0L)
    private val learningDuplicateSuppressions = AtomicLong(0L)

    fun positionId(
        runtimeGeneration: Long,
        mode: String,
        mint: String,
        openedAtMs: Long,
        primaryLane: String,
    ): String = "$runtimeGeneration:${mode.uppercase()}:$mint:$openedAtMs:${primaryLane.uppercase()}"

    fun positionId(ts: TokenState, trade: Trade? = null): String {
        val mode = when {
            trade?.mode?.isNotBlank() == true -> trade.mode
            ts.position.isPaperPosition -> "paper"
            else -> "live"
        }
        val lane = (ts.position.tradingMode.ifBlank { trade?.tradingMode ?: "UNKNOWN" }).uppercase()
        val openedAt = ts.position.entryTime.takeIf { it > 0L } ?: trade?.ts ?: 0L
        return positionId(BotRuntimeController.currentGeneration(), mode, ts.mint, openedAt, lane)
    }

    fun recordOpen(ts: TokenState, trade: Trade): Boolean {
        if (!trade.side.equals("BUY", ignoreCase = true)) return true
        val id = positionId(ts, trade)
        val rec = OpenRecord(
            positionId = id,
            runtimeGeneration = BotRuntimeController.currentGeneration(),
            mode = trade.mode.ifBlank { if (ts.position.isPaperPosition) "paper" else "live" },
            mint = ts.mint,
            openedAtMs = ts.position.entryTime.takeIf { it > 0L } ?: trade.ts,
            primaryLane = ts.position.tradingMode.ifBlank { trade.tradingMode.ifBlank { "UNKNOWN" } },
        )
        val old = opens.putIfAbsent(id, rec)
        if (old != null) {
            duplicateOpenSuppressed.incrementAndGet()
            return false
        }
        return true
    }

    fun recordClose(ts: TokenState, trade: Trade, partial: Boolean): CloseVerdict {
        if (!trade.side.equals("SELL", ignoreCase = true)) {
            return CloseVerdict(true, positionId(ts, trade), "", "NOT_CLOSE")
        }
        val id = positionId(ts, trade)
        if (partial) {
            return CloseVerdict(false, id, "$id:PARTIAL:${trade.ts}", "PARTIAL_SELL_NOT_FINAL", partial = true)
        }
        val orphan = ts.position.entryTime <= 0L || ts.position.tradingMode.isBlank()
        if (orphan) {
            orphanCloseSuppressed.incrementAndGet()
            return CloseVerdict(false, id, "$id:ORPHAN:${trade.ts}", "ORPHAN_CLOSE_EXCLUDED", orphan = true)
        }
        val outcomeId = "$id:CLOSE"
        val verdict = CloseVerdict(true, id, outcomeId, "CLOSE_ACCEPTED")
        val old = closes.putIfAbsent(outcomeId, verdict)
        if (old != null) {
            duplicateCloseSuppressed.incrementAndGet()
            learningDuplicateSuppressions.incrementAndGet()
            return CloseVerdict(false, id, outcomeId, "DUPLICATE_CLOSE_SUPPRESSED")
        }
        return verdict
    }

    fun duplicateOpenSuppressions(): Long = duplicateOpenSuppressed.get()
    fun duplicateCloseSuppressions(): Long = duplicateCloseSuppressed.get()
    fun orphanCloseSuppressions(): Long = orphanCloseSuppressed.get()
    fun learningDuplicateSuppressions(): Long = learningDuplicateSuppressions.get()
    fun uniqueClosedPositionCount(): Int = closes.size
    fun resetForTests() {
        opens.clear(); closes.clear()
        duplicateOpenSuppressed.set(0L); duplicateCloseSuppressed.set(0L)
        orphanCloseSuppressed.set(0L); learningDuplicateSuppressions.set(0L)
    }
}
