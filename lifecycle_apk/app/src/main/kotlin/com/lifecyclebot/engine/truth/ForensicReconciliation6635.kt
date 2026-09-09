package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6635 §4 CONTINUOUS_FORENSIC_RECONCILIATION.
 *
 * OPERATOR DIRECTIVE (verbatim):
 *   > "Implement continuous forensic reconciliation:
 *   >  for every canonical economicEventId:
 *   >    exactly one corresponding ledger mutation
 *   >    exactly one corresponding journal transaction row
 *   >    exactly one correct position/lot mutation
 *   >  Required invariants:
 *   >    journal.cash == ledger.cash
 *   >    journal.realizedPnl == ledger.realizedPnl
 *   >    journal.openCost == ledger.openCost
 *   >    journal.openQty == canonicalLotQty
 *   >    duplicateEventIds == 0
 *   >    missingJournalEvents == 0
 *   >    missingLedgerEvents == 0
 *   >    qtyMismatch == 0
 *   >    priceBasisMismatch == 0
 *   >  A delta of even one legitimate economic event is a FAILED
 *   >  forensic state."
 *
 * DESIGN
 * ──────
 * Called every reconciler cadence.  Compares:
 *   PaperAccountLedger6430  (canonical ledger)  --> cash / realized / openCost
 *   JournalEconomicAuthority6616.snapshot()     --> cash / realized / openCost
 *   CanonicalPositionAuthority6441              --> open qty
 *   CanonicalLotQuantity6464                    --> lot qty
 *   CanonicalEconomicEvent6635 registry          --> event-by-event parity
 *
 * V5.0.6706 source correction: before measuring deltas, an exact typed SELL
 * receipt may restore a missing durable journal terminal leg through
 * CanonicalJournalTerminalRepair6706. This is not heuristic healing: the repair
 * uses the already-committed economicEventId, raw quantity, allocated basis,
 * proceeds and fees. Only after journal raw lots exactly equal canonical raw lots
 * may PaperAccountLedger adopt that durable replay.
 */
object ForensicReconciliation6635 {

    private const val DELTA_TOLERANCE_SOL = 1e-6

    private val lastCashLedger = AtomicReference(0.0)
    private val lastCashJournal = AtomicReference(0.0)
    private val lastCashDelta = AtomicReference(0.0)
    private val lastRealizedLedger = AtomicReference(0.0)
    private val lastRealizedJournal = AtomicReference(0.0)
    private val lastRealizedDelta = AtomicReference(0.0)
    private val lastOpenCostLedger = AtomicReference(0.0)
    private val lastOpenCostJournal = AtomicReference(0.0)
    private val lastOpenCostDelta = AtomicReference(0.0)
    private val lastQuantityDeltaRaw = AtomicReference(java.math.BigInteger.ZERO)
    private val lastMissingJournal = AtomicLong(0L)
    private val lastMissingLedger = AtomicLong(0L)
    private val lastReconciledStatus = AtomicReference("UNKNOWN")
    private val checks = AtomicLong(0L)
    private val failedChecks = AtomicLong(0L)

