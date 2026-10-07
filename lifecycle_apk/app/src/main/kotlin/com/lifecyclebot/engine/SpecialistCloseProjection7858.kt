package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441

/** Specialist inventory is a projection. A sell request alone cannot close it. */
internal object SpecialistCloseProjection7858 {
    fun mayClose(mint: String, paper: Boolean, entryTimeMs: Long): Boolean {
        val mode = if (paper) "PAPER" else "LIVE"
        val finalized = try {
            val stillOpen = CanonicalPositionAuthority6441.openPositions().any {
                it.mint == mint && it.mode.equals(mode, true) && it.remainingQtyRaw.signum() > 0
            } || (!paper && CanonicalPositionAuthority6441.protectiveInventory7807("live").any {
                it.mint == mint && it.remainingQtyRaw.signum() > 0
            })
            !stillOpen && CanonicalPositionAuthority6441.closedPositions().any {
                it.mint == mint && it.mode.equals(mode, true) &&
                    it.lastMutationMs >= entryTimeMs && it.remainingQtyRaw.signum() == 0
            }
        } catch (_: Throwable) { false }
        if (!finalized) try {
            PipelineHealthCollector.labelInc("SPECIALIST_CLOSE_AWAIT_CANONICAL_FINALITY_7858")
            ForensicLogger.lifecycle("SPECIALIST_CLOSE_AWAIT_CANONICAL_FINALITY_7858",
                "mint=$mint mode=$mode action=retain_inventory_and_exit_owner")
        } catch (_: Throwable) {}
        return finalized
    }
}
