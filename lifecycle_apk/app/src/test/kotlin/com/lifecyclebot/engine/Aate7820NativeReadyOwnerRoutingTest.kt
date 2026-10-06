package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.7820 — regression coverage for native READY specialist ownership.
 *
 * The live 7819 funnel could report residentReady>0 for Moonshot/Bluechip/etc
 * while ownerSelected/buyIntent remained zero because bounded style routing
 * omitted the lane that the coordinator would elect.
 */
class Aate7820NativeReadyOwnerRoutingTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun coordinator_exposes_the_same_ready_owner_contest_used_for_execution() {
        val s = src("engine/LaneExecutionCoordinator.kt")
        val fn = s.substringAfter("internal fun preferredReadyOwner7820(")
            .substringBefore("private fun secondaryFresh7803(")
        assertTrue(fn.contains("readyProposalScores7803"))
        assertTrue(fn.contains("pickFreshPrimary"))
        assertTrue(fn.contains("ownExecutorRefusal7807"))
        assertTrue(fn.contains("nativeRefused7774"))
        assertTrue(fn.contains("NATIVE_READY_OWNER_RESOLVED_7820"))
    }

    @Test fun bounded_router_reserves_primary_slot_for_native_ready_owner() {
        val s = src("engine/AgenticStyleRouter.kt")
        val fn = s.substringAfter("private fun boundedLanes(")
            .substringBefore("private fun rapidToxicRegimePivot(")
        assertTrue(fn.contains("nativeReadyOwner7820"))
        assertTrue(fn.contains("val primary = readyOwner7820"))
        assertTrue(fn.contains("NATIVE_READY_OWNER_ROUTED_7820"))
        // The repair must replace the primary slot, not append another
        // executable lane and recreate same-mint multi-buy fanout.
        assertFalse(fn.contains("out += readyOwner7820"))
    }

    @Test fun lanesFor_reads_candidate_generation_ready_owner_before_bounded_fanout() {
        val s = src("engine/AgenticStyleRouter.kt")
        val fn = s.substringAfter("fun lanesFor(").substringBefore("fun toolsFor(")
        assertTrue(fn.contains("LaneExecutionCoordinator.candidateVersionFor(ts.mint)"))
        assertTrue(fn.contains("LaneExecutionCoordinator.preferredReadyOwner7820"))
        assertTrue(fn.contains("nativeReadyOwner7820 = nativeReadyOwner7820.orEmpty()"))
    }
}
