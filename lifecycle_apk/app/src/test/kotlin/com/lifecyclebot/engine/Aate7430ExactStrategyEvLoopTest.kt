package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7430ExactStrategyEvLoopTest {

    @Test fun hypothesisContextIncludesExactPlaybookIdentity() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/StrategyHypothesisEngine.kt").readText()
        assertTrue(s.contains("ctxKey7430"))
        assertTrue(s.contains("|X="))
        assertTrue(s.contains("strategyIdentity: String = \"\""))
        assertTrue(s.contains("HYPOTHESIS_EXACT_CONTEXT_STAMPED_7430"))
        assertTrue(s.contains("HYPOTHESIS_EXACT_PARENT_BASELINE_SEEDED_7430"))
    }

    @Test fun fdgStampsExactIdentityIntoHypothesisDecision() {
        val f = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(f.contains("exactStrategyIdentity7430"))
        assertTrue(f.contains("cls7430.tradeType.name"))
        assertTrue(f.contains("style7430.toolkit.setup.name"))
        assertTrue(f.contains("style7430.style.name"))
        assertTrue(f.contains("style7430.tactic.name"))
        assertTrue(f.contains("FDG_EXACT_STRATEGY_IDENTITY_7430"))
        assertTrue(f.contains("exactStrategyIdentity7430,"))
    }

    @Test fun exactCausalRowsRetainRealizedPnlAndExposeEvPrior() {
        val e = File("src/main/kotlin/com/lifecyclebot/engine/truth/EntryStrategySnapshot6450.kt").readText()
        assertTrue(e.contains("val pnlPct:Double"))
        assertTrue(e.contains("env.realizedReturnPct, snap.entryScore"))
        assertTrue(e.contains("ExactStrategyStats7430"))
        assertTrue(e.contains("exactStrategyStats7430"))
        assertTrue(e.contains("exactStrategyPriorMultiplier7430"))
        assertTrue(e.contains("EXACT_STRATEGY_EV_PRIOR_READ_7430"))
        assertTrue(e.contains(".put(\"pnl\",r.pnlPct)"))
    }

    @Test fun exactEvIsPriorNotPerTickIndependentVeto() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/StrategyHypothesisEngine.kt").readText()
        val seed = s.substringAfter("private fun seedExactFromParent7430").substringBefore("private fun decisionKey7428")
        assertTrue(seed.contains("exactStrategyPriorMultiplier7430"))
        assertTrue(seed.contains("baseline.putIfAbsent"))
        assertFalse(seed.contains("return false"))
        assertFalse(seed.contains("DENY"))
    }
}
