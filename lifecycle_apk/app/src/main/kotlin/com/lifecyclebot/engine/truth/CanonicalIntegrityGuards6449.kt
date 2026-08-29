package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6449 §2/§3 — SELL QTY SOURCE LOCK + PAPER CASH CONSERVATION AUDIT.
 *
 * §3 SELL QTY SOURCE LOCK
 * ───────────────────────
 * Operator: KMNo3n buy=10.495 sell=19.535 — sold ~2x what was bought.
 * `clampToRemaining(mint, requestedQtyToken)` returns the sell qty
 * clamped to `CanonicalPositionAuthority6441.remainingQtyRaw` so
 * oversell is structurally impossible. Any executor sell call that
 * ignores this returns the same value — but callers that DO consult
 * this get the guarantee.
 *
 * §2 PAPER CASH CONSERVATION AUDIT
 * ───────────────────────────────
 * Operator: startingCash+realized-fees=15.588728 vs
 *           cash+openCost+reserved=16.398142 delta=-0.809414.
 * `auditConservation(startingCash)` compares canonical state to the
 * expected identity and returns the delta + first offending mint if
 * any mutation history exists. Emits CAPITAL_CONSERVATION_VIOLATION_6449
 * when |delta| > 1e-4.
 */
object CanonicalIntegrityGuards6449 {

    private val clampCount = AtomicLong(0L)
    private val oversellPrevented = AtomicLong(0L)
    private val conservationChecks = AtomicLong(0L)
    private val conservationViolations = AtomicLong(0L)

    /**
     * Clamp a requested sell qty to canonical remaining. Returns:
     *   - 0.0 if position unknown/closed/quarantined in canonical
     *   - min(requestedQtyToken, canonicalRemainingQtyToken) otherwise
     */
    /**
     * Strict clamp result — no fail-open on UNKNOWN.
     * (V5.0.6452 §P0-#8) Unknown canonical sell state must NOT fail-open to
     * the full requested quantity. Callers get a distinct UNKNOWN return
     * and must handle it explicitly (typically: skip the sell and force a
     * canonical reconstruct on the next cycle).
     */
    enum class ClampReason { OK, CLAMPED, CLOSED_OR_QUARANTINED, UNKNOWN_POSITION }
    data class ClampResult(val qtyToken: Double, val reason: ClampReason)

