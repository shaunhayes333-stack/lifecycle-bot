package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.RoutableMinRiskGuard7236
import com.lifecyclebot.v3.sizing.SmartSizerV3
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate7238LiveExecutionConvergenceTest {
    private fun source(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test
    fun small_live_wallet_uses_operational_reserve_and_can_carry_two_routable_tickets() {
        val walletSol = 0.1261
        val reserve = SmartSizerV3.effectiveLiveReserveSol7238(walletSol, 0.05)
        assertEquals(0.01261, reserve, 0.00001)

        val tradeable = walletSol - reserve
        val pf = SmartSizerV3.routableCapacityPreflight7224(tradeable, 117.51)

        assertEquals(2, pf.capacity)
        assertFalse(pf.wouldRefuse)
        assertTrue(pf.safeShareCapSol >= pf.routableMinSol)
    }

    @Test
    fun pending_proof_dampener_is_not_a_second_hard_veto_after_entry_authority_passes() {
        RoutableMinRiskGuard7236.clearForTest()
        val allowed = RoutableMinRiskGuard7236.evaluate(
            mint = "mint",
            symbol = "TEST",
            lane = "QUALITY",
            score = 20.0,
            regimeSizeMult = 1.0,
            livePendingProofPenalty = true,
            riskSizedSol = 0.020,
            routableMinSol = 0.043,
        )
        assertEquals(RoutableMinRiskGuard7236.Verdict.ALLOW_LIFT, allowed.verdict)

        val weak = RoutableMinRiskGuard7236.evaluate(
            mint = "mint2",
            symbol = "WEAK",
            lane = "QUALITY",
            score = 10.0,
            regimeSizeMult = 1.0,
            livePendingProofPenalty = false,
            riskSizedSol = 0.020,
            routableMinSol = 0.043,
        )
        assertEquals(RoutableMinRiskGuard7236.Verdict.REFUSE_LIFT_WEAK, weak.verdict)
    }

    @Test
    fun wallet_only_assets_do_not_become_bot_open_positions() {
        val reconciler = source("engine/WalletReconciler.kt")
        assertTrue(reconciler.contains("EXTERNAL_WALLET_HOLDING_NOT_OPEN_POSITION_7238"))
        assertTrue(reconciler.contains("canonicalOwn7238 || pendingOwn7238 || fillOwn7238 || persistedOwn7238"))
        assertTrue(reconciler.contains("continue"))
        assertTrue(reconciler.contains("recoverOrphanPosition(status, mint, uiAmount, ts)"))
    }

    @Test
    fun executor_and_preflight_share_the_same_reserve_authority() {
        val executor = source("engine/Executor.kt")
        val preflight = source("engine/truth/LivePreflight7222.kt")
        val v3 = source("v3/V3EngineManager.kt")
        assertTrue(executor.contains("effectiveLiveReserveSol7238"))
        assertTrue(preflight.contains("effectiveLiveReserveSol7238"))
        assertTrue(v3.contains("effectiveLiveReserveSol7238"))
    }
}
