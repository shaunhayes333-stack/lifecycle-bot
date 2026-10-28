package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.SpecialistCandidateBooks7803
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8418 — every qualified CORE / CYCLIC candidate reaches its FDG verdict or ends by name (5.0.8416: CORE
 * lostNoTerminal{QUALIFIED=348}, CYCLIC {QUALIFIED=621}); the CYCLIC ring takes its own native-qualified candidates
 * first; PROJECT_SNIPER's own hunter selections count as discovered.
 */
class Aate8418LaneDispatchTest {
    private val d = LaneDispatch8418
    private val chain = OwnershipChain8364
    private val t0 = 1_000_000_000L
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Before fun setUp() { reset() }
    @After fun tearDown() { reset() }
    private fun reset() {
        chain.resetForTests8364(); SpecialistOwnership7951.resetForTests7951(); SpecialistElection8136.resetForTests8136()
        SpecialistCandidateBooks7803.resetForTests()
    }

    @Test fun blocklessLanesAreEveryOneThatIsNotThePrimary() {
        assertEquals(listOf("CORE", "CYCLIC"), d.notEvaluatedBlockless8418("MOONSHOT"))
        assertEquals(listOf("CYCLIC"), d.notEvaluatedBlockless8418("CORE"))
        assertEquals(listOf("CORE"), d.notEvaluatedBlockless8418("cyclic"))
    }

    @Test fun aQualifiedCoreAndCyclicCandidateOnAnotherLanesCycleEndsNotPrimary() {
        chain.onDeskStage8364("CORE", "QUALIFIED", "MintC:5", t0)
        chain.onDeskStage8364("CYCLIC", "QUALIFIED", "MintC:5", t0)
        d.noteCycle8418("MintC", "MOONSHOT", positionOpen = false)
        chain.flushForTests8364()
        val core = chain.reportLine8364("CORE")!!
        val cyc = chain.reportLine8364("CYCLIC")!!
        assertTrue(core, core.contains("NOT_EVALUATED_NOT_PRIMARY_MOONSHOT=1"))
        assertTrue(cyc, cyc.contains("NOT_EVALUATED_NOT_PRIMARY_MOONSHOT=1"))
        assertFalse(core, core.contains("lostNoTerminal{QUALIFIED"))
        assertFalse(cyc, cyc.contains("lostNoTerminal{QUALIFIED"))
    }

    @Test fun anOpenPositionCycleStampsNothing() {
        chain.onDeskStage8364("CORE", "QUALIFIED", "MintO:5", t0)
        d.noteCycle8418("MintO", "MOONSHOT", positionOpen = true)
        chain.flushForTests8364()
        assertTrue(chain.reportLine8364("CORE")!!.contains("lostNoTerminal{QUALIFIED=1}"))
    }

    @Test fun aBlocklessPrimaryWhoseSpineDidNotProposeEndsByName() {
        assertEquals("PRIMARY_SIGNAL_WAIT_8418", d.primarySpineWhy8418("CORE", false, "WAIT", true))
        assertEquals("PRIMARY_CANNOT_PROPOSE_8418", d.primarySpineWhy8418("CYCLIC", false, "BUY", false))
        assertNull(d.primarySpineWhy8418("CORE", false, "BUY", true))   // it proposes: the FDG verdict names it
        assertNull(d.primarySpineWhy8418("CORE", true, "WAIT", true))   // open position: an exit cycle
        assertNull(d.primarySpineWhy8418("MOONSHOT", false, "WAIT", true)) // a lane with its own block names itself
        chain.onDeskStage8364("CORE", "QUALIFIED", "MintP:5", t0)
        d.notePrimarySpine8418("MintP", "CORE", false, "WAIT", true)
        chain.flushForTests8364()
        val core = chain.reportLine8364("CORE")!!
        assertTrue(core, core.contains("NOT_EVALUATED_PRIMARY_SIGNAL_WAIT_8418=1"))
    }

    @Test fun aSpineVerdictStillReplacesTheSoftEnd() {
        chain.onDeskStage8364("CORE", "QUALIFIED", "MintV:5", t0)
        d.noteCycle8418("MintV", "MOONSHOT", positionOpen = false)
        chain.onFdgVerdict8364("CORE", "MintV", 5L, false, "NET_EDGE_BELOW_2X_COST_8030", t0 + 2)
        chain.flushForTests8364()
        val core = chain.reportLine8364("CORE")!!
        assertTrue(core, core.contains("FDG_NET_EDGE_BELOW_2X_COST_8030=1"))
        assertFalse(core, core.contains("NOT_EVALUATED"))
    }

