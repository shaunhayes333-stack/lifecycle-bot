package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6632 §P0-A — ONE PAPER ECONOMIC EVENT AUTHORITY (attempt-keyed atomic commit).
 *
 * OPERATOR DIRECTIVE (verbatim, Feb 2026):
 *   > "REMOVE THE DUAL PAPER ECONOMIC AUTHORITY.
 *   >  Establish EXACTLY ONE durable paper economic event authority
 *   >  based on attemptId + side + terminalFillIndex. Make BUY / SELL
 *   >  commits perfectly atomic across position, ledger, snapshot,
 *   >  and journal. No ledger mutation may exist without its matching
 *   >  journal row; no journal row may exist without its matching
 *   >  ledger mutation; both must be visible to any hero surface at
 *   >  the same monotonic revision."
 *
 * FORENSIC EVIDENCE FUELING THIS AUTHORITY (5.0.6616 → 5.0.6631):
 *   PAPER_LEDGER_VS_JOURNAL_DIVERGENCE_6619 = non-zero on every dump
 *   PAPER_CLOSE_NO_JOURNAL_ROW_6623         = 30-149 per session
 *   HERO_JOURNAL_PARITY_FAIL_6616           = 54 at the last checkpoint
 *
 * Every one of those counters says the same thing: a mutation
 * happened on ONE side of the economy without a matching mutation on
 * the OTHER side.  This authority is the single source-level witness
 * that gates every paper economic mutation on a key and refuses to
 * treat the mutation as canonically committed until BOTH sides have
 * stamped the same key.
 *
 * DESIGN
 * ──────
 *  1. The atomic key is `"$attemptId|$side|$terminalFillIndex"` (or
 *     `"$mint|$side|$sigBucket"` for legacy callers that never
 *     surfaced attemptId).  Any writer that lacks the identifier
 *     synthesises it deterministically from the mutation payload —
 *     the important property is idempotency, not opacity.
 *  2. `stampLedger(key, ...)` / `stampJournal(key, ...)` each mark
 *     their side present.  Both sides present → the key is
 *     `COMMITTED` and `PAPER_ATOMIC_COMMIT_OK_6632` fires; the
 *     JournalEconomicAuthority's snapshot revision is bumped so
 *     hero surfaces reading the next tick observe both sides atomically.
 *  3. `sweepUnpaired6632(ttlMs)` runs periodically and fires
 *     `PAPER_ATOMIC_COMMIT_LEDGER_ONLY_6632` or
 *     `PAPER_ATOMIC_COMMIT_JOURNAL_ONLY_6632` for any key that has
 *     been half-committed for longer than `ttlMs`.  This is the
 *     causal-source telemetry the operator needs — half-writes are
 *     visible at the write site, not merely deducible from a
 *     divergence probe run against pre-existing accumulators.
 *  4. Idempotency: a stamp with a key already `COMMITTED` returns
 *     `Verdict.DUPLICATE_IGNORED` and the caller MUST bail out of
 *     the mutation.  This subsumes the existing per-side idempotency
 *     latches (TerminalSellIdempotency6464 &
 *     PaperCatastrophicCloseIdempotency6497) with a single canonical
 *     key.
 *
 * This module never mutates ledger nor journal itself.  It is a
 * witness + gate.  Wiring lives in `PaperAccountLedger6430` (ledger
 * writers) and `TradeHistoryStore` (journal writers).
 */
object PaperEconomicAtomicCommit6632 {

    enum class Side { BUY, SELL, PARTIAL_SELL, ROLLBACK_BUY, PURGE }

    enum class Verdict { PROCEED, DUPLICATE_IGNORED, BLANK_KEY }

