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
 *   CASH                — PaperCapitalAuthority6577.cashSol
 *   RESERVED            — sum of PENDING_ENTRY costSol
 *   OPEN_COST_BASIS     — canonical open cost, excluding reserved
 *   OPEN_MARKET_VALUE   — sum(currentMarkValue) via caller-supplied mark
 *   UNREALIZED_PNL      — OPEN_MARKET_VALUE - OPEN_COST_BASIS
 *   REALIZED_PNL        — PaperCapitalAuthority6577.realizedPnlSol
 *   FEES                — PaperCapitalAuthority6577.feesSol
 *   TOTAL_EQUITY        — CASH + RESERVED + OPEN_MARKET_VALUE
 *
 * The wallet UI MUST NOT display CASH as equity. Callers use snapshot().
 *
 * Invariant (checked every audit tick):
 *   startingCapital + realized - fees ≈ cash + reserved + openCostBasis
 *
 * V5.0.6681 — a capital read takes exactly ONE PaperCapitalAuthority snapshot.
 * The old implementation reacquired PaperAccountLedger6430.snapshotAtomic6643
 * once per field (start/cash/realized/fees/openCost). On the Android main
 * thread this multiplied lock contention and showed up directly in the ANR
 * sampler. One immutable facade snapshot supplies the entire ledger side.
 *
 * Legacy per-field reads are named here only as forbidden regression markers:
 * PaperCapitalAuthority6577.startingCashSol(), PaperCapitalAuthority6577.cashSol(),
 * PaperCapitalAuthority6577.realizedPnlSol(), PaperCapitalAuthority6577.feesSol(),
 * PaperCapitalAuthority6577.openCostBasisSol(). They must never re-enter snapshot().
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
        val staleMarkMints: Int = 0,
        val fallbackMarkMints: Int = 0,
        val authoritativeOpenMarketValueSol: Double = 0.0,
        val authoritativeEquitySol: Double = 0.0,
    )

    private val invariantChecks = AtomicLong(0L)
    private val invariantViolations = AtomicLong(0L)
    private val lastDeltaMicros = AtomicLong(0L)

    private val markProviderRef = java.util.concurrent.atomic.AtomicReference<((String) -> Double)?>(null)
    private data class GoodMark6492(val wholeMintValueSol: Double, val observedAtMs: Long)
    private val lastGoodMark6492 = java.util.concurrent.ConcurrentHashMap<String, GoodMark6492>()

    fun installMarkProvider(provider: (String) -> Double) {
        markProviderRef.set(provider)
        try { PipelineHealthCollector.labelInc("CAPITAL_MARK_PROVIDER_INSTALLED_6456") } catch (_: Throwable) {}
    }

    fun snapshot(markProvider: (String) -> Double = markProviderRef.get() ?: { 0.0 }): Snapshot {
        // V5.0.6681 — ONE ledger read, one immutable current-capital image.
        val paper = PaperCapitalAuthority6577.snapshot()
        val startingCash = paper.startingCashSol
        val cash = paper.availableCashSol
        val realized = paper.realizedPnlSol
        val fees = paper.feesSol
        val activeMints = try { CanonicalPositionAuthority6441.activeMintProjections6490("paper") } catch (_: Throwable) { emptyList() }
        val reserved = 0.0
        val openCost = paper.openMarketValueSol
        var staleMarkMints6492 = 0
        var fallbackMarkMints6492 = 0
        var authoritativeOpenMv6508 = 0.0
        var authoritativeOpenCost6508 = 0.0
        val activeMintSet6492 = activeMints.map { it.mint }.toSet()
        lastGoodMark6492.keys.removeIf { it !in activeMintSet6492 }
        val markedValue6492 = activeMints.sumOf { aggregate ->
            val fresh = try { markProvider(aggregate.mint) } catch (_: Throwable) { 0.0 }
            val costBasis6604 = aggregate.remainingCostBasisSol
            val SANITY_MULT_6604 = 100.0
            val perPositionInflated6604 = fresh.isFinite() && fresh > 0.0 &&
                costBasis6604 > 0.0 && fresh > costBasis6604 * SANITY_MULT_6604
            if (perPositionInflated6604) {
                try {
                    PipelineHealthCollector.labelInc("HERO_OPENMV_PER_POSITION_QUARANTINE_6604")
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "HERO_OPENMV_PER_POSITION_QUARANTINE_6604",
                        "mint=${aggregate.mint.take(10)} costBasis=${"%.6f".format(costBasis6604)} " +
                            "rawMark=${"%.6f".format(fresh)} ratio=${"%.1f".format(fresh / costBasis6604)}x " +
                            "action=treat_as_fallback_mark",
                    )
                } catch (_: Throwable) {}
                fallbackMarkMints6492++
                return@sumOf costBasis6604
            }
            when {
                fresh.isFinite() && fresh > 0.0 -> {
                    lastGoodMark6492[aggregate.mint] = GoodMark6492(fresh, System.currentTimeMillis())
                    authoritativeOpenMv6508 += fresh
                    authoritativeOpenCost6508 += aggregate.remainingCostBasisSol
                    fresh
                }
                lastGoodMark6492[aggregate.mint] != null -> {
                    staleMarkMints6492++
                    try { PipelineHealthCollector.labelInc("PAPER_MARK_STALE_LAST_GOOD_6508") } catch (_: Throwable) {}
                    lastGoodMark6492.getValue(aggregate.mint).wholeMintValueSol
                }
                else -> {
                    fallbackMarkMints6492++
                    try { PipelineHealthCollector.labelInc("PAPER_MARK_UNPRICED_6508") } catch (_: Throwable) {}
                    aggregate.remainingCostBasisSol
                }
            }
        }
        val openMvRaw6602 = if (activeMints.isEmpty() && openCost > 0.0) {
            fallbackMarkMints6492++
            try { PipelineHealthCollector.labelInc("CAPITAL_MARK_FALLBACK_NO_CANON_POSITION_6492") } catch (_: Throwable) {}
            openCost
        } else markedValue6492
        val SANITY_MULT_6602 = 100.0
        val openMv = if (openCost > 0.0 && openMvRaw6602 > openCost * SANITY_MULT_6602) {
            try {
                PipelineHealthCollector.labelInc("HERO_OPENMV_SANITY_CLAMP_6602")
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "HERO_OPENMV_SANITY_CLAMP_6602",
                    "openCost=${"%.4f".format(openCost)} openMvRaw=${"%.4f".format(openMvRaw6602)} " +
                        "ratio=${"%.1f".format(openMvRaw6602 / openCost)}x mints=${activeMints.size} " +
                        "action=clamp_to_costBasis",
                )
            } catch (_: Throwable) {}
            openCost
        } else openMvRaw6602
        if (staleMarkMints6492 > 0) try { PipelineHealthCollector.labelInc("CAPITAL_STALE_LAST_GOOD_MARK_6492") } catch (_: Throwable) {}
        val unrealized = authoritativeOpenMv6508 - authoritativeOpenCost6508
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
            staleMarkMints = staleMarkMints6492,
            fallbackMarkMints = fallbackMarkMints6492,
            authoritativeOpenMarketValueSol = authoritativeOpenMv6508,
            authoritativeEquitySol = cash + reserved + authoritativeOpenMv6508,
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
            "staleMarks=${s.staleMarkMints} fallbackMarks=${s.fallbackMarkMints} " +
            "checks=${invariantChecks.get()} violations=${invariantViolations.get()}"
    }
}
