package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * V5.0.6635 §CANONICAL_ECONOMIC_EVENT — the single event authority.
 *
 * OPERATOR DIRECTIVE (verbatim, Feb 2026 — "NON-NEGOTIABLE"):
 *   > "Every BUY / SELL / PARTIAL SELL / FEE / ADJUSTMENT must
 *   >  receive one immutable economicEventId ... the exact same
 *   >  economicEventId must appear in: canonical position authority,
 *   >  fill/lot ledger, paper/live account ledger, trade journal,
 *   >  terminal execution record.  No subsystem may independently
 *   >  manufacture another version of the transaction."
 *
 * ARCHITECTURE
 * ────────────
 *                 CANONICAL ECONOMIC EVENT
 *                            │
 *          ┌─────────┬───────┼────────┬──────────────┐
 *          ▼         ▼       ▼        ▼              ▼
 *      Ledger  Journal  Position  Fill/Lot  UI Snapshot
 *
 * The event is CREATED once at the execution boundary.  All five
 * stores accept the event verbatim — none may recompute quantity from
 * price, cash from UI snapshot, or basis from decimals.  If any
 * mandatory commit fails the event moves to
 * ACCOUNTING_RECONCILIATION_PENDING and NO BUY_OK / SELL_OK is
 * reported.
 *
 * SUCCESS CONDITION
 * ─────────────────
 * `PAPER_ATOMIC_COMMIT_OK` (existing) fires only after all five
 * commits succeed against the SAME economicEventId.  The former
 * `..._LEDGER_ONLY` / `..._JOURNAL_ONLY` outcomes are now DEFECTS —
 * the two-phase commit here treats them as forensic evidence of a
 * split write, never as legitimate terminal outcomes.
 *
 * FAILURE MODE — RECONCILIATION_PENDING
 * ─────────────────────────────────────
 * When only a subset of commits succeeded, the event is stamped
 * `ACCOUNTING_RECONCILIATION_PENDING`.  The forensic reconciler
 * (`ReconcilerWatchdog6430`) inspects pending events every tick and
 * emits `FORENSIC_ACCOUNTING_STUCK_PENDING_6635` if any event has
 * been pending longer than 60s.  Learning / hero balance / analytics
 * are strictly forbidden from consuming a PENDING event.
 */
object CanonicalEconomicEvent6635 {

    enum class Side { BUY, SELL, PARTIAL_SELL, FEE, ADJUSTMENT, ROLLBACK }

    enum class Store { POSITION, LEDGER, JOURNAL, FILL_LOT, TERMINAL_EXEC }

    enum class Terminal { OPEN, COMMITTED, PENDING_RECONCILIATION, FAILED }

    data class Event(
        val economicEventId: String,
        val positionId: String,
        val mint: String,
        val canonicalMint: String,
        val symbol: String,
        val mode: String,
        val lane: String,
        val side: Side,
        val timestampMs: Long,
        val qtyRaw: BigInteger,
        val decimals: Int,
        val executionPriceUsd: Double,
        val executionPriceSol: Double,
        val notionalSol: Double,
        val feeSol: Double,
        val cashDeltaSol: Double,
        val positionQtyDeltaRaw: BigInteger,
        val realizedPnlDeltaSol: Double,
        val terminalFillIndex: Int,
    )

    private data class CommitState(
        val event: Event,
        @Volatile var positionCommitted: Boolean = false,
        @Volatile var ledgerCommitted: Boolean = false,
        @Volatile var journalCommitted: Boolean = false,
        @Volatile var fillLotCommitted: Boolean = false,
        @Volatile var terminalExecCommitted: Boolean = false,
        @Volatile var terminal: Terminal = Terminal.OPEN,
        @Volatile var pendingSinceMs: Long = 0L,
        @Volatile var completedAtMs: Long = 0L,
    ) {
        fun allCommitted(): Boolean = positionCommitted && ledgerCommitted &&
            journalCommitted && fillLotCommitted && terminalExecCommitted

        fun anyCommitted(): Boolean = positionCommitted || ledgerCommitted ||
            journalCommitted || fillLotCommitted || terminalExecCommitted

        fun snapshotCommittedFlags(): String =
            "pos=$positionCommitted led=$ledgerCommitted jrn=$journalCommitted " +
                "flt=$fillLotCommitted term=$terminalExecCommitted"
    }

    private val events = ConcurrentHashMap<String, CommitState>()
    private val positionIndex = ConcurrentHashMap<String, String>() // positionId → last-opening event
    private val byMint = ConcurrentHashMap<String, MutableList<String>>() // canonicalMint → eventIds
    private val lock = ReentrantLock()
    private const val CAP = 8192

    private val opened = AtomicLong(0L)
    private val committed = AtomicLong(0L)
    private val pending = AtomicLong(0L)
    private val stuck = AtomicLong(0L)

