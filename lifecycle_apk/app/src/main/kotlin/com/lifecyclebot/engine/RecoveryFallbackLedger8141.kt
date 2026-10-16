package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8141 — capital-recovery input counts the legacy-fallback proceeds (no Android, no clock).
 *
 * Executor.profitLockSlice7884 commits a live profit-lock / capital-recovery slice through the canonical slice
 * authority; when that commit is unavailable (no canonical proof, finalize pending, canonical row missing) it books the
 * slice with the legacy accounting only. The canonical position never sees those proceeds, so
 * RunnerExitLive8136.recoveryTranche8136 — which sizes the next tranche on entry cost less the canonical CONFIRMED
 * proceeds — re-sold capital that had already come back. Each confirmed legacy-fallback fill (chain-verified SOL
 * delta, one per signature) is kept here per position and added to the canonical proceeds in the tranche input.
 * Unconfirmed fills are never counted. The canonical commit for a proof is attempted once (the proof is consumed), so
 * the same proceeds cannot reach both books.
 *
 * V5.0.8144 — the book survives a process death and never re-counts a signature (ExitTruthProceedsBook8144):
 *  - every confirmed fill is persisted (recovery_proceeds_8144.json) by immutable positionId + tx signature + token
 *    quantity consumed + actual SOL delta, and rebuilt on the first read after a restart;
 *  - dedup is durable and bounded: the 8141 seen-fill set was CLEARED at capacity (a replayed signature counted
 *    twice); active rows are now never evicted and retired signatures stay in a dedup ring;
 *  - on every read the position's rows are reconciled against the canonical economic events: a row whose signature a
 *    later backfill committed canonically leaves the fallback sum, so canonical + fallback never double counts;
 *  - a terminal position's rows are retired (TailBag8034.onClose8034, the canonical terminal fanout).
 */
object RecoveryFallbackLedger8141 {
    private const val FILE_8144 = "recovery_proceeds_8144.json"
    private const val STAGED_CAP_8144 = 256

    private val book = ExitTruthProceedsBook8144()
    /** signature -> raw token quantity the verifier saw consumed (staged by Executor just before the note). */
    private val stagedQty = ConcurrentHashMap<String, java.math.BigInteger>()
    @Volatile private var loaded8144 = false

    /** Pure: the confirmed proceeds the recovery tranche reads — canonical plus legacy fallback (non-finite / negative read as 0). */
    fun effectiveProceeds8141(canonicalProceedsSol: Double, fallbackProceedsSol: Double): Double {
        val c = if (canonicalProceedsSol.isFinite()) canonicalProceedsSol.coerceAtLeast(0.0) else 0.0
        val f = if (fallbackProceedsSol.isFinite()) fallbackProceedsSol.coerceAtLeast(0.0) else 0.0
        return c + f
    }

    /** Pure: capital still unrecovered once the legacy-fallback proceeds are counted (RunnerExitContract8136.unrecoveredSol8136). */
    fun unrecoveredSol8141(entryCostSol: Double, canonicalProceedsSol: Double, fallbackProceedsSol: Double, feesSol: Double): Double =
        RunnerExitContract8136.unrecoveredSol8136(entryCostSol, effectiveProceeds8141(canonicalProceedsSol, fallbackProceedsSol), feesSol)

    // ── V5.0.8144 — durable store ──

    private fun file8144(): java.io.File? = com.lifecyclebot.AATEApp.appContextOrNull()?.let { java.io.File(it.filesDir, FILE_8144) }

    private fun ensureLoaded8144() {
        if (loaded8144) return
        // No store yet (early boot / JVM test): latching now would let the next save replace the file with empty state.
        val f = try { file8144() } catch (_: Throwable) { null } ?: return
        synchronized(this) {
            if (loaded8144) return
            loaded8144 = true
            val n = try { if (f.exists()) book.restore8144(f.readText()) else 0 } catch (_: Throwable) { 0 }
            if (n > 0) try {
                PipelineHealthCollector.labelInc("RECOVERY_PROCEEDS_REBUILT_8144")
                ForensicLogger.lifecycle("RECOVERY_PROCEEDS_REBUILT_8144", "fills=$n action=durable_fallback_proceeds_restored")
            } catch (_: Throwable) {}
        }
    }