    @Test fun theCyclicRingTakesItsOwnNativeQualifiedCandidatesFirst() {
        // a READY candidate outranks a higher-scored one CYCLIC's own brain did not qualify
        assertTrue(d.cyclicRank8418(55.0, 60, nativeReady = true) > d.cyclicRank8418(95.0, 100, nativeReady = false))
        // among equals the old score x confidence order stands
        assertTrue(d.cyclicRank8418(80.0, 90, false) > d.cyclicRank8418(80.0, 50, false))
        assertEquals(40.0, d.cyclicRank8418(80.0, null, false), 1e-9)
        assertEquals(0.0, d.cyclicRank8418(Double.NaN, 50, false), 1e-9)
        SpecialistCandidateBooks7803.markReady("CYCLIC", "MintR", "R", 5L, 70, 70.0, "ready")
        assertTrue(d.cyclicReady8418("MintR"))
        assertFalse(d.cyclicReady8418("MintX"))
    }

    @Test fun readyCyclicCandidatesTheRingDidNotTakeEndByName() {
        SpecialistCandidateBooks7803.markReady("CYCLIC", "MintA", "A", 5L, 70, 70.0, "ready")
        SpecialistCandidateBooks7803.markReady("CYCLIC", "MintB", "B", 5L, 70, 70.0, "ready")
        chain.onDeskStage8364("CYCLIC", "QUALIFIED", "MintA:5", t0)
        chain.onDeskStage8364("CYCLIC", "QUALIFIED", "MintB:5", t0)
        d.noteCyclicRing8418("MintA", "CYCLIC_RING_TOOK_OTHER")   // the ring took A: its FDG verdict will name it
        chain.onFdgVerdict8364("CYCLIC", "MintA", 5L, false, "CHASING_SPIKE_8065", t0 + 1)
        chain.flushForTests8364()
        val line = chain.reportLine8364("CYCLIC")!!
        assertTrue(line, line.contains("NOT_EVALUATED_CYCLIC_RING_TOOK_OTHER=1"))
        assertTrue(line, line.contains("FDG_CHASING_SPIKE_8065=1"))
        assertFalse(line, line.contains("lostNoTerminal{QUALIFIED"))
    }

    @Test fun aLanesOwnHunterSelectionCountsAsDiscovered() {
        assertEquals(setOf("PROJECT_SNIPER"), d.hunterPoolLanes8418(listOf("project_sniper"), emptyList(), false))
        assertEquals(setOf("PROJECT_SNIPER"), d.hunterPoolLanes8418(emptyList(), listOf("SHITCOIN"), sniperHuntSource = true))
        // a lane holding a hypothesis already stamped POOL; unknown lanes never
        assertEquals(emptySet<String>(), d.hunterPoolLanes8418(listOf("SHITCOIN", "NOT_A_LANE"), listOf("SHITCOIN"), false))
        assertEquals(setOf("BLUECHIP", "EXPRESS"), d.hunterPoolLanes8418(listOf("BLUE_CHIP", "SHITCOIN_EXPRESS"), emptyList(), false))
    }

    @Test fun wiredAtTheCycleTheSpineTheRingAndTheSheet() {
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("LaneDispatch8418.noteCycle8418(ts.mint, cyclePrimaryLane, ts.position.isOpen)"))
        assertTrue(bot.contains("LaneDispatch8418.notePrimarySpine8418(ts.mint, cyclePrimaryLane, ts.position.isOpen, decision.finalSignal, canProposeEarly)"))
        val ring = src("engine/CyclicTradeEngine.kt")
        assertTrue(ring.contains("LaneDispatch8418.cyclicRank8418((ts.lastV3Score ?: ts.entryScore.toInt()).toDouble(), ts.lastV3Confidence, LaneDispatch8418.cyclicReady8418(ts.mint))"))
        for (k in listOf("CYCLIC_RING_DISABLED", "CYCLIC_RING_HOLDING", "CYCLIC_RING_BELOW_RING_FILTERS", "CYCLIC_RING_TOOK_OTHER")) assertTrue(k, ring.contains("\"$k\""))
        assertTrue(src("engine/ToolkitSignalSheet.kt").contains("LaneDispatch8418.hunterPoolLanes8418(residentHunterLanes7803, deskHypotheses.keys,"))
    }
}