    fun clampToRemainingStrict(mint: String, requestedQtyToken: Double, tokenDecimals: Int = 9): ClampResult {
        clampCount.incrementAndGet()
        val positionId = ExecutorCanonicalMirror6442.positionIdOf(mint)
        val pos = CanonicalPositionAuthority6441.getPosition(positionId)
        if (pos == null) {
            try {
                ForensicLogger.lifecycle(
                    "SELL_QTY_UNKNOWN_POSITION_6452",
                    "mint=${mint.take(10)} requested=${"%.6f".format(requestedQtyToken)}",
                )
                PipelineHealthCollector.labelInc("SELL_QTY_UNKNOWN_POSITION_6452")
            } catch (_: Throwable) {}
            return ClampResult(0.0, ClampReason.UNKNOWN_POSITION)
        }
        if (pos.lifecycle == CanonicalPositionAuthority6441.Lifecycle.CLOSED ||
            pos.lifecycle == CanonicalPositionAuthority6441.Lifecycle.QUARANTINED) {
            oversellPrevented.incrementAndGet()
            try { PipelineHealthCollector.labelInc("SELL_QTY_OVERSELL_PREVENTED_6449") } catch (_: Throwable) {}
            return ClampResult(0.0, ClampReason.CLOSED_OR_QUARANTINED)
        }
        val scale = Math.pow(10.0, tokenDecimals.toDouble())
        val remainingQtyToken = pos.remainingQtyRaw.toDouble() / scale
        val clamped = kotlin.math.min(requestedQtyToken, remainingQtyToken).coerceAtLeast(0.0)
        if (clamped < requestedQtyToken) {
            oversellPrevented.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "SELL_QTY_OVERSELL_PREVENTED_6449",
                    "mint=${mint.take(10)} requested=${"%.6f".format(requestedQtyToken)} " +
                        "canonicalRemaining=${"%.6f".format(remainingQtyToken)} clamped=${"%.6f".format(clamped)}",
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("SELL_QTY_OVERSELL_PREVENTED_6449") } catch (_: Throwable) {}
            return ClampResult(clamped, ClampReason.CLAMPED)
        }
        return ClampResult(clamped, ClampReason.OK)
    }

    /**
     * Compat wrapper — preserved for existing callers. NOTE: this still
     * fail-opens on UNKNOWN by returning the requested qty; new sell paths
     * should call clampToRemainingStrict and handle UNKNOWN_POSITION
     * explicitly (V5.0.6452 §P0-#8).
     */
    fun clampToRemaining(mint: String, requestedQtyToken: Double, tokenDecimals: Int = 9): Double {
        clampCount.incrementAndGet()
        val positionId = ExecutorCanonicalMirror6442.positionIdOf(mint)
        val pos = CanonicalPositionAuthority6441.getPosition(positionId) ?: return requestedQtyToken
        if (pos.lifecycle == CanonicalPositionAuthority6441.Lifecycle.CLOSED ||
            pos.lifecycle == CanonicalPositionAuthority6441.Lifecycle.QUARANTINED) {
            oversellPrevented.incrementAndGet()
            try { PipelineHealthCollector.labelInc("SELL_QTY_OVERSELL_PREVENTED_6449") } catch (_: Throwable) {}
            return 0.0
        }
        val scale = Math.pow(10.0, tokenDecimals.toDouble())
        val remainingQtyToken = pos.remainingQtyRaw.toDouble() / scale
        val clamped = kotlin.math.min(requestedQtyToken, remainingQtyToken)
        if (clamped < requestedQtyToken) {
            oversellPrevented.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "SELL_QTY_OVERSELL_PREVENTED_6449",
                    "mint=${mint.take(10)} requested=${"%.6f".format(requestedQtyToken)} " +
                        "canonicalRemaining=${"%.6f".format(remainingQtyToken)} clamped=${"%.6f".format(clamped)}",
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("SELL_QTY_OVERSELL_PREVENTED_6449") } catch (_: Throwable) {}
        }
        return clamped.coerceAtLeast(0.0)
    }

    /**
     * Audit paper cash conservation using PaperAccountLedger6430 (the
     * authoritative cash ledger, wired at every paper BUY/SELL). Returns
     * delta between expected and actual; emits
     * CAPITAL_CONSERVATION_VIOLATION_6449 with the first-offending mint
     * attribution on breach.
     */
    fun auditConservation(toleranceSol: Double = 1e-4): Double {
        conservationChecks.incrementAndGet()
        val startingCash = PaperCapitalAuthority6577.startingCashSol()
        val cash = PaperCapitalAuthority6577.cashSol()
        val openCost = PaperCapitalAuthority6577.openCostBasisSol()
        val realized = PaperCapitalAuthority6577.realizedPnlSol()
        val fees = PaperCapitalAuthority6577.feesSol()
        val expected = startingCash + realized - fees
        val actual = cash + openCost
        val delta = actual - expected
        if (kotlin.math.abs(delta) > toleranceSol) {
            conservationViolations.incrementAndGet()
            val open = try { CanonicalPositionAuthority6441.openPositions() } catch (_: Throwable) { emptyList() }
            // First offending mint = the open position whose entryCost is
            // furthest from expected (or first open if all healthy). Best-
            // effort attribution to accelerate operator diagnosis.
            val firstOpen = open.maxByOrNull { it.entryCostSol - it.soldCostBasisSol }?.mint?.take(10) ?: "none"
            try {
                ForensicLogger.lifecycle(
                    "CAPITAL_CONSERVATION_VIOLATION_6449",
                    "expected=${"%.6f".format(expected)} actual=${"%.6f".format(actual)} " +
                        "delta=${"%.6f".format(delta)} startingCash=${"%.6f".format(startingCash)} " +
                        "cash=${"%.6f".format(cash)} openCost=${"%.6f".format(openCost)} " +
                        "realized=${"%.6f".format(realized)} fees=${"%.6f".format(fees)} " +
                        "openN=${open.size} firstOpen=$firstOpen",
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("CAPITAL_CONSERVATION_VIOLATION_6449") } catch (_: Throwable) {}
        }
        return delta
    }

    fun statusLine(): String =
        "clamps=${clampCount.get()} oversellPrevented=${oversellPrevented.get()} " +
            "conservationChecks=${conservationChecks.get()} conservationViolations=${conservationViolations.get()}"
}
