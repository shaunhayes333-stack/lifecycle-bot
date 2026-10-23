package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8358 §A_STALE_REPLAY_IS_NOT_EVIDENCE — paper-accounting lag.
 *
 * CI 5.0.8356 smoke: cashDelta=0.129750 openCostDelta=0.129105 realizedDelta=0
 * quantityDeltaRaw=84930968 with every canonical receipt committed. 0.129750 -
 * 0.129105 = 0.000645 = 0.129105 x 0.005: exactly the fee Executor.paperTopUp
 * charges (sol * 0.005) on a CanonicalPaperTransaction6486.add() leg. That leg
 * opens no CanonicalEconomicEvent6635 receipt, so openInFlight/pending/
 * missingJournal can never see it, and its ledger debit lands before its journal
 * row (ledger -> canonical add -> TradeHistoryStore.recordTrade).
 *
 * ForensicReconciliation6635.reconcile6635 skips any pass whose state key
 * (journal revision | canonical mutations | receipts | ledger cash) is unchanged.
 * The coalesced snapshot worker (JournalEconomicAuthority6616) computes a journal
 * replay and THEN calls reconcile6635(replay). With 8192 seeded rows that replay
 * takes long; an add() that completes inside it leaves a replay from revision R
 * compared against a ledger and a key from revision R+1. The FAILED verdict was
 * stored under the CURRENT key, so every later cadence (30 s scheduler, watchdog)
 * skipped as "unchanged" and the gap stayed until some unrelated mutation moved
 * the key. The 8192 ring (CanonicalEconomicEvent6635.CAP) holds the CI seed
 * exactly; neither the ledger nor the journal replay reads it, so its eviction
 * moves neither total.
 *
 * Fix: the replay carries the journal revision it was computed at; a replay from
 * another revision is replaced by a fresh one, and if the journal is still moving
 * the key is cleared so the next pass recomputes. No tolerance is widened and no
 * check is skipped: a real divergence still FAILS on the fresh replay.
 */
object ReconLag8358 {

    private val staleReplays8358 = AtomicLong(0L)
    private val windowTrimmed8358 = AtomicLong(0L)
    @Volatile private var lastStale8358: String = ""
    @Volatile private var lastSource8358: String = "none"

    /** Pure: a replay is evidence for the key only when both name the same journal revision. */
    fun isStaleReplay8358(replayRevision: Long, keyRevision: Long): Boolean =
        replayRevision >= 0L && keyRevision >= 0L && replayRevision != keyRevision

    /**
     * Pure over its arguments: return a replay computed at [keyRevision]. A stale [first] is replaced by [fresh];
     * when the journal is still moving, [onStillStale] runs (the caller clears its unchanged-state key).
     */
    fun currentReplay8358(
        first: JournalEconomicReplay6619.ReplayResult?,
        keyRevision: Long,
        fresh: () -> JournalEconomicReplay6619.ReplayResult?,
        onStillStale: () -> Unit,
    ): JournalEconomicReplay6619.ReplayResult? {
        if (first == null || !isStaleReplay8358(first.journalRevision8358, keyRevision)) {
            lastStale8358 = ""
            return first
        }
        staleReplays8358.incrementAndGet()
        try { PipelineHealthCollector.labelInc("FORENSIC_STALE_REPLAY_REFRESHED_8358") } catch (_: Throwable) {}
        val again = try { fresh() } catch (_: Throwable) { null }
        if (again != null && !isStaleReplay8358(again.journalRevision8358, keyRevision)) {
            lastStale8358 = ""
            return again
        }
        val used = again ?: first
        lastStale8358 = "rev=${used.journalRevision8358}/$keyRevision"
        try { onStillStale() } catch (_: Throwable) {}
        return used
    }

    /** TradeHistoryStore: rows dropped from the in-memory journal window the replay reads. */
    fun noteWindowTrimmed8358(rows: Int) {
        if (rows > 0) windowTrimmed8358.addAndGet(rows.toLong())
    }

    /**
     * Pure: name the source of a ledger/journal delta. The position whose canonical open basis differs most from the
     * journal's lot basis is the leg one side holds and the other does not.
     */
    fun attribute8358(
        cashDeltaSol: Double,
        basisDeltaSol: Double,
        quantityDeltaRaw: BigInteger,
        journalBasisByPosition: Map<String, Double>,
        canonicalBasisByPosition: Map<String, Double>,
        mintByPosition: Map<String, String>,
        trimmedRows: Long,
        stale: String,
    ): String {
        val tol = ForensicReconciliation6635.DELTA_TOLERANCE_SOL
        val clean = cashDeltaSol.isFinite() && basisDeltaSol.isFinite() &&
            cashDeltaSol <= tol && basisDeltaSol <= tol && quantityDeltaRaw.signum() == 0
        if (clean) return "none"
        if (stale.isNotBlank()) return "STALE_REPLAY $stale"
        var bestId = ""
        var bestDiff = 0.0
        for (id in journalBasisByPosition.keys + canonicalBasisByPosition.keys) {
            val diff = (canonicalBasisByPosition[id] ?: 0.0) - (journalBasisByPosition[id] ?: 0.0)
            if (diff.isFinite() && kotlin.math.abs(diff) > kotlin.math.abs(bestDiff)) { bestDiff = diff; bestId = id }
        }
        if (bestId.isNotBlank() && kotlin.math.abs(bestDiff) > tol) {
            val kind = if (bestDiff > 0.0) "LEDGER_ONLY_BUY_LEG" else "JOURNAL_ONLY_BUY_LEG"
            val mint = (mintByPosition[bestId] ?: bestId).take(16)
            return "$kind $mint ${"%.6f".format(java.util.Locale.ROOT, kotlin.math.abs(bestDiff))}"
        }
        if (trimmedRows > 0L) return "WINDOW_CAP $trimmedRows"
        return "UNATTRIBUTED cash=${"%.6f".format(java.util.Locale.ROOT, cashDeltaSol)} " +
            "basis=${"%.6f".format(java.util.Locale.ROOT, basisDeltaSol)}"
    }

    /** ForensicReconciliation6635: attribute this pass's deltas from the authorities it already compared. */
    fun recordAttribution8358(
        cashDeltaSol: Double,
        basisDeltaSol: Double,
        quantityDeltaRaw: BigInteger,
        replay: JournalEconomicReplay6619.ReplayResult?,
    ) {
        val open = try {
            CanonicalPositionAuthority6441.openPositions().filter { it.mode.equals("paper", true) }
        } catch (_: Throwable) { emptyList() }
        val canonicalBasis = open.associate { it.positionId to (it.entryCostSol - it.soldCostBasisSol).coerceAtLeast(0.0) }
        val mints = open.associate { it.positionId to it.mint }
        lastSource8358 = attribute8358(
            cashDeltaSol, basisDeltaSol, quantityDeltaRaw,
            replay?.openBasisByPosition.orEmpty(), canonicalBasis, mints,
            windowTrimmed8358.get(), lastStale8358,
        )
    }

    /** AcceptanceInvariantAudit6441: `deltaFrom8358=<kind> <mint> <sol>` / `WINDOW_CAP <n>` / `STALE_REPLAY rev=a/b`. */
    fun lastSource8358(): String = lastSource8358

    fun staleReplays8358(): Long = staleReplays8358.get()

    internal fun resetForTest8358() {
        staleReplays8358.set(0L); windowTrimmed8358.set(0L)
        lastStale8358 = ""; lastSource8358 = "none"
    }
}
