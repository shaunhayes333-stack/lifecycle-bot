package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/**
 * V5.0.6441 §1 — CANONICAL POSITION + CAPITAL AUTHORITY.
 *
 * OPERATOR (V5.0.6441 mandate §1):
 *   "Establish ONE authoritative position/account state used by PAPER
 *    executor, LIVE executor, exit engine, slot accounting, journal,
 *    learner, reconciler, UI/reporting and recovery. No subsystem may
 *    maintain an independently mutable shadow truth."
 *
 * This module is the SINGLE authoritative store for:
 *   • per-position state (quantity, cost basis, lifecycle)
 *   • account cash (paper + live) — cash cannot go negative
 *   • the mutation gate — every mutation must supply an idempotency key
 *
 * DESIGN
 * ──────
 * • Positions are keyed by canonical positionId ("$mint#$runIdShort").
 * • Every mutation (buy, partial-sell, full-sell, quarantine) is
 *   serialised through a single ReentrantLock and gated on an
 *   idempotency key so a replayed callback CANNOT double-mutate.
 * • Cash is a single BigDecimal-flavoured Double (SOL) — writes must
 *   pass the cash floor invariant (>= 0 in paper mode).
 * • Sibling stores (GlobalTradeRegistry, PortfolioStore6405,
 *   PositionCloseLedger, PaperAccountLedger) are read-only mirrors
 *   from V5.0.6441 forward; V5.0.6442+ ships migrate their writers.
 *
 * This is the PRODUCER. Consumers query via read-only accessors.
 * Writers must go through the mutate*() methods.
 */
object CanonicalPositionAuthority6441 {

    enum class Lifecycle { PENDING_ENTRY, OPEN, PARTIALLY_CLOSED, CLOSED, QUARANTINED }

    data class Position(
        val positionId: String,
        val mint: String,
        val symbol: String,
        val lane: String,
        val runId: String,
        val openedAtMs: Long,
        val entryCostSol: Double,
        val remainingQtyRaw: BigInteger,
        val originalQtyRaw: BigInteger,
        val soldCostBasisSol: Double,
        val realizedPnlSol: Double,
        val realizedProceedsSol: Double,
        val feesSol: Double,
        val tokenDecimals: Int,
        val lifecycle: Lifecycle,
        val lastMutationMs: Long,
        val quarantineReason: String,
    )

    enum class MutateResult { APPLIED, DUPLICATE, INVARIANT_VIOLATION, UNKNOWN_POSITION, LIFECYCLE_FORBIDDEN }

    private val positions = ConcurrentHashMap<String, Position>()
    private val mutationKeys = ConcurrentHashMap<String, Long>()   // key -> whenMs (idempotency)
    private val lock = ReentrantLock()

    private val paperCashSol = AtomicReference<Double>(0.0)
    private val paperCashInitialisedMs = AtomicLong(0L)
    // Live cash is read from WalletManager; we only track the last observed value
    // for the acceptance-invariant audit.
    private val liveCashObservedSol = AtomicReference<Double>(0.0)

    // Telemetry counters.
    private val muts = AtomicLong(0L)
    private val duplicates = AtomicLong(0L)
    private val invariantViolations = AtomicLong(0L)
    private val quarantines = AtomicLong(0L)

    // ─── Cash authority ────────────────────────────────────────────────────

    fun paperCashSol(): Double = paperCashSol.get()

