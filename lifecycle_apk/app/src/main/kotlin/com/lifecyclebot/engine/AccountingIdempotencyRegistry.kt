package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6324 — ACCOUNTING IDEMPOTENCY (operator hotfix §13).
 *
 * A single confirmed transaction may fire several parallel callbacks:
 * websocket-observed broadcast, polling confirmation, wallet
 * reconciliation, UI backfill, retry sweep. Each one used to be able
 * to mutate the trade journal or the canonical position independently,
 * producing duplicate PnL rows and inflating governor sample counts
 * with the same underlying fill.
 *
 * This registry gives a single key
 *   {signature|mint|action|instructionIndex?}
 * per accounting mutation, backed by an in-memory ConcurrentHashMap
 * with an LRU-style eviction cap. First writer wins; every subsequent
 * caller with the same key is rejected via [claim] returning false and
 * the label [REJECT_LABEL] is emitted so the operator can see how much
 * duplicate accounting is being suppressed.
 *
 * The store is intentionally in-memory only: on process restart every
 * mutation is proven by the canonical wallet delta + transaction parse
 * anyway (P1/P4), so a fresh boot legitimately re-accounts pending
 * events. Cross-restart de-duplication is handled by the journal side
 * (signature is already stored on every SELL row).
 */
object AccountingIdempotencyRegistry {

    private const val CAP: Int = 2_048
    private const val REJECT_LABEL: String = "ACCOUNTING_IDEMPOTENCY_REJECTED_6324"
    private const val ACCEPT_LABEL: String = "ACCOUNTING_IDEMPOTENCY_CLAIMED_6324"

    private val seen = ConcurrentHashMap<String, Long>()

    /** Compose the canonical idempotency key. `instructionIndex` is
     *  optional — pass `null` if the callsite doesn't have it (falls
     *  back to signature+mint+action). */
    fun keyFor(
        signature: String,
        mint: String,
        action: String,
        instructionIndex: Int? = null,
    ): String {
        val sig = signature.trim().ifBlank { "NOSIG" }
        val m = mint.trim().ifBlank { "NOMINT" }
        val a = action.trim().uppercase().ifBlank { "UNKNOWN" }
        return if (instructionIndex != null) "$sig|$m|$a|$instructionIndex" else "$sig|$m|$a"
    }

    /**
     * Attempt to claim the given key for a mutation. Returns true if
     * the caller is the first writer and MAY proceed; returns false if
     * the key was already claimed and the mutation MUST be skipped.
     * Blank signatures always allow (early estimates without a real
     * on-chain tx yet — those are handled by the canonical position
     * authority ladder, not this registry).
     */
    fun claim(key: String, reasonForLog: String = ""): Boolean {
        // Blank / obviously synthetic keys always allow — they represent
        // pre-signature estimates that will be superseded once a real
        // signature lands.
        if (key.isBlank() || key.startsWith("NOSIG|") || key.contains("|PHANTOM_")) return true
        val now = System.currentTimeMillis()
        val prev = seen.putIfAbsent(key, now)
        return if (prev == null) {
            // First writer wins.
            evictIfOverCap()
            try { PipelineHealthCollector.labelInc(ACCEPT_LABEL) } catch (_: Throwable) {}
            true
        } else {
            try {
                ForensicLogger.lifecycle(
                    REJECT_LABEL,
                    "key=${key.take(96)} prevMs=$prev nowMs=$now ageMs=${now - prev} reason=${reasonForLog.take(64)}",
                )
                PipelineHealthCollector.labelInc(REJECT_LABEL)
            } catch (_: Throwable) {}
            false
        }
    }

    /** Convenience overload for the common signature+mint+action shape. */
    fun claim(signature: String, mint: String, action: String, reasonForLog: String = ""): Boolean =
        claim(keyFor(signature, mint, action), reasonForLog)

    fun size(): Int = seen.size

    fun reset() = seen.clear()

    private fun evictIfOverCap() {
        if (seen.size <= CAP) return
        // Simple oldest-first eviction — cheap because we only run when
        // the map crosses the cap, which is rare in normal operation.
        try {
            val toDrop = seen.entries.sortedBy { it.value }.take(seen.size - CAP + 128)
            for (e in toDrop) seen.remove(e.key, e.value)
        } catch (_: Throwable) {}
    }
}
