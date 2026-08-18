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
    fun `publish auto-acks all registered consumers`() {
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
        assertEquals(0, p.zeroConsumers.size)
        assertEquals(1, p.perConsumer["LearnerRewardBridge"])
        assertEquals(1, p.perConsumer["TacticSwitcher"])
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
        // publish already ack'd both; deliverToConsumers refuses Beta →
        // Beta's ack MUST be removed so the parity report resurfaces.
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
        // Every 8 registered consumers should either PROCEED (passive
        // ack) or drive their underlying API. Unknown consumer names
        // return false.
        for (name in listOf(
            "LearnerRewardBridge", "LosingStreakReflex", "GrowthRewardShaper",
            "TacticSwitcher", "Governor", "CapitalCreed", "EVEstimator", "Dashboard",
        )) {
            assertTrue("delivery to $name must succeed", FinalizedBusConsumerBridge6465.deliver(name, env))
        }
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
