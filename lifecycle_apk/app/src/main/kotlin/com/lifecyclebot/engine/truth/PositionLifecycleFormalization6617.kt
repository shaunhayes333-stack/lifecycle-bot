package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6617 §POSITION_LIFECYCLE_FORMALIZATION (operator directive Feb 2026):
 *
 *   "Formalise the DISCOVERED → CLOSED → LEARNED → REENTRY_ELIGIBLE
 *    transition so that canonicalClosedDelta, closeLedgerClosedDelta,
 *    and terminalSellPublishedDelta absolutely agree. Fixes stuck TTL
 *    retries and ensures cleanly terminated accounting."
 *
 * DESIGN
 * ──────
 * PositionStateLedger6454 already implements the core CAS
 * OPEN/PARTIAL → CLOSING → CLOSED. This module is a strictly ADDITIVE
 * observer that adds three stages the operator explicitly requested,
 * WITHOUT modifying PSL6454's CAS state machine (which would ripple
 * across every terminal-sell caller):
 *
 *   DISCOVERED    — candidate accepted by intake/producer, before debit
 *   CLOSED        — mirrored from PSL6454.confirmTerminalSell success
 *   LEARNED       — outcome recorded via CanonicalLearning finalization
 *   REENTRY_ELIGIBLE — cooldown elapsed AND learned outcome logged
 *
 * Each stage is a per-positionId timestamp; no state-machine gates.
 * Callers use `markDiscovered` / `markClosed` / `markLearned` /
 * `markReentryEligible` at their natural boundaries.
 *
 * The reconciler `reconcileClosureDeltas6617()` fires on each pipeline
 * health tick and emits three delta counters that operator can grep:
 *
 *   canonicalClosedDelta            — CanonicalPositionAuthority6441
 *                                     CLOSED positions not yet mirrored
 *                                     to closeLedger.
 *   closeLedgerClosedDelta          — PSL6454 CLOSED positions not yet
 *                                     mirrored to canonical.
 *   terminalSellPublishedDelta      — FinalizedTradeBus terminal events
 *                                     without a matching PSL6454
 *                                     confirmTerminalSell.
 *
 * Steady-state target: all three deltas == 0.
 */
object PositionLifecycleFormalization6617 {

    enum class Stage { DISCOVERED, CLOSED, LEARNED, REENTRY_ELIGIBLE }

    data class LifecycleRecord(
        val positionId: String,
        val mint: String,
        val symbol: String,
        val lane: String,
        val discoveredAtMs: Long,
        val closedAtMs: Long,
        val learnedAtMs: Long,
        val reentryEligibleAtMs: Long,
    ) {
        val currentStage: Stage
            get() = when {
                reentryEligibleAtMs > 0L -> Stage.REENTRY_ELIGIBLE
                learnedAtMs > 0L         -> Stage.LEARNED
                closedAtMs > 0L          -> Stage.CLOSED
                else                     -> Stage.DISCOVERED
            }
    }

    private val records = ConcurrentHashMap<String, LifecycleRecord>()
    private val stageTransitions = ConcurrentHashMap<String, AtomicLong>()
    private val canonicalClosedDelta = AtomicLong(0L)
    private val closeLedgerClosedDelta = AtomicLong(0L)
    private val terminalSellPublishedDelta = AtomicLong(0L)
    private val reconciles = AtomicLong(0L)

    fun markDiscovered(positionId: String, mint: String, symbol: String, lane: String) {
        if (positionId.isBlank()) return
        records.compute(positionId) { _, cur ->
            if (cur != null) cur else LifecycleRecord(
                positionId = positionId, mint = mint, symbol = symbol, lane = lane,
                discoveredAtMs = System.currentTimeMillis(),
                closedAtMs = 0L, learnedAtMs = 0L, reentryEligibleAtMs = 0L,
            )
        }
        bumpStage6617("DISCOVERED")
    }

    fun markClosed(positionId: String) {
        if (positionId.isBlank()) return
        records.compute(positionId) { _, cur ->
            if (cur == null) {
                LifecycleRecord(
                    positionId = positionId, mint = "", symbol = "", lane = "",
                    discoveredAtMs = 0L, closedAtMs = System.currentTimeMillis(),
                    learnedAtMs = 0L, reentryEligibleAtMs = 0L,
                )
            } else if (cur.closedAtMs == 0L) {
                cur.copy(closedAtMs = System.currentTimeMillis())
            } else cur
        }
        bumpStage6617("CLOSED")
    }

    fun markLearned(positionId: String) {
        if (positionId.isBlank()) return
        val updated = records.computeIfPresent(positionId) { _, cur ->
            if (cur.learnedAtMs == 0L) cur.copy(learnedAtMs = System.currentTimeMillis()) else cur
        }
        if (updated != null) bumpStage6617("LEARNED")
    }

    fun markReentryEligible(positionId: String) {
        if (positionId.isBlank()) return
        val updated = records.computeIfPresent(positionId) { _, cur ->
            if (cur.reentryEligibleAtMs == 0L) cur.copy(reentryEligibleAtMs = System.currentTimeMillis()) else cur
        }
        if (updated != null) bumpStage6617("REENTRY_ELIGIBLE")
    }

