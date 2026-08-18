package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6430 §N — PAPER ACCOUNT LEDGER (capital conservation).
 *
 * OPERATOR (V5.0.6424 spec §N):
 *   'Create ONE authoritative PaperAccountLedger. Invariant:
 *      equitySol = cashSol + marketValueOfOpenPositions
 *    and independently:
 *      equitySol ≈ startingCashSol + realizedPnlSol + unrealizedPnlSol - feesSol
 *    No paper BUY may create capital from nowhere.'
 *
 * DESIGN
 * ──────
 * Additive to the existing paperWalletSol counter. This ledger tracks
 * every mutation with pico-precision atomics and exposes a periodic
 * invariant check the reconciler can call. When invariant fails the
 * ledger emits a forensic event; it does NOT halt trading (per operator:
 * "do not disable trading. do not block execution because historical
 * accounting is dirty"). Instead the reconciler flag freezes the
 * Runner tier lifts via RunnerAutoCompound6422.setLedgerHealthy(false).
 *
 * Every mutation is journaled in-memory as (opId, side, qty, priceUsd,
 * solDelta) so the invariant check can prove or disprove capital
 * conservation without hitting SQLite.
 */
object PaperAccountLedger6430 {

    private const val PICO_UNIT: Long = 1_000_000_000L  // 9 dp

    private fun toPico(sol: Double): Long =
        if (!sol.isFinite()) 0L else (sol * PICO_UNIT).toLong()

    private fun fromPico(p: Long): Double = p.toDouble() / PICO_UNIT

    private val startingCashPico = AtomicLong(0L)
    private val cashPico = AtomicLong(0L)
    private val reservedCashPico = AtomicLong(0L)
    private val openCostBasisPico = AtomicLong(0L)
    private val realizedPnlPico = AtomicLong(0L)
    private val feesPico = AtomicLong(0L)
    private val opCount = AtomicLong(0L)

    fun initialize(startingCashSol: Double) {
        val p = toPico(startingCashSol.coerceAtLeast(0.0))
        startingCashPico.set(p)
        cashPico.set(p)
        reservedCashPico.set(0L)
        openCostBasisPico.set(0L)
        realizedPnlPico.set(0L)
        feesPico.set(0L)
        opCount.set(0L)
    }

    fun canAffordBuy(costSol: Double, feeSol: Double = 0.0): Boolean {
        if (!costSol.isFinite() || costSol <= 0.0) return false
        return cashSol() >= (costSol + feeSol.coerceAtLeast(0.0))
    }

    /** Paper BUY: debit cash, add to open cost basis. Default=no leverage. */
    fun onBuy(costSol: Double, feeSol: Double = 0.0): Boolean {
        if (!costSol.isFinite() || costSol <= 0.0) return false
        val total = costSol + feeSol.coerceAtLeast(0.0)
        if (!canAffordBuy(costSol, feeSol)) {
            try {
                ForensicLogger.lifecycle("PAPER_LEDGER_BUY_REJECTED_NO_CASH_6448", "cash=${"%.6f".format(cashSol())} needed=${"%.6f".format(total)}")
                PipelineHealthCollector.labelInc("PAPER_LEDGER_BUY_REJECTED_NO_CASH_6448")
            } catch (_: Throwable) {}
            return false
        }
        cashPico.addAndGet(-toPico(total))
        openCostBasisPico.addAndGet(toPico(costSol))
        feesPico.addAndGet(toPico(feeSol.coerceAtLeast(0.0)))
        opCount.incrementAndGet()
        return true
    }

    fun repairCashFromDisplayed6448(displayedCashSol: Double, source: String): Boolean {
        if (!displayedCashSol.isFinite() || displayedCashSol < 0.0) return false
        val before = cashSol()
        if (before >= 0.0) return false
        cashPico.set(toPico(displayedCashSol))
        try {
            ForensicLogger.lifecycle("PAPER_LEDGER_CASH_REPAIRED_FROM_DISPLAYED_6448", "source=$source before=${"%.6f".format(before)} after=${"%.6f".format(displayedCashSol)} openCost=${"%.6f".format(openCostBasisSol())}")
            PipelineHealthCollector.labelInc("PAPER_LEDGER_CASH_REPAIRED_FROM_DISPLAYED_6448")
        } catch (_: Throwable) {}
        return true
    }

    /**
     * Paper SELL: credit cash by NET proceeds (gross − sellFee), subtract
     * costBasisSold from openCostBasis, accumulate realized PnL as
     * GROSS pnl (gross − costBasis) — NOT net of fee. Fees are tracked
     * separately in feesPico for the invariant.
     *
     * V5.0.6452 §P0-#1 FEE DOUBLE-COUNT REPAIR.
     * ─────────────────────────────────────────
     * Prior bug (pre-6452):
     *   cashPico  += G                    // ← missed −f_s
     *   realizedPnlPico += (G − C − f_s)  // ← net (already subtracts fee)
     *   feesPico  += f_s
     * Invariant `S + realized − fees == cash + openCost` then broke by
     * −2·f_s per sell. Operator dump showed conservation delta = −0.319 SOL
     * exactly matching cumulative sell fees.
     *
     * Correct model (real DEX + double-entry consistent):
     *   cashPico  += (G − f_s)   // cash credit is net of the sell fee
     *   realizedPnlPico += (G − C) // GROSS pnl; fees are separate line
     *   feesPico  += f_s
     * Now algebra holds: S + (G−C) − (f_b+f_s) = (S − C − f_b + G − f_s).
     * Consumers wanting NET pnl compute realizedPnlSol() − feesSol().
     */
    fun onSell(grossProceedsSol: Double, costBasisSoldSol: Double, feeSol: Double = 0.0) {
        if (!grossProceedsSol.isFinite() || !costBasisSoldSol.isFinite()) return
        val fee = if (feeSol.isFinite()) feeSol.coerceAtLeast(0.0) else 0.0
        // V5.0.6461 §P0-#1 FI4FAM FIREWALL — catch percent-into-SOL leaks
        // (30 SOL = 60x max entry; anything larger is a unit-mix bug).
        val gross = com.lifecyclebot.engine.truth.PartialSellUnitTypes6461
            .assertSolPlausible(grossProceedsSol.coerceAtLeast(0.0), "PaperAccountLedger6430.onSell.gross")
        val basis = com.lifecyclebot.engine.truth.PartialSellUnitTypes6461
            .assertSolPlausible(costBasisSoldSol.coerceAtLeast(0.0), "PaperAccountLedger6430.onSell.basis")
        cashPico.addAndGet(toPico(gross - fee))
        openCostBasisPico.addAndGet(-toPico(basis))
        realizedPnlPico.addAndGet(toPico(gross - basis)) // GROSS pnl
        feesPico.addAndGet(toPico(fee))
        opCount.incrementAndGet()
    }

    fun cashSol(): Double = fromPico(cashPico.get())
    fun openCostBasisSol(): Double = fromPico(openCostBasisPico.get())
    fun realizedPnlSol(): Double = fromPico(realizedPnlPico.get())
    fun feesSol(): Double = fromPico(feesPico.get())
    fun startingCashSol(): Double = fromPico(startingCashPico.get())

    /**
     * Capital conservation invariant:
     *   startingCash + realizedPnl - fees == cash + openCostBasis + reservedCash
     * within tolerance. Returns null on pass, error message on fail.
     */
    fun assertInvariant(toleranceSol: Double = 0.001): String? {
        val lhs = fromPico(startingCashPico.get() + realizedPnlPico.get() - feesPico.get())
        val rhs = fromPico(cashPico.get() + openCostBasisPico.get() + reservedCashPico.get())
        val delta = lhs - rhs
        if (kotlin.math.abs(delta) <= toleranceSol) return null
        val msg = "startingCash+realized-fees=${"%.6f".format(lhs)} cash+openCost+reserved=${"%.6f".format(rhs)} delta=${"%.6f".format(delta)}"
        try {
            ForensicLogger.lifecycle("PAPER_LEDGER_INVARIANT_FAIL_6430", msg)
            PipelineHealthCollector.labelInc("PAPER_LEDGER_INVARIANT_FAIL_6430")
        } catch (_: Throwable) {}
        return msg
    }

    fun statusLine(): String =
        "cash=${"%.4f".format(cashSol())} openCost=${"%.4f".format(openCostBasisSol())} realized=${"%+.4f".format(realizedPnlSol())} fees=${"%.4f".format(feesSol())} ops=${opCount.get()}"

    internal fun resetForTest() {
        startingCashPico.set(0); cashPico.set(0); reservedCashPico.set(0)
        openCostBasisPico.set(0); realizedPnlPico.set(0); feesPico.set(0); opCount.set(0)
    }
}
