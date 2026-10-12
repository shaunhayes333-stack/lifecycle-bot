package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8079 — a proven-losing setup is graded and refused as itself, with no side door; sealed size clears the moving floor. */
class Aate8079SetupSelectionTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun setupRefusalsAreGradedPerLaneAndSetup() {
        assertEquals("PLAYBOOK_SETUP_PROVEN_LOSING@MOONSHOT_LAUNCH_CONTINUATION",
            Cortex7885.vetoRuleOf("PLAYBOOK_SETUP_PROVEN_LOSING_7907_MOONSHOT_LAUNCH_CONTINUATION"))
        assertEquals("PLAYBOOK_SETUP_PROVEN_LOSING@SHITCOIN_LAUNCH_LADDER_PROVEN",
            Cortex7885.vetoRuleOf("PLAYBOOK_SETUP_PROVEN_LOSING_7907_SHITCOIN_LAUNCH_LADDER_PROVEN"))
        assertEquals("PLAYBOOK_SETUP_EXPECTED_NEGATIVE@PROJECT_SNIPER_LOW_RUNUP_BASE",
            Cortex7885.vetoRuleOf("PLAYBOOK_SETUP_EXPECTED_NEGATIVE_7948_PROJECT_SNIPER_LOW_RUNUP_BASE"))
        // Every other rule keeps its historical key (persisted veto books stay valid).
        assertEquals("EDGE_NO_PREDICTED_EDGE", Cortex7885.vetoRuleOf("EDGE_7877_NO_PREDICTED_EDGE_SHITCOIN"))
        assertEquals("LIVE_PROVEN_SETUP_ONLY", Cortex7885.vetoRuleOf("LIVE_PROVEN_SETUP_ONLY_8059"))
    }

    @Test fun provenLoserIsCheckedBeforeChartReaderAndSpecialistDoors() {
        val g = src("engine/truth/LiveEdgeGate7877.kt")
        val check = g.indexOf("LanePlaybook7907.provenLosingSetupRefusal8079(ts, l8079, nowMs)")
        assertTrue(check > 0)
        assertTrue(check > g.indexOf("cortex7950?.let { return it }"))
        assertTrue(check < g.indexOf("if (chartAdmits7950(ts, lane, nowMs)) return null"))
        assertTrue(check < g.indexOf("SpecialistMiner7972.match7972(ts, l, nowMs)?.let { sp ->"))
        assertTrue(g.contains("Cortex7885.vetoRefusesWinners8006(r, l8079)"))
        assertTrue(g.contains("Cortex7885.fullAuthorityOverrules8078(ts, l8079, r)"))
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(pb.contains("if (losing) \"PLAYBOOK_SETUP_PROVEN_LOSING_7907_\${lane}_\$setup\" else null"))
    }

    @Test fun sealedLiveSizeHasHeadroomOverTheSolPricedFloor() {
        val f = src("engine/FinalDecisionGate.kt")
        assertTrue(f.contains("private const val SEAL_FLOOR_HEADROOM_8079 = 0.03"))
        assertTrue(f.contains("cap8079 >= headroomMin8079 - 1e-12"))
        val cap = com.lifecyclebot.engine.truth.LiveRiskPolicy7807.routeAwareSpendCap7842(
            walletSol = 0.2113, solUsd = 120.85, maxLiveBuySol = 2.0, walletSharePct = 0.10,
        )
        assertTrue(cap.routeFloorLifted)
        assertTrue(cap.maxSpendableSol >= cap.routableMinSol * 1.03 - 1e-9 || cap.maxSpendableSol >= cap.tradeableSol - 1e-9)
    }

    @Test fun exitQuoteBudgetLeavesTimeForTheNextEndpoint() {
        com.lifecyclebot.network.ExitQuoteBudget7863.run(0L) {
            // a stalled first hop gets 2 s, leaving 3 s for Ultra after it
            assertEquals(2000L, com.lifecyclebot.network.ExitQuoteBudget7863.remainingMs(0L))
            assertEquals(2000L, com.lifecyclebot.network.ExitQuoteBudget7863.remainingMs(2_000_000_000L))
        }
        val j = src("network/JupiterApi.kt")
        val timeoutBreak = j.indexOf("JUPITER_V6_TIMEOUT_YIELDS_NEXT_ENDPOINT_8079")
        assertTrue(timeoutBreak > j.indexOf("if (msg.contains(\" 429\")) continue@hosts"))
        assertTrue(timeoutBreak < j.indexOf("Jupiter v6 quote exhausted adaptive fallbacks"))
    }

    @Test fun pumpPortalSellAmountIsAJsonNumber() {
        val p = src("network/PumpFunDirectApi.kt")
        assertTrue(p.contains("amountField = java.math.BigDecimal(uiAmount)"))
        assertTrue(!p.contains("                amountField = uiAmount\n"))
    }
}