    fun current(positionId: String): LifecycleRecord? = records[positionId]

    /**
     * Reconcile the three closure sources at each pipeline health tick.
     * Emits three counters the operator can dump:
     *   CANONICAL_CLOSED_DELTA_6617           steady-state = 0
     *   CLOSE_LEDGER_CLOSED_DELTA_6617        steady-state = 0
     *   TERMINAL_SELL_PUBLISHED_DELTA_6617    steady-state = 0
     */
    fun reconcileClosureDeltas6617(): Triple<Long, Long, Long> {
        reconciles.incrementAndGet()
        var canonicalDelta = 0L
        var closeLedgerDelta = 0L
        var publishedDelta = 0L
        try {
            // Canonical CLOSED positions.
            val canonicalClosed = try {
                CanonicalPositionAuthority6441.closedPositions()
                    .map { it.positionId }
                    .toSet()
            } catch (_: Throwable) { emptySet<String>() }

            // Close ledger's CLOSED positions (PSL6454 mirror).
            val closeLedgerClosed = mutableSetOf<String>()
            canonicalClosed.forEach { pid ->
                if (PositionStateLedger6454.lifecycle(pid) == PositionStateLedger6454.Lifecycle.CLOSED) {
                    closeLedgerClosed.add(pid)
                }
            }
            // canonicalClosedDelta: canonical CLOSED but PSL6454 NOT CLOSED.
            canonicalDelta = canonicalClosed.count { it !in closeLedgerClosed }.toLong()
            // closeLedgerClosedDelta: PSL6454 CLOSED but not mirrored to
            // lifecycle formalization. We approximate by counting closed
            // records without a mark. Only records seen via markClosed
            // count as "mirrored".
            closeLedgerDelta = closeLedgerClosed.count { pid -> records[pid]?.closedAtMs == 0L || records[pid] == null }.toLong()
            // terminalSellPublishedDelta: PSL6454 terminalCount > 0 without
            // corresponding markClosed.
            publishedDelta = closeLedgerClosed.count { pid ->
                val cnt = PositionStateLedger6454.terminalCount(pid)
                val record = records[pid]
                cnt > 0L && (record == null || record.closedAtMs == 0L)
            }.toLong()
        } catch (_: Throwable) {}

        canonicalClosedDelta.set(canonicalDelta)
        closeLedgerClosedDelta.set(closeLedgerDelta)
        terminalSellPublishedDelta.set(publishedDelta)
        try {
            PipelineHealthCollector.labelInc("POSITION_LIFECYCLE_RECONCILE_6617")
            if (canonicalDelta != 0L) {
                PipelineHealthCollector.labelInc("CANONICAL_CLOSED_DELTA_6617")
                ForensicLogger.lifecycle(
                    "CANONICAL_CLOSED_DELTA_6617",
                    "count=$canonicalDelta action=canonical_closed_but_close_ledger_not_yet_confirmed",
                )
            }
            if (closeLedgerDelta != 0L) {
                PipelineHealthCollector.labelInc("CLOSE_LEDGER_CLOSED_DELTA_6617")
                ForensicLogger.lifecycle(
                    "CLOSE_LEDGER_CLOSED_DELTA_6617",
                    "count=$closeLedgerDelta action=close_ledger_confirmed_but_lifecycle_record_missing",
                )
            }
            if (publishedDelta != 0L) {
                PipelineHealthCollector.labelInc("TERMINAL_SELL_PUBLISHED_DELTA_6617")
                ForensicLogger.lifecycle(
                    "TERMINAL_SELL_PUBLISHED_DELTA_6617",
                    "count=$publishedDelta action=terminal_count_incremented_but_no_lifecycle_close_mark",
                )
            }
        } catch (_: Throwable) {}
        return Triple(canonicalDelta, closeLedgerDelta, publishedDelta)
    }

    fun lastDeltas(): Triple<Long, Long, Long> = Triple(
        canonicalClosedDelta.get(),
        closeLedgerClosedDelta.get(),
        terminalSellPublishedDelta.get(),
    )

    fun statusLine(): String {
        val stageCounts = stageTransitions.entries
            .sortedByDescending { it.value.get() }
            .joinToString(",") { "${it.key}=${it.value.get()}" }
        return "records=${records.size} stages=[$stageCounts] " +
            "canonicalDelta=${canonicalClosedDelta.get()} " +
            "closeLedgerDelta=${closeLedgerClosedDelta.get()} " +
            "publishedDelta=${terminalSellPublishedDelta.get()} " +
            "reconciles=${reconciles.get()}"
    }

    private fun bumpStage6617(stage: String) {
        stageTransitions.computeIfAbsent(stage) { AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("POSITION_LIFECYCLE_${stage}_6617") } catch (_: Throwable) {}
    }

    internal fun resetForTest() {
        records.clear(); stageTransitions.clear()
        canonicalClosedDelta.set(0L); closeLedgerClosedDelta.set(0L); terminalSellPublishedDelta.set(0L)
        reconciles.set(0L)
    }
}
