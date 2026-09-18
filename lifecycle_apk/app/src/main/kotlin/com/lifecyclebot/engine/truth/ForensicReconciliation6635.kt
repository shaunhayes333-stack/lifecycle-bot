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
 * Fires strict deltas:
 *   FORENSIC_CASH_DELTA_6635           = |journal.cash - ledger.cash|
 *   FORENSIC_REALIZED_DELTA_6635       = |journal.realized - ledger.realized|
 *   FORENSIC_OPEN_COST_DELTA_6635      = |journal.openCost - ledger.openCost|
 *   FORENSIC_MISSING_JOURNAL_6635      = count(events with LEDGER but !JOURNAL)
 *   FORENSIC_MISSING_LEDGER_6635       = count(events with JOURNAL but !LEDGER)
 *   FORENSIC_DUPLICATE_EVENT_ID_6635   = distinct opens same eventId
 *
 * Any non-zero counter above is a FAILED forensic state and the
 * operator's health line shows status=FAILED.  Never healed by this
 * module — the operator inspects and repairs the source.
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
        val cashLedger = try { PaperCapitalAuthority6577.cashSol() } catch (_: Throwable) { Double.NaN }
        val replay6647 = precomputedReplay6699 ?: try { JournalEconomicReplay6619.replay() } catch (_: Throwable) { null }
        // V5.0.6750 §ACCOUNTING_ERROR_UI_ROOT_CAUSE — operator screenshot
        //   Feb 2026: "ACCOUNTING ERROR" / "ACCOUNT UNAVAILABLE"
        //   painted across every hero surface even during otherwise
        //   healthy runtime.
        // Root cause: UnifiedAccountSnapshot6635.forSurface() calls
        // this reconciler on the UI main thread. JournalEconomicReplay
        // 6619 correctly declines to run its full replay on the main
        // thread (it would block the frame) and returns a synthetic
        // result with invariantFailures=[MAIN_THREAD_REPLAY_DEFERRED]
        // and, on first boot before any background pass has completed,
        // reconciled=false. That would drop lastReconciledStatus to
        // FAILED even though the ledger is fine — we simply couldn't
        // verify it from the UI thread.
        // Fix: when the replay was intentionally deferred (not a real
        // event-stream failure), skip the status downgrade. The next
        // background reconciler pass (Executor/BotService cadence)
        // supplies the real precomputedReplay6699 and drives the
        // status normally. Deltas from the deferred sample are still
        // recorded diagnostically but do NOT drive the failure flag.
        val replayDeferredOnMainThread6750 = replay6647?.invariantFailures?.contains("MAIN_THREAD_REPLAY_DEFERRED") == true
        // V5.0.6750 §BOOT_TRIVIAL_RECONCILED — before any trade has
        // been recorded the ledger and journal are trivially in sync
        // (both hold only the starting balance). Boot-time WARMUP
        // must not paint ACCOUNTING ERROR on a hero that has never
        // seen a trade.
        val ledgerInitialized6750 = try { PaperCapitalAuthority6577.isAuthorityInitialized6489() } catch (_: Throwable) { false }
        val noJournalActivity6750 = (replay6647?.paperRows ?: 0) == 0
        if (ledgerInitialized6750 && noJournalActivity6750 && !replayDeferredOnMainThread6750) {
            lastReconciledStatus.set("RECONCILED")
            try { PipelineHealthCollector.labelInc("FORENSIC_RECONCILE_BOOT_TRIVIAL_6750") } catch (_: Throwable) {}
            return
        }
        val cashJournal = replay6647?.cashSol ?: Double.NaN
        val realizedLedger = try { PaperCapitalAuthority6577.realizedPnlSol() } catch (_: Throwable) { 0.0 }
        val realizedJournal = replay6647?.realizedPnlSol ?: Double.NaN
        val openCostLedger = try { PaperCapitalAuthority6577.openCostBasisSol() } catch (_: Throwable) { 0.0 }
        val openCostJournal = replay6647?.openCostBasisSol ?: Double.NaN

        val cashDelta = kotlin.math.abs(cashJournal - cashLedger)
        val realizedDelta = kotlin.math.abs(realizedJournal - realizedLedger)
        val openCostDelta = kotlin.math.abs(openCostJournal - openCostLedger)
        val journalRaw6647 = replay6647?.openRawQtyByPosition.orEmpty()
        val canonicalRaw6647 = try {
            CanonicalPositionAuthority6441.openPositions()
                .filter { it.mode.equals("paper", true) }
                .associate { it.positionId to it.remainingQtyRaw }
        } catch (_: Throwable) { emptyMap() }
        val quantityDeltaRaw6647 = (journalRaw6647.keys + canonicalRaw6647.keys).fold(java.math.BigInteger.ZERO) { acc, positionId ->
            acc + ((journalRaw6647[positionId] ?: java.math.BigInteger.ZERO) -
                (canonicalRaw6647[positionId] ?: java.math.BigInteger.ZERO)).abs()
        }
        // V5.0.6912 §NAME_THE_DIVERGENT_POSITION_NOT_JUST_THE_DELTA.
        //
        // OPERATOR EVIDENCE (5.0.6909), 582 occurrences each:
        //   FORENSIC_CASH_DELTA_6635      ledger=2.142721 journal=1.506327 delta=0.636394
        //   FORENSIC_OPEN_COST_DELTA_6635 ledger=9.165980 journal=9.825552 delta=0.659572
        //   FORENSIC_REALIZED_DELTA_6635  ledger=-0.299753 journal=-0.278174 delta=0.021579
        //   FORENSIC_QUANTITY_DELTA_6647  absoluteRawDelta=1000000000
        //                                 journalPositions=81 canonicalPositions=80
        //
        // The three money deltas reconcile against each other
        // (0.659572 - 0.021579 ~= 0.636394), so this is one position present in
        // the journal and absent from canonical inventory, carrying ~0.66 SOL of
        // open cost. The authority already holds BOTH position sets right here
        // and reduces them to a single scalar, so 582 log lines said "they
        // differ" and not one said which row. Meanwhile PositionParity6464's
        // replay reports openCostD=-0.0000 with orphanLots=0 — two reconcilers,
        // two answers, and neither names a subject.
        //
        // Diff the key sets and name them. Pure diagnostics: no mutation, no
        // healing, no gating. The counters and status remain exactly as before.
        // V5.0.7018 §THE_DIFF_WAS_REPORTING_THE_RULES_NOT_THE_DATA.
        //
        // canonicalRaw6647 is built from openPositions(), which filters to
        // OPEN-with-quantity. QUARANTINED positions are deliberately excluded
        // from that set and are still present in the journal replay — so every
        // quarantined position appeared here as a "journal-only divergence".
        // The two sides were built with different inclusion rules and the diff
        // reported the difference in the rules.
        //
        // The operator's 5.0.7012 snapshot says so twice in one report:
        //   FORENSIC_POSITION_SET_DIVERGENCE_6912 journalOnly=17 canonicalOnly=0
        //   Canonical positions (§6441) sumCheck ... QUARANTINED=17
        // Same seventeen. Seventeen ALT rows that canonical knows about, has
        // quarantined on purpose, and this reconciler was calling missing.
        //
        // Split them out and name them for what they are. This does NOT net
        // the money away: a quarantined position's basis really does sit in the
        // journal and not in the ledger, so the cash/realized/openCost deltas
        // above stay exactly as they were and the operator still sees them.
        // What changes is that the position-set alarm stops crying "split
        // write" about rows that were quarantined by design, so a genuine
        // split write is visible again instead of buried in seventeen.
        val quarantined7018 = try {
            CanonicalPositionAuthority6441.quarantinedPositionIds6635("paper")
        } catch (_: Throwable) { emptySet() }
        val journalOnlyAll7018 = journalRaw6647.keys - canonicalRaw6647.keys
        val journalOnlyQuarantined7018 = journalOnlyAll7018 intersect quarantined7018
        val journalOnly6912 = journalOnlyAll7018 - quarantined7018
        val canonicalOnly6912 = canonicalRaw6647.keys - journalRaw6647.keys
        val qtyMismatched6912 = (journalRaw6647.keys intersect canonicalRaw6647.keys)
            .filter { journalRaw6647[it] != canonicalRaw6647[it] }
        if (journalOnlyQuarantined7018.isNotEmpty()) {
            try {
                PipelineHealthCollector.labelInc("FORENSIC_JOURNAL_ONLY_IS_QUARANTINED_7018")
                ForensicLogger.lifecycle(
                    "FORENSIC_JOURNAL_ONLY_IS_QUARANTINED_7018",
                    "quarantinedInJournal=${journalOnlyQuarantined7018.size} " +
                        "ids=${journalOnlyQuarantined7018.take(5).joinToString(",") { it.take(40) }} " +
                        "action=not_a_split_write_excluded_by_openPositions_filter_on_purpose",
                )
            } catch (_: Throwable) {}
        }
        if (journalOnly6912.isNotEmpty() || canonicalOnly6912.isNotEmpty() || qtyMismatched6912.isNotEmpty()) {
            try {
                PipelineHealthCollector.labelInc("FORENSIC_POSITION_SET_DIVERGENCE_6912")
                ForensicLogger.lifecycle(
                    "FORENSIC_POSITION_SET_DIVERGENCE_6912",
                    "journalOnly=${journalOnly6912.size} canonicalOnly=${canonicalOnly6912.size} " +
                        "qtyMismatched=${qtyMismatched6912.size} " +
                        // V5.0.7018 — 28 characters was shorter than the ids.
                        // Canonical ALT ids look like
                        // ALT:1:PAPER:ASSET_7e3ad4fd:BUY:CRYPTO:59657629:55,
                        // so take(28) cut every one of them mid-hash and two
                        // different positions printed as the same string. The
                        // operator's snapshot shows ALT:1:PAPER:HZRCwxP2Vq9PCpPX
                        // listed twice, which is not a duplicate row — it is one
                        // truncation of two rows. A diagnostic that cannot
                        // distinguish its own subjects is not a diagnostic.
                        "journalOnlyIds=${journalOnly6912.take(5).joinToString(",") { it.take(52) }} " +
                        "canonicalOnlyIds=${canonicalOnly6912.take(5).joinToString(",") { it.take(52) }} " +
                        "qtyMismatchedIds=${qtyMismatched6912.take(5).joinToString(",") { id ->
                            "${id.take(20)}(j=${journalRaw6647[id]},c=${canonicalRaw6647[id]})"
                        }} " +
                        "action=identify_split_write_subject",
                )
            } catch (_: Throwable) {}

            // V5.0.6980 §NAME_THE_PROCEEDS_NOT_JUST_THE_POSITION.
            //
            // 6912 names WHICH positions differ. It still cannot say WHAT about
            // them differs, and the 6979 CI smoke run shows why that matters:
            //
            //     cashDelta=0.054842  realizedDelta=0.054842  openCostDelta=0.000000
            //     quantityDeltaRaw=1000000000
            //
            // PaperAccountLedger6430.onSellAtomic6632 and JournalEconomicReplay6619
            // apply the identical formulas — cash += (gross - fee),
            // openCost -= basis, realized += (gross - basis), fees += fee — so no
            // formula split can produce this. openCostDelta of exactly 0 says the
            // basis flows agree; cashDelta == realizedDelta says the fee flows
            // agree. Once basis and fee are eliminated, a single quantity is left
            // that can move cash and realized together by the same amount and
            // leave open cost untouched: the GROSS PROCEEDS of a sell.
            //
            // So print the journal's own sell economics for the divergent
            // positions. Whatever the ledger applied, this is what the journal
            // did, per position, and the difference has somewhere to be read
            // from. Pure diagnostics — no mutation, no healing, no gating.
            try {
                val suspects6980 = (qtyMismatched6912 + journalOnly6912 + canonicalOnly6912).distinct().take(5)
                if (suspects6980.isNotEmpty()) {
                    val detail6980 = suspects6980.joinToString(" ") { id ->
                        val g = replay6647?.sellGrossByPosition6980?.get(id) ?: 0.0
                        val b = replay6647?.sellBasisByPosition6980?.get(id) ?: 0.0
                        val f = replay6647?.sellFeeByPosition6980?.get(id) ?: 0.0
                        val n = replay6647?.sellCountByPosition6980?.get(id) ?: 0
                        "${id.take(20)}[sells=$n gross=${"%.6f".format(g)} basis=${"%.6f".format(b)} " +
                            "fee=${"%.6f".format(f)} pnl=${"%+.6f".format(g - b)}]"
                    }
                    PipelineHealthCollector.labelInc("FORENSIC_SELL_ECONOMICS_NAMED_6980")
                    ForensicLogger.lifecycle(
                        "FORENSIC_SELL_ECONOMICS_NAMED_6980",
                        "cashDelta=${"%.6f".format(cashDelta)} realizedDelta=${"%.6f".format(realizedDelta)} " +
                            "openCostDelta=${"%.6f".format(openCostDelta)} " +
                            "journalSellEconomics=$detail6980 " +
                            "read=basis_and_fee_agree_so_a_nonzero_cash_eq_realized_delta_is_a_proceeds_disagreement",
                    )
                }
            } catch (_: Throwable) {}
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
        // V5.0.7036 §THE_THIRD_LEG_WAS_BUILT_AND_NEVER_STOOD_ON.
        //
        // AcceptanceInvariantAuthority6501.checkJournalVsLedger has existed
        // since 6502 with zero callers. Its own comment says it exists to
        // catch "the class of bug where reported and canonical are BOTH wrong
        // in the SAME direction", and the operator's 5.0.7027 snapshot is that
        // bug at full size: ledger=92.519955 journal=0.632539.
        //
        // This is the one place in the app that already holds both figures, so
        // it is the one place that can feed it. Read-only: the authority logs
        // and counts, it does not gate anything, so wiring it changes no
        // trading decision — it makes an existing divergence say its own name.
        try {
            AcceptanceInvariantAuthority6501.checkJournalVsLedger(realizedJournal)
        } catch (_: Throwable) {}
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

        // V5.0.6899 — gate on totalsComplete6899, not reconciled. `reconciled`
        // means "no event was anomalous"; this predicate needs "every event's
        // economics were applied", and V5.0.6868 made those two different
        // things by applying both legs of a residual terminal sell before
        // rejecting it. Requiring `reconciled` here meant one quarantined lot
        // held the account UNRECONCILED forever, which fired the
        // BotService:17091 early return on all 281 cycles of the 5.0.6892
        // snapshot and disabled the growth ring, the anti-reward-hacking guard
        // and the four acceptance conservation invariants at once — while
        // cashDelta and realizedDelta both read exactly 0.0000.
        //
        // The delta comparisons below are unchanged and still have to pass, so
        // V5.0.6735's mandate ("Unknown/unreconciled evidence still FAILS;
        // never heal it to pass") is intact: a genuine mismatch still fails.
        // What no longer fails is an account whose numbers agree.
        val allZero = replay6647?.totalsComplete6899 == true && cashDelta.isFinite() && realizedDelta.isFinite() && openCostDelta.isFinite() &&
            cashDelta <= DELTA_TOLERANCE_SOL &&
            realizedDelta <= DELTA_TOLERANCE_SOL &&
            openCostDelta <= DELTA_TOLERANCE_SOL && quantityDeltaRaw6647 == java.math.BigInteger.ZERO
        // V5.0.6750 — deferred-main-thread replays MUST NOT downgrade
        // a prior RECONCILED status to FAILED. Preserve the last
        // status when the current sample was not authoritative.
        if (replayDeferredOnMainThread6750) {
            try { PipelineHealthCollector.labelInc("FORENSIC_RECONCILE_MAIN_THREAD_DEFERRED_PRESERVED_6750") } catch (_: Throwable) {}
            return
        }
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
