package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8527PaperExitOrderTest {
    private val policy = PaperExitOrder8527

    @Test fun earnedProfitRulesActDuringWarmupBeforeAnOrdinaryWait() {
        val level = requireNotNull(RunnerTrail8419.holdLayerLevel8419(100.0, Double.NaN))
        assertEquals(40.0, level.levelPct, 1e-9)
        assertEquals(PaperExitOrder8527.Decision.PROFIT_FLOOR,
            policy.choose8527(1.0, 10.0 < level.levelPct, 100.0 - 10.0 >= level.pointsUnderPeak))
        assertEquals(PaperExitOrder8527.Decision.TRAIL,
            policy.choose8527(1.0, profitFloorBreached = false, trailBreached = true))
        assertEquals(PaperExitOrder8527.Decision.PROFIT_FLOOR,
            policy.choose8527(0.0, profitFloorBreached = true, trailBreached = true))
    }

    @Test fun warmupAndMatureContractsAreOtherwiseUnchanged() {
        assertEquals(PaperExitOrder8527.Decision.WARMUP, policy.choose8527(0.0, false, false))
        assertEquals(PaperExitOrder8527.Decision.WARMUP, policy.choose8527(2.999, false, false))
        assertEquals(PaperExitOrder8527.Decision.CONTINUE, policy.choose8527(3.0, false, false))
        assertEquals(PaperExitOrder8527.Decision.PROFIT_FLOOR, policy.choose8527(20.0, true, false))
        assertEquals(PaperExitOrder8527.Decision.TRAIL, policy.choose8527(20.0, false, true))
    }

    @Test fun actualHeldLotModeAndProductionPriorityAreBoundAtTheCallsite() {
        val code = File("src/main/kotlin/com/lifecyclebot/engine/LifecycleStrategy.kt").readText()
            .substringAfter("private fun decideSignal(")
        val paper = code.substringBefore("// REAL MODE: Full exit logic")
        assertTrue(paper.contains("if (pos.isPaperPosition)"))
        assertFalse(paper.contains("if (c.paperMode)"))
        val emergency = paper.indexOf("paperHeldMins < 3.0 && paperGainPct <= -25.0")
        val ordered = paper.indexOf("PaperExitOrder8527.choose8527(")
        val wait = paper.indexOf("PaperExitOrder8527.Decision.WARMUP -> return \"WAIT\"")
        val normalStop = paper.indexOf("paperGainPct <= -12.0")
        assertTrue(emergency >= 0 && emergency < ordered && ordered < wait && wait < normalStop)
        assertFalse(paper.substring(emergency, ordered).contains("return \"WAIT\""))
        assertTrue(paper.contains("profitFloorBreached = paperGainPct < profitFloor"))
        assertTrue(paper.contains("trailBreached = peakGain >= 10.0 && drawdownFromPeak >= trailPct"))
        assertTrue(paper.indexOf("paperHeldMins >= 12.0") > normalStop)
        assertTrue(paper.indexOf("paperHeldMins >= 45.0") > normalStop)
    }
}
