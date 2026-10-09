package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.7948 — specialist execution funnel (diag 5.0.7947 §3). */
class Aate7948SpecialistExecutionTest {
    private val x = SpecialistExecution7948
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun thresholdReasonPrintsTheComparisonThatHolds() {
        val r = x.thresholdFailReason7948("THRESHOLD_FAIL", 19, 15, 4, 20)
        assertEquals("THRESHOLD_FAIL: score=19>=15 conf=4<20 failed=conf", r)
        assertFalse(r.contains("19<15"))
        assertEquals(
            "WR_RECOVERY_SCORE_FLOOR: score=10<30 (base=15) conf=25>=20 failed=score",
            x.thresholdFailReason7948("WR_RECOVERY_SCORE_FLOOR", 10, 30, 25, 20, "(base=15)"),
        )
        assertTrue(x.thresholdFailReason7948("T", 1, 15, 1, 20).endsWith("failed=score+conf"))
    }

    @Test fun ownershipConvictionIsOnEachLanesOwnBar() {
        assertEquals(50.0, x.relativeToBar7948(15, 15), 1e-9)
        assertEquals(100.0, x.relativeToBar7948(100, 15), 1e-9)
        assertEquals(25.0, x.relativeToBar7948(10, 20), 1e-9)
        assertEquals(42.0, x.relativeToBar7948(42, 0), 1e-9) // unknown bar keeps raw
        // A SHITCOIN pass at its own bar (score 19 >= 15, conf 25 >= 20) is no longer a
        // 25-point conviction against a MOONSHOT raw 40.
        val shit = x.laneRelativeConviction7948(19, 25, 15, 20)
        assertTrue(shit > 50.0)
        assertTrue(shit < 60.0)
        // Below its bar it stays below 50: normalisation never promotes a refusal.
        assertTrue(x.laneRelativeConviction7948(10, 10, 15, 20) < 50.0)
    }

    @Test fun onlyAConfirmedRugIsALiveRefusal() {
        assertTrue(x.isConfirmedRugForLive7948(0, paper = false))
        assertFalse(x.isConfirmedRugForLive7948(-1, paper = false)) // not fetched
        assertFalse(x.isConfirmedRugForLive7948(1, paper = false))
        assertFalse(x.isConfirmedRugForLive7948(0, paper = true))
    }

    @Test fun sealLiquidityTakesTheFirstObservedDepth() {
        assertEquals(1230.0, x.sealLiquidityUsd7948(0.0, 0.0, 1230.0), 1e-9)
        assertEquals(5000.0, x.sealLiquidityUsd7948(null, 5000.0, 1230.0), 1e-9)
        assertEquals(800.0, x.sealLiquidityUsd7948(800.0, 5000.0), 1e-9)
        assertEquals(0.0, x.sealLiquidityUsd7948(null, Double.NaN, -1.0), 1e-9)
    }

    @Test fun sealedSizeFollowsFdgDownNeverUp() {
        assertEquals(0.04, x.sealedIntentSize7948(0.05, 0.04), 1e-12)
        assertEquals(0.05, x.sealedIntentSize7948(0.05, 0.06), 1e-12)
        assertEquals(0.06, x.sealedIntentSize7948(null, 0.06), 1e-12)
        assertEquals(0.05, x.sealedIntentSize7948(0.05, 0.0), 1e-12)
        assertTrue(x.shrinkSealedSize7948(0.05, 0.04))
        assertFalse(x.shrinkSealedSize7948(0.04, 0.05))
        assertFalse(x.shrinkSealedSize7948(0.0, 0.04))
        assertTrue(x.sealWithinFdgSize7948(0.04, 0.05))
        assertTrue(x.sealWithinFdgSize7948(0.05, 0.05))
        assertFalse(x.sealWithinFdgSize7948(0.06, 0.05))
        assertFalse(x.sealWithinFdgSize7948(0.0, 0.05))
    }

    @Test fun confirmedRugIsRefusedBeforeTheSeal() {
        val s = src("engine/TradeAuthorizer.kt")
        val rug = s.indexOf("SpecialistExecution7948.isConfirmedRugForLive7948(rugcheckScore, isPaperMode)")
        val ready = s.indexOf("SpecialistCandidateBooks7803.markReady(")
        val seal = s.indexOf("SpecialistPreauthSeal7834.ensure(")
        assertTrue(rug in 1 until ready)
        assertTrue(ready < seal)
        assertTrue(s.contains("terminalizeUnexecutedTicket7948(requestedBook.name, sealedTicketAttempt7948"))
    }

    @Test fun unexecutedTicketsEndByName() {
        assertTrue(src("engine/ToolkitSignalSheet.kt").contains("internal fun terminalizeUnexecutedTicket7948("))
        assertTrue(src("engine/BotService.kt").contains("permitRefusedTicket7948(\"BLUECHIP\""))
        assertTrue(src("engine/Executor.kt").contains("unexecutedTicket7948(layerTag, preflight.attemptId, \"BUY_NOT_OPENED_7948\")"))
    }

    @Test fun shitcoinOwnershipUsesItsOwnBar() {
        assertTrue(src("engine/SpecialistBrainBridge7542.kt").contains("onLaneBar7948(it,ShitCoinTraderAI.getFluidScoreThreshold(),ShitCoinTraderAI.getFluidConfidenceThreshold())"))
        assertTrue(src("engine/ToolkitSignalSheet.kt").contains("conviction7948 = native.ownershipConviction7948"))
    }
}
