package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8144 — ONE SIGNATURE, ONE ACCOUNTING MOVEMENT, ACROSS RESTARTS.
 *
 * AccountingIdempotencyRegistry keys {signature|mint|side} in memory only. After a restart the
 * live-sell finality resume (Executor.resumeLiveSellFinalization7362) or a recovered signed
 * transaction (SignedTxLedger8085.restore) can deliver a signature whose row the journal already
 * holds; the fresh in-memory registry accepts it, and Executor.recordTrade writes a second journal
 * row and fans out a second learning reward for the same chain transaction.
 *
 * The journal is the durable record of what was accounted, so it answers the question: a claim for
 * a signature and side the journal already holds for that mint is a duplicate. The lookup is
 * injected by ReconcileRunner8144 (it reads TradeHistoryStore off the main thread); with no lookup
 * (JVM tests, before the runner starts) the registry behaves exactly as before.
 */
object ReconcileSignatureGuard8144 {
    /** mint -> (signature, side) of the LIVE journal rows already committed for it. */
    @Volatile var journalLookup8144: ((String) -> List<Pair<String, String>>)? = null
    private val blocked = AtomicLong(0)

    /** Pure: does [existing] already hold an accounting movement for this signature and side? */
    fun duplicateOf8144(existing: List<Pair<String, String>>, signature: String, action: String): Boolean {
        if (signature.isBlank()) return false
        val side = action.trim().uppercase()
        return existing.any { (sig, s) -> sig == signature && s.trim().uppercase() == side }
    }

    /** AccountingIdempotencyRegistry.claim: true when the journal already accounted this movement. */
    fun alreadyJournaled8144(signature: String, mint: String, action: String): Boolean {
        // paper close ids (Executor 6508 "PAPER:...") are not chain signatures and never reach the LIVE journal
        if (signature.isBlank() || signature.startsWith("PHANTOM_") || signature.startsWith("PAPER:") || mint.isBlank()) return false
        val lookup = journalLookup8144 ?: return false
        val rows = try { lookup(mint) } catch (_: Throwable) { return false }
        val dup = duplicateOf8144(rows, signature, action)
        if (dup) {
            blocked.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("ACCOUNTING_DUPLICATE_SIGNATURE_BLOCKED_8144")
                ForensicLogger.lifecycle(
                    "ACCOUNTING_DUPLICATE_SIGNATURE_BLOCKED_8144",
                    "sig=${signature.take(16)} mint=${mint.take(10)} side=${action.uppercase()} action=no_second_journal_row_no_second_reward",
                )
            } catch (_: Throwable) {}
        }
        return dup
    }

    fun blockedCount8144(): Long = blocked.get()
}
