package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6577 §P0-1 — ONE CANONICAL PAPER CAPITAL AUTHORITY.
 *
 * OPERATOR DIRECTIVE (verbatim):
 *   "Paper mode must reproduce how LIVE mode behaves:
 *    ONE account.  ONE spendable cash balance.  ONE reservation ledger.
 *    ONE open-market-value calculation.  ONE equity calculation.
 *    ONE debit/credit path.
 *    All lanes must compete for the same capital.
 *    DO NOT create fixed pots. Capital allocation is supposed to remain fluid.
 *    Brains/risk/sizing may SHAPE allocation, but every actual reservation
 *    comes from the SAME account."
 *
 * FORENSIC EVIDENCE (6573):
 *   Main AATE       ~$1,190
 *   Markets         ~$931  PAPER
 *   Crypto Alt      ~$648
 *   Meme            ~1.000 SOL (local lane balance)
 *   Canonical       CASH=0.9081 SOL, OPEN=10.5901, EQUITY=11.4981
 *
 * Each UI surface computed a *different* balance from a *different* formula.
 * MEME could stop opening positions while its own screen still displayed
 * available funds. Traders never asked the canonical ledger — they trusted
 * an intermediate cache.
 *
 * DESIGN
 * ──────
 * PaperCapitalAuthority6577 is a THIN FACADE over the pre-existing
 * PaperAccountLedger6430 (which already holds atomic pico-SOL state,
 * conservation invariants, and durable persistence). We do NOT create a
 * parallel ledger. We provide a stable read API for every UI/trader:
 *
 *   snapshot()           → immutable PaperAccountSnapshot (cash / open / equity)
 *   availableCashSol()   → what a new reservation may spend
 *   openMarketValueSol() → cost basis of live positions
 *   totalEquitySol()     → cash + open
 *   probeUiCash(...)     → invariant check: any UI-computed cash that
 *                          diverges from the ledger by > toleranceSol emits
 *                          PAPER_UI_CASH_DIVERGENCE_6577 (target = 0).
 *
 * No hard partitioning. No lane-local wallet. If a lane stops opening
 * because the shared cash is exhausted, its UI displays the same shared
 * available cash — because it reads the same authority.
 */
object PaperCapitalAuthority6577 {

    data class PaperAccountSnapshot(
        val accountId: String,
        val availableCashSol: Double,
        val openMarketValueSol: Double,
        val realizedPnlSol: Double,
        val feesSol: Double,
        val totalEquitySol: Double,
        val startingCashSol: Double,
        val timestampMs: Long,
    ) {
        /** True when this snapshot represents a live authority (initialized ledger). */
        val initialized: Boolean get() = startingCashSol > 0.0
    }

    private const val ACCOUNT_ID = "aate.paper.default"

    private val uiDivergenceHits = AtomicLong(0L)
    private val debitWithoutReservation = AtomicLong(0L)
    private val equityConservationViolations = AtomicLong(0L)

    fun snapshot(): PaperAccountSnapshot {
        val ledger = try { PaperAccountLedger6430.snapshotAtomic6643() } catch (_: Throwable) { null }
        val cash = ledger?.cashSol ?: 0.0
        val open = ledger?.openCostBasisSol ?: 0.0
        val realized = ledger?.realizedPnlSol ?: 0.0
        val fees = ledger?.feesSol ?: 0.0
        val start = ledger?.startingCashSol ?: 0.0
        return PaperAccountSnapshot(
            accountId = ACCOUNT_ID,
            availableCashSol = cash,
            openMarketValueSol = open,
            realizedPnlSol = realized,
            feesSol = fees,
            totalEquitySol = cash + open,
            startingCashSol = start,
            timestampMs = ledger?.capturedAtMs ?: System.currentTimeMillis(),
        )
    }

    fun availableCashSol(): Double = snapshot().availableCashSol
    fun openMarketValueSol(): Double = snapshot().openMarketValueSol
    fun totalEquitySol(): Double = snapshot().totalEquitySol
    fun accountId(): String = ACCOUNT_ID

