package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.SellAmountAuthority
import com.lifecyclebot.engine.truth.FinalizedBusConsumerBridge6465
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 8241: complete empty wallet evidence and durable orphan learning do not silently fail. */
class Aate8241FinalityRepairTest {
    @Test fun completeEmptyIsEvidenceNotBroadcastAuthority() {
        assertTrue(SellAmountAuthority.completeEmptyCountsAsAbsent8241(true))
        assertFalse(SellAmountAuthority.completeEmptyCountsAsAbsent8241(false))
        val sell = File("src/main/kotlin/com/lifecyclebot/engine/sell/SellAmountAuthority.kt").readText()
        val branch = sell.substringAfter("if (balances.isEmpty()) {").substringBefore("val entry = balances[mint]")
        assertTrue(branch.contains("noteAbsentFromComplete7733(mint, System.currentTimeMillis())"))
        assertTrue(branch.contains("else absentFromComplete7733.remove(mint)"))
        assertTrue(branch.contains("return Resolution.Unknown"))
        val poll = File("src/main/kotlin/com/lifecyclebot/engine/sell/BalanceProofPoller.kt").readText()
        assertTrue(poll.contains("if (!zeroCorroborated8144(entry)) return"))
        assertTrue(poll.contains("ABSENT_COMPLETE_MIN_READS_7733 = 2"))
    }

    @Test fun orphanIsExcludedOnlyWithDurableProofAndCompletedRecovery() {
        val b = FinalizedBusConsumerBridge6465
        assertFalse(b.orphanPositionTerminal8241("RewardPurity", true, true, true, 200_000))
        assertFalse(b.orphanPositionTerminal8241("RewardPurity", false, false, true, 200_000))
        assertFalse(b.orphanPositionTerminal8241("RewardPurity", false, true, false, 200_000))
        assertFalse(b.orphanPositionTerminal8241("RewardPurity", false, true, true, 30_000))
        assertFalse(b.orphanPositionTerminal8241("Dashboard", false, true, true, 200_000))
        assertTrue(b.orphanPositionTerminal8241("RewardPurity", false, true, true, 200_000))
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        assertTrue(s.contains("CanonicalFinalizedTradeBus6464.exclude(consumer, env.tradeId, \"ORPHAN_CANONICAL_POSITION_8241\")"))
    }
}
