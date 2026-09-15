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

        // V5.0.6805 §RETIRE_JOURNAL_REPLAY_ACCOUNTING — operator diagnosis
        //   Feb 2026: "UI hero + acceptance audits still consume TRADE_
        //    JOURNAL_REPLAY_6619 → 3.44 vs -0.37 SOL divergence and 151/153
        //    J_* audit failures. CanonicalCapitalAuthority6450 must become
        //    the sole read surface."
        //
        //   UI is a pure renderer of canonical capital. Journal replay is
        //   forensic history / recovery only; it can no longer decide hero
        //   availability, balances, equity, or reconciliation status.
        //   ForensicReconciliation6635.reconcile6635() is a side-effecting
        //   observer and must not run in the render path.

        val paperMode = mode.equals("paper", true)
        val markAuthority = try { CanonicalCapitalAuthority6450.snapshot() } catch (_: Throwable) { null }
        val cash = markAuthority?.cashSol ?: 0.0
        val realized = markAuthority?.realizedPnlSol ?: 0.0
        val openCost = markAuthority?.openMarketValueSol ?: 0.0
        val equity = markAuthority?.totalEquitySol ?: (cash + openCost)
        // Stable value-derived revision: identical canonical economics render
        // the same revision across MEME / MARKETS / CRYPTO hero reads.
        val revision = markAuthority?.hashCode()?.toLong() ?: -1L
        val source = "CANONICAL_CAPITAL_AUTHORITY_6450"

        val openPositions = try {
            CanonicalPositionAuthority6441.openPositions().count { it.mode.equals(mode, true) }
        } catch (_: Throwable) { 0 }

        val unrealized = markAuthority?.unrealizedPnlSol ?: 0.0
        val canonicalDelta6805 = markAuthority?.conservationDeltaSol
        val canonicalHealthy6805 = canonicalDelta6805 != null && canonicalDelta6805.isFinite() &&
            kotlin.math.abs(canonicalDelta6805) <= 1e-4
        val status = when {
            markAuthority == null -> Status.WARMUP
            canonicalHealthy6805 -> Status.RECONCILED
            else -> Status.FAILED
        }
        val forensicLine = if (markAuthority == null) {
            "source=CANONICAL_CAPITAL_AUTHORITY_6450 status=WARMUP"
        } else {
            "source=CANONICAL_CAPITAL_AUTHORITY_6450 status=${status.name} " +
                "cash=${"%.6f".format(markAuthority.cashSol)} openMV=${"%.6f".format(markAuthority.openMarketValueSol)} " +
                "realized=${"%.6f".format(markAuthority.realizedPnlSol)} fees=${"%.6f".format(markAuthority.feesSol)} " +
                "equity=${"%.6f".format(markAuthority.totalEquitySol)} delta=${"%.9f".format(markAuthority.conservationDeltaSol)}"
        }

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
