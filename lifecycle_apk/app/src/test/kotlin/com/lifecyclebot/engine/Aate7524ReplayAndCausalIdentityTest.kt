package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.truth.PaperAccountReplay6461
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7524ReplayAndCausalIdentityTest {
    @Test fun replay_basis_is_position_scoped_and_duplicate_terminal_does_not_consume_other_position() {
        val rows = listOf(
            Trade(side="BUY", mode="paper", sol=1.0, price=1.0, ts=1L, mint="A", positionId="PA", economicEventId="A_BUY"),
            Trade(side="BUY", mode="paper", sol=2.0, price=1.0, ts=2L, mint="B", positionId="PB", economicEventId="B_BUY"),
            Trade(side="SELL", mode="paper", sol=1.1, price=1.1, ts=3L, mint="A", positionId="PA", pnlSol=0.1, soldCostBasisSol=1.0, postCostSol=0.0, economicEventId="A_SELL"),
            Trade(side="SELL", mode="paper", sol=1.1, price=1.1, ts=4L, mint="A", positionId="PA", pnlSol=0.1, soldCostBasisSol=1.0, postCostSol=0.0, economicEventId="A_SELL"),
        )
        val s = PaperAccountReplay6461.replayForTest(10.0, rows)
        assertEquals(2.0, s.openCostBasisSol, 1e-9)
    }

    @Test fun causal_lookup_prefers_exact_sealed_intent_not_latest_generation() {
        val f = File("src/main/kotlin/com/lifecyclebot/engine/truth/MemeExecutionFunnelReceivers6625.kt").readText()
        val t = File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()
        assertTrue(f.contains("fun keyForIntent7524"))
        assertTrue(t.contains("keyForIntent7524(mint, lane, expectedIntentId6647, resolvedMode6858)"))
        assertTrue(t.indexOf("keyForIntent7524(mint, lane, expectedIntentId6647, resolvedMode6858)") <
            t.indexOf("priorCausalKey6647?.takeIf"))
        assertTrue(t.contains("SPECIALIST_CAUSAL_EXACT_INTENT_REBOUND_7524"))
    }
}
