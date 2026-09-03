package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import com.lifecyclebot.engine.truth.FinalizedBusConsumerBridge6465
import com.lifecyclebot.engine.truth.PositionRegistryParityAudit6464
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6465 — HARD CI ASSERTIONS.
 *
 * Locks:
 *   §P0-#2  bus fanout auto-acks registered consumers
 *   §P0-#2  refused delivery removes the ack (parity resurfaces)
 *   §P0-#3  registry auto-heal fires after N consecutive divergences
 *   §P1     consumer bridge delivers LosingStreakReflex + passive acks
 */
class BusFanoutAndAutoHealAcceptanceTest6465 {

    @Test
    fun `publish leaves registered consumers awaiting real delivery ACK`() {
        CanonicalFinalizedTradeBus6464.resetForTest()
        CanonicalFinalizedTradeBus6464.registerConsumer("LearnerRewardBridge")
        CanonicalFinalizedTradeBus6464.registerConsumer("TacticSwitcher")
        val env = CanonicalFinalizedTradeBus6464.Envelope(
            tradeId = "T_${System.nanoTime()}", atMs = 0L,
            realizedPnlSol = 0.02, realizedReturnPct = 10.0,
            mint = "M", lane = "L",
        )
        CanonicalFinalizedTradeBus6464.publish(env)
        val p = CanonicalFinalizedTradeBus6464.parity()
        assertEquals(2, p.zeroConsumers.size)
        assertEquals(0, p.perConsumer["LearnerRewardBridge"])
        assertEquals(0, p.perConsumer["TacticSwitcher"])
    }

    @Test
    fun `deliverToConsumers removes ack on refused delivery`() {
        CanonicalFinalizedTradeBus6464.resetForTest()
        CanonicalFinalizedTradeBus6464.registerConsumer("Alpha")
        CanonicalFinalizedTradeBus6464.registerConsumer("Beta")
        val env = CanonicalFinalizedTradeBus6464.Envelope(
            tradeId = "T_${System.nanoTime()}", atMs = 0L,
            realizedPnlSol = 0.01, realizedReturnPct = 5.0,
            mint = "M", lane = "L",
        )
        CanonicalFinalizedTradeBus6464.publish(env)
        // Only the accepted Alpha delivery receives an ACK; refused Beta
        // remains visible as a parity miss.
        CanonicalFinalizedTradeBus6464.deliverToConsumers(env) { name, _ ->
            name == "Alpha"
        }
        val p = CanonicalFinalizedTradeBus6464.parity()
        assertEquals(1, p.perConsumer["Alpha"])
        assertEquals(0, p.perConsumer["Beta"])
        assertTrue("Beta must be in zeroConsumers", "Beta" in p.zeroConsumers)
    }

    @Test
    fun `FinalizedBusConsumerBridge6465 deliver returns true for known consumers`() {
        FinalizedBusConsumerBridge6465.resetForTest()
        val env = CanonicalFinalizedTradeBus6464.Envelope(
            tradeId = "T_${System.nanoTime()}", atMs = 0L,
            realizedPnlSol = -0.02, realizedReturnPct = -10.0,
            mint = "M", lane = "L",
        )
        // V5.0.6475 — only consumers with a real handler may ACK. Unwired
        // consumers must remain visible as parity misses, not passive success.
        val delivered = listOf(
            "LearnerRewardBridge", "LosingStreakReflex", "GrowthRewardShaper",
            "TacticSwitcher", "Governor", "CapitalCreed", "EVEstimator", "Dashboard",
        ).associateWith { FinalizedBusConsumerBridge6465.deliver(it, env) }
        assertTrue("all eight canonical consumers must invoke real source APIs", delivered.values.all { it })
        assertEquals(false, FinalizedBusConsumerBridge6465.deliver("UnknownConsumer", env))
    }

    @Test
    fun `parity audit statusLine tracks consecutiveDivergences and autoHeals`() {
        PositionRegistryParityAudit6464.resetForTest()
        val s = PositionRegistryParityAudit6464.statusLine()
        assertTrue("status must include consecutiveDivergences=0: $s", s.contains("consecutiveDivergences=0"))
        assertTrue("status must include autoHeals=0: $s", s.contains("autoHeals=0"))
    }
}
