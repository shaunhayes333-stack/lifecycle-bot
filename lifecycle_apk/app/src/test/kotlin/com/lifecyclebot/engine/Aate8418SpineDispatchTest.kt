package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8418 — 8418 phone run: CORE qualified 182 / CYCLIC qualified 342, both fdg=0/0 (READY_NOT_EVALUATED_PRIMARY_*).
 * A READY CORE / CYCLIC candidate on a block lane's cycle is now evaluated by the primary spine under its own lane, behind
 * the same lane-cycle gate the other lanes pass.
 */
class Aate8418SpineDispatchTest {
    private val d = LaneDispatch8418
    private val all: (String) -> Boolean = { true }
    private val none: (String) -> Boolean = { false }

    @Test fun aReadyCoreCandidateOnAShitcoinCycleIsEvaluatedAsCore() {
        assertEquals("CORE", d.spineLanePure8418("SHITCOIN", false, coreReady = true, cyclicReady = false, allowed = all))
        assertEquals("CORE", d.spineLanePure8418("MOONSHOT", false, coreReady = true, cyclicReady = true, allowed = all))
    }

    @Test fun cyclicTakesTheSpineWhenCoreIsNotReadyOrNotAllowed() {
        assertEquals("CYCLIC", d.spineLanePure8418("BLUECHIP", false, coreReady = false, cyclicReady = true, allowed = all))
        assertEquals("CYCLIC", d.spineLanePure8418("BLUECHIP", false, coreReady = true, cyclicReady = true, allowed = { it == "CYCLIC" }))
    }

    @Test fun theLaneCycleGateStillDecides() {
        assertEquals("SHITCOIN", d.spineLanePure8418("SHITCOIN", false, coreReady = true, cyclicReady = true, allowed = none))
    }

    @Test fun nothingChangesWithoutAReadyBlocklessLaneOrForAnOpenPosition() {
        assertEquals("SHITCOIN", d.spineLanePure8418("SHITCOIN", false, coreReady = false, cyclicReady = false, allowed = all))
        assertEquals("SHITCOIN", d.spineLanePure8418("SHITCOIN", true, coreReady = true, cyclicReady = true, allowed = all))
    }

    @Test fun aPrimaryWithoutItsOwnBlockKeepsTheSpine() {
        // CORE / CYCLIC / STANDARD / V3_CORE primaries are evaluated only by the spine: never displaced.
        assertEquals("CORE", d.spineLanePure8418("CORE", false, coreReady = false, cyclicReady = true, allowed = all))
        assertEquals("CYCLIC", d.spineLanePure8418("CYCLIC", false, coreReady = true, cyclicReady = true, allowed = all))
        assertEquals("V3_CORE", d.spineLanePure8418("V3_CORE", false, coreReady = true, cyclicReady = true, allowed = all))
        assertEquals("STANDARD", d.spineLanePure8418("STANDARD", false, coreReady = true, cyclicReady = true, allowed = all))
    }

    @Test fun theSpineEvaluatesAndCachesUnderTheResolvedLane() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val a = s.indexOf("val spineLane8418 = LaneDispatch8418.spineLane8418(")
        assertTrue(a > 0)
        val block = s.substring(a, s.indexOf("TradeAuthorizer.authorize(", a))
        assertTrue(block.contains("laneEvaluationAllowed7951(ts, it, cyclePrimaryLane) && cyclicPolicyAllows7809(it)"))
        assertTrue(block.contains("specialistLane = spineLane8418,"))
        assertTrue(block.contains("identity.mint, fdgCandidateVersion6653, spineLane8418,"))
        assertTrue(!block.contains("specialistLane = cyclePrimaryLane,"))
    }
}
