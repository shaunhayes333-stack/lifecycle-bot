package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.v3.scoring.*

/** Retire confirmed PAPER projections without inventing another trade or training receipt. */
internal object PaperStopLaneRegistry8521 {
    fun sharedTrackingReleased8521(positionId: String): Boolean =
        CanonicalPositionAuthority6441.canReleaseSharedTrackingAfterPaperClose8521(positionId)

    fun retire(positionId: String) {
        val p = CanonicalPositionAuthority6441.getPosition(positionId) ?: return
        if (!PaperStopLifecycle8521.confirmedClosed(positionId) ||
            !SpecialistCloseProjection7858.mayClose(p.mint, true, p.openedAtMs)) return
        val mint = p.mint
        CashGenerationAI.evictGhost(mint, isPaper = true)
        BlueChipTraderAI.evictGhost(mint, isPaper = true)
        ShitCoinTraderAI.evictGhost(mint, isPaper = true)
        ManipulatedTraderAI.evictGhost(mint, isPaper = true)
        QualityTraderAI.evictGhost(mint, isPaper = true)
        MoonshotTraderAI.evictGhost(mint, isPaper = true)
        if (DipHunterAI.getActiveDips().any { it.mint == mint && it.isPaper && it.entryTime <= p.lastMutationMs })
            DipHunterAI.evictGhost(mint)
        if (ShitCoinExpress.getActiveRides().any { it.mint == mint && it.isPaper && it.entryTime <= p.lastMutationMs })
            ShitCoinExpress.evictGhost(mint)
        ProjectSniperAI.retireClosedPaperStop8521(positionId)
    }
}
