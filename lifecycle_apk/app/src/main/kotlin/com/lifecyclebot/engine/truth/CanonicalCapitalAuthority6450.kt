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
        val staleMarkMints: Int = 0,
        val fallbackMarkMints: Int = 0,
        // V5.0.6508 §P0-3 — authoritative subset of openMarketValueSol
        // (fresh marks only; excludes stale/fallback held at basis).
        // Learners/rewards must consume this instead of openMarketValueSol
        // to avoid training on manufactured PnL from fallback marks.
        val authoritativeOpenMarketValueSol: Double = 0.0,
        val authoritativeEquitySol: Double = 0.0,
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
        // V5.0.6487 — PaperAccountLedger is the sole capital read authority.
        // Replay is parity diagnostics only and may never replace wallet surfaces.
        val startingCash = PaperAccountLedger6430.startingCashSol()
        val cash = PaperAccountLedger6430.cashSol()
        val realized = PaperAccountLedger6430.realizedPnlSol()
        val fees = PaperAccountLedger6430.feesSol()
        // V5.0.6489 — the mark provider returns WHOLE-MINT market value from
        // TokenState.position. Canonical storage may contain multiple economic lots
        // for one mint, so value each mint once; summing one provider value per lot
        // multiplied equity whenever historical same-mint lots coexisted.
        val activeMints = try { CanonicalPositionAuthority6441.activeMintProjections6490("paper") } catch (_: Throwable) { emptyList() }
        val reserved = 0.0 // no reserved event currently exists; remains explicit
        val openCost = PaperAccountLedger6430.openCostBasisSol()
        var staleMarkMints6492 = 0
        var fallbackMarkMints6492 = 0
        // V5.0.6508 §P0-3 — TRACK AUTHORITATIVE MARK VALUE SEPARATELY.
        // Operator mandate: fallback/stale marks MUST NOT manufacture
        // PnL for learning/reward. Sum only the fresh-marked slice so
        // downstream consumers can gate WR/EV/tactic training on
        // authoritativeOpenMv rather than the fallback-inflated total.
        var authoritativeOpenMv6508 = 0.0
        val activeMintSet6492 = activeMints.map { it.mint }.toSet()
        lastGoodMark6492.keys.removeIf { it !in activeMintSet6492 }
        val markedValue6492 = activeMints.sumOf { aggregate ->
            val fresh = try { markProvider(aggregate.mint) } catch (_: Throwable) { 0.0 }
            when {
                fresh.isFinite() && fresh > 0.0 -> {
                    lastGoodMark6492[aggregate.mint] = GoodMark6492(fresh, System.currentTimeMillis())
                    authoritativeOpenMv6508 += fresh
                    fresh
                }
                lastGoodMark6492[aggregate.mint] != null -> {
                    staleMarkMints6492++
                    try { PipelineHealthCollector.labelInc("PAPER_MARK_STALE_LAST_GOOD_6508") } catch (_: Throwable) {}
                    lastGoodMark6492.getValue(aggregate.mint).wholeMintValueSol
                }
                else -> {
                    fallbackMarkMints6492++
                    // Position held at entry basis, UNPRICED authoritatively.
                    try { PipelineHealthCollector.labelInc("PAPER_MARK_UNPRICED_6508") } catch (_: Throwable) {}
                    aggregate.remainingCostBasisSol
                }
            }
        }
        // A non-zero paper open cost with no paper position projection is an
        // explicit lifecycle mismatch, not a real -100% mark. Keep equity at
        // basis while the reconciler restores carry positions and surface it.
        val openMv = if (activeMints.isEmpty() && openCost > 0.0) {
            fallbackMarkMints6492++
            try { PipelineHealthCollector.labelInc("CAPITAL_MARK_FALLBACK_NO_CANON_POSITION_6492") } catch (_: Throwable) {}
            openCost
        } else markedValue6492
        if (staleMarkMints6492 > 0) try { PipelineHealthCollector.labelInc("CAPITAL_STALE_LAST_GOOD_MARK_6492") } catch (_: Throwable) {}
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
            staleMarkMints = staleMarkMints6492,
            fallbackMarkMints = fallbackMarkMints6492,
            authoritativeOpenMarketValueSol = authoritativeOpenMv6508,
            // V5.0.6508a — authoritative equity: cash + reserved +
            // AUTHORITATIVE openMV only (excludes stale/fallback marks).
            // Main UI hero uses this to avoid the +28400% start
            // impossibility that stale entry-basis marks manufactured.
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
