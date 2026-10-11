package com.lifecyclebot.engine

import com.lifecyclebot.engine.learning.TacticSwitcher
import com.lifecyclebot.engine.learning.TacticSwitcher.Tactic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8049 — every lane starts in its own tactic (its best playbook setup's shape), not MOMENTUM for all. */
class Aate8049LaneTacticSeedTest {
    @Test fun setupShapes() {
        assertEquals(Tactic.REACCUMULATION, TacticSwitcher.seedTactic8049("SHITCOIN", "PRE_IGNITION_BASE"))
        assertEquals(Tactic.BREAKOUT, TacticSwitcher.seedTactic8049("MOONSHOT", "BREAKOUT_RUNNER"))
        assertEquals(Tactic.PULLBACK, TacticSwitcher.seedTactic8049("SHITCOIN", "FIRST_PULLBACK"))
        assertEquals(Tactic.MOMENTUM, TacticSwitcher.seedTactic8049("EXPRESS", "ACCELERATING_TAPE"))
    }

    @Test fun laneDefaultsWhenNoSetup() {
        assertEquals(Tactic.PULLBACK, TacticSwitcher.seedTactic8049("QUALITY", null))
        assertEquals(Tactic.PULLBACK, TacticSwitcher.seedTactic8049("BLUECHIP", "NO_TRIGGER"))
        assertEquals(Tactic.REACCUMULATION, TacticSwitcher.seedTactic8049("SHITCOIN", null))
        assertEquals(Tactic.BREAKOUT, TacticSwitcher.seedTactic8049("MOONSHOT", null))
        assertEquals(Tactic.MOMENTUM, TacticSwitcher.seedTactic8049("EXPRESS", null))
        val distinct = listOf("SHITCOIN", "MOONSHOT", "QUALITY", "EXPRESS").map { TacticSwitcher.seedTactic8049(it, null) }.toSet()
        assertTrue(distinct.size >= 4)
    }

    @Test fun wiring() {
        val t = File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        assertTrue(t.contains("seed8049(lane, k, cell)"))
        assertTrue(t.contains("LanePlaybook7907.bestSetup8049(lane)"))
    }
}

/** V5.0.8050 — a trade's tactic is the token's shape (its classified setup), so cohorts are graded by what the coin did. */
class Aate8050TokenTacticTest {
    @Test fun stampedFromTheToken() {
        val e = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertEquals(3, Regex("TacticSwitcher\\.tacticForToken8050\\(ts, ").findAll(e).count())
        assertTrue(!e.contains("TacticSwitcher.currentTactic(safeLane, score.toInt())"))
        val t = File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        assertTrue(t.contains("LanePlaybook7907.classify(ts, lane)"))
    }
}
