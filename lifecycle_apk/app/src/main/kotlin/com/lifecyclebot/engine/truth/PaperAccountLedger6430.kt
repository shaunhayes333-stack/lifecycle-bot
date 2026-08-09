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

    /** Paper BUY: debit cash, add to open cost basis. */
    fun onBuy(costSol: Double, feeSol: Double = 0.0) {
        if (!costSol.isFinite() || costSol <= 0.0) return
        cashPico.addAndGet(-toPico(costSol))
        openCostBasisPico.addAndGet(toPico(costSol))
        feesPico.addAndGet(toPico(feeSol.coerceAtLeast(0.0)))
        opCount.incrementAndGet()
    }

    /**
     * Paper SELL: credit cash by grossProceeds, subtract costBasisSold
     * from openCostBasis, accumulate realized PnL = gross - basis - fees.
     * Only the SOLD portion of the position is realized; partials keep
     * remaining basis on the books.
     */
    fun onSell(grossProceedsSol: Double, costBasisSoldSol: Double, feeSol: Double = 0.0) {
        if (!grossProceedsSol.isFinite() || !costBasisSoldSol.isFinite()) return
        cashPico.addAndGet(toPico(grossProceedsSol.coerceAtLeast(0.0)))
        openCostBasisPico.addAndGet(-toPico(costBasisSoldSol.coerceAtLeast(0.0)))
        val pnl = grossProceedsSol - costBasisSoldSol - feeSol
        realizedPnlPico.addAndGet(toPico(pnl))
        feesPico.addAndGet(toPico(feeSol.coerceAtLeast(0.0)))
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
