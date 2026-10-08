package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.7884 — Cortex Phase 0 ("make the data true") acceptance read-out.
 *
 * One snapshot line showing that each Phase 0 repair is acting: stale marks
 * refused instead of re-stamped, stale rug windows skipped, loss exits that
 * used to resolve to NONE now in the emergency class, the remote kill switch
 * consulted, and profit-lock partials booked through the canonical slice.
 */
object CortexPhase0Status7884 {
    private fun n(k: String): Long = PipelineHealthCollector.labelCountSnapshot(k)

    fun statusLine(): String {
        val remote = try {
            if (com.lifecyclebot.engine.RemoteKillSwitch.isKilled) "KILLED(${com.lifecyclebot.engine.RemoteKillSwitch.killReason.take(30)})" else "clear"
        } catch (_: Throwable) { "unknown" }
        return "marks: staleDexSkipped=${n("PRICE_FALLBACK_STALE_DEX_PAIR_SKIPPED_7884")} " +
            "exitClassifyNoFresh=${n("LIVE_EXIT_CLASSIFY_NO_FRESH_MARK_7884")} " +
            "offRouteLossSilent=${n("ROUTE_LOCK_LOSS_ONROUTE_SILENT_7759")} " +
            "rugWindowStale=${n("RUG_INTAKE_WINDOW_STALE_SKIPPED_7884")} · " +
            "exits: emergencyRetries=${n("EMERGENCY_EXIT_RETRY_7807")} · " +
            "safety: remoteKill=$remote pausedCrossAsset=${n("PREAUTH_BLOCK_RUNTIME_PAUSED_7884")} · " +
            "accounting: profitLockCanonical=${n("PROFIT_LOCK_CANONICAL_SLICE_7884")} " +
            "profitLockLegacy=${n("PROFIT_LOCK_CANONICAL_SLICE_UNAVAILABLE_7884")}"
    }
}
