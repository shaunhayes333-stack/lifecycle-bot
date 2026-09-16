package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6817 §PAPER_COMMIT_ORDER — operator directive Feb 2026:
 *   "Commit cash, basis, quantity, realized PnL, canonical position
 *    state and journal terminal row under one transaction/version.
 *    Publish FinalizedTradeBus only AFTER commit. AcceptanceAudit6441
 *    must inspect the post-commit version, never intermediate
 *    mutations. Eliminate J_CASH_DELTA / J_BASIS_DELTA /
 *    J_REALIZED_DELTA / J_QUANTITY_DELTA while preserving current
 *    replay conservation."
 *
 * DESIGN — witness + causal ordering audit, not a mutex.
 *   • `beginCommit(commitId)` stamps the beginning of a paper terminal
 *     mutation batch. Consumers pass a stable commitId (typically the
 *     attemptId|side|terminalFillIndex from PaperEconomicAtomicCommit6632).
 *   • Sub-mutations stamp their side via `stampCash / stampBasis /
 *     stampQuantity / stampRealized / stampPositionState / stampJournal`.
 *   • `finalize(commitId)` bumps a monotonic revision that Acceptance
 *     Audit consumers observe. A finalize with any missing side fires
 *     `PAPER_COMMIT_ORDER_INCOMPLETE_6817` with the specific missing
 *     side named.
 *   • FinalizedTradeBus publication is expected AFTER `finalize`. A
 *     bus publish observed BEFORE finalize fires
 *     `FINALIZED_BUS_PUBLISHED_BEFORE_COMMIT_6817` — the operator's
 *     ordering violation counter.
 *   • Fail-open: any exception preserves current behaviour. This
 *     authority is an audit ledger; existing mutations continue.
 *
 * The J_*_DELTA labels in the operator dump come from mid-mutation
 * snapshots where cash was already stamped but basis or realized had
 * not yet applied. Under this guard, AcceptanceInvariantAuthority6501
 * consumers should read the `postCommitRevision()` and consult
 * `isCommitComplete(commitId)` before probing the ledger.
 */
object PaperCommitOrderGuard6817 {

    private data class Batch(
        val commitId: String,
        val startAtMs: Long,
        @Volatile var cash: Boolean = false,
        @Volatile var basis: Boolean = false,
        @Volatile var quantity: Boolean = false,
        @Volatile var realized: Boolean = false,
        @Volatile var positionState: Boolean = false,
        @Volatile var journal: Boolean = false,
        @Volatile var finalizedAtMs: Long = 0L,
        @Volatile var busPublishedAtMs: Long = 0L,
    ) {
        fun allSidesPresent(): Boolean = cash && basis && quantity && realized && positionState && journal
    }

    private val batches = ConcurrentHashMap<String, Batch>()
    private const val CAP = 2048
    private val postCommitRev = AtomicLong(0L)

    private val begins = AtomicLong(0L)
    private val finalizes = AtomicLong(0L)
    private val incompleteFinalizes = AtomicLong(0L)
    private val busBeforeCommit = AtomicLong(0L)

    fun beginCommit(commitId: String): Boolean {
        if (commitId.isBlank()) return false
        val prev = batches.putIfAbsent(commitId, Batch(commitId, System.currentTimeMillis()))
        begins.incrementAndGet()
        try { PipelineHealthCollector.labelInc("PAPER_COMMIT_BEGIN_6817") } catch (_: Throwable) {}
        maybeEvict()
        return prev == null
    }

    fun stampCash(commitId: String) { batches[commitId]?.cash = true }
    fun stampBasis(commitId: String) { batches[commitId]?.basis = true }
    fun stampQuantity(commitId: String) { batches[commitId]?.quantity = true }
    fun stampRealized(commitId: String) { batches[commitId]?.realized = true }
    fun stampPositionState(commitId: String) { batches[commitId]?.positionState = true }
    fun stampJournal(commitId: String) { batches[commitId]?.journal = true }

    /**
     * Finalize the batch. Bumps the post-commit revision. If any side
     * is missing, `PAPER_COMMIT_ORDER_INCOMPLETE_6817` fires with the
     * specific missing side named. The revision still bumps so
     * consumers do not deadlock waiting.
     */
    fun finalize(commitId: String): Long {
        val b = batches[commitId] ?: return postCommitRev.get()
        b.finalizedAtMs = System.currentTimeMillis()
        val newRev = postCommitRev.incrementAndGet()
        finalizes.incrementAndGet()
        if (!b.allSidesPresent()) {
            incompleteFinalizes.incrementAndGet()
            val missing = buildList {
                if (!b.cash) add("cash")
                if (!b.basis) add("basis")
                if (!b.quantity) add("quantity")
                if (!b.realized) add("realized")
                if (!b.positionState) add("positionState")
                if (!b.journal) add("journal")
            }
            try {
                PipelineHealthCollector.labelInc("PAPER_COMMIT_ORDER_INCOMPLETE_6817")
                for (m in missing) {
                    PipelineHealthCollector.labelInc(
                        "PAPER_COMMIT_ORDER_INCOMPLETE_6817_${m.uppercase()}"
                    )
                }
                ForensicLogger.lifecycle(
                    "PAPER_COMMIT_ORDER_INCOMPLETE_6817",
                    "commitId=${commitId.take(48)} missing=${missing.joinToString(",")} " +
                        "ageMs=${b.finalizedAtMs - b.startAtMs} action=preserved_but_labelled",
                )
            } catch (_: Throwable) {}
        } else {
            try { PipelineHealthCollector.labelInc("PAPER_COMMIT_ORDER_OK_6817") } catch (_: Throwable) {}
        }
        return newRev
    }

    /**
     * Called by FinalizedTradeBus publish path (or any consumer that
     * publishes) to record that the bus fired. If the commit batch was
     * not yet finalized, the ordering-violation counter fires.
     */
    fun recordBusPublish(commitId: String) {
        val b = batches[commitId] ?: return
        b.busPublishedAtMs = System.currentTimeMillis()
        if (b.finalizedAtMs == 0L) {
            busBeforeCommit.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("FINALIZED_BUS_PUBLISHED_BEFORE_COMMIT_6817")
                ForensicLogger.lifecycle(
                    "FINALIZED_BUS_PUBLISHED_BEFORE_COMMIT_6817",
                    "commitId=${commitId.take(48)} action=ordering_violation_observed",
                )
            } catch (_: Throwable) {}
        }
    }

    fun isCommitComplete(commitId: String): Boolean =
        batches[commitId]?.let { it.finalizedAtMs > 0L && it.allSidesPresent() } ?: false

    fun postCommitRevision(): Long = postCommitRev.get()

    private fun maybeEvict() {
        if (batches.size <= CAP) return
        val oldest = batches.entries.minByOrNull { it.value.startAtMs }?.key ?: return
        batches.remove(oldest)
    }

    fun statusLine(): String =
        "batches=${batches.size} begins=${begins.get()} finalizes=${finalizes.get()} " +
            "incomplete=${incompleteFinalizes.get()} busBeforeCommit=${busBeforeCommit.get()} " +
            "postCommitRev=${postCommitRev.get()}"

    internal fun clearForTest() {
        batches.clear()
        postCommitRev.set(0L)
        begins.set(0L); finalizes.set(0L)
        incompleteFinalizes.set(0L); busBeforeCommit.set(0L)
    }
}
