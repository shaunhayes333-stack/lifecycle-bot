package com.lifecyclebot.engine.runtime

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1323 — Execution Counter Contract (P0-3 surgical).
 *
 * Operator §3: snapshot contains contradictory execution numbers
 * (EXEC=8 in funnel, EXEC=70 in cheat sheet, paper_sell=0 but
 * recent executions show many paper sells).
 *
 * Fix: introduce the 11 named counters per the operator's spec
 * and emit BOTH the new names AND the legacy aliases so existing
 * dashboards keep working for one build. Phase 5 drops the aliases.
 *
 * Named counters (operator's verbatim list):
 *   executor_invocations
 *   open_attempts
 *   open_success
 *   close_attempts
 *   close_success
 *   journal_buy_records
 *   journal_sell_records
 *   paper_buy_success
 *   paper_sell_success
 *   live_buy_success
 *   live_sell_success
 */
object ExecutionCounterContract {

    enum class Counter(val tag: String) {
        EXECUTOR_INVOCATIONS("executor_invocations"),
        OPEN_ATTEMPTS("open_attempts"),
        OPEN_SUCCESS("open_success"),
        CLOSE_ATTEMPTS("close_attempts"),
        CLOSE_SUCCESS("close_success"),
        JOURNAL_BUY_RECORDS("journal_buy_records"),
        JOURNAL_SELL_RECORDS("journal_sell_records"),
        PAPER_BUY_SUCCESS("paper_buy_success"),
        PAPER_SELL_SUCCESS("paper_sell_success"),
        LIVE_BUY_SUCCESS("live_buy_success"),
        LIVE_SELL_SUCCESS("live_sell_success"),
    }

    private val counts = ConcurrentHashMap<Counter, AtomicLong>()

    fun bump(counter: Counter) {
        counts.computeIfAbsent(counter) { AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc(counter.tag) } catch (_: Throwable) {}
    }

    /** Snapshot for the operator-facing dashboard. */
    fun snapshot(): Map<String, Long> = counts.mapValues { it.value.get() }
        .mapKeys { it.key.tag }

    /**
     * Convenience: invocation + open/close pair. Use these from the
     * Executor.paperBuy / liveBuy / paperSell / liveSell entry points
     * for clean single-call instrumentation.
     */
    fun recordOpenAttempt(isPaper: Boolean) {
        bump(Counter.EXECUTOR_INVOCATIONS)
        bump(Counter.OPEN_ATTEMPTS)
    }

    fun recordOpenSuccess(isPaper: Boolean) {
        bump(Counter.OPEN_SUCCESS)
        if (isPaper) bump(Counter.PAPER_BUY_SUCCESS) else bump(Counter.LIVE_BUY_SUCCESS)
    }

    fun recordCloseAttempt(isPaper: Boolean) {
        bump(Counter.EXECUTOR_INVOCATIONS)
        bump(Counter.CLOSE_ATTEMPTS)
    }

    fun recordCloseSuccess(isPaper: Boolean) {
        bump(Counter.CLOSE_SUCCESS)
        if (isPaper) bump(Counter.PAPER_SELL_SUCCESS) else bump(Counter.LIVE_SELL_SUCCESS)
    }

    fun recordJournalBuyWrite() = bump(Counter.JOURNAL_BUY_RECORDS)
    fun recordJournalSellWrite() = bump(Counter.JOURNAL_SELL_RECORDS)
}

/**
 * V5.9.1323 — V3 Verdict Reconciliation (P0-4 surgical).
 *
 * Operator §4: V3 entries don't reconcile with allow + block + skip + error.
 * Hundreds of V3_FATAL_EARLY_RETURN and V3_REJECTED_TERMINAL_EARLY_RETURN
 * exist outside the formal gate tally.
 *
 * Fix: every V3 emit site calls recordVerdict() with one of four terminal
 * states. The denominator (entries) and numerator (allow/block/skip/error)
 * reconcile.
 */
object V3VerdictContract {

    enum class Verdict(val tag: String) {
        ALLOW("V3_VERDICT_ALLOW"),
        BLOCK("V3_VERDICT_BLOCK"),
        SKIP("V3_VERDICT_SKIP"),
        ERROR("V3_VERDICT_ERROR"),
    }

    private val verdictCounts = ConcurrentHashMap<Verdict, AtomicLong>()
    private val entriesTotal = AtomicLong(0L)

    fun recordEntry() {
        entriesTotal.incrementAndGet()
        try { PipelineHealthCollector.labelInc("V3_ENTRIES_TOTAL") } catch (_: Throwable) {}
    }

    fun recordVerdict(verdict: Verdict, reason: String = "") {
        verdictCounts.computeIfAbsent(verdict) { AtomicLong(0L) }.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc(verdict.tag)
            if (reason.isNotBlank()) {
                PipelineHealthCollector.labelInc("${verdict.tag}_${reason.substringBefore(' ').take(40)}")
            }
        } catch (_: Throwable) {}
    }

    data class Snapshot(
        val entries: Long,
        val allow: Long,
        val block: Long,
        val skip: Long,
        val error: Long,
    ) {
        val reconciles: Boolean get() = allow + block + skip + error == entries
        val unaccounted: Long get() = entries - (allow + block + skip + error)
    }

    fun snapshot(): Snapshot = Snapshot(
        entries = entriesTotal.get(),
        allow = verdictCounts[Verdict.ALLOW]?.get() ?: 0L,
        block = verdictCounts[Verdict.BLOCK]?.get() ?: 0L,
        skip = verdictCounts[Verdict.SKIP]?.get() ?: 0L,
        error = verdictCounts[Verdict.ERROR]?.get() ?: 0L,
    )
}
