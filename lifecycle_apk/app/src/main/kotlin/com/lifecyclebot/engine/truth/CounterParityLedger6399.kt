package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6399 — COUNTER PARITY LEDGER.
 *
 * All funnel counters are DERIVED from canonical terminal events —
 * no independent increments. Parity guards fail loudly on drift.
 *
 * Required equations:
 *   FDG_TOTAL = ALLOW_LIVE + ALLOW_SHADOW + BLOCK_SCORE + BLOCK_HARD_SAFETY
 *             + DEFER_HYDRATION + DEFER_COOLDOWN
 *   LIVE_BUY_ATTEMPTS = count(BUY_ATTEMPT where routeMode=LIVE)
 *   LIVE_BUY_FAILURES = count(BUY_FAILED after real attempt)
 *   Live authority tickets == FDG_ALLOW_LIVE
 *   Live executor invocations <= valid live authority tickets
 *   BUY_ATTEMPT <= live executor invocations
 *   BUY_FAILED  <= BUY_ATTEMPT
 */
object CounterParityLedger6399 {

    private val fdgTerminalCounts = ConcurrentHashMap<FdgTerminalOutcome6399, AtomicLong>().apply {
        FdgTerminalOutcome6399.values().forEach { put(it, AtomicLong(0L)) }
    }
    val liveAuthorityTicketsIssued = AtomicLong(0L)
    val liveExecutorInvocations = AtomicLong(0L)
    val liveBuyAttempts = AtomicLong(0L)
    val liveBuyFailures = AtomicLong(0L)
    val sellExecutorInvocations = AtomicLong(0L)

    val parityViolations = AtomicLong(0L)

    /** Record one canonical terminal FDG outcome. Increments the correct bucket. */
    fun recordTerminal(outcome: FdgTerminalOutcome6399) {
        fdgTerminalCounts.getValue(outcome).incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("FDG_${outcome.name}_6399") } catch (_: Throwable) {}
    }

    fun recordLiveTicketIssued() { liveAuthorityTicketsIssued.incrementAndGet() }
    fun recordLiveExecutorInvocation() { liveExecutorInvocations.incrementAndGet() }
    fun recordSellExecutorInvocation() { sellExecutorInvocations.incrementAndGet() }
    fun recordBuyAttempt() { liveBuyAttempts.incrementAndGet() }
    fun recordBuyFailure() { liveBuyFailures.incrementAndGet() }

    fun fdgTotal(): Long = fdgTerminalCounts.values.sumOf { it.get() }
    fun fdgCount(o: FdgTerminalOutcome6399): Long = fdgTerminalCounts.getValue(o).get()

    data class ParityReport(val ok: Boolean, val violations: List<String>)

    fun checkParity(): ParityReport {
        val v = mutableListOf<String>()
        val allowLive = fdgCount(FdgTerminalOutcome6399.FDG_ALLOW_LIVE)
        if (liveAuthorityTicketsIssued.get() != allowLive)
            v += "TICKETS(${liveAuthorityTicketsIssued.get()}) != FDG_ALLOW_LIVE($allowLive)"
        if (liveExecutorInvocations.get() > liveAuthorityTicketsIssued.get())
            v += "EXEC(${liveExecutorInvocations.get()}) > TICKETS(${liveAuthorityTicketsIssued.get()})"
        if (liveBuyAttempts.get() > liveExecutorInvocations.get())
            v += "BUY_ATTEMPT(${liveBuyAttempts.get()}) > EXEC(${liveExecutorInvocations.get()})"
        if (liveBuyFailures.get() > liveBuyAttempts.get())
            v += "BUY_FAILED(${liveBuyFailures.get()}) > BUY_ATTEMPT(${liveBuyAttempts.get()})"
        if (v.isNotEmpty()) parityViolations.incrementAndGet()
        return ParityReport(v.isEmpty(), v)
    }

    /** Snapshot dump for the health report. */
    data class Snapshot(
        val fdgTotal: Long,
        val terminalCounts: Map<FdgTerminalOutcome6399, Long>,
        val liveTickets: Long,
        val liveExec: Long,
        val buyAttempts: Long,
        val buyFailures: Long,
        val sellExec: Long,
        val parityOk: Boolean,
        val parityViolations: List<String>,
    )
    fun snapshot(): Snapshot {
        val terminals = FdgTerminalOutcome6399.values().associateWith { fdgCount(it) }
        val p = checkParity()
        return Snapshot(
            fdgTotal = fdgTotal(), terminalCounts = terminals,
            liveTickets = liveAuthorityTicketsIssued.get(),
            liveExec = liveExecutorInvocations.get(),
            buyAttempts = liveBuyAttempts.get(),
            buyFailures = liveBuyFailures.get(),
            sellExec = sellExecutorInvocations.get(),
            parityOk = p.ok, parityViolations = p.violations,
        )
    }

    internal fun clearAllForTest() {
        fdgTerminalCounts.values.forEach { it.set(0L) }
        liveAuthorityTicketsIssued.set(0L)
        liveExecutorInvocations.set(0L)
        liveBuyAttempts.set(0L)
        liveBuyFailures.set(0L)
        sellExecutorInvocations.set(0L)
        parityViolations.set(0L)
    }
}
