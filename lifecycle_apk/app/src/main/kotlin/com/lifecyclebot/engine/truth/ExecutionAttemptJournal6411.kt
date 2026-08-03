package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6411 §5.2 — EXECUTION-ATTEMPT JOURNAL.
 *
 * OPERATOR DIRECTIVE (Feb 2026)
 * ─────────────────────────────
 * Current defect (build 6410):
 *   EXEC_OPEN_ALLOWED = 501, TRADEJRNL_REC = 0
 * → 501 authorised trades left no forensic record of why they never
 *   executed. This journal fills that gap: one row per execution
 *   intent even when NO transaction is submitted.
 *
 * DESIGN
 * ──────
 *   • Bounded in-memory ring (last N rows) so the phone doesn't
 *     accumulate unbounded state; a proper SQLite-backed table can
 *     replace this in V5.0.6412+.
 *   • Every append emits EXEC_ATTEMPT_JOURNAL_WRITTEN_6411 so the
 *     forensic funnel sees the row.
 *   • Snapshot exposes counts + last-N rows for the health report.
 *
 * This is NOT the canonical trade journal. Rows here are ATTEMPTS.
 * Canonical trade rows are only created after chain-confirmed fills.
 */
object ExecutionAttemptJournal6411 {

    private const val CAP = 512

    enum class TerminalReason {
        NONE,
        ROUTE_ADAPTER_SELECTED,
        ROUTE_UNAVAILABLE_TERMINAL,
        VENUE_UNSUPPORTED,
        JUPITER_SKIPPED_CIRCUIT_OPEN,
        NO_HEALTHY_ADAPTER,
        QUOTE_FAILED,
        SIMULATION_FAILED,
        RPC_SEND_FAILED,
        SUBMITTED,
        CONFIRMED,
        SUBMISSION_UNKNOWN_RECONCILE,
        DUPLICATE_SUPPRESSED,
        SAFETY_REJECTED,
        TICKET_EXPIRED,
        STALE_CANCELLED,
    }

    data class Row(
        val createdAtMs: Long,
        val decisionId: String,
        val executionIntentId: String,
        val mint: String,
        val symbol: String,
        val lane: String,
        val wallet: String,
        val venue: ExecutableVenue6411?,
        val adapterSelected: ExecutionAdapter6411?,
        val adapterCandidates: List<ExecutionAdapter6411>,
        val requestedSol: Double,
        val ticketState: String,
        val terminalReason: TerminalReason,
        val reasonDetail: String,
    )

    private val rows = ConcurrentLinkedDeque<Row>()
    private val written = AtomicLong(0L)

    fun append(row: Row) {
        rows.addLast(row)
        while (rows.size > CAP) rows.pollFirst()
        written.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "EXEC_ATTEMPT_JOURNAL_WRITTEN_6411",
                "intent=${row.executionIntentId.take(16)} decision=${row.decisionId.take(16)} " +
                    "mint=${row.mint.take(10)} sym=${row.symbol} lane=${row.lane} " +
                    "venue=${row.venue?.name ?: "-"} adapter=${row.adapterSelected?.name ?: "-"} " +
                    "candidates=[${row.adapterCandidates.joinToString(",") { it.name }}] " +
                    "state=${row.ticketState} terminal=${row.terminalReason} " +
                    "reason=${row.reasonDetail.take(120)}",
            )
            PipelineHealthCollector.labelInc("EXEC_ATTEMPT_JOURNAL_WRITTEN_6411")
        } catch (_: Throwable) {}
    }

    fun snapshot(limit: Int = 20): List<Row> {
        val n = rows.size
        val take = if (n <= limit) rows.toList() else rows.toList().subList(n - limit, n)
        return take
    }

    fun writtenCount(): Long = written.get()

    fun statusLine(): String {
        val n = rows.size
        val last = rows.peekLast()
        return "written=${written.get()} bufSize=$n lastTerminal=${last?.terminalReason ?: "-"} lastAdapter=${last?.adapterSelected?.name ?: "-"}"
    }

    internal fun resetForTest() {
        rows.clear()
        written.set(0L)
    }
}
