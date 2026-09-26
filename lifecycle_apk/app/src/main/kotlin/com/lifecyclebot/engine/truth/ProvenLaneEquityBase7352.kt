package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.StrategyTelemetry
import com.lifecyclebot.engine.TradeHistoryStore

/**
 * V5.0.7352 §A_PROVEN_LANE_SIZES_WITH_THE_ACCOUNT.
 *
 * Operator (approved option B): "grow the base with equity."
 *
 * 5.0.7351 at 25 minutes: paper equity 70.7 SOL, cash 39.8 SOL idle, 99 of 100
 * position slots full — and every entry still 0.107 SOL, the same as at 11 SOL
 * equity. Paper sizing read CASH, never equity, and with 70+ positions open the
 * inventory-pressure multiplier (x0.20 per resolver pass) pushed nearly every
 * request under the fee-aware floor (FeeAwareSizeFloor7277 = 0.1073), where it
 * was promoted back to exactly that floor. PROJECT_SNIPER's +108%/trade over
 * 403 closes could only ever compound by adding positions, and the slot cap
 * stopped that first.
 *
 * For a PROVEN lane only, the executable floor becomes a fixed fraction of paper
 * equity. Proven = its own clean paper terminal closes: at least
 * [MIN_PROVEN_CLOSES] trades with net-positive SOL and a positive mean. Every
 * other lane keeps the existing floor.
 *
 * Equity is cash + open COST basis (not marks), the same basis the drawdown
 * guard uses, so a phantom unrealised spike cannot size the book up. The result
 * is applied as the resolver's minimum, so everything that already governs the
 * final size still does: real market depth and the bonding-curve exit cap (via
 * the ticket cap), available cash, the exit-throughput block, and the
 * collapsed-conviction refusal.
 */
object ProvenLaneEquityBase7352 {

    /** 1% of equity: one position per slot of the 100-slot book. */
    const val EQUITY_FRACTION = 0.01

    /** Same bar as EvidenceMaturity7277's lane opinion (30 closes). */
    const val MIN_PROVEN_CLOSES = 30

    fun paperEquitySol(): Double = try {
        val cash = PaperCapitalAuthority6577.cashSol().coerceAtLeast(0.0)
        val openCost = PaperCapitalAuthority6577.openCostBasisSol().coerceAtLeast(0.0)
        (cash + openCost).takeIf { it.isFinite() } ?: 0.0
    } catch (_: Throwable) { 0.0 }

    private fun norm(lane: String): String = try {
        TradeHistoryStore.normalizeTradeModeName(lane).ifBlank { lane.trim().uppercase() }
    } catch (_: Throwable) { lane.trim().uppercase() }

    private fun isProvenPaperLane(lane: String?): Boolean {
        if (lane.isNullOrBlank()) return false
        val key = norm(lane)
        return try {
            val row = StrategyTelemetry.computeCleanPaperTerminalLeaderboard()
                .firstOrNull { norm(it.strategy) == key } ?: return false
            row.trades >= MIN_PROVEN_CLOSES && row.totalSolPnl > 0.0 && row.meanPnlPct > 0.0
        } catch (_: Throwable) { false }
    }

    /** The equity-scaled base for [lane], or null when the lane is not proven. */
    fun baseSolFor(lane: String?): Double? {
        if (!isProvenPaperLane(lane)) return null
        val equity = paperEquitySol()
        if (!equity.isFinite() || equity <= 0.0) return null
        val base = equity * EQUITY_FRACTION
        try { PipelineHealthCollector.labelInc("PROVEN_LANE_EQUITY_BASE_7352") } catch (_: Throwable) {}
        return base
    }
}