    private data class Entry(
        val key: String,
        val mint: String,
        val side: Side,
        val createdAtMs: Long,
        @Volatile var ledgerAtMs: Long = 0L,
        @Volatile var journalAtMs: Long = 0L,
        @Volatile var committedAtMs: Long = 0L,
        @Volatile var ledgerCall: String = "",
        @Volatile var journalCall: String = "",
    ) {
        fun bothStamped(): Boolean = ledgerAtMs > 0L && journalAtMs > 0L
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private const val CAP = 4096

    private val ledgerStamps = AtomicLong(0L)
    private val journalStamps = AtomicLong(0L)
    private val commits = AtomicLong(0L)
    private val duplicateLedger = AtomicLong(0L)
    private val duplicateJournal = AtomicLong(0L)
    private val blankLedger = AtomicLong(0L)
    private val blankJournal = AtomicLong(0L)
    private val ledgerOnlyExpired = AtomicLong(0L)
    private val journalOnlyExpired = AtomicLong(0L)
    private val lastCommitKey = AtomicReference<String>("")

    /** Default half-write TTL — matches `PAPER_CLOSE_NO_JOURNAL_ROW_6623`. */
    const val DEFAULT_UNPAIRED_TTL_MS: Long = 60_000L

    /**
     * Build a stable atomic-commit key.  If `attemptId` is present,
     * key = `attemptId|side|terminalFillIndex`.  Otherwise the caller
     * falls back to a payload signature so a repeated identical
     * mutation still stamps the same slot idempotently.
     *
     * `sigBucket` is a caller-derived, rounded scalar (e.g. gross SOL
     * rounded to 6dp) that catches phantom retries.  Blank sigBucket
     * degrades to timestamp bucketing (see `keyFromMintSide`).
     */
    fun keyFromAttempt(attemptId: String?, side: Side, terminalFillIndex: Int): String {
        val a = attemptId?.trim().orEmpty()
        if (a.isBlank()) return ""
        return "$a|${side.name}|$terminalFillIndex"
    }

    fun keyFromMintSide(
        mint: String,
        side: Side,
        sigBucket: String = "",
        timeWindowMs: Long = 1_000L,
    ): String {
        val m = mint.trim()
        if (m.isBlank()) return ""
        val bucket = if (sigBucket.isNotBlank()) sigBucket
        else (System.currentTimeMillis() / timeWindowMs.coerceAtLeast(1L)).toString()
        return "$m|${side.name}|$bucket"
    }

    /**
     * Stamp the LEDGER side of an atomic paper commit.  Returns
     * PROCEED on first observation, DUPLICATE_IGNORED if this key's
     * ledger side is already stamped.  A duplicate stamp MUST NOT
     * mutate the ledger — the caller bails out.
     */
    fun stampLedger(
        key: String,
        mint: String,
        side: Side,
        callSite: String,
    ): Verdict {
        if (key.isBlank()) {
            blankLedger.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("PAPER_ATOMIC_COMMIT_LEDGER_BLANK_KEY_6632")
                ForensicLogger.lifecycle(
                    "PAPER_ATOMIC_COMMIT_LEDGER_BLANK_KEY_6632",
                    "mint=${mint.take(10)} side=$side callSite=$callSite " +
                        "action=cannot_pair_with_journal_missing_attemptId_or_signature",
                )
            } catch (_: Throwable) {}
            return Verdict.BLANK_KEY
        }
        var duplicate = false
        val entry = entries.compute(key) { _, cur ->
            if (cur == null) {
                val now = System.currentTimeMillis()
                Entry(
                    key = key, mint = mint, side = side,
                    createdAtMs = now, ledgerAtMs = now, ledgerCall = callSite,
                )
            } else {
                if (cur.ledgerAtMs > 0L) {
                    duplicate = true
                    cur
                } else {
                    cur.also {
                        it.ledgerAtMs = System.currentTimeMillis()
                        it.ledgerCall = callSite
                    }
                }
            }
        } ?: return Verdict.BLANK_KEY

        if (duplicate) {
            duplicateLedger.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("PAPER_ATOMIC_COMMIT_LEDGER_DUPLICATE_6632")
                ForensicLogger.lifecycle(
                    "PAPER_ATOMIC_COMMIT_LEDGER_DUPLICATE_6632",
                    "key=${key.take(48)} mint=${mint.take(10)} side=$side " +
                        "firstCallSite=${entry.ledgerCall} newCallSite=$callSite " +
                        "ageMs=${System.currentTimeMillis() - entry.ledgerAtMs}",
                )
            } catch (_: Throwable) {}
            return Verdict.DUPLICATE_IGNORED
        }
        ledgerStamps.incrementAndGet()
        try { PipelineHealthCollector.labelInc("PAPER_ATOMIC_COMMIT_LEDGER_STAMPED_6632") } catch (_: Throwable) {}
        maybeMarkCommitted(entry)
        maybeEvictOldest()
        return Verdict.PROCEED
    }

    /**
     * Stamp the JOURNAL side of an atomic paper commit.  Same
     * semantics as `stampLedger`.
     */
    fun stampJournal(
        key: String,
        mint: String,
        side: Side,
        callSite: String,
    ): Verdict {
        if (key.isBlank()) {
            blankJournal.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("PAPER_ATOMIC_COMMIT_JOURNAL_BLANK_KEY_6632")
                ForensicLogger.lifecycle(
                    "PAPER_ATOMIC_COMMIT_JOURNAL_BLANK_KEY_6632",
                    "mint=${mint.take(10)} side=$side callSite=$callSite " +
                        "action=cannot_pair_with_ledger_missing_attemptId_or_signature",
                )
            } catch (_: Throwable) {}
            return Verdict.BLANK_KEY
        }
        var duplicate = false
        val entry = entries.compute(key) { _, cur ->
            if (cur == null) {
                val now = System.currentTimeMillis()
                Entry(
                    key = key, mint = mint, side = side,
                    createdAtMs = now, journalAtMs = now, journalCall = callSite,
                )
            } else {
                if (cur.journalAtMs > 0L) {
                    duplicate = true
                    cur
                } else {
                    cur.also {
                        it.journalAtMs = System.currentTimeMillis()
                        it.journalCall = callSite
                    }
                }
            }
        } ?: return Verdict.BLANK_KEY

        if (duplicate) {
            duplicateJournal.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("PAPER_ATOMIC_COMMIT_JOURNAL_DUPLICATE_6632")
                ForensicLogger.lifecycle(
                    "PAPER_ATOMIC_COMMIT_JOURNAL_DUPLICATE_6632",
                    "key=${key.take(48)} mint=${mint.take(10)} side=$side " +
                        "firstCallSite=${entry.journalCall} newCallSite=$callSite " +
                        "ageMs=${System.currentTimeMillis() - entry.journalAtMs}",
                )
            } catch (_: Throwable) {}
            return Verdict.DUPLICATE_IGNORED
        }
        journalStamps.incrementAndGet()
        try { PipelineHealthCollector.labelInc("PAPER_ATOMIC_COMMIT_JOURNAL_STAMPED_6632") } catch (_: Throwable) {}
        maybeMarkCommitted(entry)
        maybeEvictOldest()
        return Verdict.PROCEED
    }

    private fun maybeMarkCommitted(e: Entry) {
        if (e.bothStamped() && e.committedAtMs == 0L) {
            e.committedAtMs = System.currentTimeMillis()
            commits.incrementAndGet()
            lastCommitKey.set(e.key)
            try {
                PipelineHealthCollector.labelInc("PAPER_ATOMIC_COMMIT_OK_6632")
                PipelineHealthCollector.labelInc("PAPER_ATOMIC_COMMIT_OK_${e.side.name}_6632")
                ForensicLogger.lifecycle(
                    "PAPER_ATOMIC_COMMIT_OK_6632",
                    "key=${e.key.take(48)} mint=${e.mint.take(10)} side=${e.side} " +
                        "ledgerAgeMs=${e.committedAtMs - e.ledgerAtMs} " +
                        "journalAgeMs=${e.committedAtMs - e.journalAtMs} " +
                        "ledgerCall=${e.ledgerCall} journalCall=${e.journalCall}",
                )
            } catch (_: Throwable) {}
            // Bump snapshot revision so hero surfaces reading the next
            // tick observe both sides atomically. Defensive: journal
            // authority may not be initialised in early boot.
            try {
                JournalEconomicAuthority6616.notifyEconomicMutation("ATOMIC_${e.side.name}")
            } catch (_: Throwable) {}
        }
    }

    /**
     * Sweep entries whose only one side is stamped and whose age
     * exceeds `ttlMs`.  Emits the appropriate half-write counter for
     * each and removes the entry from the ring.  Called from the
     * BotService loop and reconciler watchdog.
     */
    fun sweepUnpaired6632(ttlMs: Long = DEFAULT_UNPAIRED_TTL_MS) {
        val now = System.currentTimeMillis()
        val victims = mutableListOf<Entry>()
        for ((_, e) in entries) {
            if (e.committedAtMs > 0L) continue
            val age = now - e.createdAtMs
            if (age < ttlMs) continue
            victims.add(e)
        }
        for (e in victims) {
            when {
                e.ledgerAtMs > 0L && e.journalAtMs == 0L -> {
                    ledgerOnlyExpired.incrementAndGet()
                    try {
                        PipelineHealthCollector.labelInc("PAPER_ATOMIC_COMMIT_LEDGER_ONLY_6632")
                        PipelineHealthCollector.labelInc("PAPER_ATOMIC_COMMIT_LEDGER_ONLY_${e.side.name}_6632")
                        ForensicLogger.lifecycle(
                            "PAPER_ATOMIC_COMMIT_LEDGER_ONLY_6632",
                            "key=${e.key.take(48)} mint=${e.mint.take(10)} side=${e.side} " +
                                "ledgerCall=${e.ledgerCall} ageMs=${now - e.ledgerAtMs} " +
                                "action=ledger_mutated_without_journal_row_p0_a_violation",
                        )
                    } catch (_: Throwable) {}
                }
                e.journalAtMs > 0L && e.ledgerAtMs == 0L -> {
                    journalOnlyExpired.incrementAndGet()
                    try {
                        PipelineHealthCollector.labelInc("PAPER_ATOMIC_COMMIT_JOURNAL_ONLY_6632")
                        PipelineHealthCollector.labelInc("PAPER_ATOMIC_COMMIT_JOURNAL_ONLY_${e.side.name}_6632")
                        ForensicLogger.lifecycle(
                            "PAPER_ATOMIC_COMMIT_JOURNAL_ONLY_6632",
                            "key=${e.key.take(48)} mint=${e.mint.take(10)} side=${e.side} " +
                                "journalCall=${e.journalCall} ageMs=${now - e.journalAtMs} " +
                                "action=journal_row_without_ledger_mutation_p0_a_violation",
                        )
                    } catch (_: Throwable) {}
                }
                else -> { /* both zero — impossible: entry only exists via a stamp */ }
            }
            entries.remove(e.key)
        }
    }

    private fun maybeEvictOldest() {
        if (entries.size <= CAP) return
        val oldest = entries.entries.minByOrNull { it.value.createdAtMs }?.key ?: return
        entries.remove(oldest)
    }

    fun isCommitted(key: String): Boolean = entries[key]?.committedAtMs?.let { it > 0L } ?: false

    fun statusLine6632(): String {
        return "entries=${entries.size} ledgerStamps=${ledgerStamps.get()} " +
            "journalStamps=${journalStamps.get()} commits=${commits.get()} " +
            "dupLedger=${duplicateLedger.get()} dupJournal=${duplicateJournal.get()} " +
            "blankLedger=${blankLedger.get()} blankJournal=${blankJournal.get()} " +
            "ledgerOnly=${ledgerOnlyExpired.get()} journalOnly=${journalOnlyExpired.get()} " +
            "lastCommitKey=${lastCommitKey.get().take(48)}"
    }

    internal fun resetForTest() {
        entries.clear()
        ledgerStamps.set(0L); journalStamps.set(0L); commits.set(0L)
        duplicateLedger.set(0L); duplicateJournal.set(0L)
        blankLedger.set(0L); blankJournal.set(0L)
        ledgerOnlyExpired.set(0L); journalOnlyExpired.set(0L)
        lastCommitKey.set("")
    }
}
