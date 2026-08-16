package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6450 §P0 — CANONICAL CAPITAL AUTHORITY.
 *
 * OPERATOR MANDATE (V5.0.6450 dump):
 *   PositionStateLedger OPEN=274 vs CanonicalPositions OPEN=42
 *   PaperAccount cash=2.2441 vs Canonical paperCash=2.26394
 *   capital conservation delta=-0.319310
 *
 *   "Establish ONE authoritative PositionId-based lifecycle ledger.
 *    All of these MUST derive from it: paper cash, reserved capital, open
 *    positions, partial positions, closed positions, realized PnL,
 *    unrealized PnL, fees, wallet equity, runner state, learner
 *    finalization, lane/tactic statistics."
 *
 * DESIGN
 * ──────
 * This is the single READ authority for capital state. Underlying stores
 * remain PaperAccountLedger6430 (cash + fees + realized) and
 * CanonicalPositionAuthority6441 (positions). This module *does not*
 * duplicate storage — it computes the 5 canonical surfaces:
 *
 *   CASH                — PaperAccountLedger6430.cashSol
 *   RESERVED            — sum of PENDING_ENTRY costSol
 *   OPEN_COST_BASIS     — canonical open cost, excluding reserved
 *   OPEN_MARKET_VALUE   — sum(currentMarkValue) via caller-supplied mark
 *   UNREALIZED_PNL      — OPEN_MARKET_VALUE - OPEN_COST_BASIS
 *   REALIZED_PNL        — PaperAccountLedger6430.realizedPnlSol
 *   FEES                — PaperAccountLedger6430.feesSol
 *   TOTAL_EQUITY        — CASH + RESERVED + OPEN_MARKET_VALUE
 *
 * The wallet UI MUST NOT display CASH as equity. Callers use snapshot().
 *
 * Invariant (checked every audit tick):
 *   startingCapital + realized - fees ≈ cash + reserved + openCostBasis
 */
object CanonicalCapitalAuthority6450 {

    data class Snapshot(
        val startingCashSol: Double,
        val cashSol: Double,
        val reservedSol: Double,
        val openCostBasisSol: Double,
        val openMarketValueSol: Double,
        val unrealizedPnlSol: Double,
        val realizedPnlSol: Double,
        val feesSol: Double,
        val totalEquitySol: Double,
        val conservationDeltaSol: Double,
    )

    private val invariantChecks = AtomicLong(0L)
    private val invariantViolations = AtomicLong(0L)
    private val lastDeltaMicros = AtomicLong(0L) // *1e6, atomic-safe

    /**
     * Compute the canonical snapshot. Caller supplies a mark provider that
     * returns current SOL market value for a mint (0.0 = mark unknown, use
     * costBasis fallback so unrealized reads as 0 rather than -100%).
     */
    fun snapshot(markProvider: (String) -> Double = { 0.0 }): Snapshot {
        val startingCash = PaperAccountLedger6430.startingCashSol()
        val cash = PaperAccountLedger6430.cashSol()
        val realized = PaperAccountLedger6430.realizedPnlSol()
        val fees = PaperAccountLedger6430.feesSol()
        val open = try { CanonicalPositionAuthority6441.openPositions() } catch (_: Throwable) { emptyList() }
        val reserved = 0.0 // reserved capital tracked upstream; kept 0 until wired
        val openCost = open.sumOf { (it.entryCostSol - it.soldCostBasisSol).coerceAtLeast(0.0) }
        val openMv = open.sumOf {
            val remainingRatio = if (it.entryCostSol > 0.0) ((it.entryCostSol - it.soldCostBasisSol) / it.entryCostSol).coerceAtLeast(0.0) else 1.0
            val mark = try { markProvider(it.mint) } catch (_: Throwable) { 0.0 }
            if (mark > 0.0) mark * remainingRatio else (it.entryCostSol - it.soldCostBasisSol).coerceAtLeast(0.0)
        }
        val unrealized = openMv - openCost
        val equity = cash + reserved + openMv
        val expected = startingCash + realized - fees
        val actual = cash + reserved + openCost
        return Snapshot(
            startingCashSol = startingCash,
            cashSol = cash,
            reservedSol = reserved,
            openCostBasisSol = openCost,
            openMarketValueSol = openMv,
            unrealizedPnlSol = unrealized,
            realizedPnlSol = realized,
            feesSol = fees,
            totalEquitySol = equity,
            conservationDeltaSol = actual - expected,
        )
    }

    fun assertInvariant(toleranceSol: Double = 1e-4): Double {
        invariantChecks.incrementAndGet()
        val s = snapshot()
        lastDeltaMicros.set((s.conservationDeltaSol * 1_000_000.0).toLong())
        if (kotlin.math.abs(s.conservationDeltaSol) > toleranceSol) {
            invariantViolations.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "CANONICAL_CAPITAL_INVARIANT_VIOLATION_6450",
                    "delta=${"%.6f".format(s.conservationDeltaSol)} " +
                        "startCash=${"%.6f".format(s.startingCashSol)} " +
                        "cash=${"%.6f".format(s.cashSol)} " +
                        "reserved=${"%.6f".format(s.reservedSol)} " +
                        "openCost=${"%.6f".format(s.openCostBasisSol)} " +
                        "realized=${"%.6f".format(s.realizedPnlSol)} " +
                        "fees=${"%.6f".format(s.feesSol)} " +
                        "equity=${"%.6f".format(s.totalEquitySol)}",
                )
                PipelineHealthCollector.labelInc("CANONICAL_CAPITAL_INVARIANT_VIOLATION_6450")
            } catch (_: Throwable) {}
        }
        return s.conservationDeltaSol
    }

    fun statusLine(): String {
        val s = snapshot()
        return "cash=${"%.4f".format(s.cashSol)} reserved=${"%.4f".format(s.reservedSol)} " +
            "openMV=${"%.4f".format(s.openMarketValueSol)} unrealized=${"%.4f".format(s.unrealizedPnlSol)} " +
            "realized=${"%.4f".format(s.realizedPnlSol)} fees=${"%.4f".format(s.feesSol)} " +
            "equity=${"%.4f".format(s.totalEquitySol)} delta=${"%.6f".format(s.conservationDeltaSol)} " +
            "checks=${invariantChecks.get()} violations=${invariantViolations.get()}"
    }
}
