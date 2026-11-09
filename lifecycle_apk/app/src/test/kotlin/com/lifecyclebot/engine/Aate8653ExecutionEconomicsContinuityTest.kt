package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EntryEconomics8329
import com.lifecyclebot.engine.cortex.Cortex7885
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Regression against 8648's 23 terminal unpriced economics tickets. */
class Aate8653ExecutionEconomicsContinuityTest {
    @Test fun finiteSealedGrossIsUsedWithoutRecomputingTheDecision() {
        var calls = 0
        val gross = EntryEconomics8329.expectedGrossAtExecution8653(45.0) { calls++; 99.0 }
        assertEquals(45.0, gross, 0.0)
        assertEquals(0, calls)
    }

    @Test fun nanAndMissingSealedGrossCanUseARealFreshSameSetupEstimate() {
        assertEquals(27.0, EntryEconomics8329.expectedGrossAtExecution8653(Double.NaN) { 27.0 }, 0.0)
        assertEquals(18.0, EntryEconomics8329.expectedGrossAtExecution8653(null) { 18.0 }, 0.0)
        assertEquals(29.0, EntryEconomics8329.expectedGrossAtExecution8653(Double.POSITIVE_INFINITY) { 29.0 }, 0.0)
    }

    @Test fun noFreshForecastRemainsUnpricedAndCannotBecomeABuy() {
        assertTrue(EntryEconomics8329.expectedGrossAtExecution8653(Double.NaN) { Double.NaN }.isNaN())
        assertTrue(EntryEconomics8329.expectedGrossAtExecution8653(null) { Double.NaN }.isNaN())
        assertTrue(EntryEconomics8329.expectedGrossAtExecution8653(Double.NaN) { error("fresh quote unavailable") }.isNaN())
    }

    @Test fun theLiveBuyQueuesLearningOffThreadAndRejectsPostFillVoterReads() {
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val cortex = File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
        val approved = gate.substringAfter("if (winner?.fdgCan == true && winner.hardNoReasons.isEmpty() && winner.preFdgVerdict == \"BUY\")")
        assertTrue("the chosen live BUY gets learning priority before the execution seal",
            approved.indexOf("scheduleLiveExecutionRead8653(") in 0 until approved.indexOf("publishFdgIntent6519("))
        assertTrue("the full voter graph must stay off the FDG/executor thread",
            cortex.contains("priorityPool8653.execute {"))
        assertTrue("a versioned immediate seal may only bind pre-fill evidence",
            cortex.contains("readBindable8187(sealedRead.atMs, openedAtMs)"))
        assertTrue("the deferred versioned seal also needs the same causal proof",
            cortex.contains("readBindable8187(res.read!!.atMs, d.openedAtMs)"))
        assertTrue(Cortex7885.readBindable8187(5_000L, 7_000L))
        assertFalse("a voter score completed after the buy is hindsight", Cortex7885.readBindable8187(7_001L, 7_000L))
    }
}
