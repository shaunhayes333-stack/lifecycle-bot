package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import org.junit.Assert.*
import org.junit.Test

class Aate8076LaneSetupIdentityTest {
    @Test fun everyLaneSetupAndNoTriggerHasItsOwnOutcomeBin() {
        val edges = LanePlaybook7907.setupBinEdges8076()
        val lanes = listOf("QUALITY", "BLUECHIP", "SHITCOIN", "MOONSHOT", "EXPRESS", "CORE", "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN", "CYCLIC")
        for (lane in lanes) {
            val setups = LanePlaybook7907.menuIds(lane).distinct() + LanePlaybook7907.NO_TRIGGER
            val indices = setups.map { LanePlaybook7907.setupIndexFor8076(lane, it) }
            val bins = indices.map { CortexLedger7885.binOf(edges, it) }
            assertEquals("$lane setup index collision", setups.size, indices.distinct().size)
            assertEquals("$lane voter bin collision", setups.size, bins.distinct().size)
        }
        // This is the concrete V2 defect: appended setup indices all overflowed bin 20.
        val setupA = LanePlaybook7907.setupIndexFor8076("MOONSHOT", "SPECIALIST_EDGE")
        val setupB = LanePlaybook7907.setupIndexFor8076("MOONSHOT", "COPYCAT_BETA")
        assertNotEquals(CortexLedger7885.binOf(edges, setupA), CortexLedger7885.binOf(edges, setupB))
    }
}
