package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.*

/** Slot priority is scoped to the requested account, never a combined UI book. */
internal object SlotInventory9065 {
    fun subTraderMints(isPaper: Boolean): Set<String> {
        val out = linkedSetOf<String>()
        try { out.addAll(CashGenerationAI.getActivePositionsForMode(isPaper).map { it.mint }) } catch (_: Throwable) {}
        try { out.addAll(MoonshotTraderAI.getActivePositionsForMode(isPaper).map { it.mint }) } catch (_: Throwable) {}
        try { out.addAll(ShitCoinTraderAI.getActivePositionsForMode(isPaper).map { it.mint }) } catch (_: Throwable) {}
        try { out.addAll(QualityTraderAI.getActivePositionsForMode(isPaper).map { it.mint }) } catch (_: Throwable) {}
        try { out.addAll(BlueChipTraderAI.getActivePositionsForMode(isPaper).map { it.mint }) } catch (_: Throwable) {}
        try { out.addAll(ManipulatedTraderAI.getActivePositionsForMode(isPaper).map { it.mint }) } catch (_: Throwable) {}
        try { out.addAll(DipHunterAI.getActiveDips().filter { it.isPaper == isPaper }.map { it.mint }) } catch (_: Throwable) {}
        try { out.addAll(ShitCoinExpress.getActiveRides().filter { it.isPaper == isPaper }.map { it.mint }) } catch (_: Throwable) {}
        return out
    }

    fun canonicalMints(isPaper: Boolean): Set<String> = try {
        com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
            .protectiveInventory7807(if (isPaper) "paper" else "live")
            .filter { it.assetClass == com.lifecyclebot.engine.truth.AssetClass.SOLANA_TOKEN }
            .mapTo(HashSet()) { it.mint }
    } catch (_: Throwable) { emptySet() }
}
