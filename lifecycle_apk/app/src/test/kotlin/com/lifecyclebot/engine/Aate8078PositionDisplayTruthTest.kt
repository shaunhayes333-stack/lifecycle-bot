package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** V5.0.8078 — the Open Positions card and the detail sheet paint one basis and one mark. */
class Aate8078PositionDisplayTruthTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun oneViewUsesCanonicalRemainingBasisAndVerifiedExitMark() {
        val v = src("engine/truth/PositionDisplayTruth8078.kt")
        assertTrue(v.contains("CanonicalPositionAuthority6441.getPosition(it)"))
        assertTrue(v.contains("canon.entryCostSol - canon.soldCostBasisSol"))
        assertTrue(v.contains("CanonicalPriceMarkRegistry6522.get(ts.mint, CanonicalMarkPurpose6570.EXIT_ECONOMIC)"))
        assertTrue(v.contains("entryPrice = entryUsd, currentPrice = markUsd"))
        assertFalse(v.contains(".registerPosition("))
        assertFalse(v.contains("position.entryPrice ="))
    }

    @Test fun liveSolanaProfitRequiresAQuantityBoundExitEstimate() {
        val spot = com.lifecyclebot.engine.truth.PositionDisplayTruth8078.View(
            1.0, 10.0, 1.0, 1.2, "spot", 0, false, true, true, "", 20.0, 0.2, 1.2)
        assertFalse(spot.canDisplayPnl(false))
        assertTrue(spot.canDisplayPnl(true))
        assertTrue(spot.copy(executablePnlSol = -0.1, executablePnlPct = -10.0).canDisplayPnl(false))
        assertFalse(spot.copy(basisOk = false, executablePnlSol = 0.1, executablePnlPct = 10.0).canDisplayPnl(false))
    }

    @Test fun cardAndDetailSheetConsumeTheSameView() {
        val ui = src("ui/MainActivity.kt")
        val pop = src("ui/MemePositionPopout.kt")
        assertTrue(ui.contains("PositionDisplayTruth8078.view(ts)"))
        assertTrue(ui.contains("view8078?.canDisplayPnl(false)"))
        assertTrue(pop.contains("view8078?.canDisplayPnl(pos.isPaperPosition)"))
        assertTrue(ui.contains("\"Mark \$mk · \${view8078.markTag} \$age\""))
        assertTrue(ui.contains("val entryToRender6634 = view8078?.takeIf { it.canonicalBasis }?.entryUsd"))
        assertTrue(pop.contains("PositionDisplayTruth8078.view(t)"))
        assertTrue(pop.contains("current = view8078?.markUsd ?: t.ref"))
        assertTrue(pop.contains("qty = view8078?.qty ?: pos.qtyToken"))
    }

    @Test fun speculativePeakVerificationCannotSpawnUnboundedThreads8791() {
        val pool = com.lifecyclebot.engine.truth.PositionDisplayTruth8078.newCorroborationPool8791("test-peak-8791", 1, 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            pool.execute { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            pool.execute { } // the single bounded queue slot
            try {
                pool.execute { }
                fail("a third price-read task cannot fit one active worker and one queued slot")
            } catch (_: RejectedExecutionException) { /* backlog is retryable, never a mark */ }
            assertTrue(pool.poolSize <= 1)
            assertTrue(pool.queue.size <= 1)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