    /** Mint an event ID.  UUID-based for uniqueness across process restarts. */
    fun mintEventId(prefix: String = "AATE"): String =
        "$prefix-${UUID.randomUUID().toString().replace("-", "").take(20)}-${System.currentTimeMillis()}"

    /**
     * Open the two-phase commit boundary.  Returns the event ID under
     * which every subsequent per-store `markCommitted` MUST be tagged.
     * `positionId` is mandatory — a blank positionId is a defect and
     * this call refuses.
     */
    fun openEvent(event: Event): Boolean {
        if (event.economicEventId.isBlank() || event.positionId.isBlank()) {
            try {
                PipelineHealthCollector.labelInc("CANONICAL_EVENT_OPEN_REFUSED_BLANK_ID_6635")
                ForensicLogger.lifecycle(
                    "CANONICAL_EVENT_OPEN_REFUSED_BLANK_ID_6635",
                    "economicEventId=${event.economicEventId} positionId=${event.positionId} " +
                        "action=blank_id_is_defect_no_transaction",
                )
            } catch (_: Throwable) {}
            return false
        }
        val prev = events.putIfAbsent(event.economicEventId, CommitState(event))
        if (prev != null) {
            try { PipelineHealthCollector.labelInc("CANONICAL_EVENT_DUPLICATE_OPEN_6635") } catch (_: Throwable) {}
            return false
        }
        opened.incrementAndGet()
        try {
            byMint.computeIfAbsent(event.canonicalMint) { java.util.Collections.synchronizedList(mutableListOf()) }
                .add(event.economicEventId)
            if (event.side == Side.BUY) positionIndex[event.positionId] = event.economicEventId
            PipelineHealthCollector.labelInc("CANONICAL_EVENT_OPENED_6635")
            PipelineHealthCollector.labelInc("CANONICAL_EVENT_OPENED_${event.side.name}_6635")
        } catch (_: Throwable) {}
        maybeEvictOldestCommitted()
        return true
    }

    /**
     * Mark one of the five stores committed against this event.
     * Idempotent — a repeated markCommitted for the same (event, store)
     * is a no-op counter (`CANONICAL_EVENT_STORE_DUP_COMMIT_6635`).
     * When all five stores are committed, `finalizeIfComplete6635` is
     * invoked automatically.
     */
    fun markCommitted(economicEventId: String, store: Store, callSite: String): Boolean {
        val state = events[economicEventId] ?: run {
            try {
                PipelineHealthCollector.labelInc("CANONICAL_EVENT_STORE_COMMIT_UNKNOWN_ID_6635")
                ForensicLogger.lifecycle(
                    "CANONICAL_EVENT_STORE_COMMIT_UNKNOWN_ID_6635",
                    "economicEventId=${economicEventId.take(30)} store=${store.name} " +
                        "callSite=$callSite action=commit_without_open_is_defect",
                )
            } catch (_: Throwable) {}
            return false
        }
        val already = when (store) {
            Store.POSITION -> state.positionCommitted.also { state.positionCommitted = true }
            Store.LEDGER -> state.ledgerCommitted.also { state.ledgerCommitted = true }
            Store.JOURNAL -> state.journalCommitted.also { state.journalCommitted = true }
            Store.FILL_LOT -> state.fillLotCommitted.also { state.fillLotCommitted = true }
            Store.TERMINAL_EXEC -> state.terminalExecCommitted.also { state.terminalExecCommitted = true }
        }
        if (already) {
            try {
                PipelineHealthCollector.labelInc("CANONICAL_EVENT_STORE_DUP_COMMIT_6635")
                PipelineHealthCollector.labelInc("CANONICAL_EVENT_STORE_DUP_COMMIT_${store.name}_6635")
            } catch (_: Throwable) {}
            return false
        }
        try {
            PipelineHealthCollector.labelInc("CANONICAL_EVENT_STORE_COMMIT_6635")
            PipelineHealthCollector.labelInc("CANONICAL_EVENT_STORE_COMMIT_${store.name}_6635")
        } catch (_: Throwable) {}
        finalizeIfComplete6635(state)
        return true
    }

