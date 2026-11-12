package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8630 — the runner doctrine becomes a complete exit for meme lanes (audit_exits_now / DIAG_AND_BUILD_8629 Step 4):
 * it keys on the folded entry lane, its trail sells at the line, and the two profit paths that never consulted it
 * (checkProfitLock, checkPartialSell) no longer sell a doctrine winner before its ladder.
 */
class Aate8630DoctrineSellsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/engine/$p").readText()

    @Test fun lanes_fold_to_the_entry_desk() {
        assertEquals("MOONSHOT", RunnerDoctrine8584.foldLane8630("MOONSHOT_LUNAR"))
        assertEquals("MOONSHOT", RunnerDoctrine8584.foldLane8630("moonshot_orbital"))
        assertEquals("EXPRESS", RunnerDoctrine8584.foldLane8630("SHITCOIN_EXPRESS"))
        assertEquals("PROJECT_SNIPER", RunnerDoctrine8584.foldLane8630("SNIPER"))
        assertTrue(RunnerDoctrine8584.rides8584("MOONSHOT_LUNAR"))
        assertFalse(RunnerDoctrine8584.rides8584("STANDARD"))
        assertFalse(RunnerDoctrine8584.rides8584("BLUECHIP"))
    }

    @Test fun the_trail_sells_at_the_line_once_armed() {
        RunnerDoctrine8584.enabled8584 = true
        assertEquals(25.0, RunnerDoctrine8584.TRAIL_ARM_PEAK_PCT_8630, 0.0)
        assertFalse("not armed below a +25 % peak", RunnerDoctrine8584.trailBreached8630("SHITCOIN", 2.0, 20.0))
        assertFalse("above half the peak gain it holds", RunnerDoctrine8584.trailBreached8630("SHITCOIN", 20.0, 30.0))
        assertTrue("at half the peak gain it sells (no stop read; 8719 widens it by the stop)", RunnerDoctrine8584.trailBreached8630("SHITCOIN", 15.0, 30.0, Double.NaN))
        assertFalse("a 5x on a 45 % give-back line holds at 3x", RunnerDoctrine8584.trailBreached8630("MOONSHOT_LUNAR", 200.0, 400.0))
        assertTrue("and sells at 2.75x", RunnerDoctrine8584.trailBreached8630("MOONSHOT_LUNAR", 175.0, 400.0))
        assertFalse("other lanes keep their exits", RunnerDoctrine8584.trailBreached8630("QUALITY", 0.0, 80.0))
        assertFalse(RunnerDoctrine8584.trailBreached8630("SHITCOIN", Double.NaN, 80.0))
        assertTrue(RunnerDoctrine8584.ownExit8630(RunnerDoctrine8584.TRAIL_REASON_8630))
        assertFalse("its own trail is never held by the doctrine", RunnerDoctrine8584.holds8584("SHITCOIN", RunnerDoctrine8584.TRAIL_REASON_8630, 60.0, 80.0))
        assertTrue("nor by the lifecycle hold", LifecycleHold8065.passes8065(RunnerDoctrine8584.TRAIL_REASON_8630))
        assertFalse("nor deferred by a grabbed runner", RunnerGrab7967.deferrable7967(RunnerDoctrine8584.TRAIL_REASON_8630, 40.0))
        assertTrue(RunnerDoctrine8584.profitTakingOwned8630("EXPRESS"))
        assertFalse(RunnerDoctrine8584.profitTakingOwned8630("TREASURY"))
    }

    @Test fun the_wiring_reaches_every_door() {
        val bs = src("BotService.kt")
        val rapid = bs.substringAfter("private suspend fun evaluateRapidMonitorExit(").substringBefore("val drawdownFromPeak = peakGainPct - pnlPct")
        assertTrue(rapid.contains("if (doctrineTrail8630(ts, pnlPct, peakGainPct, wallet, effectiveBalance)) return true"))
        assertTrue(bs.contains("requestSellOffLoop7288(ts, RunnerDoctrine8584.TRAIL_REASON_8630, wallet, balance)"))
        val ex = src("Executor.kt")
        for (head in listOf("fun checkProfitLock(ts: TokenState, wallet: SolanaWallet?, walletSol: Double): Boolean {",
                "fun checkPartialSell(ts: TokenState, wallet: SolanaWallet?, walletSol: Double): Boolean {"))
            assertTrue(head, ex.substringAfter(head).take(200).contains("RunnerDoctrine8584.profitTakingOwned8630(RunnerDoctrine8584.entryLane8630(ts))) return false"))
        val gate = ex.substringAfter("private fun moonbagGate7322(").substringBefore("\n    }\n")
        assertTrue(gate.indexOf("RunnerDoctrine8584.ownExit8630(requestReason)) return null") in 0 until gate.indexOf("tailBagGate8034("))
        assertTrue(ex.contains("val held = RunnerDoctrine8584.holds8584(lane8630, reason, pnl, ts.position.peakGainPct)"))
    }
}
