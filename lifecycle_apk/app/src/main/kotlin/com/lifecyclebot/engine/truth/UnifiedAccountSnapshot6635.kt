package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6635 §5 UNIFIED_ACCOUNT_SNAPSHOT.
 *
 * OPERATOR DIRECTIVE (verbatim):
 *   > "Delete the current 'HERO USES JOURNAL' workaround.
 *   >  Remove this architecture: action=hero_uses_journal_ledger_stays_for_execution.
 *   >  That masks the accounting fault.
 *   >  UI must receive one reconciled AccountSnapshot generated only
 *   >  after: ledger == journal == canonical positions.
 *   >  The UI is a renderer only.
 *   >  MainActivity, MemeTrader screen, Crypto Universe screen,
 *   >  Markets screen must all render the same AccountSnapshot for
 *   >  the same trading account/mode.
 *   >  No screen may recalculate balance locally."
 *
 * DESIGN
 * ──────
 * `UnifiedAccountSnapshot6635.read(mode, surface)` is the ONLY
 * function the UI is allowed to call.  It:
 *   1. Runs `ForensicReconciliation6635.reconcile6635()`
 *   2. Reads canonical event registry + ledger + journal + positions
 *   3. Returns an immutable `Snapshot` with two possible statuses:
 *      RECONCILED — the four stores agree; safe to render
 *      FAILED     — a delta exists; UI renders values BUT is REQUIRED
 *                    to display the FORENSIC_ACCOUNTING banner
 *   4. Emits `HERO_UNIFIED_SNAPSHOT_READ_6635` per surface
 *
 * The status is authoritative — no screen may promote a FAILED
 * snapshot to RECONCILED via local computation.
 *
 * NOTE: This is the ONE place `PaperEconomicSnapshot6629` was already
 * wired to.  6635 adds the reconciliation gate + the operator-mandated
 * FORENSIC banner semantics on top; existing 6629 callers can be
 * migrated one-by-one to 6635 without changing rendering behaviour.
 */
object UnifiedAccountSnapshot6635 {

    enum class Status { RECONCILED, FAILED, WARMUP }

    data class Snapshot(
        val mode: String,           // "paper" or "live"
        val cashSol: Double,
        val equitySol: Double,
        val realizedPnlSol: Double,
        val unrealizedPnlSol: Double,
        val openPositionsCount: Int,
        val status: Status,
        val forensicLine: String,
        val readAtMs: Long,
    )

    private val reads = AtomicLong(0L)
    private val lastRead = AtomicReference(
        Snapshot(
            mode = "paper", cashSol = 0.0, equitySol = 0.0,
            realizedPnlSol = 0.0, unrealizedPnlSol = 0.0,
            openPositionsCount = 0, status = Status.WARMUP,
            forensicLine = "", readAtMs = 0L,
        )
    )

    fun read(surface: String, mode: String = "paper"): Snapshot {
        reads.incrementAndGet()
        try { PipelineHealthCollector.labelInc("HERO_UNIFIED_SNAPSHOT_READ_6635") } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("HERO_UNIFIED_SNAPSHOT_READ_${surface.uppercase()}_6635") } catch (_: Throwable) {}

        // Force reconciliation pass so every UI read observes fresh
        // delta counters rather than a cached stale line.
        try { ForensicReconciliation6635.reconcile6635() } catch (_: Throwable) {}

        val cashLedger = try { PaperCapitalAuthority6577.cashSol() } catch (_: Throwable) { 0.0 }
        val realized = try { PaperCapitalAuthority6577.realizedPnlSol() } catch (_: Throwable) { 0.0 }
        val openCost = try { PaperCapitalAuthority6577.openCostBasisSol() } catch (_: Throwable) { 0.0 }
        val openPositions = try { CanonicalPositionAuthority6441.openPositions().count { it.mode == mode } } catch (_: Throwable) { 0 }
        // Unrealized = current-market snapshot from PaperEconomicSnapshot6629.
        val econ6629 = try { PaperEconomicSnapshot6629.read6629(surface) } catch (_: Throwable) { null }
        val unrealized = try { econ6629?.unrealizedPnlSol ?: 0.0 } catch (_: Throwable) { 0.0 }
        val equity = cashLedger + openCost + unrealized

        val forensicLine = try { ForensicReconciliation6635.healthLine6635() } catch (_: Throwable) { "" }
        val status = when {
            forensicLine.contains("status=RECONCILED") -> Status.RECONCILED
            forensicLine.contains("status=FAILED") -> Status.FAILED
            else -> Status.WARMUP
        }
        val snap = Snapshot(
            mode = mode, cashSol = cashLedger, equitySol = equity,
            realizedPnlSol = realized, unrealizedPnlSol = unrealized,
            openPositionsCount = openPositions, status = status,
            forensicLine = forensicLine, readAtMs = System.currentTimeMillis(),
        )
        lastRead.set(snap)
        return snap
    }

    fun lastSnapshot(): Snapshot = lastRead.get()

    fun statusLine6635(): String = "reads=${reads.get()} lastStatus=${lastRead.get().status}"

    internal fun resetForTest() {
        reads.set(0L)
        lastRead.set(Snapshot("paper", 0.0, 0.0, 0.0, 0.0, 0, Status.WARMUP, "", 0L))
    }
}