    private fun finalizeIfComplete6635(state: CommitState) {
        if (state.terminal != Terminal.OPEN) return
        if (state.allCommitted()) {
            state.terminal = Terminal.COMMITTED
            state.completedAtMs = System.currentTimeMillis()
            committed.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("CANONICAL_EVENT_COMMITTED_6635")
                PipelineHealthCollector.labelInc("CANONICAL_EVENT_COMMITTED_${state.event.side.name}_6635")
                PipelineHealthCollector.labelInc("PAPER_ATOMIC_COMMIT_OK_6635")
            } catch (_: Throwable) {}
        }
    }

    /**
     * Called by the sweeper: any event OPEN longer than `ttlMs` and
     * with partial commits is stamped `PENDING_RECONCILIATION`.  The
     * event is NOT auto-healed; it awaits operator inspection.
     */
    fun sweepPending6635(ttlMs: Long = 60_000L) {
        val now = System.currentTimeMillis()
        for ((_, s) in events) {
            if (s.terminal != Terminal.OPEN) continue
            val age = now - s.event.timestampMs
            if (age < ttlMs) continue
            if (!s.anyCommitted()) continue // no commit at all — a lookup failure, not a partial write
            s.terminal = Terminal.PENDING_RECONCILIATION
            s.pendingSinceMs = now
            pending.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("ACCOUNTING_RECONCILIATION_PENDING_6635")
                PipelineHealthCollector.labelInc("ACCOUNTING_RECONCILIATION_PENDING_${s.event.side.name}_6635")
                ForensicLogger.lifecycle(
                    "ACCOUNTING_RECONCILIATION_PENDING_6635",
                    "economicEventId=${s.event.economicEventId.take(30)} side=${s.event.side} " +
                        "positionId=${s.event.positionId.take(24)} mint=${s.event.canonicalMint.take(12)} " +
                        "flags=${s.snapshotCommittedFlags()} ageMs=$age " +
                        "action=await_operator_no_auto_heal_no_hero_promotion",
                )
            } catch (_: Throwable) {}
            // Escalate stuck-pending after another 60s
            if (age > 2L * ttlMs && s.terminal == Terminal.PENDING_RECONCILIATION) {
                stuck.incrementAndGet()
                try { PipelineHealthCollector.labelInc("FORENSIC_ACCOUNTING_STUCK_PENDING_6635") } catch (_: Throwable) {}
            }
        }
    }

    /**
     * FORENSIC RECONCILIATION HEALTH LINE — operator directive §10.
     * Called by the health-report emitter every cadence tick.
     */
    fun forensicReconciliationLine6635(): String {
        var open = 0; var comm = 0; var pend = 0; var stuckN = 0
        var missingJournal = 0; var missingLedger = 0
        var journalOnly = 0; var ledgerOnly = 0
        var duplicateJournal = 0 // not tracked (schema prevents by construction)
        for ((_, s) in events) {
            when (s.terminal) {
                Terminal.OPEN -> open++
                Terminal.COMMITTED -> comm++
                Terminal.PENDING_RECONCILIATION -> pend++
                Terminal.FAILED -> {}
            }
            if (s.terminal == Terminal.PENDING_RECONCILIATION) {
                if (!s.journalCommitted) missingJournal++
                if (!s.ledgerCommitted) missingLedger++
                if (s.journalCommitted && !s.ledgerCommitted) journalOnly++
                if (s.ledgerCommitted && !s.journalCommitted) ledgerOnly++
            }
            if (s.pendingSinceMs > 0 && System.currentTimeMillis() - s.pendingSinceMs > 60_000L) stuckN++
        }
        val eventParity = (pend == 0 && stuckN == 0)
        val status = if (eventParity && open == 0) "RECONCILED" else "FAILED"
        return buildString {
            append("FORENSIC_ACCOUNTING_RECONCILIATION_6635 ")
            append("canonicalEconomicEvents=${events.size} ")
            append("committed=$comm openInFlight=$open pending=$pend stuck=$stuckN ")
            append("missingJournal=$missingJournal missingLedger=$missingLedger ")
            append("journalOnlyCommits=$journalOnly ledgerOnlyCommits=$ledgerOnly ")
            append("duplicateJournal=$duplicateJournal ")
            append("eventParity=$eventParity status=$status")
        }
    }

    /** Resolve the OPEN event for a positionId (BUY committed, no matching terminal SELL). */
    fun openEventForPosition(positionId: String): Event? {
        if (positionId.isBlank()) return null
        val eventId = positionIndex[positionId] ?: return null
        val state = events[eventId] ?: return null
        if (state.event.side != Side.BUY) return null
        return state.event
    }

    fun eventById(economicEventId: String): Event? = events[economicEventId]?.event

    fun eventsForCanonicalMint(canonicalMint: String): List<Event> {
        val ids = byMint[canonicalMint] ?: return emptyList()
        return ids.mapNotNull { events[it]?.event }
    }

    fun clearPositionIndex(positionId: String) {
        positionIndex.remove(positionId)
    }

    private fun maybeEvictOldestCommitted() {
        if (events.size <= CAP) return
        val oldest = events.entries
            .filter { it.value.terminal == Terminal.COMMITTED }
            .minByOrNull { it.value.completedAtMs }
            ?.key ?: return
        events.remove(oldest)
    }

    fun statusLine6635(): String =
        "opened=${opened.get()} committed=${committed.get()} pending=${pending.get()} " +
            "stuck=${stuck.get()} inRing=${events.size}"

    internal fun resetForTest() {
        events.clear(); positionIndex.clear(); byMint.clear()
        opened.set(0L); committed.set(0L); pending.set(0L); stuck.set(0L)
    }
}
