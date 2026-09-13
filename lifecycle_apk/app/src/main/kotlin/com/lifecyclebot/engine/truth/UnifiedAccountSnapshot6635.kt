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
 * function the UI is allowed to call. It is observational only.
 *
 * V5.0.6756 closes a remaining temporal split-brain: the three paper heroes
 * all called this facade, but each read rebuilt economics from the mutable paper
 * ledger at a different instant. A trade committing between screen refreshes
 * could therefore make MEME / MARKETS / CRYPTO display different balances even
 * though every caller used the "same" API.
 *
 * Paper economics now come from JournalEconomicAuthority6616's immutable,
 * revisioned, journal-replay snapshot. That snapshot is published only after
 * journal/ledger/canonical reconciliation succeeds. Every UI read within a
 * revision therefore receives the exact same cash/equity/realized tuple. The
 * mutable execution ledger remains an execution authority, not a UI calculator.
 *
 * V5.0.6678 — READ PATH PURITY.
 * Account/UI reads are observational only. They never schedule or execute
 * journal projection, canonical position mutation, refunds, or migration work.
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
        val economicRevision: Long = -1L,
        val economicSource: String = "UNIFIED_ACCOUNT_SNAPSHOT_6635",
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

        // Read-path purity: reconciliation may observe and report deltas, but
        // this UI-facing method must never repair, project, refund, or mutate
        // canonical economic state as a side effect of rendering a balance.
        try { ForensicReconciliation6635.reconcile6635() } catch (_: Throwable) {}

        val paperMode = mode.equals("paper", true)
        val journal = if (paperMode) try { JournalEconomicAuthority6616.currentSnapshot() } catch (_: Throwable) { null } else null
        val capital = try { PaperCapitalAuthority6577.snapshot() } catch (_: Throwable) { null }
        val markAuthority = try { CanonicalCapitalAuthority6450.snapshot() } catch (_: Throwable) { null }

        // V5.0.6756 — UI paper economics MUST be an immutable mutation revision.
        // Do not reconstruct these values independently on every screen read.
        val cash = if (paperMode && journal != null) journal.cashSol else capital?.availableCashSol ?: 0.0
        val realized = if (paperMode && journal != null) journal.realizedPnlSol else capital?.realizedPnlSol ?: 0.0
        val openCost = if (paperMode && journal != null) journal.openMarketValueSol else capital?.openMarketValueSol ?: 0.0
        val equity = if (paperMode && journal != null) journal.equitySol else cash + openCost
        val revision = if (paperMode) journal?.revision ?: -1L else -1L
        val source = if (paperMode) journal?.source ?: "PAPER_LEDGER_WARMUP_FALLBACK" else "LIVE_CAPITAL_AUTHORITY"

        val openPositions = try {
            CanonicalPositionAuthority6441.openPositions().count { it.mode.equals(mode, true) }
        } catch (_: Throwable) { 0 }

        // Market marks remain diagnostic until they are captured in the same
        // immutable account transaction; never splice a second-time mark into
        // a paper hero revision.
        val unrealized = if (paperMode) 0.0 else markAuthority?.unrealizedPnlSol ?: 0.0

        val forensicLine = try { ForensicReconciliation6635.healthLine6635() } catch (_: Throwable) { "" }
        val reconciliationStatus = when {
            forensicLine.contains("status=RECONCILED") -> Status.RECONCILED
            forensicLine.contains("status=FAILED") -> Status.FAILED
            else -> Status.WARMUP
        }
        // A paper hero is not truly reconciled until a journal revision exists.
        val status = if (paperMode && journal == null && reconciliationStatus == Status.RECONCILED)
            Status.WARMUP else reconciliationStatus

        val snap = Snapshot(
            mode = mode,
            cashSol = cash,
            equitySol = equity,
            realizedPnlSol = realized,
            unrealizedPnlSol = unrealized,
            openPositionsCount = openPositions,
            status = status,
            forensicLine = forensicLine,
            readAtMs = System.currentTimeMillis(),
            openMarketValueSol = openCost,
            accountAvailable = status == Status.RECONCILED,
            authoritativePrices = status == Status.RECONCILED &&
                (markAuthority?.fallbackMarkMints ?: Int.MAX_VALUE) == 0 &&
                (markAuthority?.staleMarkMints ?: Int.MAX_VALUE) == 0,
            economicRevision = revision,
            economicSource = source,
        )
        if (status == Status.RECONCILED) {
            lastReconciled[mode.lowercase()] = snap
            lastRead.set(snap)
            return snap
        }

        // During an in-flight mutation all screens retain the SAME last
        // reconciled revision. No screen is allowed to invent a newer balance.
        val retained = lastReconciled[mode.lowercase()]?.copy(
            status = Status.FAILED,
            forensicLine = "$forensicLine accountAction=RETAIN_LAST_RECONCILED",
            readAtMs = System.currentTimeMillis(),
            accountAvailable = true,
        ) ?: Snapshot(
            mode = mode, cashSol = 0.0, equitySol = 0.0,
            realizedPnlSol = 0.0, unrealizedPnlSol = 0.0,
            openPositionsCount = openPositions, status = status,
            forensicLine = "$forensicLine ACCOUNT_UNAVAILABLE",
            readAtMs = System.currentTimeMillis(), openMarketValueSol = 0.0,
            accountAvailable = false, authoritativePrices = false,
            economicRevision = revision, economicSource = source,
        )
        lastRead.set(retained)
        return retained
    }

    fun lastSnapshot(): Snapshot = lastRead.get()

    fun statusLine6635(): String {
        val s = lastRead.get()
        return "reads=${reads.get()} lastStatus=${s.status} rev=${s.economicRevision} source=${s.economicSource}"
    }

    internal fun resetForTest() {
        reads.set(0L)
        lastReconciled.clear()
        lastRead.set(Snapshot("paper", 0.0, 0.0, 0.0, 0.0, 0, Status.WARMUP, "", 0L))
    }
}
