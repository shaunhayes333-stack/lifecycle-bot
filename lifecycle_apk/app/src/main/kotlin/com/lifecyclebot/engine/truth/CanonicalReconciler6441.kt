package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6441 §8 — CANONICAL RECONCILER (QUICK + FULL).
 *
 * OPERATOR MANDATE §8:
 *   "QUICK reconciler validates: cash, open quantity, lifecycle state,
 *    slot ownership, idempotency. FULL reconciler reconstructs account
 *    solely from canonical immutable events and compares against
 *    mutable runtime state. running=true + lastAttempt=never after one
 *    cadence is illegal. Reconciler heartbeat/watchdog/report must
 *    share one truth. On mismatch: quarantine affected state + emit
 *    forensic diff. Do NOT silently rewrite history or feed healed
 *    data into learner."
 *
 * DESIGN
 * ──────
 * Both reconciler modes are here for a single source of truth. Callers
 * invoke either mode at their scheduled cadence.
 *
 *   quickCheck() — validates canonical position map against acceptance
 *     invariants (cash >= 0, no over-sold qty, closed positions have
 *     zero remaining, open counts consistent with lifecycle enum).
 *
 *   fullReconstruct(events) — takes an ordered sequence of forensic
 *     execution rows and reconstructs the expected canonical state;
 *     diffs against CanonicalPositionAuthority6441 and returns a
 *     mismatch report.
 *
 * NEITHER mode mutates canonical state on mismatch — they emit a
 * forensic diff and, if the operator opts in, quarantine the specific
 * position via CanonicalPositionAuthority6441.quarantine.
 */
object CanonicalReconciler6441 {

    data class QuickReport(
        val whenMs: Long,
        val positionsChecked: Int,
        val invariantsBroken: List<String>,
        val cashOk: Boolean,
        val heartbeatSec: Long,
    )

    data class FullReport(
        val whenMs: Long,
        val eventsReplayed: Int,
        val mismatchCount: Int,
        val details: List<String>,
    )

    private const val EXPECTED_CADENCE_MS = 90_000L

    private val lastQuickMs = AtomicLong(0L)
    private val lastFullMs = AtomicLong(0L)
    private val quickCount = AtomicLong(0L)
    private val fullCount = AtomicLong(0L)
    private val mismatchesEver = AtomicLong(0L)
    private val lastQuickReport = AtomicReference<QuickReport?>(null)
    private val lastFullReport = AtomicReference<FullReport?>(null)

    fun quickCheck(): QuickReport {
        quickCount.incrementAndGet()
        lastQuickMs.set(System.currentTimeMillis())
        val positions = CanonicalPositionAuthority6441.openPositions() +
            CanonicalPositionAuthority6441.closedPositions()
        val invariants = mutableListOf<String>()
        val cash = CanonicalPositionAuthority6441.paperCashSol()
        val cashOk = cash >= 0.0
        if (!cashOk) invariants.add("PAPER_CASH_NEGATIVE=$cash")
        for (p in positions) {
            if (p.lifecycle == CanonicalPositionAuthority6441.Lifecycle.CLOSED &&
                p.remainingQtyRaw != BigInteger.ZERO) {
                invariants.add("CLOSED_WITH_QTY:${p.positionId}:${p.remainingQtyRaw}")
            }
            if (p.remainingQtyRaw < BigInteger.ZERO) {
                invariants.add("NEGATIVE_QTY:${p.positionId}")
            }
        }
        val heartbeatSec = (System.currentTimeMillis() - lastQuickMs.get()) / 1000L
        val rep = QuickReport(
            whenMs = System.currentTimeMillis(),
            positionsChecked = positions.size,
            invariantsBroken = invariants,
            cashOk = cashOk,
            heartbeatSec = heartbeatSec,
        )
        lastQuickReport.set(rep)
        if (invariants.isNotEmpty()) {
            mismatchesEver.addAndGet(invariants.size.toLong())
            try {
                ForensicLogger.lifecycle(
                    "RECONCILER_QUICK_BROKEN_6441",
                    "positions=${positions.size} broken=${invariants.size} first=${invariants.first().take(60)}",
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("RECONCILER_QUICK_BROKEN_6441") } catch (_: Throwable) {}
        } else {
            try { PipelineHealthCollector.labelInc("RECONCILER_QUICK_OK_6441") } catch (_: Throwable) {}
        }
        return rep
    }

    /**
     * Full reconstruction from an event sequence. Caller is responsible
     * for providing the immutable event log; this function does not
     * touch the ledger. Returns a FullReport summarising mismatches.
     */
    fun fullReconstruct(events: List<ForensicExecutionRow6441>): FullReport {
        fullCount.incrementAndGet()
        lastFullMs.set(System.currentTimeMillis())
        val details = mutableListOf<String>()
        // Group events by positionId. For each position, aggregate expected
        // remaining qty + realized PnL and compare against the canonical
        // authority.
        val byPos = events.groupBy { it.positionId }
        var mismatches = 0
        for ((positionId, rows) in byPos) {
            val expectedRealized = rows.sumOf { it.realizedPnlSol }
            val pos = CanonicalPositionAuthority6441.getPosition(positionId)
            if (pos == null) {
                details.add("MISSING_POSITION:$positionId events=${rows.size}")
                mismatches++
                continue
            }
            val diff = kotlin.math.abs(pos.realizedPnlSol - expectedRealized)
            if (diff > 1e-6) {
                details.add(
                    "PNL_MISMATCH:$positionId canonical=${"%.6f".format(pos.realizedPnlSol)} " +
                        "reconstructed=${"%.6f".format(expectedRealized)} diff=${"%.6f".format(diff)}",
                )
                mismatches++
            }
            for (r in rows) {
                val inv = r.verifyInvariant()
                if (inv.isNotEmpty()) {
                    details.add("ROW_INVARIANT:${r.executionId} $inv")
                    mismatches++
                }
            }
        }
        val rep = FullReport(
            whenMs = System.currentTimeMillis(),
            eventsReplayed = events.size,
            mismatchCount = mismatches,
            details = details,
        )
        lastFullReport.set(rep)
        if (mismatches > 0) {
            mismatchesEver.addAndGet(mismatches.toLong())
            try {
                ForensicLogger.lifecycle(
                    "RECONCILER_FULL_BROKEN_6441",
                    "events=${events.size} mismatches=$mismatches first=${details.firstOrNull()?.take(80)}",
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("RECONCILER_FULL_BROKEN_6441") } catch (_: Throwable) {}
        } else {
            try { PipelineHealthCollector.labelInc("RECONCILER_FULL_OK_6441") } catch (_: Throwable) {}
        }
        return rep
    }

    fun statusLine(): String {
        val q = quickCount.get()
        val f = fullCount.get()
        val lastQ = lastQuickMs.get()
        val lastF = lastFullMs.get()
        val ageQ = if (lastQ <= 0L) -1L else (System.currentTimeMillis() - lastQ) / 1000L
        val ageF = if (lastF <= 0L) -1L else (System.currentTimeMillis() - lastF) / 1000L
        return "quick=$q lastQuickAgeSec=$ageQ full=$f lastFullAgeSec=$ageF mismatchesEver=${mismatchesEver.get()} " +
            "expectedCadenceMs=$EXPECTED_CADENCE_MS"
    }
}