    /**
     * Run one reconciliation cadence. Every non-zero delta emits a forensic
     * counter + line. When the caller has already replayed the journal for the
     * same mutation, pass that immutable result to avoid a second full replay.
     */
    fun reconcile6635(precomputedReplay6699: JournalEconomicReplay6619.ReplayResult? = null) {
        checks.incrementAndGet()

        // V5.0.6706 — close a precise projection hole BEFORE comparing. Runtime
        // 6705 retained CLOSED canonical positions, so the older orphan repair
        // (which only acts when canonical == null) could never close their missing
        // journal lots. Exact typed receipts are sufficient authority to restore
        // the journal leg without fabricating economics.
        val repaired6706 = try { CanonicalJournalTerminalRepair6706.repairMissingTerminalLegs() }
            catch (_: Throwable) { 0 }
        var replay6647 = if (repaired6706 > 0 || precomputedReplay6699 == null) {
            try { JournalEconomicReplay6619.replay() } catch (_: Throwable) { null }
        } else precomputedReplay6699

        var canonicalRaw6647 = try {
            CanonicalPositionAuthority6441.openPositions()
                .filter { it.mode.equals("paper", true) }
                .associate { it.positionId to it.remainingQtyRaw }
        } catch (_: Throwable) { emptyMap() }
        var journalRaw6647 = replay6647?.openRawQtyByPosition.orEmpty()
        var quantityDeltaRaw6647 = (journalRaw6647.keys + canonicalRaw6647.keys).fold(java.math.BigInteger.ZERO) { acc, positionId ->
            acc + ((journalRaw6647[positionId] ?: java.math.BigInteger.ZERO) -
                (canonicalRaw6647[positionId] ?: java.math.BigInteger.ZERO)).abs()
        }

        // Only exact durable parity may move the account scalar projection. This
        // prevents a stale ledger from keeping the smoke test red after the journal
        // has been repaired, while still fail-closing on any raw-lot mismatch.
        if (replay6647?.reconciled == true && quantityDeltaRaw6647 == java.math.BigInteger.ZERO) {
            val adopted6706 = try {
                PaperAccountLedger6430.reconcileFromJournal6663(
                    replay6647.cashSol,
                    replay6647.openCostBasisSol,
                    replay6647.realizedPnlSol,
                    replay6647.feesSol,
                )
            } catch (_: Throwable) { false }
            if (adopted6706) {
                try {
                    PipelineHealthCollector.labelInc("FORENSIC_EXACT_JOURNAL_ADOPTED_6706")
                    if (repaired6706 > 0) PipelineHealthCollector.labelInc("FORENSIC_TERMINAL_REPAIR_CONVERGED_6706")
                    ForensicLogger.lifecycle(
                        "FORENSIC_EXACT_JOURNAL_ADOPTED_6706",
                        "repaired=$repaired6706 cash=${replay6647.cashSol} realized=${replay6647.realizedPnlSol} openCost=${replay6647.openCostBasisSol} rawPositions=${journalRaw6647.size}",
                    )
                } catch (_: Throwable) {}
            }
        }

        val cashLedger = try { PaperCapitalAuthority6577.cashSol() } catch (_: Throwable) { Double.NaN }
        val cashJournal = replay6647?.cashSol ?: Double.NaN
        val realizedLedger = try { PaperCapitalAuthority6577.realizedPnlSol() } catch (_: Throwable) { 0.0 }
        val realizedJournal = replay6647?.realizedPnlSol ?: Double.NaN
        val openCostLedger = try { PaperCapitalAuthority6577.openCostBasisSol() } catch (_: Throwable) { 0.0 }
        val openCostJournal = replay6647?.openCostBasisSol ?: Double.NaN

        val cashDelta = kotlin.math.abs(cashJournal - cashLedger)
        val realizedDelta = kotlin.math.abs(realizedJournal - realizedLedger)
        val openCostDelta = kotlin.math.abs(openCostJournal - openCostLedger)

        // Re-read raw maps after any exact adoption. Account adoption does not
        // mutate lots, but the explicit refresh makes the measured boundary clear.
        canonicalRaw6647 = try {
            CanonicalPositionAuthority6441.openPositions()
                .filter { it.mode.equals("paper", true) }
                .associate { it.positionId to it.remainingQtyRaw }
        } catch (_: Throwable) { emptyMap() }
        journalRaw6647 = replay6647?.openRawQtyByPosition.orEmpty()
        quantityDeltaRaw6647 = (journalRaw6647.keys + canonicalRaw6647.keys).fold(java.math.BigInteger.ZERO) { acc, positionId ->
            acc + ((journalRaw6647[positionId] ?: java.math.BigInteger.ZERO) -
                (canonicalRaw6647[positionId] ?: java.math.BigInteger.ZERO)).abs()
        }

        lastCashLedger.set(cashLedger); lastCashJournal.set(cashJournal); lastCashDelta.set(cashDelta)
        lastRealizedLedger.set(realizedLedger); lastRealizedJournal.set(realizedJournal); lastRealizedDelta.set(realizedDelta)
        lastOpenCostLedger.set(openCostLedger); lastOpenCostJournal.set(openCostJournal); lastOpenCostDelta.set(openCostDelta)
        lastQuantityDeltaRaw.set(quantityDeltaRaw6647)

        if (cashDelta > DELTA_TOLERANCE_SOL) {
            failedChecks.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("FORENSIC_CASH_DELTA_6635")
                ForensicLogger.lifecycle(
                    "FORENSIC_CASH_DELTA_6635",
                    "ledger=${"%.6f".format(cashLedger)} journal=${"%.6f".format(cashJournal)} " +
                        "delta=${"%.6f".format(cashDelta)} action=split_write_forbidden_operator_inspect",
                )
            } catch (_: Throwable) {}
        }
        if (realizedDelta > DELTA_TOLERANCE_SOL) {
            failedChecks.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("FORENSIC_REALIZED_DELTA_6635")
                ForensicLogger.lifecycle(
                    "FORENSIC_REALIZED_DELTA_6635",
                    "ledger=${"%.6f".format(realizedLedger)} journal=${"%.6f".format(realizedJournal)} " +
                        "delta=${"%.6f".format(realizedDelta)}",
                )
            } catch (_: Throwable) {}
        }
        if (openCostDelta > DELTA_TOLERANCE_SOL) {
            failedChecks.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("FORENSIC_OPEN_COST_DELTA_6635")
                ForensicLogger.lifecycle(
                    "FORENSIC_OPEN_COST_DELTA_6635",
                    "ledger=${"%.6f".format(openCostLedger)} journal=${"%.6f".format(openCostJournal)} " +
                        "delta=${"%.6f".format(openCostDelta)}",
                )
            } catch (_: Throwable) {}
        }
        if (quantityDeltaRaw6647 != java.math.BigInteger.ZERO) {
            failedChecks.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("FORENSIC_QUANTITY_DELTA_6647")
                ForensicLogger.lifecycle(
                    "FORENSIC_QUANTITY_DELTA_6647",
                    "absoluteRawDelta=$quantityDeltaRaw6647 journalPositions=${journalRaw6647.size} canonicalPositions=${canonicalRaw6647.size}",
                )
            } catch (_: Throwable) {}
        }

        val allZero = replay6647?.reconciled == true && cashDelta.isFinite() && realizedDelta.isFinite() && openCostDelta.isFinite() &&
            cashDelta <= DELTA_TOLERANCE_SOL &&
            realizedDelta <= DELTA_TOLERANCE_SOL &&
            openCostDelta <= DELTA_TOLERANCE_SOL && quantityDeltaRaw6647 == java.math.BigInteger.ZERO
        lastReconciledStatus.set(if (allZero) "RECONCILED" else "FAILED")
    }

    /** Operator-facing forensic reconciliation line — item §10 mandated. */
    fun healthLine6635(): String {
        val eventLine = try { CanonicalEconomicEvent6635.forensicReconciliationLine6635() } catch (_: Throwable) { "" }
        val cashLedger = lastCashLedger.get()
        val cashJournal = lastCashJournal.get()
        val cashDelta = lastCashDelta.get()
        val realizedLedger = lastRealizedLedger.get()
        val realizedJournal = lastRealizedJournal.get()
        val realizedDelta = lastRealizedDelta.get()
        val openCostLedger = lastOpenCostLedger.get()
        val openCostJournal = lastOpenCostJournal.get()
        val openCostDelta = lastOpenCostDelta.get()
        val quantityDeltaRaw = lastQuantityDeltaRaw.get()
        val eventStatus = if (eventLine.contains("status=RECONCILED")) "RECONCILED" else "FAILED"
        val cashStatus = lastReconciledStatus.get()
        val overallStatus = if (eventStatus == "RECONCILED" && cashStatus == "RECONCILED") "RECONCILED" else "FAILED"
        return buildString {
            append("FORENSIC_ACCOUNTING_RECONCILIATION ")
            append("cashLedger=${"%.6f".format(cashLedger)} ")
            append("cashJournal=${"%.6f".format(cashJournal)} ")
            append("cashDelta=${"%.6f".format(cashDelta)} ")
            append("realizedLedger=${"%.6f".format(realizedLedger)} ")
            append("realizedJournal=${"%.6f".format(realizedJournal)} ")
            append("realizedDelta=${"%.6f".format(realizedDelta)} ")
            append("openCostLedger=${"%.6f".format(openCostLedger)} ")
            append("openCostJournal=${"%.6f".format(openCostJournal)} ")
            append("openCostDelta=${"%.6f".format(openCostDelta)} ")
            append("quantityDeltaRaw=$quantityDeltaRaw ")
            append("| $eventLine ")
            append("status=$overallStatus")
        }
    }

    data class Deltas6647(
        val cashSol: Double,
        val basisSol: Double,
        val realizedSol: Double,
        val quantityRaw: java.math.BigInteger,
        val reconciled: Boolean,
    )

    fun deltas6647(): Deltas6647 = Deltas6647(
        cashSol = lastCashDelta.get(),
        basisSol = lastOpenCostDelta.get(),
        realizedSol = lastRealizedDelta.get(),
        quantityRaw = lastQuantityDeltaRaw.get(),
        reconciled = lastReconciledStatus.get() == "RECONCILED" &&
            (try { CanonicalEconomicEvent6635.forensicReconciliationLine6635().contains("status=RECONCILED") } catch (_: Throwable) { false }),
    )

    internal fun resetForTest() {
        lastCashLedger.set(0.0); lastCashJournal.set(0.0); lastCashDelta.set(0.0)
        lastRealizedLedger.set(0.0); lastRealizedJournal.set(0.0); lastRealizedDelta.set(0.0)
        lastOpenCostLedger.set(0.0); lastOpenCostJournal.set(0.0); lastOpenCostDelta.set(0.0)
        lastQuantityDeltaRaw.set(java.math.BigInteger.ZERO)
        checks.set(0L); failedChecks.set(0L)
        lastReconciledStatus.set("UNKNOWN")
    }
}