    private fun save8144() {
        try { file8144()?.writeTextAtomic8086(book.encode8144()) } catch (_: Throwable) {}
    }

    /** The signatures the canonical economic events carry for [positionId] (a partial's key ends in its signature). */
    private fun canonicalSignatures8144(positionId: String): Set<String> = try {
        com.lifecyclebot.engine.truth.EconomicEventSchema6464.snapshot().asSequence()
            .filterIsInstance<com.lifecyclebot.engine.truth.EconomicEventSchema6464.Sell>()
            .filter { it.positionId == positionId && it.mode.equals("live", true) }
            .flatMap { sequenceOf(it.idempotencyKey.trim(), it.idempotencyKey.substringAfterLast('|').trim()) }
            .filter { it.isNotEmpty() }
            .toSet()
    } catch (_: Throwable) { emptySet() }

    /** Executor.profitLockSlice7884: the raw token quantity the verifier proved [signature] consumed (read by the note). */
    fun stageFillQty8144(signature: String, consumedRaw: java.math.BigInteger?) {
        if (signature.isBlank() || consumedRaw == null || consumedRaw.signum() <= 0) return
        if (stagedQty.size >= STAGED_CAP_8144) stagedQty.clear()
        stagedQty[signature.trim()] = consumedRaw
    }

    /**
     * Executor.profitLockSlice7884, legacy path: a slice of [positionId] filled for [proceedsSol]. Counted only when
     * [confirmed] (the verifier proved the landed SOL delta) and once per [signature] — ever. True when counted.
     */
    fun noteLegacyFallback8141(positionId: String, signature: String, proceedsSol: Double, confirmed: Boolean): Boolean {
        ensureLoaded8144()
        val qty = if (signature.isBlank()) null else stagedQty.remove(signature.trim())
        val note = book.note8144(positionId, signature, qty, proceedsSol, confirmed, System.currentTimeMillis())
        if (note == ExitTruthProceedsBook8144.Note8144.DUPLICATE_SIGNATURE || note == ExitTruthProceedsBook8144.Note8144.FULL) {
            try { PipelineHealthCollector.labelInc("RECOVERY_LEGACY_FALLBACK_${note.name}_8144") } catch (_: Throwable) {}
        }
        if (note != ExitTruthProceedsBook8144.Note8144.COUNTED) return false
        save8144()
        try { PipelineHealthCollector.labelInc("RECOVERY_LEGACY_FALLBACK_PROCEEDS_COUNTED_8141") } catch (_: Throwable) {}
        return true
    }

    /**
     * Confirmed legacy-fallback proceeds of [positionId] (0 when none) that the canonical book does not already carry:
     * the position's rows are reconciled against the canonical economic events first.
     */
    fun confirmedFallbackSol8141(positionId: String): Double {
        if (positionId.isBlank()) return 0.0
        ensureLoaded8144()
        if (!book.hasFills8144(positionId)) return 0.0
        if (book.reconcile8144(positionId, canonicalSignatures8144(positionId)) > 0) {
            save8144()
            try { PipelineHealthCollector.labelInc("RECOVERY_FALLBACK_RECONCILED_TO_CANONICAL_8144") } catch (_: Throwable) {}
        }
        return book.unreconciledSol8144(positionId)
    }

    /** TailBag8034.onClose8034 (canonical terminal fanout): [positionId] is closed — its rows retire, signatures stay deduplicated. */
    fun retire8144(positionId: String) {
        if (positionId.isBlank()) return
        ensureLoaded8144()
        if (book.retire8144(positionId) > 0) save8144()
    }

    fun statusLine8144(): String {
        ensureLoaded8144()
        return "fills=${book.size8144()} durable=${loaded8144}"
    }

    internal fun clearForTest8141() {
        book.clear8144()
        stagedQty.clear()
    }
}
