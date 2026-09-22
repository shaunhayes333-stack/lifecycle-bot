package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7231 §SELL_OK_TRUTH — operator diagnosis (7227):
 *
 *   SELL ok/fail/pending: 920 / 0 / 0
 *   Recent journal rows: 13
 *   Canonical lifetime closes: 492
 *   SELL_FINALIZED_ONCE: 910   EXEC_TRACE_SELL: 2090
 *
 * PROBLEM — the "ok" counter is being incremented by re-entrant exit
 * work (redispatch, already-closing, duplicate terminal claim,
 * reconciler observations). It cannot be used for strategy learning,
 * performance metrics, reliability statistics or operator decisions.
 *
 * PURPOSE — reconcile a UNIQUE sell counter, keyed by
 *   (transactionSignature) primary,
 *   (canonicalCloseId) fallback,
 * so re-entrant traffic is visible and separable from real terminal
 * events.  Additive telemetry only: never blocks execution.
 *
 * ACCEPTANCE (from operator #24-26):
 *   SELL_OK increments only after unique transaction finality AND one
 *   canonical terminal mutation.  Redispatch / already-closing /
 *   duplicate terminal claim / reconciler observation are NOT
 *   successful sells.
 */
object SellFinalityUniqueCounter7231 {

    private data class Uniqueness(
        val signature: String,
        val canonicalCloseId: String,
        val firstSeenMs: Long,
        val redispatchCount: AtomicLong = AtomicLong(0L),
    )

    private val bySignature = ConcurrentHashMap<String, Uniqueness>()
    private val byCloseId = ConcurrentHashMap<String, Uniqueness>()

    private val uniqueSells = AtomicLong(0L)
    private val redispatches = AtomicLong(0L)
    private val alreadyClosing = AtomicLong(0L)
    private val duplicateTerminalClaims = AtomicLong(0L)
    private val reconcilerObservations = AtomicLong(0L)

    /**
     * Called at unique tx-finality + one canonical terminal mutation.
     * Returns true if this pair is genuinely new; false if it
     * duplicates an earlier finality.
     */
    fun recordFinality(transactionSignature: String, canonicalCloseId: String): Boolean {
        val sig = transactionSignature.trim()
        val cid = canonicalCloseId.trim()
        if (sig.isBlank() && cid.isBlank()) {
            // Nothing to key on - count as ambiguous, treat as
            // duplicate rather than manufacturing a unique event.
            duplicateTerminalClaims.incrementAndGet()
            try { PipelineHealthCollector.labelInc("SELL_FINALITY_UNKEYED_7231") } catch (_: Throwable) {}
            return false
        }
        val existing: Uniqueness? = if (sig.isNotBlank()) bySignature[sig] else byCloseId[cid]
        if (existing != null) {
            existing.redispatchCount.incrementAndGet()
            redispatches.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("SELL_FINALITY_REDISPATCH_7231")
                ForensicLogger.lifecycle(
                    "SELL_FINALITY_REDISPATCH_7231",
                    "sig=${sig.take(12)} closeId=${cid.take(12)} " +
                        "redispatchCount=${existing.redispatchCount.get()} " +
                        "action=NOT_a_unique_sell",
                )
            } catch (_: Throwable) {}
            return false
        }
        val u = Uniqueness(sig, cid, System.currentTimeMillis())
        if (sig.isNotBlank()) bySignature[sig] = u
        if (cid.isNotBlank()) byCloseId[cid] = u
        uniqueSells.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("SELL_FINALITY_UNIQUE_7231")
        } catch (_: Throwable) {}
        return true
    }

    fun recordAlreadyClosing() {
        alreadyClosing.incrementAndGet()
        try { PipelineHealthCollector.labelInc("SELL_ALREADY_CLOSING_7231") } catch (_: Throwable) {}
    }

    fun recordReconcilerObservation() {
        reconcilerObservations.incrementAndGet()
        try { PipelineHealthCollector.labelInc("SELL_RECONCILER_OBSERVATION_7231") } catch (_: Throwable) {}
    }

    data class Summary(
        val uniqueSells: Long,
        val redispatches: Long,
        val alreadyClosing: Long,
        val duplicateTerminalClaims: Long,
        val reconcilerObservations: Long,
    )

    fun summary(): Summary = Summary(
        uniqueSells = uniqueSells.get(),
        redispatches = redispatches.get(),
        alreadyClosing = alreadyClosing.get(),
        duplicateTerminalClaims = duplicateTerminalClaims.get(),
        reconcilerObservations = reconcilerObservations.get(),
    )

    fun statusLine(): String {
        val s = summary()
        return "SellFinalityUniqueCounter7231 unique=${s.uniqueSells} " +
            "redispatch=${s.redispatches} alreadyClosing=${s.alreadyClosing} " +
            "duplicateClaims=${s.duplicateTerminalClaims} reconciler=${s.reconcilerObservations}"
    }

    internal fun clearForTest() {
        bySignature.clear(); byCloseId.clear()
        uniqueSells.set(0L); redispatches.set(0L); alreadyClosing.set(0L)
        duplicateTerminalClaims.set(0L); reconcilerObservations.set(0L)
    }
}
