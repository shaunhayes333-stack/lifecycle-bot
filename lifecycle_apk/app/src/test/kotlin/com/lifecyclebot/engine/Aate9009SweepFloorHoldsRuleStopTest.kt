package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9009 — the sweep's fluid stop floor holds an armed launch rule's / proven tape signal's own graded stop for a
 * position bought on it; every other position keeps the fluid floor.
 */
class Aate9009SweepFloorHoldsRuleStopTest {
    @Test fun wired() {
        val ex = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(ex.contains("val floor = sweepFluidFloor9009(ts)"))
        val fn = ex.substringAfter("private fun sweepFluidFloor9009(ts: TokenState): Double {").substringBefore("\n    }\n")
        assertTrue(fn.contains("com.lifecyclebot.v3.scoring.FluidLearningAI.getFluidStopLoss(-25.0)"))
        assertTrue(fn.contains("LaunchSniper8748.ruleStopMag8949(ts.mint, ts.position.entryTime)"))
        assertTrue("only a wider rule stop replaces the floor", fn.contains("if (rule != null && -rule < floor)"))
        assertTrue(fn.trimEnd().endsWith("return floor"))
        assertTrue(ex.contains("doSell(ts, \"SWEEP_FLUID_FLOOR_\${floor.toInt()}\", wallet, walletSol)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 194. The sweep floor holds a launch entry's stop (9009)"))
    }
}
