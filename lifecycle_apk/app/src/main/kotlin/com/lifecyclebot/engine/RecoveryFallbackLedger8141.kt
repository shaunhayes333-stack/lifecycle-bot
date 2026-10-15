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
 */
object RecoveryFallbackLedger8141 {
    private const val CAP_8141 = 2_000

    private val proceedsByPosition = ConcurrentHashMap<String, Double>()
    private val seenFills = ConcurrentHashMap.newKeySet<String>()

    /** Pure: the confirmed proceeds the recovery tranche reads — canonical plus legacy fallback (non-finite / negative read as 0). */
    fun effectiveProceeds8141(canonicalProceedsSol: Double, fallbackProceedsSol: Double): Double {
        val c = if (canonicalProceedsSol.isFinite()) canonicalProceedsSol.coerceAtLeast(0.0) else 0.0
        val f = if (fallbackProceedsSol.isFinite()) fallbackProceedsSol.coerceAtLeast(0.0) else 0.0
        return c + f
    }

    /** Pure: capital still unrecovered once the legacy-fallback proceeds are counted (RunnerExitContract8136.unrecoveredSol8136). */
    fun unrecoveredSol8141(entryCostSol: Double, canonicalProceedsSol: Double, fallbackProceedsSol: Double, feesSol: Double): Double =
        RunnerExitContract8136.unrecoveredSol8136(entryCostSol, effectiveProceeds8141(canonicalProceedsSol, fallbackProceedsSol), feesSol)

    /**
     * Executor.profitLockSlice7884, legacy path: a slice of [positionId] filled for [proceedsSol]. Counted only when
     * [confirmed] (the verifier proved the landed SOL delta) and once per [signature]. True when counted.
     */
    fun noteLegacyFallback8141(positionId: String, signature: String, proceedsSol: Double, confirmed: Boolean): Boolean {
        if (!confirmed || positionId.isBlank() || signature.isBlank()) return false
        if (!proceedsSol.isFinite() || proceedsSol <= 0.0) return false
        if (seenFills.size > CAP_8141 * 4) seenFills.clear()
        if (!seenFills.add("$positionId|$signature")) return false
        if (proceedsByPosition.size >= CAP_8141 && !proceedsByPosition.containsKey(positionId)) return false
        proceedsByPosition.merge(positionId, proceedsSol) { a, b -> a + b }
        try { PipelineHealthCollector.labelInc("RECOVERY_LEGACY_FALLBACK_PROCEEDS_COUNTED_8141") } catch (_: Throwable) {}
        return true
    }

    /** Confirmed legacy-fallback proceeds of [positionId] (0 when none). */
    fun confirmedFallbackSol8141(positionId: String): Double =
        if (positionId.isBlank()) 0.0 else proceedsByPosition[positionId] ?: 0.0

    internal fun clearForTest8141() {
        proceedsByPosition.clear()
        seenFills.clear()
    }
}
