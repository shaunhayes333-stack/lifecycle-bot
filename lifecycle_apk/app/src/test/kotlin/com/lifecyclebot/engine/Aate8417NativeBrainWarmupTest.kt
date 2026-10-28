package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.LaneBrainVotes8366

class Aate8417NativeBrainWarmupTest {
    private val lanes = listOf("QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE", "MOONSHOT",
        "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN")
    private fun opinion(lane: String, score: Int, eligible: Boolean = true) = SpecialistBrainBridge7542.Opinion(
        lane, eligible, score, score, 0.03, "NATIVE_READY", "SETUP", "ENTRY", "EXIT",
    )

    @Test fun allQualifiedBrainsGetWarmWithoutIncreasingTheTwoNewJobBudget() {
        val ops = lanes.mapIndexed { i, lane -> opinion(lane, 100 - i) } + opinion("INELIGIBLE", 100, false)
        val snap = SpecialistBrainBridge7542.Snapshot("warm8417", 1, 1L, ops.associateBy { it.lane })
        val qualified = SpecialistBrainBridge7542.nativeEvidenceLanes8197(snap)
        val cachedOrQueued = linkedSetOf<String>()
        val batches = mutableListOf<List<String>>()
        repeat(6) {
            val newJobs = mutableListOf<String>()
            val n = SpecialistBrainBridge7542.warmNativeLanes8417(qualified) { o ->
                cachedOrQueued.add(o.lane).also { if (it) newJobs += o.lane }
            }
            assertEquals(2, n)
            assertEquals(2, newJobs.size)
            batches += newJobs
        }
        assertEquals(lanes, batches.flatten())
        assertFalse(cachedOrQueued.contains("INELIGIBLE"))
        assertEquals(0, SpecialistBrainBridge7542.warmNativeLanes8417(qualified) { cachedOrQueued.add(it.lane) })
        // An expired leader can be warmed again without permanently starving a later newly qualified lane.
        cachedOrQueued.remove(lanes.first())
        val next = qualified + opinion("LATER", 50)
        assertEquals(2, SpecialistBrainBridge7542.warmNativeLanes8417(next) { cachedOrQueued.add(it.lane) })
        assertTrue(cachedOrQueued.contains("LATER"))
    }

    @Test fun delayedOrMutatedNativeVotesCannotReplaceANewerStampedDecision() {
        val now = System.currentTimeMillis()
        val mint = "native8417-${System.nanoTime()}"
        fun vote(score: Double) = CortexLedger7885.Vote("LB_QUALITY_S", doubleArrayOf(0.0, 50.0), score, setOf("lane_brain_LB_QUALITY"))
        val newer = vote(25.0)
        val list = mutableListOf(newer)
        LaneBrainVotes8366.stamp8366(mint, list, now)
        list.clear()
        LaneBrainVotes8366.stamp8366(mint, listOf(vote(95.0)), now - 5_000L)
        assertEquals(listOf(newer), LaneBrainVotes8366.resolve8366(mint, emptyList(), now + 10_000L))
        assertTrue(LaneBrainVotes8366.resolve8366(mint, emptyList(), now + LaneBrainVotes8366.ttlNow8366() + 1L).isEmpty())
    }
}
