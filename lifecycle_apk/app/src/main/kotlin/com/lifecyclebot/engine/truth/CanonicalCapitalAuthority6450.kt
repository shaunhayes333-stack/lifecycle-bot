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
        // V5.0.6508 §P0-3 — authoritative subset of openMarketValueSol
        // (fresh marks only; excludes stale/fallback held at basis).
        // Learners/rewards must consume this instead of openMarketValueSol
        // to avoid training on manufactured PnL from fallback marks.
        val authoritativeOpenMarketValueSol: Double = 0.0,
        val authoritativeEquitySol: Double = 0.0,
        val unpricedOpenCostBasisSol: Double = 0.0,
        val inventoryCostDeltaSol: Double = 0.0,
        val fundedPositionCount: Int = 0,
        val economicallyValidMintCount: Int = 0,
        val valuationComplete: Boolean = false,
        val ledgerOperation: Long = 0L,
    )

    private val invariantChecks = AtomicLong(0L)
    private val invariantViolations = AtomicLong(0L)
    private val lastDeltaMicros = AtomicLong(0L) // *1e6, atomic-safe

    // V5.0.6456 §P0-#1 — install a real mark provider once at startup so
    // unrealized/equity/conservation reflect live prices. Consumers (bot
    // service / UI) call installMarkProvider() with a lambda that reads
    // the freshest available price for a mint from an in-memory cache.
    // Absent installation, we still fall back to costBasis to keep
    // unrealized as 0 (never a negative-100% phantom loss).
    private val markProviderRef = java.util.concurrent.atomic.AtomicReference<((String) -> Double)?>(null)
    private data class GoodMark6492(val wholeMintValueSol: Double, val observedAtMs: Long)
    private val lastGoodMark6492 = java.util.concurrent.ConcurrentHashMap<String, GoodMark6492>()

    fun installMarkProvider(provider: (String) -> Double) {
        markProviderRef.set(provider)
        try { PipelineHealthCollector.labelInc("CAPITAL_MARK_PROVIDER_INSTALLED_6456") } catch (_: Throwable) {}
    }

    /**
     * Compute the canonical snapshot. Caller supplies a mark provider that
     * returns current SOL market value for a mint (0.0 = mark unknown, use
     * costBasis fallback so unrealized reads as 0 rather than -100%).
     */
    fun snapshot(markProvider: (String) -> Double = markProviderRef.get() ?: { 0.0 }): Snapshot {
        // One account revision: never combine cash before a SELL with basis after it.
        val account = PaperAccountLedger6430.snapshotAtomic6643()
        val startingCash = account.startingCashSol
        val cash = account.cashSol
        val realized = account.realizedPnlSol
        val fees = account.feesSol
        val reserved = account.reservedCashSol
        val openCost = account.openCostBasisSol
        val funded = CanonicalPositionAuthority6441.fundedPositions6737("paper")
        val activeMints = CanonicalPositionAuthority6441.activeMintProjections6490("paper")
        val projectedCost = activeMints.sumOf { it.remainingCostBasisSol }
        val fundedCost = funded.sumOf { (it.entryCostSol - it.soldCostBasisSol).coerceAtLeast(0.0) }
        val missingProjectedBasis = (openCost - projectedCost).coerceAtLeast(0.0)
        val inventoryDelta = openCost - fundedCost
        var fallback = 0
        var stale = 0
        var unpricedBasis = missingProjectedBasis
        var authoritativeMv = 0.0
        var authoritativeCost = 0.0
        val activeMintSet = activeMints.map { it.mint }.toSet()
        lastGoodMark6492.keys.removeIf { it !in activeMintSet }
        val markedValue = activeMints.sumOf { aggregate ->
            val value = try { markProvider(aggregate.mint) } catch (_: Throwable) { 0.0 }
            val basis = aggregate.remainingCostBasisSol
            val valid = value.isFinite() && value > 0.0 && basis > 0.0 && value <= basis * 100.0
            if (valid) {
                authoritativeMv += value
                authoritativeCost += basis
                lastGoodMark6492[aggregate.mint] = GoodMark6492(value, System.currentTimeMillis())
                value
            } else {
                if (lastGoodMark6492.containsKey(aggregate.mint)) stale++
                fallback++
                unpricedBasis += basis
                // An unavailable mark cannot erase paid principal or preserve a stale
                // whole-position profit after a partial close. Basis is an explicitly
                // UNPRICED estimate, never a realized fill or a training reward.
                if (value.isFinite() && basis > 0.0 && value > basis * 100.0) try {
                    PipelineHealthCollector.labelInc("HERO_OPENMV_PER_POSITION_QUARANTINE_6604")
                } catch (_: Throwable) {}
                basis
            }
        }
        if (missingProjectedBasis > 1e-9) {
            fallback++
            try {
                PipelineHealthCollector.labelInc("CAPITAL_UNPRICED_FUNDED_BASIS_6737")
            } catch (_: Throwable) {}
        }
        // Include missing basis even when SOME valid positions remain. The previous
        // all-or-nothing fallback dropped 4.0581 SOL from the supplied 6735 snapshot.
        val openMv = markedValue + missingProjectedBasis
        val equity = cash + reserved + openMv
        val accountStable = PaperAccountLedger6430.snapshotAtomic6643().operationCount == account.operationCount
        val complete = fallback == 0 && stale == 0 && kotlin.math.abs(inventoryDelta) <= 1e-7 &&
            kotlin.math.abs(projectedCost - openCost) <= 1e-7 && accountStable
        return Snapshot(
            startingCashSol = startingCash, cashSol = cash, reservedSol = reserved,
            openCostBasisSol = openCost, openMarketValueSol = openMv,
            unrealizedPnlSol = openMv - openCost,
            realizedPnlSol = realized, feesSol = fees, totalEquitySol = equity,
            conservationDeltaSol = cash + reserved + openCost - (startingCash + realized - fees),
            staleMarkMints = stale, fallbackMarkMints = fallback,
            authoritativeOpenMarketValueSol = authoritativeMv,
            authoritativeEquitySol = cash + reserved + authoritativeMv,
            unpricedOpenCostBasisSol = unpricedBasis, inventoryCostDeltaSol = inventoryDelta,
            fundedPositionCount = funded.size, economicallyValidMintCount = activeMints.size,
            valuationComplete = complete, ledgerOperation = account.operationCount,
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
            "unpricedBasis=${"%.6f".format(s.unpricedOpenCostBasisSol)} inventoryDelta=${"%.6f".format(s.inventoryCostDeltaSol)} " +
            "funded=${s.fundedPositionCount} pricedEligibleMints=${s.economicallyValidMintCount} valuationComplete=${s.valuationComplete} ledgerOp=${s.ledgerOperation} " +
            "checks=${invariantChecks.get()} violations=${invariantViolations.get()}"
    }
}
