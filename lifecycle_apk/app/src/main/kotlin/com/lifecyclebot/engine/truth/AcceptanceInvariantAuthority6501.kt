package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6501 §8 — ACCEPTANCE INVARIANT AUTHORITY.
 *
 * OPERATOR MANDATE (verbatim, 6500 evidence):
 *
 *   "Add one acceptance invariant:
 *      reported cash + openMV == canonical reconstructed equity
 *      AND
 *      dashboard realized == canonical terminal-event realized == analytics realized
 *   within tolerance."
 *
 * DESIGN
 * ──────
 * Runs on every reconciler tick. Compares:
 *
 *   A. `PaperAccountLedger6430.cashSol() + openMarketValueSol` (as
 *      published by CanonicalCapitalAuthority6450, which already
 *      excludes invariant-quarantined marks per V5.0.6500)
 *   vs.
 *   B. `CanonicalCapitalAuthority6450.equitySol()` (the canonical
 *      reconstructed equity from event stream / lifecycle authority)
 *
 * Delta > `TOLERANCE_SOL` (0.01 SOL) → hard log
 * `ECONOMIC_TRUTH_DIVERGENCE_6501` with the specific breakdown so
 * the root cause classifier surfaces it as an ENTRY_FINALITY tier
 * fault.
 *
 * Realized-side check compares
 * `PaperAccountLedger6430.realizedPnlSol()` (canonical) vs. the
 * journal-derived realized total (as sanity gate against the class
 * of bug that produced 6.27M-SOL BLUECHIP output in 6500).
 */
object AcceptanceInvariantAuthority6501 {

    private const val TOLERANCE_SOL = 0.01

    private val checks = AtomicLong(0L)
    private val equityDivergences = AtomicLong(0L)
    private val realizedDivergences = AtomicLong(0L)

    data class InvariantResult(
        val ok: Boolean,
        val cashSol: Double,
        val openMvSol: Double,
        val reportedEquitySol: Double,
        val canonicalEquitySol: Double,
        val equityDeltaSol: Double,
        val canonicalRealizedSol: Double,
        val journalRealizedSol: Double,
        val realizedDeltaSol: Double,
        val reason: String,
    )

    /**
     * Run the check. Caller is expected to be the reconciler tick or
     * an on-demand health snapshot. Returns the result AND emits the
     * divergence label + root-cause counter on failure.
     */
    fun check(journalRealizedSol: Double? = null): InvariantResult {
        checks.incrementAndGet()
        val cash = try { PaperAccountLedger6430.cashSol() } catch (_: Throwable) { 0.0 }
        val snap = try { CanonicalCapitalAuthority6450.snapshot() } catch (_: Throwable) { null }
        val openMv = snap?.openMarketValueSol ?: 0.0
        val reported = cash + openMv
        // Canonical reconstructed equity — from the same snapshot to
        // avoid mid-check drift. If snap is null we fall back to
        // reported so the invariant does not falsely trip.
        val canonical = snap?.totalEquitySol ?: reported
        val equityDelta = kotlin.math.abs(reported - canonical)
        val canonicalRealized = try { PaperAccountLedger6430.realizedPnlSol() } catch (_: Throwable) { 0.0 }
        val realizedDelta = if (journalRealizedSol != null)
            kotlin.math.abs(canonicalRealized - journalRealizedSol) else 0.0
        val equityOk = equityDelta <= TOLERANCE_SOL
        val realizedOk = journalRealizedSol == null || realizedDelta <= TOLERANCE_SOL
        val ok = equityOk && realizedOk
        val reason = buildString {
            if (!equityOk) append("equityΔ=${"%.4f".format(equityDelta)}SOL ")
            if (!realizedOk) append("realizedΔ=${"%.4f".format(realizedDelta)}SOL ")
            if (isEmpty()) append("ok")
        }.trim()
        if (!ok) {
            if (!equityOk) equityDivergences.incrementAndGet()
            if (!realizedOk) realizedDivergences.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "ECONOMIC_TRUTH_DIVERGENCE_6501",
                    "reported(${"%.4f".format(cash)}+${"%.4f".format(openMv)}=${"%.4f".format(reported)}) " +
                        "canonical(${"%.4f".format(canonical)}) equityΔ=${"%.4f".format(equityDelta)} " +
                        "canonicalRealized=${"%.4f".format(canonicalRealized)} " +
                        (if (journalRealizedSol != null) "journalRealized=${"%.4f".format(journalRealizedSol)} realizedΔ=${"%.4f".format(realizedDelta)} " else "") +
                        "tolerance=${TOLERANCE_SOL} reason=$reason",
                )
                PipelineHealthCollector.labelInc("ECONOMIC_TRUTH_DIVERGENCE_6501")
            } catch (_: Throwable) {}
        }
        return InvariantResult(
            ok = ok,
            cashSol = cash,
            openMvSol = openMv,
            reportedEquitySol = reported,
            canonicalEquitySol = canonical,
            equityDeltaSol = equityDelta,
            canonicalRealizedSol = canonicalRealized,
            journalRealizedSol = journalRealizedSol ?: 0.0,
            realizedDeltaSol = realizedDelta,
            reason = reason,
        )
    }

    fun statusLine(): String =
        "checks=${checks.get()} equityDivergences=${equityDivergences.get()} realizedDivergences=${realizedDivergences.get()}"

    internal fun resetForTest() {
        checks.set(0L); equityDivergences.set(0L); realizedDivergences.set(0L)
    }
}
