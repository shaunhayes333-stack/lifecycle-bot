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
        val openMarketValueSol: Double = 0.0,
        val accountAvailable: Boolean = true,
        val authoritativePrices: Boolean = true,
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
    private val lastReconciled = java.util.concurrent.ConcurrentHashMap<String, Snapshot>()

    @Synchronized
    fun read(surface: String, mode: String = "paper"): Snapshot {
        reads.incrementAndGet()
        try { PipelineHealthCollector.labelInc("HERO_UNIFIED_SNAPSHOT_READ_6635") } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("HERO_UNIFIED_SNAPSHOT_READ_${surface.uppercase()}_6635") } catch (_: Throwable) {}

        // V5.0.6677 — request convergence, never execute durable journal/canonical
        // mutation on the UI thread. The repair worker is idempotent and uses only
        // typed economic receipts + the exact sentinel fingerprint authority.
        // This preserves the prior 6619 main-thread smoke/ANR fix while still
        // allowing a failed account snapshot to self-heal on the next refresh.
        if (mode.equals("paper", true)) {
            try { CanonicalJournalProjectionRepair6677.scheduleRepair6677() } catch (_: Throwable) {}
        }

        // Force reconciliation pass so every UI read observes fresh
        // delta counters rather than a cached stale line.
        try { ForensicReconciliation6635.reconcile6635() } catch (_: Throwable) {}

        val capital = try { PaperCapitalAuthority6577.snapshot() } catch (_: Throwable) { null }
        val markAuthority = try { CanonicalCapitalAuthority6450.snapshot() } catch (_: Throwable) { null }
        val cashLedger = capital?.availableCashSol ?: 0.0
        val realized = capital?.realizedPnlSol ?: 0.0
        val openCost = capital?.openMarketValueSol ?: 0.0
        val openPositions = try { CanonicalPositionAuthority6441.openPositions().count { it.mode == mode } } catch (_: Throwable) { 0 }
        // Ledger currently carries cost-basis equity. Market marks remain
        // diagnostic until they are captured in the same immutable account
        // transaction; never splice a second-time snapshot into this read.
        val unrealized = 0.0
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
            openMarketValueSol = openCost,
            accountAvailable = status == Status.RECONCILED,
            authoritativePrices = status == Status.RECONCILED &&
                (markAuthority?.fallbackMarkMints ?: Int.MAX_VALUE) == 0 &&
                (markAuthority?.staleMarkMints ?: Int.MAX_VALUE) == 0,
        )
        if (status == Status.RECONCILED) {
            lastReconciled[mode] = snap
            lastRead.set(snap)
            return snap
        }
        val retained = lastReconciled[mode]?.copy(
            status = Status.FAILED,
            forensicLine = "$forensicLine accountAction=RETAIN_LAST_RECONCILED",
            readAtMs = System.currentTimeMillis(),
            accountAvailable = true,
        ) ?: Snapshot(
            mode = mode, cashSol = 0.0, equitySol = 0.0,
            realizedPnlSol = 0.0, unrealizedPnlSol = 0.0,
            openPositionsCount = openPositions, status = Status.FAILED,
            forensicLine = "$forensicLine ACCOUNT_UNAVAILABLE",
            readAtMs = System.currentTimeMillis(), openMarketValueSol = 0.0,
            accountAvailable = false, authoritativePrices = false,
        )
        lastRead.set(retained)
        return retained
    }

    fun lastSnapshot(): Snapshot = lastRead.get()

    fun statusLine6635(): String = "reads=${reads.get()} lastStatus=${lastRead.get().status}"

    internal fun resetForTest() {
        reads.set(0L)
        lastReconciled.clear()
        lastRead.set(Snapshot("paper", 0.0, 0.0, 0.0, 0.0, 0, Status.WARMUP, "", 0L))
    }
}