    // V5.0.6604 §PAPER_LEDGER_READ_UNIFICATION (operator P2 fix).
    //   The 6577 facade previously exposed only cash/open/equity reads,
    //   so the majority of consumers still bypassed it and called
    //   PaperAccountLedger6430 directly (54 direct call sites at the last
    //   audit). Add the remaining read-only accessors as thin delegations
    //   so every non-write caller can converge on the facade without
    //   changing behaviour. Writes (onBuy / onSell / rollback / repair /
    //   invariant assert) remain on the ledger — the facade is READ-ONLY.
    fun cashSol(): Double = snapshot().availableCashSol
    fun openCostBasisSol(): Double = snapshot().openMarketValueSol
    fun realizedPnlSol(): Double = snapshot().realizedPnlSol
    fun feesSol(): Double = snapshot().feesSol
    fun startingCashSol(): Double = snapshot().startingCashSol
    fun isAuthorityInitialized6489(): Boolean = try {
        PaperAccountLedger6430.isAuthorityInitialized6489()
    } catch (_: Throwable) { false }
    fun statusLineFromLedger6604(): String = try {
        PaperAccountLedger6430.statusLine()
    } catch (_: Throwable) { "" }

    /**
     * Invariant probe — every UI surface that computes a paper cash figure
     * must call this. Divergence beyond toleranceSol emits
     * PAPER_UI_CASH_DIVERGENCE_6577 for operator forensic. This is the
     * canonical check the operator asked for: PAPER_UI_CASH_DIVERGENCE = 0.
     */
    fun probeUiCash(surface: String, uiCashSol: Double, toleranceSol: Double = 0.001): Boolean {
        val canonical = availableCashSol()
        val delta = kotlin.math.abs(uiCashSol - canonical)
        if (delta > toleranceSol) {
            uiDivergenceHits.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("PAPER_UI_CASH_DIVERGENCE_6577")
                PipelineHealthCollector.labelInc("PAPER_UI_CASH_DIVERGENCE_${surface}_6577")
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "PAPER_UI_CASH_DIVERGENCE_6577",
                    "surface=$surface uiCashSol=${"%.4f".format(uiCashSol)} " +
                        "canonicalCashSol=${"%.4f".format(canonical)} delta=${"%.6f".format(delta)}"
                )
            } catch (_: Throwable) {}
            return false
        }
        return true
    }

    /**
     * Invariant probe — a paper buy debited the ledger without first passing
     * through the canonical reservation path. Emits
     * PAPER_DEBIT_WITHOUT_RESERVATION (target = 0).
     */
    fun probeDebitReservation(positionId: String, reserved: Boolean) {
        if (!reserved) {
            debitWithoutReservation.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("PAPER_DEBIT_WITHOUT_RESERVATION")
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "PAPER_DEBIT_WITHOUT_RESERVATION",
                    "positionId=$positionId"
                )
            } catch (_: Throwable) {}
        }
    }

    fun probeEquityConservation(equityDeltaSol: Double, toleranceSol: Double = 0.001) {
        if (kotlin.math.abs(equityDeltaSol) > toleranceSol) {
            equityConservationViolations.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("PAPER_EQUITY_CONSERVATION_VIOLATION")
            } catch (_: Throwable) {}
        }
    }

    fun invariantCounts(): Triple<Long, Long, Long> = Triple(
        uiDivergenceHits.get(),
        debitWithoutReservation.get(),
        equityConservationViolations.get(),
    )

    fun statusLine(): String {
        val s = snapshot()
        return "acct=${s.accountId} cash=${"%.4f".format(s.availableCashSol)} " +
            "open=${"%.4f".format(s.openMarketValueSol)} equity=${"%.4f".format(s.totalEquitySol)} " +
            "uiDivergence=${uiDivergenceHits.get()} debitNoResv=${debitWithoutReservation.get()} " +
            "equityViol=${equityConservationViolations.get()}"
    }

    internal fun resetInvariantsForTest() {
        uiDivergenceHits.set(0L); debitWithoutReservation.set(0L); equityConservationViolations.set(0L)
    }
}
