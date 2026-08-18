package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6464 §P0-#4 — DUPLICATE CLOSE CONFIRMATION (CAS + idempotency).
 *
 * OPERATOR MANDATE:
 *   "doubleConfirm=32 → target 0. Every economic sell needs immutable
 *    sellExecutionId + positionId + fillId. Terminal mutation must use
 *    CAS: NEW → CONFIRMED. Second observation with same sellExecutionId
 *    /fillId: DUPLICATE_CONFIRMATION_IGNORED. Must NOT subtract qty,
 *    allocate cost basis, add proceeds, add realized PnL, generate
 *    reward, or transition CLOSED again."
 *
 * DESIGN
 * ──────
 * The idempotency key is `(sellExecutionId || fillId || signature)` —
 * whichever the caller has first. First observation transitions
 *   NEW -> CONFIRMED  (atomic CAS)
 * and returns `Consume.PROCEED`. Every subsequent observation with the
 * same key returns `Consume.DUPLICATE_IGNORED` and the caller MUST bail
 * out of all side effects.
 *
 * Audit all sibling paths: standard sell, stop loss, trailing stop,
 * partial TP, universal SL, catastrophic exit, dust healer,
 * reconciliation replay. Each path calls `beginTerminal` and gates its
 * side effects on the PROCEED verdict.
 */
object TerminalSellIdempotency6464 {

    enum class Consume { PROCEED, DUPLICATE_IGNORED, BLANK_KEY }

    private enum class State { NEW, CONFIRMED }

    private data class Record(
        val key: String,
        val positionId: String,
        val sitePath: String,
        val firstAtMs: Long,
        val state: State,
    )

    private val records = ConcurrentHashMap<String, Record>()
    private const val CAP = 4096       // ring size — recent terminal sells only

    private val firstConfirms = AtomicLong(0L)
    private val duplicates = AtomicLong(0L)
    private val blanks = AtomicLong(0L)

    /**
     * Build a stable idempotency key from whichever identifier the
     * caller has. Priority: sellExecutionId > fillId > signature.
     */
    fun makeKey(sellExecutionId: String?, fillId: String?, signature: String?): String {
        val cand = sequenceOf(sellExecutionId, fillId, signature).firstOrNull { !it.isNullOrBlank() }
        return cand?.trim() ?: ""
    }

    /**
     * Enter the terminal mutation critical section. Returns PROCEED
     * on the first observation and DUPLICATE_IGNORED on all subsequent
     * ones with the same key.
     */
    fun beginTerminal(
        key: String,
        positionId: String,
        sitePath: String,
    ): Consume {
        if (key.isBlank()) {
            blanks.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "TERMINAL_SELL_IDEMPOTENCY_BLANK_KEY_6464",
                    "sitePath=$sitePath positionId=${positionId.take(16)}",
                )
                PipelineHealthCollector.labelInc("TERMINAL_SELL_IDEMPOTENCY_BLANK_KEY_6464")
            } catch (_: Throwable) {}
            return Consume.BLANK_KEY
        }
        var existed = true
        val rec = records.compute(key) { _, cur ->
            if (cur != null) return@compute cur
            existed = false
            Record(
                key = key, positionId = positionId, sitePath = sitePath,
                firstAtMs = System.currentTimeMillis(), state = State.CONFIRMED,
            )
        } ?: return Consume.BLANK_KEY
        if (!existed) {
            firstConfirms.incrementAndGet()
            try { PipelineHealthCollector.labelInc("TERMINAL_SELL_IDEMPOTENCY_FIRST_6464") } catch (_: Throwable) {}
            // Bounded eviction — drop oldest if over cap.
            if (records.size > CAP) {
                val victim = records.entries.minByOrNull { it.value.firstAtMs }?.key
                if (victim != null) records.remove(victim)
            }
            return Consume.PROCEED
        }
        duplicates.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "TERMINAL_SELL_DUPLICATE_CONFIRMATION_IGNORED_6464",
                "key=${key.take(20)} sitePath=$sitePath firstSitePath=${rec.sitePath} positionId=${positionId.take(16)} " +
                    "ageMs=${System.currentTimeMillis() - rec.firstAtMs}",
            )
            PipelineHealthCollector.labelInc("TERMINAL_SELL_DUPLICATE_CONFIRMATION_IGNORED_6464")
        } catch (_: Throwable) {}
        return Consume.DUPLICATE_IGNORED
    }

    fun statusLine(): String =
        "tracked=${records.size} firstConfirms=${firstConfirms.get()} duplicates=${duplicates.get()} blanks=${blanks.get()}"

    internal fun resetForTest() {
        records.clear()
        firstConfirms.set(0L); duplicates.set(0L); blanks.set(0L)
    }
}
