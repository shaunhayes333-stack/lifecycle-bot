package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.truth.EntryStrategySnapshot6450
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Aate9062HeldExitIdentityTest {
    private fun token(): TokenState {
        val id = UUID.randomUUID().toString()
        return TokenState(mint = "held9062-$id", position = Position(
            qtyToken = 100.0, entryPrice = 1.0, entryTime = System.currentTimeMillis(),
            costSol = 0.1, positionId = "position9062-$id", tradingMode = "MOONSHOT"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun snapshots() = EntryStrategySnapshot6450::class.java.getDeclaredField("snapshots")
        .apply { isAccessible = true }.get(EntryStrategySnapshot6450)
        as ConcurrentHashMap<String, EntryStrategySnapshot6450.Snapshot>

    private fun snapshot(ts: TokenState, setup: String) = EntryStrategySnapshot6450.Snapshot(
        positionId = ts.position.positionId, mint = ts.mint, entryLane = "EXPRESS",
        entryStrategyPid = "test", entryTactic = "test", entryRiskProfile = "test",
        entryExitProfile = "test", entrySource = "test", entryScore = 50,
        entryLiquiditySol = 10.0, entryMarketCapUsd = 20000.0,
        entryTimestampMs = ts.position.entryTime, entryThresholdSnapshot = "",
        entrySetup = setup)

    @Suppress("UNCHECKED_CAST")
    private fun clearPlan(ts: TokenState) {
        val plans = ExitProfile7955::class.java.getDeclaredField("positionPlans")
            .apply { isAccessible = true }.get(ExitProfile7955) as ConcurrentHashMap<String, *>
        plans.remove("${ts.mint}|${ts.position.entryTime}")
    }

    @Test fun missingEntrySetupDoesNotWaitForTheStrategyRankingMonitor() {
        val ts = token()
        assertTrue(ts.position.isOpen)
        EntryStrategySnapshot6450.snapshot(ts.position.positionId)
        val expected = ExitProfile7955.planForKey7955("MOONSHOT", "NONE")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val holder = Thread {
            synchronized(LanePlaybook7907) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        holder.start()
        try {
            assertTrue("ranking monitor acquired", entered.await(5, TimeUnit.SECONDS))
            val result = executor.submit<ExitProfile7955.Plan7955?> { ExitProfile7955.planFor7955(ts) }
                .get(3, TimeUnit.SECONDS)
            assertNotNull(result)
            assertEquals("MOONSHOT|NONE", result!!.key)
            assertEquals(expected, result)
            assertEquals("ranking lock still held during exit read", 1L, release.count)
        } finally {
            release.countDown()
            holder.join(5000)
            executor.shutdownNow()
            clearPlan(ts)
        }
    }

    @Test fun frozenEntryLaneAndSetupSurviveDisplayLaneChanges() {
        val ts = token()
        val snap = snapshot(ts, "DIAMOND_HANDS_RUNNER")
        snapshots()[snap.positionId] = snap
        try {
            val plan = ExitProfile7955.planFor7955(ts)!!
            assertEquals("EXPRESS|DIAMOND_HANDS_RUNNER", plan.key)
            ts.position = ts.position.copy(tradingMode = "CORE")
            assertSame(plan, ExitProfile7955.planFor7955(ts))
        } finally {
            snapshots().remove(snap.positionId)
            clearPlan(ts)
        }
    }

    @Test fun unknownRecordedSetupRetainsEntryLaneWithoutInventingASetup() {
        for (setup in listOf("", "NONE", "NO_TRIGGER")) {
            val ts = token()
            val snap = snapshot(ts, setup)
            snapshots()[snap.positionId] = snap
            try {
                assertEquals("EXPRESS|NONE", ExitProfile7955.planFor7955(ts)!!.key)
            } finally {
                snapshots().remove(snap.positionId)
                clearPlan(ts)
            }
        }
    }
}
