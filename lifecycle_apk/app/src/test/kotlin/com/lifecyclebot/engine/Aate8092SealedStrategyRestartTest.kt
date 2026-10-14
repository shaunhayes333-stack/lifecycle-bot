package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test

/** Behavioural tests against the real strategy authority, not source-string matching. */
class Aate8092SealedStrategyRestartTest {
    private val winning = "BREAKOUT>IGNITION>DIAMOND_HANDS_RUNNER>MOMENTUM"
    private val alternative = "REVERSAL>PULLBACK>QUICK_FLIP>PULLBACK"

    @Test fun sealSurvivesRestartBeforeConfirmedFillAndCannotBeReclassified() {
        val h = StrategyHypothesisEngine
        val prior = RuntimeModeAuthority.isPaper()
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = true)
        h.reset()
        try {
            h.getSizeBias("MOONSHOT", 60, "NORMAL", "restart8092", winning, 8092L, "IGNITION")
            h.sealDecision8091("restart8092", 8092L, "MOONSHOT", "PAPER")
            val disk = h.exportState()
            h.reset(); h.importState(disk)
            h.getSizeBias("MOONSHOT", 80, "NORMAL", "restart8092", alternative, 8092L, "PULLBACK")
            h.bindExecutedPosition7428("position8092", "restart8092", 8092L, "MOONSHOT", "PAPER")
            assertEquals(winning.split('>'), h.exactEntryIdentity8090("position8092", "restart8092", "MOONSHOT", 8092L))
            assertEquals("IGNITION", h.entrySetupTag8091("position8092", "restart8092", "MOONSHOT", 8092L))
            h.bindExecutedPosition7428("duplicate8092", "restart8092", 8092L, "MOONSHOT", "PAPER")
            assertNull(h.exactEntryIdentity8090("duplicate8092", "restart8092", "MOONSHOT", 8092L))
        } finally {
            h.reset(); RuntimeModeAuthority.publishConfig(paperMode = prior, autoTrade = true)
        }
    }

    @Test fun parallelBindCannotClaimTheSameWinningDecisionTwice() {
        val h = StrategyHypothesisEngine
        val prior = RuntimeModeAuthority.isPaper()
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = true)
        h.reset()
        try {
            h.getSizeBias("MOONSHOT", 60, "NORMAL", "race8092", winning, 8092L, "IGNITION")
            h.sealDecision8091("race8092", 8092L, "MOONSHOT", "PAPER")
            val latch = java.util.concurrent.CountDownLatch(1)
            val workers = (0 until 8).map { i -> Thread {
                latch.await()
                h.bindExecutedPosition7428("racePosition$i", "race8092", 8092L, "MOONSHOT", "PAPER")
            }.also { it.start() } }
            latch.countDown(); workers.forEach { it.join(5000L); assertFalse(it.isAlive) }
            assertEquals(1, (0 until 8).count { h.exactEntryIdentity8090("racePosition$it", "race8092", "MOONSHOT", 8092L) != null })
        } finally {
            h.reset(); RuntimeModeAuthority.publishConfig(paperMode = prior, autoTrade = true)
        }
    }

    @Test fun conflictingPositionRebindCannotReturnAnotherLanesVariant() {
        val h = StrategyHypothesisEngine
        val prior = RuntimeModeAuthority.isPaper()
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = true)
        h.reset()
        try {
            h.getSizeBias("MOONSHOT", 60, "NORMAL", "mint8092", winning, 8092L, "IGNITION")
            h.sealDecision8091("mint8092", 8092L, "MOONSHOT", "PAPER")
            h.bindExecutedPosition7428("identity8092", "mint8092", 8092L, "MOONSHOT", "PAPER")
            assertEquals("", h.bindExecutedPosition7428("identity8092", "other8092", 8092L, "EXPRESS", "LIVE"))
            assertEquals(winning.split('>'), h.exactEntryIdentity8090("identity8092", "mint8092", "MOONSHOT", 8092L))
        } finally {
            h.reset(); RuntimeModeAuthority.publishConfig(paperMode = prior, autoTrade = true)
        }
    }
}