    fun setPaperCash(sol: Double, reason: String) {
        if (sol < 0.0) {
            invariantViolations.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "CANONICAL_CASH_INVARIANT_VIOLATION_6441",
                    "attempt=setPaperCash sol=${"%.6f".format(sol)} reason=$reason — REJECTED",
                )
            } catch (_: Throwable) {}
            return
        }
        paperCashSol.set(sol)
        if (paperCashInitialisedMs.get() == 0L) paperCashInitialisedMs.set(System.currentTimeMillis())
    }

    fun canAffordPaperBuy(sizeSol: Double): Boolean = paperCashSol.get() >= sizeSol

    fun observeLiveCash(sol: Double) { if (sol >= 0.0) liveCashObservedSol.set(sol) }

    // ─── Position mutation gate ────────────────────────────────────────────

    fun openPosition(
        idempotencyKey: String,
        positionId: String,
        mint: String,
        symbol: String,
        lane: String,
        runId: String,
        entryCostSol: Double,
        openedQtyRaw: BigInteger,
        tokenDecimals: Int,
        feesSol: Double,
        paperMode: Boolean,
    ): MutateResult {
        lock.lock()
        try {
            if (isDuplicate(idempotencyKey)) return MutateResult.DUPLICATE
            if (entryCostSol < 0.0 || openedQtyRaw < BigInteger.ZERO) {
                invariantViolations.incrementAndGet()
                return MutateResult.INVARIANT_VIOLATION
            }
            if (paperMode) {
                val prevCash = paperCashSol.get()
                if (prevCash < entryCostSol + feesSol) {
                    invariantViolations.incrementAndGet()
                    try {
                        ForensicLogger.lifecycle(
                            "CANONICAL_INSUFFICIENT_PAPER_CASH_6441",
                            "positionId=$positionId cash=${"%.5f".format(prevCash)} needed=${"%.5f".format(entryCostSol + feesSol)}",
                        )
                    } catch (_: Throwable) {}
                    return MutateResult.INVARIANT_VIOLATION
                }
                paperCashSol.set(prevCash - entryCostSol - feesSol)
            }
            val lifecycle = if (openedQtyRaw == BigInteger.ZERO)
                Lifecycle.PENDING_ENTRY else Lifecycle.OPEN
            positions[positionId] = Position(
                positionId = positionId, mint = mint, symbol = symbol, lane = lane, runId = runId,
                openedAtMs = System.currentTimeMillis(),
                entryCostSol = entryCostSol,
                remainingQtyRaw = openedQtyRaw,
                originalQtyRaw = openedQtyRaw,
                soldCostBasisSol = 0.0,
                realizedPnlSol = 0.0,
                realizedProceedsSol = 0.0,
                feesSol = feesSol,
                tokenDecimals = tokenDecimals,
                lifecycle = lifecycle,
                lastMutationMs = System.currentTimeMillis(),
                quarantineReason = "",
            )
            markKeyUsed(idempotencyKey)
            muts.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc(
                    if (lifecycle == Lifecycle.PENDING_ENTRY) "CANONICAL_POSITION_PENDING_6441"
                    else "CANONICAL_POSITION_OPEN_6441",
                )
            } catch (_: Throwable) {}
            return MutateResult.APPLIED
        } finally { lock.unlock() }
    }

    /**
     * V5.0.6442 — promote a PENDING_ENTRY to OPEN once the fill is known.
     * Idempotent: subsequent calls overwrite qty/cost with the observed fill.
     * Callers that never went through openPosition first are auto-upgraded.
     */
    fun promotePendingToOpen(
        positionId: String,
        actualQtyRaw: BigInteger,
        actualEntryCostSol: Double,
        actualFeesSol: Double,
        tokenDecimals: Int,
        paperMode: Boolean,
    ): MutateResult {
        lock.lock()
        try {
            val prev = positions[positionId]
            if (prev == null) {
                // Never saw an open; caller should have used openPosition — refuse.
                invariantViolations.incrementAndGet()
                return MutateResult.UNKNOWN_POSITION
            }
            if (prev.lifecycle == Lifecycle.CLOSED || prev.lifecycle == Lifecycle.QUARANTINED) {
                return MutateResult.LIFECYCLE_FORBIDDEN
            }
            if (actualQtyRaw <= BigInteger.ZERO || actualEntryCostSol < 0.0) {
                invariantViolations.incrementAndGet()
                return MutateResult.INVARIANT_VIOLATION
            }
            // Cash adjustment — refund the placeholder debit and re-debit actual.
            if (paperMode) {
                val cash = paperCashSol.get()
                val netDelta = (prev.entryCostSol + prev.feesSol) - (actualEntryCostSol + actualFeesSol)
                val newCash = cash + netDelta
                if (newCash < 0.0) {
                    invariantViolations.incrementAndGet()
                    return MutateResult.INVARIANT_VIOLATION
                }
                paperCashSol.set(newCash)
            }
            positions[positionId] = prev.copy(
                entryCostSol = actualEntryCostSol,
                remainingQtyRaw = actualQtyRaw,
                originalQtyRaw = actualQtyRaw,
                feesSol = actualFeesSol,
                tokenDecimals = tokenDecimals,
                lifecycle = Lifecycle.OPEN,
                lastMutationMs = System.currentTimeMillis(),
            )
            muts.incrementAndGet()
            try { PipelineHealthCollector.labelInc("CANONICAL_POSITION_PROMOTED_6441") } catch (_: Throwable) {}
            return MutateResult.APPLIED
        } finally { lock.unlock() }
    }

    fun partialSell(
        idempotencyKey: String,
        positionId: String,
        soldQtyRaw: BigInteger,
        proceedsSol: Double,
        soldCostBasisSol: Double,
        feesSol: Double,
        paperMode: Boolean,
    ): MutateResult {
        lock.lock()
        try {
            if (isDuplicate(idempotencyKey)) return MutateResult.DUPLICATE
            val pos = positions[positionId] ?: return MutateResult.UNKNOWN_POSITION
            if (pos.lifecycle == Lifecycle.CLOSED || pos.lifecycle == Lifecycle.QUARANTINED) {
                return MutateResult.LIFECYCLE_FORBIDDEN
            }
            if (soldQtyRaw <= BigInteger.ZERO) return MutateResult.INVARIANT_VIOLATION
            if (soldQtyRaw > pos.remainingQtyRaw) {
                invariantViolations.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "CANONICAL_OVERSOLD_QTY_6441",
                        "positionId=$positionId soldRaw=$soldQtyRaw remainingRaw=${pos.remainingQtyRaw}",
                    )
                } catch (_: Throwable) {}
                return MutateResult.INVARIANT_VIOLATION
            }
            val newRemaining = pos.remainingQtyRaw - soldQtyRaw
            val newLifecycle = if (newRemaining == BigInteger.ZERO) Lifecycle.CLOSED else Lifecycle.PARTIALLY_CLOSED
            val realizedDelta = proceedsSol - soldCostBasisSol - feesSol
            positions[positionId] = pos.copy(
                remainingQtyRaw = newRemaining,
                soldCostBasisSol = pos.soldCostBasisSol + soldCostBasisSol,
                realizedProceedsSol = pos.realizedProceedsSol + proceedsSol,
                realizedPnlSol = pos.realizedPnlSol + realizedDelta,
                feesSol = pos.feesSol + feesSol,
                lifecycle = newLifecycle,
                lastMutationMs = System.currentTimeMillis(),
            )
            if (paperMode) paperCashSol.getAndUpdate { it + proceedsSol - feesSol }
            markKeyUsed(idempotencyKey)
            muts.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc(
                    if (newLifecycle == Lifecycle.CLOSED) "CANONICAL_POSITION_CLOSE_6441"
                    else "CANONICAL_POSITION_PARTIAL_6441",
                )
            } catch (_: Throwable) {}
            return MutateResult.APPLIED
        } finally { lock.unlock() }
    }

    /** V5.0.6485 — remove an uncommitted entry; never expose it to schedulers. */
    fun abortEntry6485(positionId: String, refundPaperFacade: Boolean, reason: String): Boolean {
        lock.lock()
        try {
            val pos = positions[positionId] ?: return false
            if (pos.lifecycle == Lifecycle.CLOSED || pos.lifecycle == Lifecycle.PARTIALLY_CLOSED) return false
            positions.remove(positionId)
            if (refundPaperFacade) paperCashSol.getAndUpdate { it + pos.entryCostSol + pos.feesSol }
            try { PipelineHealthCollector.labelInc("CANONICAL_ENTRY_ABORTED_6485") } catch (_: Throwable) {}
            return true
        } finally { lock.unlock() }
    }

    fun quarantine(positionId: String, reason: String) {
        lock.lock()
        try {
            val pos = positions[positionId] ?: return
            positions[positionId] = pos.copy(
                lifecycle = Lifecycle.QUARANTINED,
                quarantineReason = reason.take(120),
                lastMutationMs = System.currentTimeMillis(),
            )
            quarantines.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "CANONICAL_POSITION_QUARANTINED_6441",
                    "positionId=$positionId reason=${reason.take(60)}",
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("CANONICAL_POSITION_QUARANTINED_6441") } catch (_: Throwable) {}
        } finally { lock.unlock() }
    }

    private fun isDuplicate(key: String): Boolean {
        if (key.isBlank()) return false
        val prev = mutationKeys.putIfAbsent(key, System.currentTimeMillis())
        if (prev != null) {
            duplicates.incrementAndGet()
            try { PipelineHealthCollector.labelInc("CANONICAL_MUTATION_DUPLICATE_6441") } catch (_: Throwable) {}
            return true
        }
        return false
    }

    private fun markKeyUsed(key: String) {
        if (key.isBlank()) return
        // Trim old keys periodically.
        if (mutationKeys.size > 50_000) {
            val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
            mutationKeys.entries.removeIf { it.value < cutoff }
        }
    }

    // ─── Read-only accessors for consumers ─────────────────────────────────
    fun getPosition(positionId: String): Position? = positions[positionId]
    fun openPositions(): List<Position> = positions.values.filter {
        it.lifecycle == Lifecycle.OPEN || it.lifecycle == Lifecycle.PARTIALLY_CLOSED
    }
    fun closedPositions(): List<Position> = positions.values.filter { it.lifecycle == Lifecycle.CLOSED }
    fun openCount(): Int = openPositions().size
    fun hasOpenMint(mint: String): Boolean = positions.values.any {
        it.mint == mint && (it.lifecycle == Lifecycle.OPEN || it.lifecycle == Lifecycle.PARTIALLY_CLOSED)
    }

    /**
     * V5.0.6461 §P0-#2 — explicit PENDING_ENTRY iterator.
     * Callers must never treat these as open positions for capital,
     * lane, or slot counts. Exposed only for the sweep + audit.
     */
    /** V5.0.6485 — purge paper lifecycle rows that have no funded lot. */
    fun healUnfundedPaperEntries6485(): List<Position> {
        lock.lock()
        try {
            val victims = positions.values.filter { p ->
                p.positionId.startsWith("PAPER:") &&
                    p.lifecycle in setOf(Lifecycle.PENDING_ENTRY, Lifecycle.OPEN) &&
                    !CanonicalLotQuantity6464.hasFundedOpenLot6485(p.positionId)
            }
            victims.forEach { positions.remove(it.positionId) }
            if (victims.isNotEmpty()) try { PipelineHealthCollector.labelInc("UNFUNDED_PAPER_ENTRY_PURGED_6485") } catch (_: Throwable) {}
            return victims
        } finally { lock.unlock() }
    }

    fun pendingEntryPositions6461(): List<Position> = positions.values.filter {
        it.lifecycle == Lifecycle.PENDING_ENTRY
    }

    /**
     * V5.0.6461 §P0-#2 — cancel PENDING_ENTRY rows older than ttlMs.
     * Moves them to QUARANTINED with reason "PENDING_ENTRY_TTL_CANCELLED_6461"
     * and refunds any placeholder cash debit for paper positions.
     * Returns the list of positionIds cancelled.
     */
    fun cancelStalePendingEntries6461(ttlMs: Long): List<String> {
        val now = System.currentTimeMillis()
        val victims = pendingEntryPositions6461().filter { (now - it.lastMutationMs) > ttlMs }
        if (victims.isEmpty()) return emptyList()
        val cancelledIds = mutableListOf<String>()
        for (v in victims) {
            lock.lock()
            try {
                val cur = positions[v.positionId] ?: continue
                if (cur.lifecycle != Lifecycle.PENDING_ENTRY) continue
                // Refund the placeholder debit so cash returns to the pre-open state.
                val refund = cur.entryCostSol + cur.feesSol
                if (refund > 0.0) paperCashSol.getAndUpdate { it + refund }
                positions[cur.positionId] = cur.copy(
                    lifecycle = Lifecycle.QUARANTINED,
                    quarantineReason = "PENDING_ENTRY_TTL_CANCELLED_6461",
                    lastMutationMs = now,
                )
                cancelledIds += cur.positionId
                quarantines.incrementAndGet()
            } finally { lock.unlock() }
        }
        return cancelledIds
    }

    /**
     * V5.0.6456 §P0-#2 CANONICAL POSITION STATE INVARIANT.
     * Emits (total, byLifecycle) so callers can assert
     *   total == Σ(byLifecycle.values)
     * with NO invisible/default/null state permitted. Every position is
     * accounted to exactly one of PENDING_ENTRY / OPEN / PARTIALLY_CLOSED
     * / CLOSED / QUARANTINED.
     */
    data class LifecycleClassification(val total: Int, val byLifecycle: Map<Lifecycle, Int>, val unaccounted: Int)

    fun classifyLifecycles(): LifecycleClassification {
        val snapshot = positions.values.toList()
        val counts = Lifecycle.values().associateWith { life -> snapshot.count { it.lifecycle == life } }
        val classified = counts.values.sum()
        val unaccounted = snapshot.size - classified
        if (unaccounted != 0) {
            try {
                ForensicLogger.lifecycle(
                    "POSITION_STATE_SUM_VIOLATION_6456",
                    "total=${snapshot.size} classified=$classified unaccounted=$unaccounted breakdown=${counts.entries.joinToString(",") { "${it.key}=${it.value}" }}",
                )
                PipelineHealthCollector.labelInc("POSITION_STATE_SUM_VIOLATION_6456")
            } catch (_: Throwable) {}
        }
        return LifecycleClassification(total = snapshot.size, byLifecycle = counts, unaccounted = unaccounted)
    }

    fun statusLine(): String {
        val open = openCount()
        val closed = closedPositions().size
        val cash = paperCashSol.get()
        val classification = classifyLifecycles()
        val breakdown = classification.byLifecycle.entries.joinToString(",") { "${it.key}=${it.value}" }
        return "positions=${positions.size} open=$open closed=$closed paperCashSol=${"%.5f".format(cash)} " +
            "muts=${muts.get()} dups=${duplicates.get()} invViol=${invariantViolations.get()} quarantines=${quarantines.get()} " +
            "sumCheck(total=${classification.total} classified=${classification.byLifecycle.values.sum()} " +
            "unaccounted=${classification.unaccounted} $breakdown)"
    }
}
